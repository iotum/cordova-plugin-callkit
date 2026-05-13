package com.dmarc.cordovacall;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.AudioManager;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import androidx.core.app.ServiceCompat;

/**
 * This is a service designed to be launched directly into the foreground throughout the duration
 * of an active incoming or outgoing call to retain microphone access and set the correct audio mode.
 */
public class CallAudioService extends Service {
    private static final String TAG = "CallAudioService";
    private static int currentNotificationId = -1;

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

        OngoingCallNotification onGoingCallNotification = new OngoingCallNotification(this.getApplicationContext(), peerName, sessionId);

        Notification notification = onGoingCallNotification.build();
        int notificationID = onGoingCallNotification.getNotificationID();
        currentNotificationId = notificationID;

        // For Android 14 (API 34) and above, you MUST specify types in code
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            Log.d(TAG, "calling startForeground (service types phone call + microphone)...");
            ServiceCompat.startForeground(
                    this,
                    notificationID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL |
                            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            );
        } else {
            startForeground(notificationID, notification);
        }

        Log.d(TAG, "Setting audio mode to MODE_IN_COMMUNICATION");
        AudioManager audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        audioManager.setMode(AudioManager.MODE_IN_COMMUNICATION);

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

        Log.d(TAG, "Returning audio mode to MODE_NORMAL");
        AudioManager audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        audioManager.setMode(AudioManager.MODE_NORMAL);
    }

    @Override
    public IBinder onBind(Intent intent) {
        // We don't provide binding, so return null
        return null;
    }
}