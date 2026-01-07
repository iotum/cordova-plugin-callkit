package com.dmarc.cordovacall;

import static android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL;

import org.apache.cordova.PluginResult;
import org.json.JSONException;
import org.json.JSONObject;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Intent;
import android.content.Context;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.drawable.Icon;
import android.media.AudioManager;
import android.os.Build;
import android.os.Bundle;
import android.telecom.Connection;
import android.telecom.ConnectionRequest;
import android.telecom.ConnectionService;
import android.telecom.DisconnectCause;
import android.telecom.PhoneAccountHandle;
import android.telecom.StatusHints;
import android.telecom.TelecomManager;
import android.os.Handler;
import android.net.Uri;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;
import androidx.lifecycle.DefaultLifecycleObserver;
import androidx.lifecycle.LifecycleOwner;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import java.util.concurrent.ConcurrentHashMap;

public class MyConnectionService extends ConnectionService {

    static final String TAG = "MyConnectionService";
    private static final ConcurrentHashMap<String, Connection> connectionMap = new ConcurrentHashMap<String, Connection>(); // Keys are call_uuid strings
    private static final ConcurrentHashMap<String, Boolean> connectionAddedMap = new ConcurrentHashMap<String, Boolean>(); // Keys are call_uuid strings, true if addIncomingCall called for the given call uuid.
    Context context;

    private CallActionReceiver callActionReceiver;

    // TODO: store the outgoing connections in connectionMap() to do that we will need a call UUID which we could pass into the app through sendCall
    // we can then get rid of this variable, and just always use connectionMap() + connection UUIDs to access both incoming and outgoing connections.
    private static Connection activeOutgoingConnection;

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

