package com.dmarc.cordovacall;

import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.telecom.Connection;
import android.util.Log;

public class CallActionReceiver extends BroadcastReceiver {
    static final String TAG = "CallActionReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        Log.d(TAG, "onReceive, intent action: " + action);
        String pushMessagePayload = intent.getStringExtra("pushMessagePayload");

        Connection conn = MyConnectionService.getConnectionByPayload(pushMessagePayload);

        if (conn != null) {
            if (action.equals("declineCall")) {
                conn.onReject();
            } else if (action.equals("answerCall")) {
                conn.onAnswer();
            } else {
                throw new RuntimeException("Invalid action: " + action);
            }
        } else {
            Log.d(TAG, "Exiting, connection no longer exists. pushMessagePayload: " + pushMessagePayload);
            if (intent.hasExtra("notificationID")) {
                // For safety ensure any associated notification is closed (avoids bad UX if the normal logic
                // that closes the notification when the connection is disconnected fails/crashes/etc.)
                int notificationID = intent.getIntExtra("notificationID", 0);
                Log.e(TAG, "Closing orphaned notification, ID: " + notificationID);
                NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
                nm.cancel(notificationID);
            }
        }
    }
}
