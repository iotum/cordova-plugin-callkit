package com.dmarc.cordovacall;

import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.ImageButton;
import android.widget.RelativeLayout;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;

import android.telecom.Connection;

public class IncomingCallActivity extends AppCompatActivity {
    private static final String TAG = "IncomingCallActivity";

    public static final String EXTRA_CALLER_NAME = "callerName";
    public static final String EXTRA_MESSAGE_PAYLOAD = "pushMessagePayload";

    private String pushMessagePayload;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Intent intent = this.getIntent();
        String callerName = intent.getStringExtra(EXTRA_CALLER_NAME);
        this.pushMessagePayload = intent.getStringExtra(EXTRA_MESSAGE_PAYLOAD);

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
        tvCallerName.setId(View.generateViewId()); // Generate a unique ID
        tvCallerName.setTextColor(Color.WHITE);
        tvCallerName.setTextSize(TypedValue.COMPLEX_UNIT_SP, 34);
        tvCallerName.setGravity(Gravity.CENTER_HORIZONTAL);

        tvCallerName.setText(callerName != null ? callerName : "Unknown Caller");

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
        answerIntent.putExtra("pushMessagePayload", this.pushMessagePayload);
        this.sendBroadcast(answerIntent);

        this.finishAndRemoveTask();
    }

    private void onDeclineClicked() {
        Log.d(TAG, "onDeclineClicked");

        Intent declineIntent = new Intent(this.getApplicationContext(), CallActionReceiver.class);
        declineIntent.setAction("declineCall");
        declineIntent.putExtra("pushMessagePayload", this.pushMessagePayload);
        this.sendBroadcast(declineIntent);

        this.finishAndRemoveTask();
    }
}