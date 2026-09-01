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
import android.util.Log;
import android.view.WindowManager;
import android.widget.Toast;

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
    private String realCallTo;
    private static HashMap<String, ArrayList<CallbackContext>> callbackContextMap = new HashMap<String, ArrayList<CallbackContext>>();
    private static ArrayList<HashMap> enqueuedEvents = new ArrayList<HashMap>();
    private static ArrayList<HashMap> nextWebViewEvents = new ArrayList<HashMap>();
    private static final Object nextWebViewEventsLock = new Object();
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
        ArrayList<CallbackContext> callbackContexts;
        // Locked so a registerEvent() racing this can't miss the enqueue below and also skip
        // delivering to the callback snapshot it just registered (see registerEvent()).
        synchronized (nextWebViewEventsLock) {
            callbackContexts = new ArrayList<CallbackContext>(CordovaCall.getCallbackContexts().computeIfAbsent(eventType, k -> new ArrayList<>()));
            if (callbackContexts.size() == 0) {
                Log.d(TAG, "nothing yet listening for CordovaCall event: " + eventType + " enqueuing message for later...");
                enqueuedEvents.add(createEnqueuedEvent(eventType, result));
            }
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

    // Persists the event (scoped to sessionId, surviving WebView recreation via registerEvent's
    // replay below) while also attempting immediate delivery, so answering doesn't add latency
    // when the current WebView survives to consume it. Queue insertion and the callback list
    // snapshot are done under the same lock that guards registerEvent's and emitEvent's callback
    // registration and queue snapshot, so exactly one path delivers the event.
    public static void emitDurableEvent(String eventType, String sessionId, PluginResult result) {
        Log.d(TAG, "emitDurableEvent: " + eventType + " sessionId: " + sessionId);
        HashMap event = createEnqueuedEvent(eventType, result);
        event.put("sessionId", sessionId);
        ArrayList<CallbackContext> callbackContexts;
        synchronized (nextWebViewEventsLock) {
            nextWebViewEvents.add(event);
            callbackContexts = new ArrayList<CallbackContext>(CordovaCall.getCallbackContexts().computeIfAbsent(eventType, k -> new ArrayList<>()));
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

    public static void discardNextWebViewEvents(String eventType, String sessionId) {
        synchronized (nextWebViewEventsLock) {
            nextWebViewEvents.removeIf(event -> event.get("eventType").equals(eventType) && sessionId.equals(event.get("sessionId")));
        }
    }

    private static HashMap createEnqueuedEvent(String eventType, PluginResult result) {
        HashMap event = new HashMap();
        event.put("eventType", eventType);
        event.put("result", result);
        return event;
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

        // Initialize AudioManager for audio route change monitoring
        this.audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);

        instance = this;

        AudioRouteMonitor.setInstance(new AudioRouteMonitor(cordova, this.audioManager));
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

    /**
     * Makes the MainActivity visible on the lockscreen. Call this when the user answers a call from
     * IncomingCallActivity so they land in the app without having to first dismiss the lockscreen.
     *
     * Deliberately does NOT also call startActivity() to bring MainActivity to the foreground - that's
     * already done moments later by MyConnectionService.showWebApp() (which delivers the answer intent
     * extras). Two near-simultaneous startActivity() calls targeting the same singleTop MainActivity
     * race each other and can make Android spin up a duplicate instance instead of reusing the existing
     * one, tearing down and recreating the whole Cordova WebView mid-answer.
     */
    public static void showMainActivityOnLockscreen() {
        CordovaInterface cordova = CordovaCall.getCordova();
        if (cordova == null) return;
        Activity activity = cordova.getActivity();
        if (activity == null) return;

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
                String to = args.getString(0);
                String sessionId = args.getString(2);
                this.sendCall(to, sessionId);
            }
            return true;
        } else if (action.equals("connectCall")) {
            String sessionId = args.getString(0);
            Connection conn = MyConnectionService.getConnection(sessionId);
            if (conn == null) {
                this.callbackContext.error("No call exists for you to connect");
            } else if (conn.getState() == Connection.STATE_ACTIVE) {
                this.callbackContext.error("Your call is already connected");
            } else {
                conn.setActive();
                this.callbackContext.success("Call connected successfully");
            }            
            return true;
        } else if (action.equals("hold")) {
            String sessionId = args.getString(0);
            Log.d(TAG, "sessionId: " + sessionId);
            Connection conn = MyConnectionService.getConnection(sessionId);
            if (conn != null) {
                conn.setOnHold();
                this.callbackContext.success("Call put on hold");
            } else {
                Log.e(TAG, "Can not hold - no connection found for session ID");
                this.callbackContext.error("No call found for session ID");
            }
            return true;
        } else if (action.equals("unhold")) {
            String sessionId = args.getString(0);
            Connection conn = MyConnectionService.getConnection(sessionId);
            if (conn != null) {
                conn.setActive();
                this.callbackContext.success("Call un-held");
            } else {
                Log.e(TAG, "Can not unhold - no connection found for session ID");
                this.callbackContext.error("No call found for session Id");
            }
            return true;
        } else if (action.equals("endCall")) {
            String sessionId = args.getString(0);
            Connection conn = MyConnectionService.getConnection(sessionId);
            if(conn == null) {
                this.callbackContext.error("No call with this sessionId exists for you to end");
            } else {
                conn.onDisconnect();
                this.callbackContext.success("Call ended successfully");
            }
            return true;
        } else if (action.equals("registerEvent")) {
            String eventType = args.getString(0);
            CallbackContext callbackContext1 = this.callbackContext;
            ArrayList<HashMap> eventsToDeliver = new ArrayList<HashMap>();
            // Callback registration and both queue snapshots/removals below are done under the
            // same lock that guards emitEvent()'s and emitDurableEvent()'s enqueue and callback
            // snapshot, so exactly one of the two paths delivers a given event instead of neither
            // or both.
            synchronized (nextWebViewEventsLock) {
                ArrayList<CallbackContext> callbackContextList = callbackContextMap.computeIfAbsent(eventType, k -> new ArrayList<>());
                callbackContextList.add(callbackContext1);
                for (final HashMap event : new ArrayList<HashMap>(enqueuedEvents)) {
                    if (event.get("eventType").equals(eventType)) {
                        eventsToDeliver.add(event);
                    }
                }
                enqueuedEvents.removeIf(e -> e.get("eventType").equals(eventType));
                for (final HashMap event : new ArrayList<HashMap>(nextWebViewEvents)) {
                    if (!event.get("eventType").equals(eventType)) {
                        continue;
                    }
                    String sessionId = (String) event.get("sessionId");
                    Connection conn = sessionId == null ? null : MyConnectionService.getConnection(sessionId);
                    // Skip a durable event whose call already connected or ended via the immediate
                    // emitDurableEvent() delivery, so a fresh listener doesn't receive it a second time.
                    if (conn == null || conn.getState() == Connection.STATE_ACTIVE || conn.getState() == Connection.STATE_DISCONNECTED) {
                        continue;
                    }
                    eventsToDeliver.add(event);
                }
                nextWebViewEvents.removeIf(e -> e.get("eventType").equals(eventType));
            }
            for (final HashMap event : eventsToDeliver) {
                Log.d(TAG, "emitting enqueued event: " + event.toString() + " now that a listener is registered");
                CordovaCall.getCordova().getThreadPool().execute(new Runnable() {
                    public void run() {
                        PluginResult result = (PluginResult) event.get("result");
                        result.setKeepCallback(true);
                        callbackContext1.sendPluginResult(result);
                    }
                });
            }
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
        } else if (action.equals("setAllowUnmute")) {
            this.callbackContext.error("setAllowUnmute is not supported on Android");
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
        } else if (action.equals("updateCallName")) {
            String sessionId = args.getString(0);
            String callName = args.isNull(1) ? null : args.getString(1);
            if (callName == null) {
                this.callbackContext.success("No callName provided, nothing to update");
                return true;
            }
            Connection conn = MyConnectionService.getConnection(sessionId);
            if (conn == null) {
                this.callbackContext.success("No call exists for the given sessionId");
            } else if (conn instanceof CallConnection) {
                ((CallConnection) conn).updatePeerName(callName);
                this.callbackContext.success("Call name updated successfully");
            }
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

    private void sendCall(String to, String sessionId) {
        // Your client web app should have already checked/requested READ_PHONE_NUMBERS before hand
        if (!CordovaCall.getCordova().hasPermission(Manifest.permission.READ_PHONE_NUMBERS)) {
            this.callbackContext.error("READ_PHONE_NUMBER_PERMISSION not granted, cant proceed with placing a call");
            return; // Important: as attempting do tm.placeCall() without permission crashes the entire app
        }

        Uri uri = Uri.fromParts("tel", to, null);
        Bundle callInfoBundle = new Bundle();
        callInfoBundle.putString("to", to);
        callInfoBundle.putString("sessionId", sessionId);
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
            AudioRouteMonitor monitoring = AudioRouteMonitor.getInstance();
            String route = monitoring != null ? monitoring.getCurrentAudioRoute() : AudioRoute.UNKNOWN;
            callbackContext.success(route);
        } catch (Exception e) {
            Log.e(TAG, "Error getting current audio route: " + e.getMessage());
            callbackContext.error("Failed to get current audio route");
        }
    }

    private void setConnectionAudioRoute(Connection conn, int route) {
        if (conn != null && route >= 0) {
            conn.setAudioRoute(route);
            AudioRouteMonitor monitoring = AudioRouteMonitor.getInstance();
            String routeName = monitoring != null ? monitoring.getRouteNameFromState(route) : String.valueOf(route);
            Log.i(TAG, "setConnectionAudioRoute: " + routeName);
            this.callbackContext.success("Connection audio route changed to: " + routeName);
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

    @Override
    public void onReset() {
        // Ensure audio route monitoring is stopped when the WebView is reset
        AudioRouteMonitor monitoring = AudioRouteMonitor.getInstance();
        if (monitoring != null) {
            monitoring.stopMonitoring();
        }
        super.onReset();
    }

    @Override
    public void onDestroy() {
        // Reset under the same lock registerEvent() uses, so a racing registration from a new
        // WebView can't be silently dropped by this replacing the map right after it's added to.
        synchronized (nextWebViewEventsLock) {
            callbackContextMap = new HashMap<String, ArrayList<CallbackContext>>();
            enqueuedEvents.clear();
        }
        // Ensure audio route monitoring is stopped when the Activity/plugin is destroyed
        AudioRouteMonitor monitoring = AudioRouteMonitor.getInstance();
        if (monitoring != null) {
            monitoring.stopMonitoring();
        }
        super.onDestroy();
    }
}
