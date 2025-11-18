package com.dmarc.cordovacall;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
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


public class CallNotification {
    private static final String TAG = "CallNotification";

    private String pushMessagePayload;
    private Integer notificationID;
    private Context context;
    private NotificationManager notificationManager;

    private static final String NOTIFICATION_CHANNEL_ID = "incoming_calls";

    public enum Style {
        INCOMING_CALL,
        ONGOING_CALL
    }

    public CallNotification(String pushMessagePayload, Context context) {
        this.pushMessagePayload = pushMessagePayload;
        this.notificationID = new Random().nextInt(100000) + 1; // Random int > 0 TODO: maybe derive from call UUID string
        this.context = context;
        this.notificationManager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);

        this.createNotificationChannel();
    }

    public Notification build(Style style) {
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

        Intent hangupIntent = new Intent(this.context, CallActionReceiver.class);
        hangupIntent.setAction("hangUpCall");
        hangupIntent.putExtra("pushMessagePayload", this.pushMessagePayload);
        hangupIntent.putExtra("notificationID", this.notificationID);
        PendingIntent hangupPendingIntent = PendingIntent.getBroadcast(
                this.context, 0, hangupIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        JSONObject payload = null;
        try {
            payload = new JSONObject(this.pushMessagePayload);
        } catch (JSONException e) {
            throw new RuntimeException("CallNotification unable to parse pushMessagePayload, error: " + e);
        }

        String callerName = payload.optString("from", "UNKNOWN");

        String contentTitle;
        switch (style) {
            case INCOMING_CALL:
                contentTitle = "Incoming call";
                break;
            case ONGOING_CALL:
                contentTitle = "Ongoing call";
                break;
            default:
                throw new RuntimeException("No CallNotification contentTitle defined for style: " + style);
        }

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this.context, CallNotification.NOTIFICATION_CHANNEL_ID)
                .setContentTitle(contentTitle)
                .setSmallIcon(android.R.drawable.ic_menu_call)
                .setLargeIcon(BitmapFactory.decodeResource(this.context.getResources(), android.R.drawable.sym_def_app_icon))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_CALL)
                .setSound(this.getRingtoneURI()) // For compatibility with Android 8.0 and less. (normally set through channel)
                .setOngoing(true); // Can't be "dismissed" by the user, app will handle closing it

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            Log.d(TAG, "Creating CallStyle.forIncomingCall style notification (as this is supported by the device)...");
            Person callerPerson = new Person.Builder()
                    .setName(callerName)
                    .setImportant(true)
                    .build();

            // "CallStyle notifications must be for a foreground service or user initated job or use a fullScreenIntent."

            if (style == style.INCOMING_CALL) {
                builder.setStyle(NotificationCompat.CallStyle.forIncomingCall(callerPerson, declinePendingIntent, answerPendingIntent));

                Intent fullScreenIntent = new Intent(this.context, IncomingCallActivity.class);
                fullScreenIntent.putExtra("pushMessagePayload", this.pushMessagePayload);
                PendingIntent fullScreenPendingIntent = PendingIntent.getActivity(
                        this.context, 0, fullScreenIntent,
                        PendingIntent.FLAG_IMMUTABLE
                );
                builder.setFullScreenIntent(fullScreenPendingIntent, true);
            } else if (style == style.ONGOING_CALL) {
                builder.setStyle(NotificationCompat.CallStyle.forOngoingCall(callerPerson, hangupPendingIntent));

                PackageManager packageManager = context.getPackageManager();

                Class mainActivity;
                String  packageName = context.getPackageName();
                Intent  launchIntent = packageManager.getLaunchIntentForPackage(packageName);
                String  className = launchIntent.getComponent().getClassName();
                try {
                    mainActivity = Class.forName(className);
                } catch (ClassNotFoundException e) {
                    throw new RuntimeException(e);
                }

                PendingIntent contentPendingIntent = PendingIntent.getActivity(
                        context,
                        0,
                        launchIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
                );

                Intent fullScreenIntent = new Intent(this.context, mainActivity);
                fullScreenIntent.putExtra("pushMessagePayload", this.pushMessagePayload);
                PendingIntent fullScreenPendingIntent = PendingIntent.getActivity(
                        this.context, 0, fullScreenIntent,
                        PendingIntent.FLAG_IMMUTABLE
                );

                builder.setContentIntent(contentPendingIntent);
                builder.setFullScreenIntent(fullScreenPendingIntent, true);
            }
        } else {
            builder.setContentText(callerName);

            if (style == Style.INCOMING_CALL) {
                builder.addAction(android.R.drawable.ic_menu_call, "Answer", answerPendingIntent)
                        .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Decline", declinePendingIntent);
            } else if (style == Style.ONGOING_CALL) {
                builder.addAction(android.R.drawable.ic_menu_call, "Hang up", hangupPendingIntent);
            }
        }

        Log.d(TAG, "launching call notification via android NotificationManager notify()...");
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
                CallNotification.NOTIFICATION_CHANNEL_ID,
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