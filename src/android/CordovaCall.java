package com.dmarc.cordovacall;

import org.apache.cordova.CordovaPlugin;
import org.apache.cordova.CallbackContext;
import org.apache.cordova.CordovaInterface;
import org.apache.cordova.CordovaWebView;
import org.apache.cordova.PluginResult;

import android.app.Activity;
import android.os.Build;
import android.os.Bundle;
import android.telecom.PhoneAccount;
import android.telecom.PhoneAccountHandle;
import android.telecom.TelecomManager;
import android.content.Intent;
import android.content.Context;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.Manifest;
import android.telecom.Connection;
import org.json.JSONArray;
import org.json.JSONException;
import java.util.ArrayList;
import java.util.HashMap;
import android.graphics.drawable.Icon;
import android.media.AudioManager;
import android.util.Log;
import android.view.WindowManager;

public class CordovaCall extends CordovaPlugin {

    private static String TAG = "CordovaCall";
    public static final int CALL_PHONE_REQ_CODE = 0;
    public static final int REAL_PHONE_CALL = 1;
    private int permissionCounter = 0;
    private String pendingAction;
    private TelecomManager tm;

    private CallbackContext callbackContext;
    private String appName;
    private String from;
    private String to;
    private String realCallTo;
    private static HashMap<String, ArrayList<CallbackContext>> callbackContextMap = new HashMap<String, ArrayList<CallbackContext>>();
    static {
        callbackContextMap.put("receiveCall", new ArrayList<CallbackContext>());
        callbackContextMap.put("answer", new ArrayList<CallbackContext>());
        callbackContextMap.put("reject", new ArrayList<CallbackContext>());
        callbackContextMap.put("mute", new ArrayList<CallbackContext>());
        callbackContextMap.put("unmute", new ArrayList<CallbackContext>());
        callbackContextMap.put("hangup", new ArrayList<CallbackContext>());
        callbackContextMap.put("sendCall", new ArrayList<CallbackContext>());
        callbackContextMap.put("DTMF", new ArrayList<CallbackContext>());
    }
    private static ArrayList<HashMap> enqueuedEvents = new ArrayList<HashMap>();
    private static CordovaInterface cordovaInterface;
    private static CordovaWebView cordovaWebView;
    private static Icon icon;
    private static CordovaCall instance;

    public static HashMap<String, ArrayList<CallbackContext>> getCallbackContexts() {
        return callbackContextMap;
    }

    public static void emitEvent(String eventType, PluginResult result) {
        Log.d(TAG, "emitEvent: " + eventType + " result " + result.toString());
        ArrayList<CallbackContext> callbackContexts = CordovaCall.getCallbackContexts().get(eventType);
        if (callbackContexts.size() == 0) {
            Log.d(TAG, "nothing yet listening for CordovaCall event: " + eventType + " enqueuing message for later...");
            HashMap event = new HashMap();
            event.put("eventType", eventType);
            event.put("result", result);
            enqueuedEvents.add(event);
        }
        for (final CallbackContext callbackContext : callbackContexts) {
            CordovaCall.getCordova().getThreadPool().execute(new Runnable() {
                public void run() {
                    result.setKeepCallback(true);
                    callbackContext.sendPluginResult(result);
                }
            });
        }
    }

    public static CordovaInterface getCordova() {
        return cordovaInterface;
    }

    public static CordovaWebView getWebView() {
        return cordovaWebView;
    }

    public static Icon getIcon() {
        return icon;
    }

    public static CordovaCall getInstance() {
        return instance;
    }

