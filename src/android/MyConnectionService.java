package com.dmarc.cordovacall;

import org.apache.cordova.CallbackContext;
import org.apache.cordova.PluginResult;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.Context;
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
import java.util.ArrayList;
import android.util.Log;
import org.json.JSONObject;
import org.json.JSONException;

public class MyConnectionService extends ConnectionService {

    private static String TAG = "MyConnectionService";
    private static Connection conn;

    public static Connection getConnection() {
        return conn;
    }

    public static void deinitConnection() {
        conn = null;
    }

    private static JSONObject convertBundleToJson(Bundle bundle) {
        JSONObject jsonObject = new JSONObject();
        if (bundle != null) {
            for (String key : bundle.keySet()) {
                Object value = bundle.get(key);
                try {
                    jsonObject.put(key, value);
                } catch (JSONException e) {
                    Log.e(TAG, "Failed to convert bundle to JSON", e);
                }
            }
        }
        return jsonObject;
    }

    @Override
    public Connection onCreateIncomingConnection(final PhoneAccountHandle connectionManagerPhoneAccount, final ConnectionRequest request) {
        final Connection connection = new Connection() {
            @Override
            public void onAnswer() {
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
                        ArrayList<CallbackContext> callbackContexts = CordovaCall.getCallbackContexts().get("answer");
                        for (final CallbackContext callbackContext : callbackContexts) {
                            CordovaCall.getCordova().getThreadPool().execute(new Runnable() {
                                public void run() {
                                    Bundle data = request.getExtras() != null ? request.getExtras() : new Bundle();
                                    PluginResult result = new PluginResult(PluginResult.Status.OK, convertBundleToJson(data));
                                    result.setKeepCallback(true);
                                    callbackContext.sendPluginResult(result);
                                }
                            });
                        }
                    }
                }, 1000);
            }

            @Override
            public void onReject() {
                DisconnectCause cause = new DisconnectCause(DisconnectCause.REJECTED);
                this.setDisconnected(cause);
                this.destroy();
                conn = null;
                ArrayList<CallbackContext> callbackContexts = CordovaCall.getCallbackContexts().get("reject");
                for (final CallbackContext callbackContext : callbackContexts) {
                    CordovaCall.getCordova().getThreadPool().execute(new Runnable() {
                        public void run() {
                            PluginResult result = new PluginResult(PluginResult.Status.OK, "reject event called successfully");
                            result.setKeepCallback(true);
                            callbackContext.sendPluginResult(result);
                        }
                    });
                }
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
                ArrayList<CallbackContext> callbackContexts = CordovaCall.getCallbackContexts().get("hangup");
                for (final CallbackContext callbackContext : callbackContexts) {
                    CordovaCall.getCordova().getThreadPool().execute(new Runnable() {
                        public void run() {
                            PluginResult result = new PluginResult(PluginResult.Status.OK, "hangup event called successfully");
                            result.setKeepCallback(true);
                            callbackContext.sendPluginResult(result);
                        }
                    });
                }
            }
        };
        connection.setAddress(Uri.parse(request.getExtras().getString("from")), TelecomManager.PRESENTATION_ALLOWED);
        Icon icon = CordovaCall.getIcon();
        if(icon != null) {
            StatusHints statusHints = new StatusHints((CharSequence)"", icon, new Bundle());
            connection.setStatusHints(statusHints);
        }
        conn = connection;
        ArrayList<CallbackContext> callbackContexts = CordovaCall.getCallbackContexts().get("receiveCall");
        for (final CallbackContext callbackContext : callbackContexts) {
            CordovaCall.getCordova().getThreadPool().execute(new Runnable() {
                public void run() {
                    PluginResult result = new PluginResult(PluginResult.Status.OK, "receiveCall event called successfully");
                    result.setKeepCallback(true);
                    callbackContext.sendPluginResult(result);
                }
            });
        }
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
                ArrayList<CallbackContext> callbackContexts = CordovaCall.getCallbackContexts().get("hangup");
                for (final CallbackContext callbackContext : callbackContexts) {
                    CordovaCall.getCordova().getThreadPool().execute(new Runnable() {
                        public void run() {
                            PluginResult result = new PluginResult(PluginResult.Status.OK, "hangup event called successfully");
                            result.setKeepCallback(true);
                            callbackContext.sendPluginResult(result);
                        }
                    });
                }
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
        ArrayList<CallbackContext> callbackContexts = CordovaCall.getCallbackContexts().get("sendCall");
        if(callbackContexts != null) {
            for (final CallbackContext callbackContext : callbackContexts) {
                CordovaCall.getCordova().getThreadPool().execute(new Runnable() {
                    public void run() {
                        PluginResult result = new PluginResult(PluginResult.Status.OK, "sendCall event called successfully");
                        result.setKeepCallback(true);
                        callbackContext.sendPluginResult(result);
                    }
                });
            }
        }
        return connection;
    }
}
