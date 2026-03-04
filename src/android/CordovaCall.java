package com.dmarc.cordovacall;

import org.apache.cordova.CordovaPlugin;
import org.apache.cordova.CallbackContext;
import org.apache.cordova.CordovaInterface;
import org.apache.cordova.CordovaWebView;
import org.apache.cordova.PluginResult;

import android.app.Activity;
import android.app.NotificationManager;
import android.content.ActivityNotFoundException;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.telecom.CallAudioState;
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
import android.media.AudioDeviceInfo;
import android.content.BroadcastReceiver;
import android.content.IntentFilter;
import org.json.JSONObject;
import android.util.Log;
import android.view.WindowManager;
import android.widget.Toast;
import android.hardware.usb.UsbManager;

public class CordovaCall extends CordovaPlugin {
    private static String READ_PHONE_NUMBERS_REQUIRED = "read_phone_numbers_permission_required";

    private static String TAG = "CordovaCall";
    public static final int REAL_PHONE_CALL = 1;

    // Audio Route Constants (standardized across platforms)
    public static class AudioRoute {
        public static final String EARPIECE = "earpiece";
        public static final String BLUETOOTH = "bluetooth";
        public static final String SPEAKER = "speaker";
        public static final String WIRED_HEADSET = "wired_headset";
        public static final String UNKNOWN = "unknown";
    }

    // Audio Route Change Types (standardized across platforms)
    public static class AudioRouteChangeType {
        public static final String DEVICE_CHANGED = "deviceChanged";
        public static final String PROGRAMMATIC_CHANGE = "programmaticChange";
    }

