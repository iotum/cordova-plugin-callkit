package com.dmarc.cordovacall;

import android.app.ComponentCaller;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.telecom.Connection;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.ImageButton;
import android.widget.RelativeLayout;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import org.json.JSONException;
import org.json.JSONObject;

// Lock screen / full screen capable activity to display a screen with an answer/decline button.
// Receives the "pushMessagePayload" as an intent extra. Reads and displays the caller name from that.
public class IncomingCallActivity extends AppCompatActivity {
    private static final String TAG = "IncomingCallActivity";

    private int callerNameViewID;

    private final BroadcastReceiver callStateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            Log.d(TAG, "callStateReciever onReceive: " + action);
            if ("connection_state_changed".equals(action)) {
                String payload = getPushMessagePayload();
                JSONObject payloadJSON;
                try {
                    payloadJSON = new JSONObject(payload);
                } catch (JSONException e) {
                    throw new RuntimeException("callStateReciever: unable to parse payload json: " + e);
                }

                String callUUID;
                try {
                    callUUID = payloadJSON.getString("call_uuid");
                } catch (JSONException e) {
                    throw new RuntimeException("callStateReceive: unable to get call_uuid from payload json");
                }

                if (callUUID.equals(intent.getStringExtra("call_uuid"))) {
                    int newState = intent.getIntExtra("state", 0);
                    if (newState == Connection.STATE_DISCONNECTED) {
                        Log.d(TAG, "closing activity as call was disconnected");
                        finishAndRemoveTask();
                    }
                }
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        IntentFilter filter = new IntentFilter("connection_state_changed");

        Log.d(TAG, "Registering callStateReciever");
        LocalBroadcastManager.getInstance(this.getApplicationContext()).registerReceiver(callStateReceiver, filter);

        Connection connection = MyConnectionService.getConnectionByPayload(this.getPushMessagePayload());
        if (connection == null) {
            Log.d(TAG, "Exiting, connection no longer exists.");
            finishAndRemoveTask();
            return;
        } else if (connection.getState() == Connection.STATE_DISCONNECTED) {
            Log.d(TAG, "Exiting, connection is disconnected");
            finishAndRemoveTask();
            return;
        }

        // --- Activity Window Setup ---
        getWindow().addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED |
                        WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD |
                        WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON |
                        WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
        );

