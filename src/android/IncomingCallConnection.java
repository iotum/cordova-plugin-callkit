package com.dmarc.cordovacall;

import org.apache.cordova.PluginResult;

import android.app.Notification;
import android.app.NotificationManager;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.telecom.Connection;
import android.telecom.DisconnectCause;
import android.util.Log;

class IncomingCallConnection extends CallConnection {
    // How long to wait, after the user taps answer, for the web app to establish its SIP/RTC
    // session and call connectCall(). If this expires the connection is treated as stuck and
    // torn down, rather than being suppressed from dismiss handling forever (see isAnsweredWithinGracePeriod()).
    private static final long ANSWER_CONNECT_TIMEOUT_MS = 15000;

    private final String callUUID;
    private final String payloadString;
    private IncomingCallNotification incomingCallNotification;
    private final Handler timeoutHandler = new Handler(Looper.getMainLooper());
    // Set once onAnswer() has run. The connection stays in STATE_RINGING until the web app
    // calls connectCall(), so this lets MyConnectionService distinguish "user already answered,
    // just waiting on the web app" from a genuinely un-answered ringing call - but only for a
    // bounded grace period, so a call whose web app never connects doesn't hang around forever.
    private volatile long answeredAtElapsed = 0;

    boolean isAnsweredWithinGracePeriod() {
        long answeredAt = answeredAtElapsed;
        long elapsed = SystemClock.elapsedRealtime() - answeredAt;
        return answeredAt != 0 && elapsed >= 0 && elapsed < ANSWER_CONNECT_TIMEOUT_MS;
    }

    IncomingCallConnection(MyConnectionService service, String callUUID, String payloadString, String callerName, String sessionId) {
        super(service, callerName, sessionId);
        this.callUUID = callUUID;
        this.payloadString = payloadString;
    }

    @Override
    public void onShowIncomingCallUi() { // Only for self managed connections
        Log.d(MyConnectionService.TAG, "onShowIncomingCallUi() invoked, for call_uuid: " + callUUID);
        this.setRinging();

        this.incomingCallNotification = new IncomingCallNotification(payloadString, service.getApplicationContext(), this.sessionId);
        Notification notification = this.incomingCallNotification.build();

        NotificationManager notificationManager = (NotificationManager) service.getSystemService(Context.NOTIFICATION_SERVICE);
        notificationManager.notify(this.incomingCallNotification.getNotificationID(), notification);
    }

    public void cancelIncomingCallNotification() {
        if (this.incomingCallNotification == null) {
            return;
        }
        NotificationManager notificationManager = (NotificationManager) service.getSystemService(Context.NOTIFICATION_SERVICE);
        notificationManager.cancel(this.incomingCallNotification.getNotificationID());
        this.incomingCallNotification = null;
    }

    @Override
    public void onAnswer() {
        onAnswer(false);
    }

    void onAnswer(boolean fromLockscreen) {
        Log.d(MyConnectionService.TAG, "onAnswer()");

        answeredAtElapsed = SystemClock.elapsedRealtime();
        timeoutHandler.postDelayed(this::abortIfStillNotConnected, ANSWER_CONNECT_TIMEOUT_MS);
        cancelIncomingCallNotification();

        // Start the (phoneCall-type) foreground service before bringing the app to the foreground below.
        // Without an active foreground service of this type, the OS blocks the startActivity() call in
        // showWebApp() as a Background Activity Launch (BAL_BLOCK), since the app has no visible window
        // at this point. This is called again once the connection is ACTIVE (see onStateChanged) to add
        // the microphone service type once RECORD_AUDIO is confirmed granted.
        this.startCallAudioService();

        service.showWebApp("answerCall", payloadString, fromLockscreen);

        Log.d(MyConnectionService.TAG, "Emitting CordovaCall answer event...");
        CordovaCall.emitDurableEvent("answer", sessionId, new PluginResult(PluginResult.Status.OK, payloadString));
    }

    @Override
    public void onReject() {
        onReject(false);
    }

    void onReject(boolean fromLockscreen) {
        Log.d(MyConnectionService.TAG, "onReject, call_uuid: " + callUUID);
        this.setDisconnected(new DisconnectCause(DisconnectCause.REJECTED));
        this.destroy();

        service.showWebApp("declineCall", payloadString, fromLockscreen); // Controversial UX but doing so that we can tell the web app to reject the call (which may let the caller know it was declined)

        CordovaCall.emitEvent("reject", new PluginResult(PluginResult.Status.OK, payloadString));
    }

    @Override
    public void onAbort() {
        Log.d(MyConnectionService.TAG, "onAbort, call_uuid: " + callUUID);
        super.onAbort();
    }

    @Override
    public void onDisconnect() {
        Log.d(MyConnectionService.TAG, "onDisconnect, call_uuid: " + callUUID);
        super.onDisconnect();
    }

    // Runs once ANSWER_CONNECT_TIMEOUT_MS after onAnswer() if the web app never called connectCall(),
    // e.g. because its SIP/RTC session failed to establish. Without this the connection would be stuck
    // in STATE_RINGING forever, since dismiss pushes are ignored for answered calls (see MyConnectionService).
    private void abortIfStillNotConnected() {
        int state = getState();
        if (state != Connection.STATE_ACTIVE && state != Connection.STATE_DISCONNECTED) {
            Log.w(MyConnectionService.TAG, "Answered call never connected within grace period, disconnecting stuck call, call_uuid: " + callUUID);
            CordovaCall.discardNextWebViewEvents("answer", sessionId);
            onAbort();
        }
    }

    @Override
    void updatePeerName(String newPeerName) {
        super.updatePeerName(newPeerName);
        setCallerDisplayName(newPeerName, android.telecom.TelecomManager.PRESENTATION_ALLOWED);
    }

    @Override
    public void onStateChanged(int state) {
        if (state == Connection.STATE_ACTIVE) {
            // For an incoming call specifically, we wait for the connection to be ACTIVE as this would
            // be after the facetalk web app has answered the call + RECORD_AUDIO permission is granted
            this.startCallAudioService();
        } else if (state == Connection.STATE_DISCONNECTED) {
            cancelIncomingCallNotification();
        }

        if (state == Connection.STATE_ACTIVE || state == Connection.STATE_DISCONNECTED) {
            timeoutHandler.removeCallbacksAndMessages(null);
        }

        if (state == Connection.STATE_DISCONNECTED) {
            CordovaCall.discardNextWebViewEvents("answer", sessionId);
        }

        super.onStateChanged(state);
    }
}
