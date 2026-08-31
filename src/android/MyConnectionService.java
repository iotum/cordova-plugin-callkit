package com.dmarc.cordovacall;

import org.apache.cordova.CordovaInterface;
import org.apache.cordova.PluginResult;
import org.json.JSONException;
import org.json.JSONObject;

import android.app.Activity;
import android.content.Intent;
import android.content.Context;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.drawable.Icon;
import android.os.Bundle;
import android.telecom.CallAudioState;
import android.telecom.Connection;
import android.telecom.ConnectionRequest;
import android.telecom.ConnectionService;
import android.telecom.DisconnectCause;
import android.telecom.PhoneAccountHandle;
import android.telecom.StatusHints;
import android.telecom.TelecomManager;
import android.net.Uri;
import android.util.Log;

import java.util.concurrent.ConcurrentHashMap;

public class MyConnectionService extends ConnectionService {

    static final String TAG = "MyConnectionService";
    static final ConcurrentHashMap<String, Connection> connectionMap = new ConcurrentHashMap<String, Connection>(); // Keys are session id strings
    private static final ConcurrentHashMap<String, Boolean> connectionAddedMap = new ConcurrentHashMap<String, Boolean>(); // Keys are call_uuid strings, true if addIncomingCall called for the given call uuid.
    // Tracks sessions for which a dismiss was received before the connection was added to connectionMap.
    // When onCreateIncomingConnection fires for such a session, the connection is immediately aborted.
    private static final ConcurrentHashMap<String, Boolean> pendingDismissals = new ConcurrentHashMap<String, Boolean>();

    private CallActionReceiver callActionReceiver;