    @Override
    public void initialize(CordovaInterface cordova, CordovaWebView webView) {
        cordovaInterface = cordova;
        cordovaWebView = webView;
        super.initialize(cordova, webView);

        Context context = cordova.getActivity().getApplicationContext();

        PhoneAccountManager.getPhoneAccount(context); // Ensure PhoneAccount is created and registered if not already

        this.tm = (TelecomManager) context.getSystemService(Context.TELECOM_SERVICE);

        Activity activity = cordova.getActivity();
        activity.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                    activity.setShowWhenLocked(true);
                    activity.setTurnScreenOn(true);
                } else {
                    activity.getWindow().addFlags(
                            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED |
                                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                    );
                }
            }
        });

        instance = this;
    }

    @Override
    public void onResume(boolean multitasking) {
        super.onResume(multitasking);
        this.checkCallPermission();
    }

    @Override
    public boolean execute(String action, JSONArray args, CallbackContext callbackContext) throws JSONException {
        Log.d(TAG, "executing action: " + action + " args: " + args);
        this.callbackContext = callbackContext;
        if (action.equals("receiveCall")) {
            Connection conn = MyConnectionService.getConnection();
            if(conn != null) {
                if(conn.getState() == Connection.STATE_ACTIVE) {
                    this.callbackContext.error("You can't receive a call right now because you're already in a call");
                } else {
                    this.callbackContext.error("You can't receive a call right now");
                }
            } else {
                from = args.getString(0);
                permissionCounter = 2;
                pendingAction = "receiveCall";
                this.checkCallPermission();
            }
            return true;
        } else if (action.equals("sendCall")) {
            Connection conn = MyConnectionService.getConnection();
            if(conn != null) {
                if(conn.getState() == Connection.STATE_ACTIVE) {
                    this.callbackContext.error("You can't make a call right now because you're already in a call");
                } else if(conn.getState() == Connection.STATE_DIALING) {
                    this.callbackContext.error("You can't make a call right now because you're already trying to make a call");
                } else {
                    this.callbackContext.error("You can't make a call right now");
                }
            } else {
                to = args.getString(0);
                permissionCounter = 2;
                pendingAction = "sendCall";
                this.checkCallPermission();
                /*cordova.getThreadPool().execute(new Runnable() {
                    public void run() {
                        getCallPhonePermission();
                    }
                });*/
            }
            return true;
        } else if (action.equals("connectCall")) {
            Connection conn = MyConnectionService.getConnection();
            if(conn == null) {
                this.callbackContext.error("No call exists for you to connect");
            } else if(conn.getState() == Connection.STATE_ACTIVE) {
                this.callbackContext.error("Your call is already connected");
            } else {
                conn.setActive();
                Intent intent = new Intent(this.cordova.getActivity().getApplicationContext(), this.cordova.getActivity().getClass());
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_SINGLE_TOP);
                this.cordova.getActivity().getApplicationContext().startActivity(intent);
                this.callbackContext.success("Call connected successfully");
            }
            return true;
        } else if (action.equals("endCall")) {
            Connection conn = MyConnectionService.getConnection();
            if(conn == null) {
                this.callbackContext.error("No call exists for you to end");
            } else {
                MyConnectionService.endActiveCall();
                ArrayList<CallbackContext> callbackContexts = CordovaCall.getCallbackContexts().get("hangup");
                for (final CallbackContext cbContext : callbackContexts) {
                    cordova.getThreadPool().execute(new Runnable() {
                        public void run() {
                            PluginResult result = new PluginResult(PluginResult.Status.OK, "hangup event called successfully");
                            result.setKeepCallback(true);
                            cbContext.sendPluginResult(result);
                        }
                    });
                }
                this.callbackContext.success("Call ended successfully");
            }
            return true;
        } else if (action.equals("registerEvent")) {
            String eventType = args.getString(0);
            CallbackContext callbackContext1 = this.callbackContext;
            ArrayList<CallbackContext> callbackContextList = callbackContextMap.get(eventType);
            callbackContextList.add(callbackContext1);
            for (final HashMap event : enqueuedEvents) {
                if (event.get("eventType").equals(eventType)) {
                    Log.d(TAG, "emitting enqueued event: " + event.toString() + " now that a listener is registered");
                    CordovaCall.getCordova().getThreadPool().execute(new Runnable() {
                        public void run() {
                            PluginResult result = (PluginResult) event.get("result");
                            result.setKeepCallback(true);
                            callbackContext1.sendPluginResult(result);
                        }
                    });
                }
            }
            enqueuedEvents.removeIf(e -> e.get("eventType").equals(eventType));
            return true;
        } else if (action.equals("setIcon")) {
            String iconName = args.getString(0);
            int iconId = this.cordova.getActivity().getApplicationContext().getResources().getIdentifier(iconName, "drawable", this.cordova.getActivity().getPackageName());
            if(iconId != 0) {
                icon = Icon.createWithResource(this.cordova.getActivity(), iconId);
                this.callbackContext.success("Icon Changed Successfully");
            } else {
                this.callbackContext.error("This icon does not exist. Make sure to add it to the res/drawable folder the right way.");
            }
            return true;
        } else if (action.equals("mute")) {
            this.mute();
            this.callbackContext.success("Muted Successfully");
            return true;
        } else if (action.equals("unmute")) {
            this.unmute();
            this.callbackContext.success("Unmuted Successfully");
            return true;
        } else if (action.equals("speakerOn")) {
            this.speakerOn();
            this.callbackContext.success("Speakerphone is on");
            return true;
        } else if (action.equals("speakerOff")) {
            this.speakerOff();
            this.callbackContext.success("Speakerphone is off");
            return true;
        } else if (action.equals("callNumber")) {
            realCallTo = args.getString(0);
            if(realCallTo != null) {
              cordova.getThreadPool().execute(new Runnable() {
                  public void run() {
                      callNumberPhonePermission();
                  }
              });
              this.callbackContext.success("Call Successful");
            } else {
              this.callbackContext.error("Call Failed. You need to enter a phone number.");
            }
            return true;
        } else if (action.equals("checkCallPermission")) {
            permissionCounter = 2;
            this.checkCallPermission();
            return true;
        }
        return false;
    }

    private void checkCallPermission() {
        if(permissionCounter >= 1) {
            PhoneAccountHandle handle = PhoneAccountManager.getPhoneAccountHandle(this.cordova.getActivity().getApplicationContext());
            PhoneAccount currentPhoneAccount = tm.getPhoneAccount(handle);
            if(currentPhoneAccount.isEnabled()) {
                if(pendingAction == "receiveCall") {
                    this.receiveCall();
                } else if(pendingAction == "sendCall") {
                    this.sendCall();
                }
            } else {
                if(permissionCounter == 2) {
                    Intent phoneIntent = new Intent(TelecomManager.ACTION_CHANGE_PHONE_ACCOUNTS);
                    phoneIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                    this.cordova.getActivity().getApplicationContext().startActivity(phoneIntent);
                } else {
                    this.callbackContext.error("You need to accept phone account permissions in order to send and receive calls");
                }
            }
        }
        permissionCounter--;
    }

    private void receiveCall() {
        Bundle callInfo = new Bundle();
        callInfo.putString("from",from);

        PhoneAccountHandle handle = PhoneAccountManager.getPhoneAccountHandle(this.cordova.getActivity().getApplicationContext());
        tm.addNewIncomingCall(handle, callInfo);

        permissionCounter = 0;
        this.callbackContext.success("Incoming call successful");

        this.bringAppToFront();
        this.tm.showInCallScreen(false);
    }

    private void sendCall() {
        Uri uri = Uri.fromParts("tel", to, null);
        Bundle callInfoBundle = new Bundle();
        callInfoBundle.putString("to",to);
        Bundle callInfo = new Bundle();
        callInfo.putParcelable(TelecomManager.EXTRA_OUTGOING_CALL_EXTRAS,callInfoBundle);

        PhoneAccountHandle handle = PhoneAccountManager.getPhoneAccountHandle(this.cordova.getActivity().getApplicationContext());
        callInfo.putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, handle);

        callInfo.putBoolean(TelecomManager.EXTRA_START_CALL_WITH_VIDEO_STATE, true);
        tm.placeCall(uri, callInfo);
        permissionCounter = 0;
        this.callbackContext.success("Outgoing call successful");
    }

    private void bringAppToFront() {
        Intent intent = new Intent(this.cordova.getActivity().getApplicationContext(), this.cordova.getActivity().getClass());
        // Intent.FLAG_ACTIVITY_REORDER_TO_FRONT Intent.FLAG_ACTIVITY_BROUGHT_TO_FRONT
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_BROUGHT_TO_FRONT | Intent.FLAG_FROM_BACKGROUND);
        this.cordova.getActivity().getApplicationContext().startActivity(intent);
    }

    private void mute() {
        AudioManager audioManager = (AudioManager) this.cordova.getActivity().getApplicationContext().getSystemService(Context.AUDIO_SERVICE);
        audioManager.setMicrophoneMute(true);
    }

    private void unmute() {
        AudioManager audioManager = (AudioManager) this.cordova.getActivity().getApplicationContext().getSystemService(Context.AUDIO_SERVICE);
        audioManager.setMicrophoneMute(false);
    }

    private void speakerOn() {
        AudioManager audioManager = (AudioManager) this.cordova.getActivity().getApplicationContext().getSystemService(Context.AUDIO_SERVICE);
        audioManager.setSpeakerphoneOn(true);
    }

    private void speakerOff() {
        AudioManager audioManager = (AudioManager) this.cordova.getActivity().getApplicationContext().getSystemService(Context.AUDIO_SERVICE);
        audioManager.setSpeakerphoneOn(false);
    }


    protected void getCallPhonePermission() {
        cordova.requestPermission(this, CALL_PHONE_REQ_CODE, Manifest.permission.CALL_PHONE);
    }

    protected void callNumberPhonePermission() {
        cordova.requestPermission(this, REAL_PHONE_CALL, Manifest.permission.CALL_PHONE);
    }

    private void callNumber() {
        try {
          Intent intent = new Intent(Intent.ACTION_CALL, Uri.fromParts("tel", realCallTo, null));
          this.cordova.getActivity().getApplicationContext().startActivity(intent);
        } catch(Exception e) {
          this.callbackContext.error("Call Failed");
        }
        this.callbackContext.success("Call Successful");
    }

    @Override
    public void onRequestPermissionResult(int requestCode, String[] permissions, int[] grantResults) throws JSONException
    {
        for(int r:grantResults)
        {
            if(r == PackageManager.PERMISSION_DENIED)
            {
                this.callbackContext.sendPluginResult(new PluginResult(PluginResult.Status.ERROR, "CALL_PHONE Permission Denied"));
                return;
            }
        }
        switch(requestCode)
        {
            case CALL_PHONE_REQ_CODE:
                this.sendCall();
                break;
            case REAL_PHONE_CALL:
                this.callNumber();
                break;
        }
    }

    public void requestPermissions() {
        Intent phoneIntent = new Intent(TelecomManager.ACTION_CHANGE_PHONE_ACCOUNTS);
        phoneIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        this.cordova.getActivity().getApplicationContext().startActivity(phoneIntent);
    }
}
