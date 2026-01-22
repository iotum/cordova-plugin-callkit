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

        if (action.equals("hangUpCall")) {
            Connection activeConnection = MyConnectionService.getConnection();
            if (activeConnection != null) {
                activeConnection.onDisconnect();
            } else {
                Log.d(TAG, "no active call to disconnect (possibly already disconnected), closing notification");
                // Normally: we disconnect the telecom connection, which brings down the CallAudioService foreground service and its notification in turn
                // For robustness (to prevent any orphaned ongoing notification) ensure the foreground service + notification is shutdown
                Intent serviceIntent = new Intent(context, CallAudioService.class);
                context.stopService(serviceIntent);
            }
            return;
        }

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
        }
    }
}
