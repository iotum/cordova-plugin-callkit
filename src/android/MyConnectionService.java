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
import java.util.HashMap;

public class MyConnectionService extends ConnectionService {

    static final String TAG = "MyConnectionService";
    private static final HashMap<String, Connection> connectionMap = new HashMap<String, Connection>(); // Keys are call_uuid strings
    private static final HashMap<String, Boolean> connectionAddedMap = new HashMap<String, Boolean>(); // Keys are call_uuid strings, true if addIncomingCall called for the given call uuid.
    Context context;

    private CallActionReceiver callActionReceiver;

    @Override
    public void onCreate() {
        super.onCreate();

        this.callActionReceiver = new CallActionReceiver();
        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction("rocks.app.callbridge.CALL_ANSWER");
        intentFilter.addAction("rocks.app.callbridge.CALL_DECLINE");
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

                        // ==== TEMPORARY CODE =====
                        // Delete this later once all issues causing lingering ringing calls are addressed
                        for (String key : connectionMap.keySet()) {
                            Log.d(TAG, "Lingering call found: " + key + " cleaning up to avoid violating max ringing calls");
                            disconnectConnection(key, DisconnectCause.LOCAL);
                        }
                        // ==== END TEMPORARY CODE ====

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
        Log.d(TAG, "getConnectionByPayload: " + pushMessagePayload + "    connectionMap: " + connectionMap);
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
        return connectionMap.get(activeConnectionUUID);
    }

    public static void endActiveCall() {
        if (activeConnectionUUID != null) {
            disconnectConnection(activeConnectionUUID, DisconnectCause.LOCAL);
        }
    }

    public static void disconnectConnection(String callUUID, int cause) {
        Connection conn = connectionMap.get(callUUID);
        if (conn != null) {
            Log.d(TAG, "Disconnecting connection for callUUID: " + callUUID);
            conn.setDisconnected(new DisconnectCause(cause));
            conn.destroy();
            connectionMap.remove(callUUID);
        }
        if (activeConnectionUUID != null && activeConnectionUUID.equals(callUUID)) {
            activeConnectionUUID = null;
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

            @Override
            public void onShowIncomingCallUi() { // Only for self managed connections
                Log.d(TAG, "onShowIncomingCallUi() invoked, for call_uuid: " + callUUID);
                this.callNotification = new CallNotification(payloadString, context);
                this.callNotification.show();
            }

            private void closeNotification() {
                if (this.callNotification != null) {
                    this.callNotification.close();
                    this.callNotification = null;
                }
            }

            @Override
            public void onAnswer() {
                Log.d(TAG, "onAnswer()");
                this.closeNotification();

                this.setActive();
                activeConnectionUUID = callUUID;

                showWebApp("answerCall", payloadString);

                CordovaCall.emitEvent("answer", new PluginResult(PluginResult.Status.OK, payloadString));
            }

            @Override
            public void onReject() {
                Log.d(TAG, "onReject, call_uuid: " + callUUID);
                this.closeNotification();

                disconnectConnection(callUUID, DisconnectCause.REJECTED);

                showWebApp("declineCall", payloadString); // Controversial UX but doing so that we can tell the web app to reject the call (which may let the caller not it was declined)

                CordovaCall.emitEvent("reject", new PluginResult(PluginResult.Status.OK, payloadString));
            }

            @Override
            public void onAbort() {
                Log.d(TAG, "onAbort, call_uuid: " + callUUID);
                this.closeNotification();

                disconnectConnection(callUUID, DisconnectCause.CANCELED);
            }

            @Override
            public void onDisconnect() {
                Log.d(TAG, "onDisconnect, call_uuid: " + callUUID);
                this.closeNotification();

                disconnectConnection(callUUID, DisconnectCause.LOCAL);

                CordovaCall.emitEvent("hangup", new PluginResult(PluginResult.Status.OK, "hangup event called successfully"));
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
            }

            @Override
            public void onDisconnect() {
                DisconnectCause cause = new DisconnectCause(DisconnectCause.LOCAL);
                this.setDisconnected(cause);
                this.destroy();
                activeConnectionUUID = null;
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
                }
            }
        };
        connection.setAddress(Uri.parse(request.getExtras().getString("to")), TelecomManager.PRESENTATION_ALLOWED);
        Icon icon = CordovaCall.getIcon();
        if(icon != null) {
            StatusHints statusHints = new StatusHints((CharSequence)"", icon, new Bundle());
            connection.setStatusHints(statusHints);
        }
        connection.setDialing();
        CordovaCall.emitEvent("sendCall", new PluginResult(PluginResult.Status.OK, "sendCall event called successfully"));
        return connection;
    }
}
