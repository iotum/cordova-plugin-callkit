package com.dmarc.cordovacall;

import org.apache.cordova.CordovaInterface;
import org.apache.cordova.PluginResult;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbManager;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.Build;
import android.telecom.CallAudioState;
import android.telecom.Connection;
import android.util.Log;

import org.json.JSONObject;

import java.util.HashMap;

/**
 * Manages audio route monitoring for active calls.
 * Handles starting/stopping a BroadcastReceiver that listens for audio device
 * changes (headset plug, Bluetooth SCO, USB audio), and exposes the current
 * audio route using both CallAudioState (when an active Connection exists) and
 * AudioManager as a fallback.
 */
public class AudioRouteMonitor {
    private static final String TAG = "AudioRouteMonitor";

    private final CordovaInterface cordova;
    private final AudioManager audioManager;
    private BroadcastReceiver audioRouteReceiver;

    private static AudioRouteMonitor instance;

    public AudioRouteMonitor(CordovaInterface cordova, AudioManager audioManager) {
        this.cordova = cordova;
        this.audioManager = audioManager;
    }

    public static AudioRouteMonitor getInstance() {
        return instance;
    }

    public static void setInstance(AudioRouteMonitor inst) {
        instance = inst;
    }

    /**
     * Called when a call transitions to the active (connected) state.
     * Starts monitoring on the first active call.
     */
    public static void onCallConnected() {
        int count = MyConnectionService.getActiveCallCount();
        Log.d(TAG, "onCallConnected, active call count: " + count);
        if (count == 1 && instance != null) {
            instance.startMonitoring();
        }
    }

    /**
     * Called when a call ends (disconnected).
     * Stops monitoring when the last active call ends.
     */
    public static void onCallEnded() {
        int count = MyConnectionService.getActiveCallCount();
        Log.d(TAG, "onCallEnded, active call count: " + count);
        if (count == 0 && instance != null) {
            instance.stopMonitoring();
        }
    }

    /**
     * Registers a BroadcastReceiver to detect audio device changes
     * (headset plug/unplug, Bluetooth SCO state, USB devices).
     */
    public void startMonitoring() {
        if (audioRouteReceiver == null) {
            Log.d(TAG, "Starting audio route monitoring");
            audioRouteReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    String action = intent.getAction();
                    Log.d(TAG, "Audio route broadcast received: " + action);

                    if (AudioManager.ACTION_HEADSET_PLUG.equals(action)) {
                        int state = intent.getIntExtra("state", -1);
                        String name = intent.getStringExtra("name");
                        Log.d(TAG, "Headset plug event - state: " + state + ", name: " + name);
                    } else if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(action) ||
                               UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)) {
                        Log.d(TAG, "USB device event - may affect audio routing");
                    }

                    emitCurrentAudioRoute(CordovaCall.AudioRouteChangeType.DEVICE_CHANGED);
                }
            };

            IntentFilter filter = new IntentFilter();
            filter.addAction(AudioManager.ACTION_HEADSET_PLUG);
            filter.addAction(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED);
            filter.addAction(AudioManager.ACTION_AUDIO_BECOMING_NOISY);
            filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
            filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                cordova.getActivity().registerReceiver(audioRouteReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
            } else {
                cordova.getActivity().registerReceiver(audioRouteReceiver, filter);
            }
        }
    }

    /**
     * Unregisters the audio route BroadcastReceiver.
     */
    public void stopMonitoring() {
        if (audioRouteReceiver != null) {
            Log.d(TAG, "Stopping audio route monitoring");
            try {
                cordova.getActivity().unregisterReceiver(audioRouteReceiver);
            } catch (IllegalArgumentException e) {
                Log.d(TAG, "Audio route receiver was not registered");
            }
            audioRouteReceiver = null;
        }
    }

    /**
     * Returns the current audio route string (e.g. "earpiece", "speaker",
     * "bluetooth", "wired_headset", "unknown").
     * Prefers CallAudioState from the active Connection; falls back to AudioManager.
     */
    public String getCurrentAudioRoute() {
        Connection activeConnection = MyConnectionService.getConnection();
        if (activeConnection != null) {
            CallAudioState audioState = activeConnection.getCallAudioState();
            if (audioState != null) {
                return getRouteNameFromState(audioState.getRoute());
            }
        }
        return getCurrentAudioRouteFromAudioManager();
    }

    private String getCurrentAudioRouteFromAudioManager() {
        try {
            // Check Bluetooth SCO first (highest priority). Do not use A2DP, since it may be media-only.
            if (audioManager.isBluetoothScoOn()) {
                return CordovaCall.AudioRoute.BLUETOOTH;
            }

            if (audioManager.isSpeakerphoneOn()) {
                return CordovaCall.AudioRoute.SPEAKER;
            }

            if (hasWiredHeadsetConnected()) {
                return CordovaCall.AudioRoute.WIRED_HEADSET;
            }

            return CordovaCall.AudioRoute.EARPIECE;
        } catch (Exception e) {
            Log.e(TAG, "Error getting audio route from AudioManager: " + e.getMessage());
            return CordovaCall.AudioRoute.UNKNOWN;
        }
    }

    private boolean hasWiredHeadsetConnected() {
        try {
            AudioDeviceInfo[] devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS);
            for (AudioDeviceInfo device : devices) {
                int type = device.getType();
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
            return audioManager.isWiredHeadsetOn();
        }
        return false;
    }

    /**
     * Builds and emits an audioRouteChange event with the current route and the
     * supplied change type.
     */
    public void emitCurrentAudioRoute(String changeType) {
        try {
            String currentRoute = getCurrentAudioRoute();

            HashMap<String, Object> routeData = new HashMap<>();
            routeData.put("route", currentRoute);
            routeData.put("changeType", changeType);

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

    String getRouteNameFromState(int route) {
        switch (route) {
            case CallAudioState.ROUTE_EARPIECE: return CordovaCall.AudioRoute.EARPIECE;
            case CallAudioState.ROUTE_BLUETOOTH: return CordovaCall.AudioRoute.BLUETOOTH;
            case CallAudioState.ROUTE_SPEAKER: return CordovaCall.AudioRoute.SPEAKER;
            case CallAudioState.ROUTE_WIRED_HEADSET: return CordovaCall.AudioRoute.WIRED_HEADSET;
            default: return CordovaCall.AudioRoute.UNKNOWN;
        }
    }
}