        // --- Create Root Layout ---
        RelativeLayout rootLayout = new RelativeLayout(this);
        rootLayout.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));
        rootLayout.setBackgroundColor(Color.parseColor("#2C3E50"));

        // --- Create Caller Name TextView ---
        TextView tvCallerName = new TextView(this);
        this.callerNameViewID = View.generateViewId();
        tvCallerName.setId(this.callerNameViewID); // Generate a unique ID
        tvCallerName.setTextColor(Color.WHITE);
        tvCallerName.setTextSize(TypedValue.COMPLEX_UNIT_SP, 34);
        tvCallerName.setGravity(Gravity.CENTER_HORIZONTAL);

        RelativeLayout.LayoutParams callerNameParams = new RelativeLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        callerNameParams.addRule(RelativeLayout.CENTER_HORIZONTAL);
        callerNameParams.topMargin = dpToPx(this, 128);
        tvCallerName.setLayoutParams(callerNameParams);

        // --- Create Call Type TextView ---
        TextView tvCallType = new TextView(this);
        tvCallType.setText("Incoming Call");
        tvCallType.setTextColor(Color.parseColor("#BDC3C7"));
        tvCallType.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);

        RelativeLayout.LayoutParams callTypeParams = new RelativeLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        callTypeParams.addRule(RelativeLayout.CENTER_HORIZONTAL);
        callTypeParams.addRule(RelativeLayout.BELOW, tvCallerName.getId());
        callTypeParams.topMargin = dpToPx(this, 8);
        tvCallType.setLayoutParams(callTypeParams);

        // --- Create Decline Button ---
        ImageButton buttonDecline = createCallButton(
                "#E74C3C", // Red background
                android.R.drawable.ic_menu_call // System "end call" icon
        );
        buttonDecline.setOnClickListener(v -> onDeclineClicked());

        RelativeLayout.LayoutParams declineParams = new RelativeLayout.LayoutParams(
                dpToPx(this, 72),
                dpToPx(this, 72)
        );
        declineParams.addRule(RelativeLayout.ALIGN_PARENT_BOTTOM);
        declineParams.addRule(RelativeLayout.ALIGN_PARENT_START);
        declineParams.leftMargin = dpToPx(this, 60);
        declineParams.bottomMargin = dpToPx(this, 96);
        buttonDecline.setLayoutParams(declineParams);

        // --- Create Answer Button ---
        ImageButton buttonAnswer = createCallButton(
                "#2ECC71", // Green background
                android.R.drawable.ic_menu_call // System "call" icon
        );
        // We'll tint this green icon to look more like an "answer" icon
        buttonAnswer.setRotation(135); // Rotate the call icon to look like an answer phone
        buttonAnswer.setOnClickListener(v -> onAnswerClicked());

        RelativeLayout.LayoutParams answerParams = new RelativeLayout.LayoutParams(
                dpToPx(this, 72),
                dpToPx(this, 72)
        );
        answerParams.addRule(RelativeLayout.ALIGN_PARENT_BOTTOM);
        answerParams.addRule(RelativeLayout.ALIGN_PARENT_END);
        answerParams.rightMargin = dpToPx(this, 60);
        answerParams.bottomMargin = dpToPx(this, 96);
        buttonAnswer.setLayoutParams(answerParams);

        // --- Add all views to the root layout ---
        rootLayout.addView(tvCallerName);
        rootLayout.addView(tvCallType);
        rootLayout.addView(buttonDecline);
        rootLayout.addView(buttonAnswer);

        // --- Set the root layout as the content view ---
        setContentView(rootLayout);

        this.updateCallerNameView();
    }

    protected void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "unregistering callStateReceiver");
        LocalBroadcastManager.getInstance(this.getApplicationContext()).unregisterReceiver(callStateReceiver);
    }

    @Override
    public void onNewIntent(@NonNull Intent intent, @NonNull ComponentCaller caller) {
        super.onNewIntent(intent, caller);
        this.setIntent(intent); // So that future calls to this.getIntent() return the new intent, and not the initial intent of the activity

        this.updateCallerNameView();
    }

    private String getPushMessagePayload() {
        Intent intent = this.getIntent();
        return intent.getStringExtra("pushMessagePayload");
    }

    private void updateCallerNameView() {
        String payload = this.getPushMessagePayload();

        String callerName = "Unknown Caller";
        try {
            JSONObject payloadJSON = new JSONObject(payload);
            callerName = payloadJSON.getString("from");
        } catch (JSONException e) {
            Log.e(TAG, "Unable to read caller name from payload: " + payload);
        }

        TextView tvCallerName = this.findViewById(this.callerNameViewID);
        tvCallerName.setText(callerName);
    }

    /**
     * Helper method to create a styled circular call button.
     */
    private ImageButton createCallButton(String backgroundColor, int iconResId) {
        ImageButton button = new ImageButton(this);

        // Create circular background
        GradientDrawable shape = new GradientDrawable();
        shape.setShape(GradientDrawable.OVAL);
        shape.setColor(Color.parseColor(backgroundColor));
        button.setBackground(shape);

        // Set icon
        button.setImageResource(iconResId);
        button.setColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN); // Tint icon to white

        // Set elevation for shadow
        button.setElevation(dpToPx(this, 8));

        return button;
    }

    /**
     * Helper method to convert density-independent pixels (dp) to pixels (px).
     */
    public static int dpToPx(Context context, int dp) {
        return (int) (dp * context.getResources().getDisplayMetrics().density);
    }

    private void onAnswerClicked() {
        Log.d(TAG, "onAnswerClicked");

        Intent answerIntent = new Intent(this.getApplicationContext(), CallActionReceiver.class);
        answerIntent.setAction("answerCall");
        answerIntent.putExtra("pushMessagePayload", this.getPushMessagePayload());
        answerIntent.putExtra("fromLockscreen", true);
        this.sendBroadcast(answerIntent);

        this.finishAndRemoveTask();
    }

    private void onDeclineClicked() {
        Log.d(TAG, "onDeclineClicked");

        Intent declineIntent = new Intent(this.getApplicationContext(), CallActionReceiver.class);
        declineIntent.setAction("declineCall");
        declineIntent.putExtra("pushMessagePayload", this.getPushMessagePayload());
        declineIntent.putExtra("fromLockscreen", true);
        this.sendBroadcast(declineIntent);

        this.finishAndRemoveTask();
    }
}