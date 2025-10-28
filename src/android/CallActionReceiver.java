package com.dmarc.cordovacall;

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
        if (action.equals("declineCall")) {
            Connection conn = MyConnectionService.getConnectionByPayload(intent.getStringExtra("pushMessagePayload"));
            if (conn != null) {
                conn.onReject();
            }
        } else if (action.equals("answerCall")) {
            Connection conn = MyConnectionService.getConnectionByPayload(intent.getStringExtra("pushMessagePayload"));
            if (conn != null) {
                conn.onAnswer();
            }
        }
    }
}
