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

        this.closeNotification(context, intent);

        Connection conn = MyConnectionService.getConnectionByPayload(intent.getStringExtra("pushMessagePayload"));

        if (conn != null) {
            if (action.equals("declineCall")) {
                conn.onReject();
            } else if (action.equals("answerCall")) {
                conn.onAnswer();
            }
        } else {
            Log.d(TAG, "Unable to action - connection no longer exists");
        }
    }

    private void closeNotification(Context context, Intent intent) {
        NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        nm.cancel(intent.getIntExtra("notificationID", 0));
    }
}