    private TelecomManager tm;
    private AudioManager audioManager;

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
        callbackContextMap.put("audioRouteChange", new ArrayList<CallbackContext>());
    }
    private static ArrayList<HashMap> enqueuedEvents = new ArrayList<HashMap>();
    private static CordovaInterface cordovaInterface;
    private static CordovaWebView cordovaWebView;
    private static Icon icon;
    private static CordovaCall instance;

    public static HashMap<String, ArrayList<CallbackContext>> getCallbackContexts() {
        return callbackContextMap;
    }

    private static boolean isMainActivityInForeground = false;
    private static ArrayList<Runnable> mainActivityForegroundListeners = new ArrayList<Runnable>();

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

        // Initialize AudioManager for audio route change monitoring
        this.audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);

        instance = this;
    }

    public void setMainActivityInForeground(boolean isInForeground) {
        isMainActivityInForeground = isInForeground;
        // Notify all listeners:
        for (final Runnable listener : mainActivityForegroundListeners) {
            listener.run();
        }
    }

    public static boolean isMainActivityInForeground() {
        return isMainActivityInForeground;
    }

    public static void registerMainActivityStateChangeListener(Runnable runnable) {
        if (!mainActivityForegroundListeners.contains(runnable)) {
            mainActivityForegroundListeners.add(runnable);
        }
    }

    public static void unregisterMainActivityStateChangeListener(Runnable runnable) {
        mainActivityForegroundListeners.remove(runnable);
    }

    @Override
    public void onResume(boolean multitasking) {
        super.onResume(multitasking);
        setMainActivityInForeground(true);
    }

    @Override
    public void onPause(boolean multitasking) {
        super.onPause(multitasking);
        setMainActivityInForeground(false);
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
                this.receiveCall();
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
                this.sendCall();
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
                onCallConnected(); // This will start monitoring if it's the first call
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
                onCallEnded(); // This will stop monitoring if it's the last call
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
            return true;
        } else if (action.equals("speakerOff")) {
            this.speakerOff();
            return true;
        } else if (action.equals("getAudioRoute")) {
            this.getAudioRoute(callbackContext);
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
            this.checkCallPermission();
            return true;
        } else if (action.equals("canUseFullScreenIntent")) {
            NotificationManager nm = (NotificationManager) this.cordova.getActivity().getSystemService(Context.NOTIFICATION_SERVICE);
            boolean canUseFullScreenIntent = true;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                canUseFullScreenIntent = nm.canUseFullScreenIntent();
            }
            this.callbackContext.sendPluginResult(new PluginResult(PluginResult.Status.OK, canUseFullScreenIntent));
            return true;
        } else if (action.equals("openFullScreenIntentSettings")) {
            Activity activity = this.cordova.getActivity();
            Intent intent = new Intent(
                    Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT,
                    Uri.parse("package:" + activity.getPackageName())
            );
            try {
                activity.startActivity(intent);
            } catch (ActivityNotFoundException e) {
                // Handle the case where the specific settings page cannot be found
                // (e.g., on some custom ROMs or older Android versions, though the action exists from Android 10+)
                Toast.makeText(activity, "Settings page not found, please manually navigate to special app access.", Toast.LENGTH_LONG).show();
                // Optional fallback to general app notification settings
                Intent fallbackIntent = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, activity.getPackageName())
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                activity.startActivity(fallbackIntent);
            }
            callbackContext.success();
            return true;
        }
        return false;
    }

    private void checkCallPermission() {
        // Your client web app should have already checked/requested the READ_PHONE_NUMBERS runtime permission before hand.
        if (!CordovaCall.getCordova().hasPermission(Manifest.permission.READ_PHONE_NUMBERS)) {
            this.callbackContext.error(READ_PHONE_NUMBERS_REQUIRED);
            return; // Don't proceed to call TelecomManager.getPhoneAccount() as that would throw an error which in some cases may crash the entire app
        }

        PhoneAccountHandle handle = PhoneAccountManager.getPhoneAccountHandle(this.cordova.getActivity().getApplicationContext());
        PhoneAccount currentPhoneAccount = tm.getPhoneAccount(handle); // Requires android.permissions.READ_PHONE_NUMBERS
        if (currentPhoneAccount == null || !currentPhoneAccount.isEnabled()) {
            Intent phoneIntent = new Intent(TelecomManager.ACTION_CHANGE_PHONE_ACCOUNTS);
            phoneIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            this.cordova.getActivity().getApplicationContext().startActivity(phoneIntent);
        }
    }

    private void receiveCall() {
        Bundle callInfo = new Bundle();
        callInfo.putString("from",from);

        PhoneAccountHandle handle = PhoneAccountManager.getPhoneAccountHandle(this.cordova.getActivity().getApplicationContext());
        tm.addNewIncomingCall(handle, callInfo);

        this.callbackContext.success("Incoming call successful");

        this.bringAppToFront();
        this.tm.showInCallScreen(false);
    }

    private void sendCall() {
        // Your client web app should have already checked/requested READ_PHONE_NUMBERS before hand
        if (!CordovaCall.getCordova().hasPermission(Manifest.permission.READ_PHONE_NUMBERS)) {
            this.callbackContext.error("READ_PHONE_NUMBER_PERMISSION not granted, cant proceed with placing a call");
            return; // Important: as attempting do tm.placeCall() without permission crashes the entire app
        }

        Uri uri = Uri.fromParts("tel", to, null);
        Bundle callInfoBundle = new Bundle();
        callInfoBundle.putString("to",to);
        Bundle callInfo = new Bundle();
        callInfo.putParcelable(TelecomManager.EXTRA_OUTGOING_CALL_EXTRAS,callInfoBundle);

        PhoneAccountHandle handle = PhoneAccountManager.getPhoneAccountHandle(this.cordova.getActivity().getApplicationContext());
        callInfo.putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, handle);

        callInfo.putBoolean(TelecomManager.EXTRA_START_CALL_WITH_VIDEO_STATE, true);

        PhoneAccount currentPhoneAccount = tm.getPhoneAccount(handle); // Requires android.permissions.READ_PHONE_NUMBERS
        if (currentPhoneAccount == null || !currentPhoneAccount.isEnabled()) {
            this.callbackContext.error("no_phone_account_enabled");
            return;
        }

        if (!CordovaCall.getCordova().hasPermission(Manifest.permission.MANAGE_OWN_CALLS)) {
            // This should in theory never happen - assuming no one removes MANAGE_OWN_CALLS from the android manifest
            this.callbackContext.error("MANAGE_OWN_CALLS permission not declared - required in order to use TelecomManager.placeCall()");
            return;
        }

        tm.placeCall(uri, callInfo); // Triggers sometime later, an onCreateOutgoingConnection callback to your ConnectionService

        this.callbackContext.success("Outgoing call successful");
    }

    private void bringAppToFront() {
        Intent intent = new Intent(this.cordova.getActivity().getApplicationContext(), this.cordova.getActivity().getClass());
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_BROUGHT_TO_FRONT | Intent.FLAG_FROM_BACKGROUND);
        this.cordova.getActivity().getApplicationContext().startActivity(intent);
    }

    private void mute() {
        this.audioManager.setMicrophoneMute(true);
    }

    private void unmute() {
        this.audioManager.setMicrophoneMute(false);
    }

    private void speakerOn() {
        Connection conn = MyConnectionService.getConnection();
        this.setConnectionAudioRoute(conn, CallAudioState.ROUTE_SPEAKER);
    }

    private void speakerOff() {
        Connection conn = MyConnectionService.getConnection();
        CallAudioState state = conn != null ? conn.getCallAudioState() : null;
        if (state == null) {
            this.setConnectionAudioRoute(null, -1);
            return;
        }

        int supportedRoutes = state.getSupportedRouteMask();
        if ((supportedRoutes & CallAudioState.ROUTE_BLUETOOTH) != 0) {
            this.setConnectionAudioRoute(conn, CallAudioState.ROUTE_BLUETOOTH);
        } else if ((supportedRoutes & CallAudioState.ROUTE_WIRED_HEADSET) != 0) {
            this.setConnectionAudioRoute(conn, CallAudioState.ROUTE_WIRED_HEADSET);
        } else if ((supportedRoutes & CallAudioState.ROUTE_EARPIECE) != 0) {
            this.setConnectionAudioRoute(conn, CallAudioState.ROUTE_EARPIECE);
        } else {
            this.setConnectionAudioRoute(conn, -1);
        }
    }

    private void getAudioRoute(CallbackContext callbackContext) {
        try {
            String route = getCurrentAudioRoute();
            callbackContext.success(route);
        } catch (Exception e) {
            Log.e(TAG, "Error getting current audio route: " + e.getMessage());
            callbackContext.error("Failed to get current audio route");
        }
    }

    private String getCurrentAudioRoute() {
        // Try to get route from active connection first
        Connection activeConnection = MyConnectionService.getConnection();
        if (activeConnection != null) {
            CallAudioState audioState = activeConnection.getCallAudioState();
            if (audioState != null) {
                return getRouteNameFromState(audioState.getRoute());
            }
        }

        // Fallback to AudioManager to determine current route
        return getCurrentAudioRouteFromAudioManager();
    }

    private String getCurrentAudioRouteFromAudioManager() {
        try {
            // Check Bluetooth SCO first (highest priority). Do not use A2DP, since it may be media-only.
            if (audioManager.isBluetoothScoOn()) {
                return AudioRoute.BLUETOOTH;
            }

            // Check speakerphone
            if (audioManager.isSpeakerphoneOn()) {
                return AudioRoute.SPEAKER;
            }

            // Check wired headset using modern API for API 23+
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                if (hasWiredHeadsetConnected()) {
                    return AudioRoute.WIRED_HEADSET;
                }
            } else {
                // Fallback to deprecated method for older Android versions
                if (audioManager.isWiredHeadsetOn()) {
                    return AudioRoute.WIRED_HEADSET;
                }
            }

            // Default to earpiece for voice calls
            return AudioRoute.EARPIECE;

        } catch (Exception e) {
            Log.e(TAG, "Error getting audio route from AudioManager: " + e.getMessage());
            return AudioRoute.UNKNOWN;
        }
    }

    private boolean hasWiredHeadsetConnected() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                AudioDeviceInfo[] devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS);
                for (AudioDeviceInfo device : devices) {
                    int type = device.getType();
                    // Check for various wired/USB headset types
                    if (type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                        type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                        type == AudioDeviceInfo.TYPE_USB_HEADSET ||
                        type == AudioDeviceInfo.TYPE_USB_DEVICE) {
                        Log.d(TAG, "Detected wired/USB headset: " + device.getProductName());
                        return true;
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Error checking for wired headset: " + e.getMessage());
                // Fall back to deprecated method if modern API fails
                return audioManager.isWiredHeadsetOn();
            }
        }
        return false;
    }

    private void setConnectionAudioRoute(Connection conn, int route) {
        if (conn != null && route >= 0) {
            conn.setAudioRoute(route);
            Log.i(TAG, "setConnectionAudioRoute: " + getRouteNameFromState(route));
            this.callbackContext.success("Connection audio route changed to: " + route);
        } else {
            this.callbackContext.error("No active connection");
        }
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
            case REAL_PHONE_CALL:
                this.callNumber();
                break;
        }
    }

    // Audio route change monitoring
    private BroadcastReceiver audioRouteReceiver;
    private static int activeCallCount = 0; // Track number of active calls

    private static void incrementActiveCallCount() {
        activeCallCount++;
        Log.d(TAG, "Active call count incremented to: " + activeCallCount);
        if (activeCallCount == 1 && instance != null) {
            instance.startAudioRouteMonitoring();
        }
    }

    private static void decrementActiveCallCount() {
        if (activeCallCount > 0) {
            activeCallCount--;
            Log.d(TAG, "Active call count decremented to: " + activeCallCount);
            if (activeCallCount == 0 && instance != null) {
                instance.stopAudioRouteMonitoring();
            }
        } else {
            Log.w(TAG, "Attempted to decrement active call count when already 0");

    private void startAudioRouteMonitoring() {
        if (audioRouteReceiver == null) {
            Log.d(TAG, "Starting audio route monitoring");
            audioRouteReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    String action = intent.getAction();
                    Log.d(TAG, "Audio route broadcast received: " + action);
                    
                    // Log additional details for headset plug events
                    if (AudioManager.ACTION_HEADSET_PLUG.equals(action)) {
                        int state = intent.getIntExtra("state", -1);
                        String name = intent.getStringExtra("name");
                        Log.d(TAG, "Headset plug event - state: " + state + ", name: " + name);
                    } else if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(action) || 
                               UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)) {
                        Log.d(TAG, "USB device event - may affect audio routing");
                    }
                    
                    emitCurrentAudioRoute(AudioRouteChangeType.DEVICE_CHANGED);
                }
            };

            IntentFilter filter = new IntentFilter();
            filter.addAction(AudioManager.ACTION_HEADSET_PLUG);
            filter.addAction(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                filter.addAction(AudioManager.ACTION_AUDIO_BECOMING_NOISY);
                // Add USB device actions for better USB headset detection
                filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
                filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
            }

            // Use explicit receiver export flag for API 33+
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                cordova.getActivity().registerReceiver(audioRouteReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
            } else {
                cordova.getActivity().registerReceiver(audioRouteReceiver, filter);
            }
        }
    }

    private void stopAudioRouteMonitoring() {
        if (audioRouteReceiver != null) {
            Log.d(TAG, "Stopping audio route monitoring");
            try {
                cordova.getActivity().unregisterReceiver(audioRouteReceiver);
            } catch (IllegalArgumentException e) {
                // Receiver not registered, ignore
                Log.d(TAG, "Audio route receiver was not registered");
            }
            audioRouteReceiver = null;
        }
    }

    @Override
    public void onReset() {
        // Ensure audio route monitoring is stopped when the WebView is reset
        stopAudioRouteMonitoring();
        super.onReset();
    }

    @Override
    public void onDestroy() {
        // Ensure audio route monitoring is stopped when the Activity/plugin is destroyed
        stopAudioRouteMonitoring();
        super.onDestroy();
    }
    public void emitCurrentAudioRoute(String changeType) {
        try {
            // Use the centralized route detection method
            String currentRoute = getCurrentAudioRoute();

            HashMap<String, Object> routeData = new HashMap<>();
            routeData.put("route", currentRoute);
            routeData.put("changeType", changeType);

            // Add additional connection info if available
            Connection conn = MyConnectionService.getConnection();
            if (conn != null) {
                CallAudioState state = conn.getCallAudioState();
                if (state != null) {
                    routeData.put("supportedRoutes", state.getSupportedRouteMask());
                    routeData.put("isMuted", state.isMuted());
                }
            }

            JSONObject jsonData = new JSONObject(routeData);
            PluginResult result = new PluginResult(PluginResult.Status.OK, jsonData);

            CordovaCall.emitEvent("audioRouteChange", result);
        } catch (Exception e) {
            Log.e(TAG, "Error emitting audio route change event: " + e.getMessage());
        }
    }

    private String getRouteNameFromState(int route) {
        switch (route) {
            case CallAudioState.ROUTE_EARPIECE: return AudioRoute.EARPIECE;
            case CallAudioState.ROUTE_BLUETOOTH: return AudioRoute.BLUETOOTH;
            case CallAudioState.ROUTE_SPEAKER: return AudioRoute.SPEAKER;
            case CallAudioState.ROUTE_WIRED_HEADSET: return AudioRoute.WIRED_HEADSET;
            default: return AudioRoute.UNKNOWN;
        }
    }
}
