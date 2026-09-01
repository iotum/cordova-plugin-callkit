package com.dmarc.cordovacall;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import androidx.core.app.ServiceCompat;
import androidx.core.content.ContextCompat;

/**
 * This is a service designed to be launched directly into the foreground throughout the duration
 * of an active incoming or outgoing call to retain microphone access and set the correct audio mode.
 */
public class CallAudioService extends Service {
    private static final String TAG = "CallAudioService";
    private static int currentNotificationId = -1;
    private AudioFocusRequest audioFocusRequest;

    public static int getCurrentNotificationId() {
        return currentNotificationId;
    }

    public static void updateNotification(Context context, String peerName, String sessionId) {
        if (currentNotificationId == -1) return;
        Notification updated = new OngoingCallNotification(context, peerName, sessionId).build();
        NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        nm.notify(currentNotificationId, updated);
    }

    // onStartCommand is called in response to the startService() intent
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "onStartCommand");

        // It's possible for the service to be started with no-intent by the OS,
        // usually this won't happen though with START_NOT_STICKY
        if (intent == null) {
            Log.e(TAG, "service started with no intent, exiting");
            this.stopSelf();
            return START_NOT_STICKY;
        }

        String peerName = intent.getStringExtra("peerName");
        String sessionId = intent.getStringExtra("sessionId");

        // If the service is already running (e.g., started again for the same call), reuse the
        // existing notification ID so startForeground updates the existing notification instead
        // of creating a new, orphaned one.
        OngoingCallNotification onGoingCallNotification = currentNotificationId != -1
                ? new OngoingCallNotification(this.getApplicationContext(), peerName, sessionId, currentNotificationId)
                : new OngoingCallNotification(this.getApplicationContext(), peerName, sessionId);

        Notification notification = onGoingCallNotification.build();
        int notificationID = onGoingCallNotification.getNotificationID();
        currentNotificationId = notificationID;

        // For Android 14 (API 34) and above, you MUST specify types in code
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // This service is started as soon as a call is answered (before the web app has necessarily
            // been granted RECORD_AUDIO) so it can act as a foreground-service BAL exemption, allowing
            // MyConnectionService to bring the app to the foreground. The microphone type must only be
            // requested once RECORD_AUDIO is actually granted, since Android 14+ throws a SecurityException
            // if a FOREGROUND_SERVICE_TYPE_MICROPHONE service is started without the permission already held.
            int serviceTypes = ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL;
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                serviceTypes |= ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE;
            }
            Log.d(TAG, "calling startForeground (service types: " + serviceTypes + ")...");
            ServiceCompat.startForeground(
                    this,
                    notificationID,
                    notification,
                    serviceTypes
            );
        } else {
            startForeground(notificationID, notification);
        }

        Log.d(TAG, "Setting audio mode to MODE_IN_COMMUNICATION");
        AudioManager audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        audioManager.setMode(AudioManager.MODE_IN_COMMUNICATION);

        // Self-managed ConnectionServices are responsible for their own audio focus - Telecom does not
        // request it on our behalf. Without this, WebView's own WebRTC audio session (which negotiates
        // focus independently) can race against our setMode() call above, especially on calls answered
        // shortly after a prior call's audio session tore down, leaving the mic in a bad state.
        Log.d(TAG, "Requesting audio focus...");
        AudioAttributes audioAttributes = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build();
        audioFocusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
                .setAudioAttributes(audioAttributes)
                .build();
        int focusResult = audioManager.requestAudioFocus(audioFocusRequest);
        Log.d(TAG, "requestAudioFocus result: " + focusResult);

        // Don't auto restart if the app crashes, or the service is killed, etc.
        // as this may result in the app having no telecom connection but an orphaned CallAudioService.
        return START_NOT_STICKY;
    }

    // Note: Although a started service is stopped by a call to either stopSelf() or stopService(),
    // there isn't a respective callback for the service (there's no onStop() callback).
    // Unless the service is bound to a client, the system destroys it when the service is stopped —onDestroy() is the only callback received.
    @Override
    public void onDestroy() {
        Log.d(TAG, "onDestroy()");

        currentNotificationId = -1;

        AudioManager audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        if (audioFocusRequest != null) {
            Log.d(TAG, "Abandoning audio focus...");
            audioManager.abandonAudioFocusRequest(audioFocusRequest);
            audioFocusRequest = null;
        }

        Log.d(TAG, "Returning audio mode to MODE_NORMAL");
        audioManager.setMode(AudioManager.MODE_NORMAL);
    }

    @Override
    public IBinder onBind(Intent intent) {
        // We don't provide binding, so return null
        return null;
    }
}