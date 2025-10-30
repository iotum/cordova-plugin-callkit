package com.dmarc.cordovacall;

import com.dmarc.cordovacall.CallActionReceiver;
import org.apache.cordova.CallbackContext;
import org.apache.cordova.PluginResult;
import org.json.JSONException;
import org.json.JSONObject;

import android.content.ComponentName;
import android.content.Intent;
import android.content.Context;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.os.Bundle;
import android.telecom.Connection;
import android.telecom.ConnectionRequest;
import android.telecom.ConnectionService;
import android.telecom.DisconnectCause;
import android.telecom.PhoneAccount;
import android.telecom.PhoneAccountHandle;
import android.telecom.StatusHints;
import android.telecom.TelecomManager;
import android.os.Handler;
import android.net.Uri;
import android.util.Log;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Random;

public class MyConnectionService extends ConnectionService {

    static final String TAG = "MyConnectionService";
    private static HashMap<String, Connection> connectionMap = new HashMap<String, Connection>(); // Keys are call_uuid strings
    private static HashMap<String, Boolean> connectionAddedMap = new HashMap<String, Boolean>(); // Keys are call_uuid strings, true if addIncomingCall called for the given call uuid.
    Context context;

    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            Log.d(TAG, "onStartCommand called with no intent");
            return super.onStartCommand(intent, flags, startId);
        }

        String intentAction = intent.getAction();

        Log.d(TAG, "onStartCommand called with intent, action: " + intentAction);

        CallActionReceiver callActionReceiver = new CallActionReceiver();
        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction("rocks.app.callbridge.CALL_ANSWER");
        intentFilter.addAction("rocks.app.callbridge.CALL_DECLINE");
        registerReceiver(callActionReceiver, intentFilter, RECEIVER_NOT_EXPORTED);

        if (intentAction.equals("INCOMING_CALL_INVITE")) {
            String from = intent.getStringExtra("from");
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

                        Log.d(TAG, "Adding new incoming connection, payload: " + payload);

                        // After this a new connection is created (see onCreateIncomingConnection below)
                        tm.addNewIncomingCall(phoneAccountHandle, callInfo);
                        connectionAddedMap.put(callUUID, true);
                    }
                }
            }
        }

        return START_STICKY; // System will attempt to re-create the service if it is killed.
    }

    private static Connection activeConnection;

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
        intent.putExtra("userAction", userAction); // So web app (when ready can process this) may need to use cordova-plugin-intent to read
        intent.putExtra("payload", payload);
        this.startActivity(intent);
    }

    public static Connection getConnection() {
        return activeConnection;
    }

    public static void deinitConnection() {
        activeConnection = null;
    }

    public static void disconnectConnection(String callUUID, int cause) {
        Connection conn = connectionMap.get(callUUID);
        if (activeConnection == conn) {
            activeConnection = null;
        }
        if (conn != null) {
            conn.setDisconnected(new DisconnectCause(cause));
            conn.destroy();
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

        final Connection connection = new Connection() {
            CallNotification callNotification;

            @Override
            public void onShowIncomingCallUi() { // Only for self managed connections
                Log.d(TAG, "onShowIncomingCallUi() invoked, for call_uuid: " + callUUID);
                this.callNotification = new CallNotification(payloadString, context);
                this.callNotification.show();
            }

            @Override
            public void onAnswer() {
                Log.d(TAG, "onAnswer()");
                this.setActive();
                activeConnection = this;
                showWebApp("answerCall", payloadString);

                CordovaCall.emitEvent("answer", new PluginResult(PluginResult.Status.OK, payloadString));
            }

            @Override
            public void onReject() {
                Log.d(TAG, "onReject, call_uuid: " + callUUID);
                disconnectConnection(callUUID, DisconnectCause.REJECTED);
                if (callNotification != null) {
                    callNotification.close();
                }

                showWebApp("declineCall", payloadString); // Controversial UX but doing so that we can tell the web app to reject the call (which may let the caller not it was declined)

                CordovaCall.emitEvent("reject", new PluginResult(PluginResult.Status.OK, payloadString));
            }

            @Override
            public void onAbort() {
                Log.d(TAG, "onAbort, call_uuid: " + callUUID);
                disconnectConnection(callUUID, DisconnectCause.CANCELED);
                if (callNotification != null) {
                    callNotification.close();
                }
            }

            @Override
            public void onDisconnect() {
                Log.d(TAG, "onDisconnect, call_uuid: " + callUUID);
                disconnectConnection(callUUID, DisconnectCause.LOCAL);
                if (callNotification != null) {
                    callNotification.close();
                }
                CordovaCall.emitEvent("hangup", new PluginResult(PluginResult.Status.OK, "hangup event called successfully"));
            }
        };

        connection.setCallerDisplayName(payload.optString("from", "UNKNOWN CALLER"), TelecomManager.PRESENTATION_ALLOWED);

        Icon icon = CordovaCall.getIcon();
        if(icon != null) {
            StatusHints statusHints = new StatusHints((CharSequence)"", icon, new Bundle());
            connection.setStatusHints(statusHints);
        }

        connectionMap.put(callUUID, connection);

        connection.setConnectionProperties(Connection.PROPERTY_SELF_MANAGED);

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
                activeConnection = null;
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
        activeConnection = connection;
        CordovaCall.emitEvent("sendCall", new PluginResult(PluginResult.Status.OK, "sendCall event called successfully"));
        return connection;
    }
}
