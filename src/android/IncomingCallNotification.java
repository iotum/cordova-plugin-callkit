package com.dmarc.cordovacall;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.graphics.BitmapFactory;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.util.Log;

import androidx.core.app.NotificationCompat;
import androidx.core.app.Person;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.Random;


public class IncomingCallNotification {
    private static final String TAG = "IncomingCallNotification";

    private String pushMessagePayload;
    private Integer notificationID;
    private Context context;
    private NotificationManager notificationManager;

    private static final String NOTIFICATION_CHANNEL_ID = "incoming_calls";

    public IncomingCallNotification(String pushMessagePayload, Context context) {
        this.pushMessagePayload = pushMessagePayload;
        this.notificationID = new Random().nextInt(100000) + 1;
        this.context = context;
        this.notificationManager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);

        this.createNotificationChannel();
    }

    public Notification build() {
        int timeout = 30000;

        // NOTE: "Notifications should only launch a BroadcastReceiver from notification actions"

        Intent answerIntent = new Intent(this.context, CallActionReceiver.class);
        answerIntent.setAction("answerCall");
        answerIntent.putExtra("pushMessagePayload", this.pushMessagePayload);
        answerIntent.putExtra("notificationID", this.notificationID);
        PendingIntent answerPendingIntent = PendingIntent.getBroadcast(
                this.context, 0, answerIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        Intent declineIntent = new Intent(this.context, CallActionReceiver.class);
        declineIntent.setAction("declineCall");
        declineIntent.putExtra("pushMessagePayload", this.pushMessagePayload);
        declineIntent.putExtra("notificationID", this.notificationID);
        PendingIntent declinePendingIntent = PendingIntent.getBroadcast(
                this.context, 1, declineIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        JSONObject payload = null;
        try {
            payload = new JSONObject(this.pushMessagePayload);
        } catch (JSONException e) {
            throw new RuntimeException("IncomingCallNotification: unable to parse pushMessagePayload, error: " + e);
        }

        String peerName = payload.optString("from", "UNKNOWN");

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this.context, IncomingCallNotification.NOTIFICATION_CHANNEL_ID)
                .setContentTitle("Incoming call")
                .setSmallIcon(android.R.drawable.ic_menu_call)
                .setLargeIcon(BitmapFactory.decodeResource(this.context.getResources(), android.R.drawable.sym_def_app_icon))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_CALL)
                .setSound(this.getRingtoneURI()) // For compatibility with Android 8.0 and less. (normally set through channel)
                .setOngoing(true); // Can't be "dismissed" by the user, app will handle closing it

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            Log.d(TAG, "Creating call-style notification (as this is supported by the device)...");
            Person callerPerson = new Person.Builder()
                    .setName(peerName)
                    .setImportant(true)
                    .build();

            // "CallStyle notifications must be for a foreground service or user initated job or use a fullScreenIntent."
            // NOTE: this requirements is met by the use of a full-screen intent
            builder.setStyle(NotificationCompat.CallStyle.forIncomingCall(callerPerson, declinePendingIntent, answerPendingIntent));

            Intent fullScreenIntent = new Intent(this.context, IncomingCallActivity.class);
            fullScreenIntent.putExtra("pushMessagePayload", this.pushMessagePayload);
            fullScreenIntent.setFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
            PendingIntent fullScreenPendingIntent = PendingIntent.getActivity(
                    this.context, 0, fullScreenIntent,
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_CANCEL_CURRENT
            );
            builder.setFullScreenIntent(fullScreenPendingIntent, true);
        } else {
            Log.d(TAG, "Creating a normal (non call-style) incoming call notification (due to lack of support of call-style notifications)...");
            builder.setContentText(peerName);
            builder.addAction(android.R.drawable.ic_menu_call, "Answer", answerPendingIntent)
                    .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Decline", declinePendingIntent);
        }

        Notification notification = builder.build();

        return notification;
    }

    public int getNotificationID() {
        return this.notificationID;
    }

    private Uri getRingtoneURI() {
        return RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE);
    }

    // Intentional create the notification channel here (rather than in javascript via window.FirebasePlugin.createChannel)
    // so that the users default ringtone can be referenced, and so that the channel is guaranteed to be established prior to the notification.
    // The notification sound on > Android 8.0 comes from the notification channel.
    private void createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(
                IncomingCallNotification.NOTIFICATION_CHANNEL_ID,
                "Incoming Calls",
                NotificationManager.IMPORTANCE_HIGH
        );
        channel.setDescription("Incoming call alerts");
        channel.setSound(this.getRingtoneURI(), null);
        channel.setVibrationPattern(new long[]{ 0, 1000, 500, 1000 });
        channel.enableVibration(true);
        this.notificationManager.createNotificationChannel(channel);
    }
}