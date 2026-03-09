package com.dmarc.cordovacall;

import org.apache.cordova.PluginResult;
import org.json.JSONException;
import org.json.JSONObject;

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
            String sessionId = payload.optString("session_id", getSessionIdFromCallUUID(callUUID));

            if (payload.optBoolean("dismiss", false)) {
                Log.d(TAG, "received intent with payload.dismiss indicating call is dismissed, call_uuid: " + callUUID);

                Connection conn = connectionMap.get(sessionId);
                if (conn == null) {
                    Log.e(TAG, "Cannot disconnect. No connection found with call_uuid: " + callUUID);
                } else {
                    if (conn.getState() == Connection.STATE_DISCONNECTED) {
                        Log.d(TAG, "Call is already marked disconnected, call_uuid: " + callUUID);
                    } else if (conn.getState() == Connection.STATE_RINGING) {
                        Log.d(TAG, "Calling connection.onAbort() in response to pushMessagePayload.dismiss, call_uuid: " + callUUID);
                        conn.onAbort();
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

                        // For robustness (avoiding violating MAX_RINGING_CALLS) + due to limitations of the current UI:
                        // End any existing or lingering ringing connections before calling addNewIncomingCall() as it would fail:
                        // (event: onCreateIncomingCallFailed reason: MAX_RINGING_CALLS)
                        for (String key : connectionMap.keySet()) {
                            Connection conn = connectionMap.get(key);
                            if (conn.getState() == Connection.STATE_RINGING) {
                                Log.d(TAG, "Disconnecting existing ringing connection for call_uuid: " + key + " before adding new call");
                                conn.setDisconnected(new DisconnectCause(DisconnectCause.LOCAL)); // Connection will later be destroyed + removed (see connection.onStateChanged).
                            }
                        }

                        Log.d(TAG, "Adding new incoming connection, callUUID: " + callUUID);

                        // After this a new connection is created (see onCreateIncomingConnection below)
                        tm.addNewIncomingCall(phoneAccountHandle, callInfo);
                        connectionAddedMap.put(callUUID, true);
                    }
                }
            }
        }

        return START_STICKY; // System will attempt to re-create the service if it is killed.
    }

    public static Connection getConnectionByPayload(String pushMessagePayload) {
        JSONObject payload;
        try {
            payload = new JSONObject(pushMessagePayload);
        } catch (JSONException e) {
            throw new RuntimeException("Failed to parse payload JSON string: " + e);
        }

        String sessionId = payload.optString("session_id", getSessionIdFromCallUUID(payload.optString("call_uuid")));

        return connectionMap.get(sessionId);
    }

    public void showWebApp(String userAction, String payload) {
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
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        intent.putExtra("userAction", userAction); // So web app (if desired) could use this to automatically answer/decline the call (can read the intent using cordova-plugin-intent)
        intent.putExtra("payload", payload);
        this.startActivity(intent);
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

        String callerName = payload.optString("from", "UNKNOWN CALLER");

        connectionAddedMap.remove(callUUID);

        String sessionId = getSessionIdFromCallUUID(callUUID);
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

        CordovaCall.emitEvent("receiveCall", new PluginResult(PluginResult.Status.OK, "receiveCall event called successfully"));

        return connection;
    }

    public static String getSessionIdFromCallUUID(String callUUID) {
        String[] parts = callUUID.split(";");

        if (parts.length >= 2) {
            return parts[0] + parts[1];
        } else {
            Log.e(TAG, "can not extract sessionId from callUUID: " + callUUID);
            return callUUID; // For robustness just use something
        }
    }

    @Override
    public void onCreateIncomingConnectionFailed(PhoneAccountHandle connectionManagerPhoneAccount, ConnectionRequest request) {
        super.onCreateIncomingConnectionFailed(connectionManagerPhoneAccount, request);
        Bundle requestExtras = request.getExtras() != null ? request.getExtras() : new Bundle();
        String payloadString = requestExtras.getString("payload");
        Log.e(TAG, "onCreateIncomingConnectionFailed, payload: " + payloadString);
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
