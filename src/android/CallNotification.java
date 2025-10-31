package com.dmarc.cordovacall;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.graphics.BitmapFactory;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.util.Log;

import androidx.core.app.NotificationCompat;
import androidx.core.app.Person;
import org.json.JSONException;
import org.json.JSONObject;

import java.net.URI;
import java.util.Random;


public class CallNotification {
    private static final String TAG = "CallNotification";

    private String pushMessagePayload;
    private Integer notificationID;
    private Context context;
    private NotificationManager notificationManager;
    private Runnable timeoutRunnable;
    private Handler timeoutHandler = new Handler();

    private static final String NOTIFICATION_CHANNEL_ID = "incoming_calls";

    public CallNotification(String pushMessagePayload, Context context) {
        this.pushMessagePayload = pushMessagePayload;
        this.notificationID = new Random().nextInt(100000) + 1; // Random int > 0 TODO: maybe derive from call UUID string
        this.context = context;
        this.notificationManager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);

        this.createNotificationChannel();
    }

    public void show() {
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
            throw new RuntimeException("CallNotification unable to parse pushMessagePayload, error: " + e);
        }

        String callerName = payload.optString("from", "UNKNOWN");

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this.context, CallNotification.NOTIFICATION_CHANNEL_ID)
                .setContentTitle("Incoming call")
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
            Intent fullScreenIntent = new Intent(this.context, IncomingCallActivity.class);
            fullScreenIntent.putExtra("callerName", callerName);
            fullScreenIntent.putExtra("pushMessagePayload", this.pushMessagePayload);
            PendingIntent fullScreenPendingIntent = PendingIntent.getActivity(
                    this.context, 0, fullScreenIntent,
                    PendingIntent.FLAG_IMMUTABLE
            );

            builder.setStyle(NotificationCompat.CallStyle.forIncomingCall(callerPerson, declinePendingIntent, answerPendingIntent));
            builder.setFullScreenIntent(fullScreenPendingIntent, true);
        } else {
            builder.setContentText(callerName);
            builder.addAction(android.R.drawable.ic_menu_call, "Answer", answerPendingIntent)
                    .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Decline", declinePendingIntent);
        }

        Log.d(TAG, "launching call notification via android NotificationManager notify()...");
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
        Log.d(TAG, "closing call notification");
        this.notificationManager.cancel(this.notificationID);
        if (this.timeoutRunnable != null) {
            this.timeoutHandler.removeCallbacks(this.timeoutRunnable);
        }
    }

    private Uri getRingtoneURI() {
        return RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE);
    }

    private void createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(
                CallNotification.NOTIFICATION_CHANNEL_ID,
                "Incoming Calls",
                NotificationManager.IMPORTANCE_HIGH
        );
        channel.setDescription("Notifications for incoming calls");
        channel.setSound(this.getRingtoneURI(), null);
        channel.setVibrationPattern(new long[]{ 0, 1000, 500, 1000 });
        channel.enableVibration(true);
        this.notificationManager.createNotificationChannel(channel);
    }
}