package com.dmarc.cordovacall;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.graphics.BitmapFactory;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;

import androidx.core.app.NotificationCompat;
import androidx.core.app.Person;

import java.util.Random;


public class OngoingCallNotification {
    private static final String TAG = "OngoingCallNotification";

    private String peerName;
    private String sessionId;
    private Integer notificationID;
    private Context context;
    private NotificationManager notificationManager;

    static final String NOTIFICATION_CHANNEL_ID = "ongoing_calls";

    public OngoingCallNotification(Context context, String peerName, String sessionId) {
        this(context, peerName, sessionId, new Random().nextInt(100000) + 1);
    }

    public OngoingCallNotification(Context context, String peerName, String sessionId, int notificationID) {
        this.notificationID = notificationID;
        this.context = context;
        this.notificationManager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);

        this.peerName = peerName;
        this.sessionId = sessionId;

        this.createNotificationChannel();
    }

    public Notification build() {
        String safePeerName = peerName;
        if (safePeerName == null || safePeerName.trim().isEmpty()) {
            safePeerName = "unavailable";
        }

        Intent hangupIntent = new Intent(this.context, CallActionReceiver.class);
        hangupIntent.setAction("hangUpCall");
        hangupIntent.putExtra("notificationID", this.notificationID);
        hangupIntent.putExtra("sessionId", this.sessionId);
        PendingIntent hangupPendingIntent = PendingIntent.getBroadcast(
                this.context, 0, hangupIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this.context, OngoingCallNotification.NOTIFICATION_CHANNEL_ID)
                .setContentTitle("Ongoing call")
                .setSmallIcon(android.R.drawable.ic_menu_call)
                .setLargeIcon(BitmapFactory.decodeResource(this.context.getResources(), android.R.drawable.sym_def_app_icon))
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .setCategory(NotificationCompat.CATEGORY_CALL)
                .setOngoing(true); // Can't be "dismissed" by the user, app will handle closing it (via notification manager or stopping associated foreground service)

        Bundle notificationExtras = new Bundle();
        notificationExtras.putString("sessionId", this.sessionId); // Read back when scanning for orphaned call notifications
        builder.addExtras(notificationExtras);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            Log.d(TAG, "Creating call-style notification for on-going call (as this is supported by the device)...");
            Person callerPerson = new Person.Builder()
                    .setName(safePeerName)
                    .setImportant(true)
                    .build();

            // "CallStyle notifications must be for a foreground service or user initiated job or use a fullScreenIntent."
            // NOTE: this requirement is met by the use of a foreground service
            builder.setStyle(NotificationCompat.CallStyle.forOngoingCall(callerPerson, hangupPendingIntent));
        } else {
            builder.setContentText(safePeerName);
            builder.addAction(android.R.drawable.ic_menu_call, "Hang up", hangupPendingIntent);
        }

        Notification notification = builder.build();

        return notification;
    }

    public int getNotificationID() {
        return this.notificationID;
    }

    private void createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(
                OngoingCallNotification.NOTIFICATION_CHANNEL_ID,
                "Ongoing Calls",
                NotificationManager.IMPORTANCE_MIN
        );
        channel.setDescription("Notifications displayed when in an active call");
        channel.setSound(null, null);
        this.notificationManager.createNotificationChannel(channel);
    }
}