    @Override
    public void onCreate() {
        super.onCreate();

        this.callActionReceiver = new CallActionReceiver();
        IntentFilter intentFilter = new IntentFilter();
        this.registerReceiver(this.callActionReceiver, intentFilter, RECEIVER_NOT_EXPORTED);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();

        this.unregisterReceiver(this.callActionReceiver);
    }

    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            Log.d(TAG, "onStartCommand called with no intent");
            return super.onStartCommand(intent, flags, startId);
        }

        String intentAction = intent.getAction();

        Log.d(TAG, "onStartCommand called with intent, action: " + intentAction);

        if (intentAction != null && intentAction.equals("INCOMING_CALL_INVITE")) {
            String payloadString = intent.getStringExtra("payload");

            JSONObject payload = null;
            try {
                payload = new JSONObject(payloadString);
            } catch (JSONException e) {
                throw new RuntimeException("Failed to parse payload JSON string: " + e);
            }

            String callUUID = payload.optString("call_uuid", "");
            String sessionId = null;
            try {
                sessionId = payload.getString("session_id");
            } catch (JSONException e) {
                throw new RuntimeException("Unable to add incoming connection - no session_id found in payload: " + payloadString);
            }

            if (payload.optBoolean("dismiss", false)) {
                Log.d(TAG, "received intent with payload.dismiss indicating call is dismissed, call_uuid: " + callUUID);

                Connection conn = connectionMap.get(sessionId);
                if (conn == null) {
                    if (Boolean.TRUE.equals(connectionAddedMap.get(callUUID))) {
                        // A dismiss can legitimately arrive before onCreateIncomingConnection adds the
                        // connection to connectionMap. Only record a pending dismissal when there is
                        // separate evidence that an incoming call for this call_uuid is actually pending.
                        Log.w(TAG, "No connection found for dismiss, recording pending dismissal. call_uuid: " + callUUID + ", sessionId: " + sessionId);
                        pendingDismissals.put(sessionId, true);
                    } else {
                        // connectionMap can also be missing because the call already disconnected and
                        // was removed, or because this is a duplicate/late dismiss push. Do not record
                        // a pending dismissal in those cases, since that can leak entries and affect a
                        // future call if sessionId is reused.
                        Log.w(TAG, "No connection found for dismiss and no pending incoming call is tracked; ignoring dismiss. call_uuid: "
                                + callUUID + ", sessionId: " + sessionId);
                    }
                } else {
                    int state = conn.getState();
                    boolean answeredLocally = conn instanceof IncomingCallConnection && ((IncomingCallConnection) conn).isAnsweredWithinGracePeriod();
                    if (state == Connection.STATE_DISCONNECTED) {
                        Log.d(TAG, "Call is already marked disconnected, call_uuid: " + callUUID);
                    } else if (answeredLocally) {
                        // The user already tapped answer; the connection just hasn't left STATE_RINGING
                        // yet because that only happens once the web app calls connectCall(). A dismiss
                        // racing in during that window must not cancel a call the user already answered.
                        // Only suppressed for a bounded grace period - see isAnsweredWithinGracePeriod() -
                        // so a call whose web app never connects doesn't become unkillable.
                        Log.d(TAG, "Ignoring dismiss for connection already answered locally, state: "
                                + Connection.stateToString(state) + ", call_uuid: " + callUUID);
                    } else if (state == Connection.STATE_NEW || state == Connection.STATE_RINGING) {
                        // Only abort pre-answer states. STATE_NEW covers the brief window between
                        // onCreateIncomingConnection returning and onShowIncomingCallUi calling
                        // setRinging(). Do not abort STATE_ACTIVE or STATE_HOLDING — a late-arriving
                        // dismiss for an already-answered call must not disconnect the live call.
                        Log.d(TAG, "Calling connection.onAbort() in response to pushMessagePayload.dismiss, state: "
                                + Connection.stateToString(state) + ", call_uuid: " + callUUID);
                        conn.onAbort();
                    } else {
                        Log.d(TAG, "Ignoring dismiss for connection in non-ringing state: "
                                + Connection.stateToString(state) + ", call_uuid: " + callUUID);
                    }
                }
            } else {
                if (connectionMap.get(sessionId) != null) {
                    Log.d(TAG, "A connection is already created for call_uuid: " + callUUID);
                } else {
                    if (connectionAddedMap.containsKey(callUUID)) {
                        Log.d(TAG, "A connection was already added for call_uuid: " + callUUID);
                    } else {
                        Context context = this.getApplicationContext();

                        TelecomManager tm = (TelecomManager) context.getSystemService(Context.TELECOM_SERVICE);

                        PhoneAccountHandle phoneAccountHandle = PhoneAccountManager.getPhoneAccountHandle(context);

                        Bundle callInfo = new Bundle();
                        callInfo.putString("payload", payloadString);

                        Log.d(TAG, "Adding new incoming connection, callUUID: " + callUUID);

                        // Request Telecom to create a new incoming connection (see onCreateIncomingConnection / onCreateIncomingConnectionFailed).
                        // Note: this call can legitimately fail when there is already a ringing call (MAX_RINGING_CALLS).
                        // In that case we intentionally preserve the first ringing call and treat the failure as expected, not a regression.
                        tm.addNewIncomingCall(phoneAccountHandle, callInfo);
                        connectionAddedMap.put(callUUID, true);
                    }
                }
            }
        }

        return START_STICKY; // System will attempt to re-create the service if it is killed.
    }

    public void showWebApp(String userAction, String payload) {
        showWebApp(userAction, payload, false);
    }

    public void showWebApp(String userAction, String payload, boolean fromLockscreen) {
        Log.d(TAG, "showWebApp()");
        Context context = this.getApplicationContext();
        PackageManager packageManager = context.getPackageManager();

        Class mainActivity;
        String  packageName = context.getPackageName();
        Intent  launchIntent = packageManager.getLaunchIntentForPackage(packageName);
        String  className = launchIntent.getComponent().getClassName();

        // Lookup the MainActivity so we can launch an explicit intent to it without
        // importing / assuming the package it came from (which differs by whitelabel)
        try {
            mainActivity = Class.forName(className);
        } catch (ClassNotFoundException e) {
            throw new RuntimeException(e);
        }

        Intent intent = new Intent(context, mainActivity);
        intent.putExtra("userAction", userAction); // So web app (if desired) could use this to automatically answer/decline the call (can read the intent using cordova-plugin-intent)
        intent.putExtra("payload", payload);
        if (fromLockscreen) {
            intent.putExtra("fromLockscreen", true);
        }

        // Prefer launching through the already-running MainActivity's own Activity context (as
        // CordovaCall.showMainActivityOnLockscreen() does), rather than this bare Service/ApplicationContext.
        // A Service-context startActivity() targeting a task that's already visible is only granted a
        // weaker background-activity-launch allowance (BAL_ALLOW_GRACE_PERIOD) by the OS, which forces
        // Android to spin up a brand-new MainActivity task/instance instead of reusing the visible one -
        // tearing down and recreating the whole Cordova WebView mid-answer. An Activity-context launch
        // isn't subject to that restriction and correctly reuses the existing task via onNewIntent.
        CordovaInterface cordova = CordovaCall.getCordova();
        Activity activity = cordova != null ? cordova.getActivity() : null;
        if (activity != null) {
            activity.runOnUiThread(() -> activity.startActivity(intent));
        } else {
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            this.startActivity(intent);
        }
    }

    // Returns the connection for the active session (or null if none).
    // connectionMap is a ConcurrentHashMap so iteration is thread-safe.
    // Per Android Telecom semantics, at most one connection should be STATE_ACTIVE at a time.
    public static Connection getConnection() {
        for (Connection conn : connectionMap.values()) {
            if (conn.getState() == Connection.STATE_ACTIVE) {
                return conn;
            }
        }
        return null;
    }

    // Returns the number of connections currently in STATE_ACTIVE.
    public static int getActiveCallCount() {
        int count = 0;
        for (Connection conn : connectionMap.values()) {
            if (conn.getState() == Connection.STATE_ACTIVE) {
                count++;
            }
        }
        return count;
    }

    public static Connection getConnection(String sessionId) {
        return connectionMap.get(sessionId);
    }

    // Returns true if any connection in the map is in a state that requires CallAudioService to remain running:
    // STATE_DIALING (outgoing call started the service on DIALING), STATE_ACTIVE, or STATE_HOLDING.
    public static boolean hasConnectionsRequiringAudioService() {
        for (Connection conn : connectionMap.values()) {
            int state = conn.getState();
            if (state == Connection.STATE_DIALING || state == Connection.STATE_ACTIVE || state == Connection.STATE_HOLDING) {
                return true;
            }
        }
        return false;
    }

    public static void onConnectionDisconnected(String sessionId) {
        Log.d(TAG, "Removing CallConnection from connectionMap, sessionId: " + sessionId);
        connectionMap.remove(sessionId);
    }

    void handleCallAudioStateChanged(CallAudioState state) {
        Log.d(TAG, "onCallAudioStateChanged: route=" + state.getRoute() + ", supportedRoutes=" + state.getSupportedRouteMask());

        // Use the centralized method from AudioRouteMonitor to emit route change event
        AudioRouteMonitor monitoring = AudioRouteMonitor.getInstance();
        if (monitoring != null) {
            monitoring.emitCurrentAudioRoute(CordovaCall.AudioRouteChangeType.PROGRAMMATIC_CHANGE);
        }
    }

    @Override
    public Connection onCreateIncomingConnection(final PhoneAccountHandle connectionManagerPhoneAccount, final ConnectionRequest request) {
        Bundle requestExtras = request.getExtras() != null ? request.getExtras() : new Bundle();
        String payloadString = requestExtras.getString("payload");
        Log.d(TAG, "onCreateIncomingConnection payload: " + payloadString);
        JSONObject payload;
        try {
            payload = new JSONObject(payloadString);
        } catch (JSONException e) {
            throw new RuntimeException("Failed to parse payload string: " + e);
        }

        String _callUUID = null;
        try {
            _callUUID = payload.getString("call_uuid");
        } catch (JSONException e) {
            throw new RuntimeException("onCreateIncomingConnection no call uuid provided for this connection");
        }
        final String callUUID = _callUUID;

        // Ensure the connectionAddedMap entry is always removed once callUUID is known,
        // even if an exception is thrown later in this method.
        try {
            String callerName = payload.optString("from", "UNKNOWN CALLER");

            String sessionId = null;
            try {
                sessionId = payload.getString("session_id");
            } catch (JSONException e) {
                throw new RuntimeException("onCreateIncomingConnection: no session_id in payload, unable to create IncomingCallConnection");
            }
            final IncomingCallConnection connection = new IncomingCallConnection(this, callUUID, payloadString, callerName, sessionId);

            connection.setCallerDisplayName(callerName, TelecomManager.PRESENTATION_ALLOWED);

            Icon icon = CordovaCall.getIcon();
            if(icon != null) {
                StatusHints statusHints = new StatusHints((CharSequence)"", icon, new Bundle());
                connection.setStatusHints(statusHints);
            }

            Log.d(TAG, "Created connection for callUUID: " + callUUID);
            connection.setConnectionProperties(Connection.PROPERTY_SELF_MANAGED);

            Log.d(TAG, "Adding IncomingCallConnection to connectionMap, sessionId: " + sessionId);
            connectionMap.put(sessionId, connection);

            // If a dismiss was received before this connection was created, abort it immediately.
            if (pendingDismissals.remove(sessionId) != null) {
                Log.w(TAG, "Pending dismissal found for sessionId: " + sessionId + ", aborting connection immediately.");
                connection.onAbort();
                return connection;
            }

            CordovaCall.emitEvent("receiveCall", new PluginResult(PluginResult.Status.OK, "receiveCall event called successfully"));

            return connection;
        } finally {
            // Deferred until after connectionMap.put so a dismiss arriving between addNewIncomingCall
            // and connectionMap.put still finds the entry and is recorded as a pending dismissal.
            // The finally block guarantees cleanup even if an exception is thrown, preventing the
            // entry from leaking and blocking future calls for the same call_uuid.
            connectionAddedMap.remove(callUUID);
        }
    }

    @Override
    public void onCreateIncomingConnectionFailed(PhoneAccountHandle connectionManagerPhoneAccount, ConnectionRequest request) {
        super.onCreateIncomingConnectionFailed(connectionManagerPhoneAccount, request);
        Bundle requestExtras = request.getExtras() != null ? request.getExtras() : new Bundle();
        String payloadString = requestExtras.getString("payload");
        boolean hasExistingRingingCall = connectionMap.values().stream()
                .anyMatch(conn -> conn.getState() == Connection.STATE_RINGING);
        if (hasExistingRingingCall) {
            Log.d(TAG, "onCreateIncomingConnectionFailed due to existing ringing call (MAX_RINGING_CALLS), payload: " + payloadString);
        } else {
            Log.e(TAG, "onCreateIncomingConnectionFailed, payload: " + payloadString);
        }
        try {
            JSONObject payload = new JSONObject(payloadString);
            String callUUID = payload.getString("call_uuid");
            connectionAddedMap.remove(callUUID);
            Log.d(TAG, "Removed connectionAddedMap entry for failed incoming connection, callUUID: " + callUUID);
            String sessionId = payload.optString("session_id", null);
            if (sessionId != null) {
                pendingDismissals.remove(sessionId);
            }
        } catch (JSONException e) {
            Log.e(TAG, "onCreateIncomingConnectionFailed failed to parse payload: " + payloadString + ", error: " + e.getMessage());
        }
    }

    @Override
    public Connection onCreateOutgoingConnection(PhoneAccountHandle connectionManagerPhoneAccount, ConnectionRequest request) {
        Bundle extras = request.getExtras();
        String peerName = extras.getString("to", "unknown");
        String sessionId = extras.getString("sessionId");

        if (sessionId == null) {
            throw new RuntimeException("onCreateOutgoingConnection: Must supply a sessionId!");
        }

        final OutgoingCallConnection connection = new OutgoingCallConnection(this, peerName, sessionId);
        connection.setAddress(Uri.parse(peerName), TelecomManager.PRESENTATION_ALLOWED);
        Icon icon = CordovaCall.getIcon();
        if(icon != null) {
            StatusHints statusHints = new StatusHints((CharSequence)"", icon, new Bundle());
            connection.setStatusHints(statusHints);
        }

        // Set capabilities to indicate this handles audio
        connection.setConnectionCapabilities(
                Connection.CAPABILITY_MUTE | Connection.CAPABILITY_HOLD | Connection.CAPABILITY_SUPPORT_HOLD
        );

        // Specifically for self-managed connections (like most VoIP apps)
        // This tells the system "I am handling the audio stream myself"
        connection.setConnectionProperties(Connection.PROPERTY_SELF_MANAGED);
        connection.setAudioModeIsVoip(true);

        connection.setDialing();
        CordovaCall.emitEvent("sendCall", new PluginResult(PluginResult.Status.OK, "sendCall event called successfully"));

        Log.d(TAG, "Adding OutgoingCallConnection to connectionMap, sessionId: " + sessionId);
        connectionMap.put(sessionId, connection);

        return connection;
    }
}
