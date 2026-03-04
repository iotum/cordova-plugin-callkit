package com.dmarc.cordovacall;

import org.apache.cordova.PluginResult;

import android.app.Notification;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.telecom.Connection;
import android.telecom.DisconnectCause;
import android.util.Log;

import androidx.localbroadcastmanager.content.LocalBroadcastManager;

class IncomingCallConnection extends CallConnection {
    private final String callUUID;
    private final String payloadString;
    private final String callerName;
    private IncomingCallNotification incomingCallNotification;
    private Runnable mainActivityChangeListener;

    IncomingCallConnection(MyConnectionService service, String callUUID, String payloadString, String callerName) {
        super(service);
        this.callUUID = callUUID;
        this.payloadString = payloadString;
        this.callerName = callerName;
    }

    @Override
    public void onShowIncomingCallUi() { // Only for self managed connections
        Log.d(MyConnectionService.TAG, "onShowIncomingCallUi() invoked, for call_uuid: " + callUUID);
        this.setRinging();

        this.incomingCallNotification = new IncomingCallNotification(payloadString, service.getApplicationContext());
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
        Log.d(MyConnectionService.TAG, "onAnswer()");

        cancelIncomingCallNotification();

        this.setActive();
        MyConnectionService.activeConnectionUUID = callUUID;

        service.showWebApp("answerCall", payloadString);

        Log.d(MyConnectionService.TAG, "Starting CallAudioService...");
        Intent intent = new Intent(service.getApplicationContext(), CallAudioService.class);
        intent.putExtra("peerName", callerName);
        service.startForegroundService(intent);

        Log.d(MyConnectionService.TAG, "Emitting CordovaCall answer event...");
        CordovaCall.emitEvent("answer", new PluginResult(PluginResult.Status.OK, payloadString));
    }

    @Override
    public void onReject() {
        Log.d(MyConnectionService.TAG, "onReject, call_uuid: " + callUUID);
        this.setDisconnected(new DisconnectCause(DisconnectCause.REJECTED));

        service.showWebApp("declineCall", payloadString); // Controversial UX but doing so that we can tell the web app to reject the call (which may let the caller know it was declined)

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

    @Override
    public void onStateChanged(int state) {
        Log.d(MyConnectionService.TAG, "connection onStateChanged: " + state);

        if (state == Connection.STATE_DISCONNECTED) {
            MyConnectionService.connectionMap.remove(callUUID);
            if (MyConnectionService.activeConnectionUUID != null && MyConnectionService.activeConnectionUUID.equals(callUUID)) {
                MyConnectionService.activeConnectionUUID = null;
            }
            if (this.mainActivityChangeListener != null) {
                CordovaCall.unregisterMainActivityStateChangeListener(this.mainActivityChangeListener);
            }
            cancelIncomingCallNotification();
        }

        super.onStateChanged(state);

        Log.d(MyConnectionService.TAG, "broadcasting connection_state_changed call_uuid: " + callUUID + " state: " + state);
        Intent intent = new Intent("connection_state_changed");
        intent.putExtra("call_uuid", callUUID);
        intent.putExtra("state", state);
        LocalBroadcastManager.getInstance(service.getApplicationContext()).sendBroadcast(intent);
    }
}
