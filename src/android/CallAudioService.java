package com.dmarc.cordovacall;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.AudioManager;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import androidx.core.app.NotificationCompat;
import androidx.core.app.ServiceCompat;

public class CallAudioService extends Service {
    private static final String TAG = "CallAudioService";
    private static final String CHANNEL_ID = "voip_call_channel";
    private static final int NOTIFICATION_ID = 123;

    // onStartCommand is called in response to the startService() intent
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "onStartCommand");

        createNotificationChannel();
        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Active Call")
                .setContentText("Microphone is in use")
                .setSmallIcon(android.R.drawable.ic_menu_call)
                .setOngoing(true)
                .build();

        // For Android 14 (API 34) and above, you MUST specify types in code
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            Log.d(TAG, "calling startForeground (service types phone call + microphone)...");
            ServiceCompat.startForeground(
                    this,
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL |
                            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            );
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }

        Log.d(TAG, "Setting audio mode to MODE_IN_COMMUNICATION");
        AudioManager audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        audioManager.setMode(AudioManager.MODE_IN_COMMUNICATION);

        return START_STICKY;
    }

    private void createNotificationChannel() {
        NotificationChannel serviceChannel = new NotificationChannel(
                CHANNEL_ID,
                "VoIP Call Service",
                NotificationManager.IMPORTANCE_LOW
        );
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.createNotificationChannel(serviceChannel);
        }
    }

    // Note: Although a started service is stopped by a call to either stopSelf() or stopService(),
    // there isn't a respective callback for the service (there's no onStop() callback).
    // Unless the service is bound to a client, the system destroys it when the service is stopped —onDestroy() is the only callback received.
    @Override
    public void onDestroy() {
        Log.d(TAG, "onDestroy()");

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