            if (payload.optBoolean("dismiss", false)) {
                Log.d(TAG, "received intent with payload.dismiss indicating call is dismissed, call_uuid: " + callUUID);
                Connection conn = connectionMap.get(callUUID);
                if (conn == null) {
                    Log.e(TAG, "Cannot disconnect. No connection found with call_uuid: " + callUUID);
                } else {
                    if (conn.getState() == Connection.STATE_DISCONNECTED) {
                        Log.d(TAG, "Call is already marked disconnected, call_uuid: " + callUUID);
                    } else {
                        Log.d(TAG, "Calling connection.onAbort() in response to pushMessagePayload.dismiss, call_uuid: " + callUUID);
                        conn.onAbort();
                    }
                }
            } else {
                if (connectionMap.get(callUUID) != null) {
                    Log.d(TAG, "A connection is already created for call_uuid: " + callUUID);
                } else {
                    if (connectionAddedMap.containsKey(callUUID)) {
                        Log.d(TAG, "A connection was already added for call_uuid: " + callUUID);
                    } else {
                        TelecomManager tm = (TelecomManager) this.getApplicationContext().getSystemService(Context.TELECOM_SERVICE);

                        context = (Context) this.getApplicationContext();

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

    private static String activeConnectionUUID;

    public static Connection getConnectionByPayload(String pushMessagePayload) {
        JSONObject payload;
        try {
            payload = new JSONObject(pushMessagePayload);
        } catch (JSONException e) {
            throw new RuntimeException("Failed to parse payload JSON string: " + e);
        }
        String callUUID = payload.optString("call_uuid");
        return connectionMap.get(callUUID);
    }

    public void showWebApp(String userAction, String payload) {
        Log.d(TAG, "showWebApp()");
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

    public static Connection getConnection() {
        // Note: if your currently in an active connection,
        // calling TelecomManager.addCall() would fail
        // Thus you can really have either (but not both) an active outgoing or an active incoming connection
        if (activeOutgoingConnection != null) {
            return activeOutgoingConnection;
        }
        return activeConnectionUUID != null ? connectionMap.get(activeConnectionUUID) : null;
    }

    public static void endActiveCall() {
        if (activeConnectionUUID != null) {
            Connection conn = connectionMap.get(activeConnectionUUID);
            conn.setDisconnected(new DisconnectCause(DisconnectCause.LOCAL));
        }
        if (activeOutgoingConnection != null) {
            activeOutgoingConnection.setDisconnected(new DisconnectCause(DisconnectCause.LOCAL));
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

        connectionAddedMap.remove(callUUID);

        final Connection connection = new Connection() {
            CallNotification callNotification;
            Runnable mainActivityChangeListener;

            @Override
            public void onShowIncomingCallUi() { // Only for self managed connections
                Log.d(TAG, "onShowIncomingCallUi() invoked, for call_uuid: " + callUUID);
                this.setRinging();

                this.mainActivityChangeListener = new Runnable() {
                    @Override
                    public void run() {
                        if (getState() == Connection.STATE_ACTIVE) {
                            updateNotification();
                        }
                    }
                };

                CordovaCall.registerMainActivityStateChangeListener(this.mainActivityChangeListener);

                this.callNotification = new CallNotification(payloadString, context);
                Notification notification = this.callNotification.build(CallNotification.Style.INCOMING_CALL);

                startForeground(this.callNotification.getNotificationID(), notification, FOREGROUND_SERVICE_TYPE_PHONE_CALL);
            }

            private void updateNotification() {
                CallNotification.Style style = this.getState() == Connection.STATE_RINGING ? CallNotification.Style.INCOMING_CALL : CallNotification.Style.ONGOING_CALL;

                // Lowering the priority of the ongoing call notification (when the MainActivity is in the foreground)
                // is done to prevent the notification from being displayed as a card that
                // would overlap the top portion of the MainActivity.
                boolean isInForeground = CordovaCall.isMainActivityInForeground();
                int priority = (style == CallNotification.Style.ONGOING_CALL && isInForeground) ? NotificationCompat.PRIORITY_MIN : NotificationCompat.PRIORITY_HIGH;

                Log.d(TAG, "updating notification style: " + style + " priority: " + priority);
                Notification notification = this.callNotification.build(style, priority);

                // Call startForeground again which allows for updating the associated notification of the same ID
                startForeground(this.callNotification.getNotificationID(), notification, FOREGROUND_SERVICE_TYPE_PHONE_CALL);
            }

            @Override
            public void onAnswer() {
                Log.d(TAG, "onAnswer()");
                this.setActive();
                activeConnectionUUID = callUUID;

                showWebApp("answerCall", payloadString);
                CordovaCall.emitEvent("answer", new PluginResult(PluginResult.Status.OK, payloadString));
            }

            @Override
            public void onReject() {
                Log.d(TAG, "onReject, call_uuid: " + callUUID);
                this.setDisconnected(new DisconnectCause(DisconnectCause.REJECTED));

                showWebApp("declineCall", payloadString); // Controversial UX but doing so that we can tell the web app to reject the call (which may let the caller not it was declined)

                CordovaCall.emitEvent("reject", new PluginResult(PluginResult.Status.OK, payloadString));
            }

            @Override
            public void onAbort() {
                Log.d(TAG, "onAbort, call_uuid: " + callUUID);
                this.setDisconnected(new DisconnectCause(DisconnectCause.CANCELED));
            }

            @Override
            public void onDisconnect() {
                Log.d(TAG, "onDisconnect, call_uuid: " + callUUID);
                this.setDisconnected(new DisconnectCause(DisconnectCause.LOCAL));
                CordovaCall.emitEvent("hangup", new PluginResult(PluginResult.Status.OK, "hangup event called successfully"));
            }

            @Override
            public void onStateChanged(int state) {
                super.onStateChanged(state);
                Log.d(TAG, "connection onStateChanged: " + state);

                switch (state) {
                    case Connection.STATE_ACTIVE:
                        updateNotification(); // Will update it to an "ongoing" CallStyle notification
                        break;
                    case Connection.STATE_DISCONNECTED:
                        stopForeground(STOP_FOREGROUND_REMOVE); // Cancels the associated notification
                        connectionMap.remove(callUUID);
                        if (activeConnectionUUID != null && activeConnectionUUID.equals(callUUID)) {
                            activeConnectionUUID = null;
                        }
                        if (this.mainActivityChangeListener != null) {
                            CordovaCall.unregisterMainActivityStateChangeListener(this.mainActivityChangeListener);
                        }
                        this.destroy();
                        break;
                }

                Log.d(TAG, "broadcasting connection_state_changed call_uuid: " + callUUID + " state: " + state);
                Intent intent = new Intent("connection_state_changed");
                intent.putExtra("call_uuid", callUUID);
                intent.putExtra("state", state);
                LocalBroadcastManager.getInstance(context).sendBroadcast(intent);
            }
        };

        connection.setCallerDisplayName(payload.optString("from", "UNKNOWN CALLER"), TelecomManager.PRESENTATION_ALLOWED);

        Icon icon = CordovaCall.getIcon();
        if(icon != null) {
            StatusHints statusHints = new StatusHints((CharSequence)"", icon, new Bundle());
            connection.setStatusHints(statusHints);
        }

        Log.d(TAG, "Created connection for callUUID: " + callUUID);
        connection.setConnectionProperties(Connection.PROPERTY_SELF_MANAGED);
        connectionMap.put(callUUID, connection);

        CordovaCall.emitEvent("receiveCall", new PluginResult(PluginResult.Status.OK, "receiveCall event called successfully"));

        return connection;
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
        final Connection connection = new Connection() {
            @Override
            public void onAnswer() {
                super.onAnswer();
            }

            @Override
            public void onReject() {
                super.onReject();
            }

            @Override
            public void onAbort() {
                super.onAbort();
                this.setDisconnected(new DisconnectCause(DisconnectCause.CANCELED));
            }

            @Override
            public void onDisconnect() {
                DisconnectCause cause = new DisconnectCause(DisconnectCause.LOCAL);
                this.setDisconnected(cause);
                CordovaCall.emitEvent("hangup", new PluginResult(PluginResult.Status.OK, "hangup event called successfully"));
            }

            @Override
            public void onStateChanged(int state) {
                if(state == Connection.STATE_DIALING) {
                    final Handler handler = new Handler();
                    handler.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            Intent intent = new Intent(CordovaCall.getCordova().getActivity().getApplicationContext(), CordovaCall.getCordova().getActivity().getClass());
                            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_SINGLE_TOP);
                            CordovaCall.getCordova().getActivity().getApplicationContext().startActivity(intent);
                        }
                    }, 500);
                } else if (state == Connection.STATE_DISCONNECTED) {
                    // In all cases when connection transitions to STATE_DISCONNECTED (both onAbort() and onDisconnect())
                    // Ensure the connection is destroyed, etc.
                    this.destroy();
                    activeOutgoingConnection = null;
                    activeConnectionUUID = null;
                    stopForeground(true); // Return ConnectionService to background and cancels notification
                }
            }
        };
        connection.setAddress(Uri.parse(request.getExtras().getString("to")), TelecomManager.PRESENTATION_ALLOWED);
        Icon icon = CordovaCall.getIcon();
        if(icon != null) {
            StatusHints statusHints = new StatusHints((CharSequence)"", icon, new Bundle());
            connection.setStatusHints(statusHints);
        }

        final String OUTGOING_CALL_NOTIFICATION_CHANNEL_ID = "outgoing_calls";

        NotificationChannel serviceChannel = new NotificationChannel(
                OUTGOING_CALL_NOTIFICATION_CHANNEL_ID,
                "Outgoing Calls",
                NotificationManager.IMPORTANCE_LOW
        );
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.createNotificationChannel(serviceChannel);
        }

        final int OUTGOING_CALL_NOTIFICATION_ID = 1;
        Notification notification = new NotificationCompat.Builder(this, OUTGOING_CALL_NOTIFICATION_CHANNEL_ID)
                .setContentTitle("Outgoing Call")
                .setContentText("Active call")
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)
                .build();

        startForeground(OUTGOING_CALL_NOTIFICATION_ID, notification);

        AudioManager audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        audioManager.setMode(AudioManager.MODE_IN_COMMUNICATION);

        connection.setConnectionCapabilities(
                Connection.CAPABILITY_MUTE | Connection.CAPABILITY_SUPPORT_HOLD
        );

        // Specifically for self-managed connections (like most VoIP apps)
        // This tells the system "I am handling the audio stream myself"
        connection.setConnectionProperties(Connection.PROPERTY_SELF_MANAGED);
        connection.setAudioModeIsVoip(true);

        connection.setDialing();
        CordovaCall.emitEvent("sendCall", new PluginResult(PluginResult.Status.OK, "sendCall event called successfully"));

        activeOutgoingConnection = connection;
        return connection;
    }
}
