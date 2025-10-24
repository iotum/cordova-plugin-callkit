package com.dmarc.cordovacall;

import android.app.Activity;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.BitmapFactory;
import android.media.RingtoneManager;
import android.os.Handler;
import androidx.core.app.NotificationCompat;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.Random;


public class CallNotification {
    private String pushMessagePayload;
    private Integer notificationID;
    private Context context;
    private NotificationManager notificationManager;
    private Runnable timeoutRunnable;
    private Class<Activity> launchActivityClass;
    private Handler timeoutHandler = new Handler();

    private static final String NOTIFICATION_CHANNEL_ID = "call_notifications";

    public CallNotification(String pushMessagePayload, Context context) {
        this.pushMessagePayload = pushMessagePayload;
        this.notificationID = new Random().nextInt(100000) + 1; // Random int > 0 TODO: maybe derive from call UUID string
        this.context = context;

        PackageManager packageManager = context.getPackageManager();

        Intent launchIntent = new Intent(Intent.ACTION_MAIN);
        launchIntent.addCategory(Intent.CATEGORY_LAUNCHER);
        ComponentName componentName = launchIntent.resolveActivity(packageManager);
        this.launchActivityClass = (Class) componentName.getClass();

        this.notificationManager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);

        this.createNotificationChannel();
    }

    public void show() {
        int timeout = 30000;

        // Create answer intent
        Intent answerIntent = new Intent("CALL_ANSWER");
            answerIntent.putExtra("pushMessagePayload", this.pushMessagePayload);
        PendingIntent answerPendingIntent = PendingIntent.getBroadcast(
                this.context, 0, answerIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        Intent declineIntent = new Intent("CALL_DECLINE");
            declineIntent.putExtra("pushMessagePayload", this.pushMessagePayload);
        PendingIntent declinePendingIntent = PendingIntent.getBroadcast(
                this.context, 1, declineIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        Intent fullScreenIntent = new Intent(this.context, this.launchActivityClass);
        PendingIntent fullScreenPendingIntent = PendingIntent.getActivity(
                this.context, 0, fullScreenIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        JSONObject payload = null;
        try {
            payload = new JSONObject(this.pushMessagePayload);
        } catch (JSONException e) {
            throw new RuntimeException("CallNotification unable to parse pushMessagePayload, error: " + e);
        }

        String callerName = payload.optString("from", "UNKNOWN");

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this.context, CallNotification.NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Incoming call")
            .setContentText(callerName)
            .setSmallIcon(android.R.drawable.ic_menu_call)
            .setLargeIcon(BitmapFactory.decodeResource(this.context.getResources(), android.R.drawable.sym_def_app_icon))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setFullScreenIntent(fullScreenPendingIntent, true)
                // .setStyle(NotificationCompat.CallStyle.forIncomingCall(person))  TODO use inComingCall notifications (where supported based on Android OS version)
            .setOngoing(true)
            .setAutoCancel(false)
            .addAction(android.R.drawable.ic_menu_call, "Answer", answerPendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Decline", declinePendingIntent);

        notificationManager.notify(this.notificationID, builder.build());

        if (this.timeoutRunnable != null) {
            this.timeoutHandler.removeCallbacks(this.timeoutRunnable);
        }

        this.timeoutRunnable = new Runnable() {
            @Override
            public void run() {
                close();
            }
        };

        this.timeoutHandler.postDelayed(timeoutRunnable, timeout);
    }

    public void close() {
        this.notificationManager.cancel(this.notificationID);
        if (this.timeoutRunnable != null) {
            this.timeoutHandler.removeCallbacks(this.timeoutRunnable);
        }
    }

    private void createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(
                CallNotification.NOTIFICATION_CHANNEL_ID,
                "Incoming Calls",
                NotificationManager.IMPORTANCE_HIGH
        );
        channel.setDescription("Notifications for incoming calls");
        channel.setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE), null);
        channel.setVibrationPattern(new long[]{ 0, 1000, 500, 1000 });
        channel.enableVibration(true);
        this.notificationManager.createNotificationChannel(channel);
    }
}