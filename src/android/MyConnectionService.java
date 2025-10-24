package com.dmarc.cordovacall;

import org.apache.cordova.CallbackContext;
import org.apache.cordova.PluginResult;
import org.json.JSONException;
import org.json.JSONObject;

import android.content.Intent;
import android.content.Context;
import android.graphics.drawable.Icon;
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
    Context context;

    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            Log.d(TAG, "onStartCommand called with no intent");
            return super.onStartCommand(intent, flags, startId);
        }

        String intentAction = intent.getAction();

        Log.d(TAG, "onStartCommand called with intent, action: " + intentAction);

        if (intentAction.equals("INCOMING_CALL_INVITE")) {
            String from = intent.getStringExtra("from");
            String payloadString = intent.getStringExtra("payload");

            JSONObject payload = null;
            try {
                payload = new JSONObject(payloadString);
            } catch (JSONException e) {
                throw new RuntimeException("Failed to parse payload JSON string: " + e);
            }

            if (payload.optBoolean("dismiss", false)) {
                Log.d(TAG, "received intent indicating call is dismissed");

                // TODO: Close the corresponding connection and notification
            } else {
                Log.d(TAG, "creating new incoming connection, associated with app PhoneAccount, from: " + from + " payload: " + payload);
                TelecomManager tm = (TelecomManager) this.getApplicationContext().getSystemService(Context.TELECOM_SERVICE);

                context = (Context) this.getApplicationContext();

                PhoneAccountHandle phoneAccountHandle = PhoneAccountManager.getPhoneAccountHandle(context);

                Bundle callInfo = new Bundle();
                callInfo.putString("payload", payloadString);

                // After this a new connection is created (see onCreateIncomingConnection below)
                tm.addNewIncomingCall(phoneAccountHandle, callInfo);
            }
        }

        return super.onStartCommand(intent, flags, startId);
    }

    private static Connection conn;

    public static Connection getConnection() {
        return conn;
    }

    public static void deinitConnection() {
        conn = null;
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

        String callUUID = payload.optString("call_uuid", Integer.toString(new Random().nextInt(1002) + 1));

        final CallNotification callNotification = new CallNotification(payloadString, context);

        final Connection connection = new Connection() {
            // CallNotification callNotification;

            @Override
            public void onShowIncomingCallUi() {
                // TODO this should be invoked automatically for self-managed PhoneAccount connections, but its not....
                Log.d(TAG, "onShowIncomingCallUi()");
                // this.callNotification = new CallNotification(payloadString, context);
                // this.callNotification.show();
            }

            @Override
            public void onAnswer() {
                Log.d(TAG, "onAnswer()");

                this.setActive();

                // Allow enough time for our app to open and register the answer callback
                final Handler handler = new Handler();
                handler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        CordovaCall.emitEvent("answer", new PluginResult(PluginResult.Status.OK, payloadString));
                    }
                }, 1000);
            }

            @Override
            public void onReject() {
                DisconnectCause cause = new DisconnectCause(DisconnectCause.REJECTED);
                this.setDisconnected(cause);
                this.destroy();
                conn = null;
                CordovaCall.emitEvent("reject", new PluginResult(PluginResult.Status.OK, payloadString));
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
                conn = null;

                CordovaCall.emitEvent("hangup", new PluginResult(PluginResult.Status.OK, "hangup event called successfully"));

                if (callNotification != null) {
                    callNotification.close();
                }
            }
        };

        connection.setCallerDisplayName(payload.optString("from", "UNKNOWN CALLER"), TelecomManager.PRESENTATION_ALLOWED);

        Icon icon = CordovaCall.getIcon();
        if(icon != null) {
            StatusHints statusHints = new StatusHints((CharSequence)"", icon, new Bundle());
            connection.setStatusHints(statusHints);
        }
        conn = connection;
        CordovaCall.emitEvent("receiveCall", new PluginResult(PluginResult.Status.OK, "receiveCall event called successfully"));

        connectionMap.put(callUUID, connection);

        // TODO move this into connection.showIncomingCallUi()
        Log.d(TAG, "Showing call notification (after connection creation)");
        callNotification.show();

        return connection;
    }

    @Override
    public void onCreateIncomingConferenceFailed(@Nullable PhoneAccountHandle connectionManagerPhoneAccount, @Nullable ConnectionRequest request) {
        super.onCreateIncomingConferenceFailed(connectionManagerPhoneAccount, request);
        Log.d(TAG, "onCreateIncomingConferenceFailed");
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
                conn = null;
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
        conn = connection;
        CordovaCall.emitEvent("sendCall", new PluginResult(PluginResult.Status.OK, "sendCall event called successfully"));
        return connection;
    }
}
