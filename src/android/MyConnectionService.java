package com.dmarc.cordovacall;

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

public class MyConnectionService extends ConnectionService {
    private static PhoneAccountHandle phoneAccountHandle;
    private static PhoneAccount phoneAccount;

    static final String TAG = "MyConnectionService";

    public int onStartCommand(Intent intent, int flags, int startId) {
        String intentAction = intent.getAction();
        Log.d(TAG, "==> onStartCommand " + intentAction);

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

                Context context = (Context) this.getApplicationContext();

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
        String from = requestExtras.getString("from");
        String payloadString = requestExtras.getString("payload");
        Log.d(TAG, "onCreateIncomingConnection from: " + from + " payload: " + payloadString);

        final Connection connection = new Connection() {
            private CallNotification callNotification;

            @Override
            public void onShowIncomingCallUi() {
                Log.d(TAG, "onShowIncomingCallUi()");
            }

            @Override
            public void onAnswer() {
                Log.d(TAG, "onAnswer()");

                this.setActive();
                // Intent intent = new Intent(CordovaCall.getCordova().getActivity().getApplicationContext(), CordovaCall.getCordova().getActivity().getClass());
                // // intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_SINGLE_TOP);
                // intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_SINGLE_TOP|Intent.FLAG_FROM_BACKGROUND);
                // CordovaCall.getCordova().getActivity().getApplicationContext().startActivity(intent);
                // ArrayList<CallbackContext> callbackContexts = CordovaCall.getCallbackContexts().get("answer");
                // for (final CallbackContext callbackContext : callbackContexts) {
                //     CordovaCall.getCordova().getThreadPool().execute(new Runnable() {
                //         public void run() {
                //             Bundle data = request.getExtras() != null ? request.getExtras() : new Bundle();
                //             PluginResult result = new PluginResult(PluginResult.Status.OK, convertBundleToJson(data));
                //             result.setKeepCallback(true);
                //             callbackContext.sendPluginResult(result);
                //         }
                //     });
                // }
                // TelecomManager tm = (TelecomManager) CordovaCall.getCordova().getActivity().getApplicationContext().getSystemService(Context.TELECOM_SERVICE);
                // tm.showInCallScreen(false);

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
            }
        };

        connection.setCallerDisplayName(from, TelecomManager.PRESENTATION_ALLOWED);

        Icon icon = CordovaCall.getIcon();
        if(icon != null) {
            StatusHints statusHints = new StatusHints((CharSequence)"", icon, new Bundle());
            connection.setStatusHints(statusHints);
        }
        conn = connection;
        CordovaCall.emitEvent("receiveCall", new PluginResult(PluginResult.Status.OK, "receiveCall event called successfully"));
        return connection;
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
