package com.dmarc.cordovacall;

import org.apache.cordova.PluginResult;

import android.content.Context;
import android.content.Intent;
import android.telecom.CallAudioState;
import android.telecom.Connection;
import android.telecom.DisconnectCause;
import android.util.Log;

import androidx.localbroadcastmanager.content.LocalBroadcastManager;

/**
 * Base class for both incoming and outgoing connections.
 * Holds the common logic shared between IncomingCallConnection and OutgoingCallConnection.
 */
class CallConnection extends Connection {
    protected final MyConnectionService service;
    protected String peerName;
    protected String sessionId;

    CallConnection(MyConnectionService service, String peerName, String sessionId) {
        this.service = service;
        this.peerName = peerName;
        this.sessionId = sessionId;
    }

    @Override
    public void onCallAudioStateChanged(CallAudioState state) {
        super.onCallAudioStateChanged(state);
        service.handleCallAudioStateChanged(state);
    }

    @Override
    public void onAbort() {
        this.setDisconnected(new DisconnectCause(DisconnectCause.CANCELED));
    }

    @Override
    public void onDisconnect() {
        this.setDisconnected(new DisconnectCause(DisconnectCause.LOCAL));
        CordovaCall.emitEvent("hangup", new PluginResult(PluginResult.Status.OK, "hangup event called successfully"));
    }

    @Override
    public void onStateChanged(int state) {
        super.onStateChanged(state);
        Log.d(MyConnectionService.TAG, "connection onStateChanged new state: " + Connection.stateToString(state));

        if (state == Connection.STATE_ACTIVE) {
            AudioRouteMonitor.onCallConnected();
        } else if (state == Connection.STATE_DISCONNECTED) {
            this.destroy();
            AudioRouteMonitor.onCallEnded();

            if (!MyConnectionService.hasActiveOrHoldingConnections()) {
                Log.d(MyConnectionService.TAG, "Stopping CallAudioService...");
                Context context = service.getApplicationContext();
                Intent serviceIntent = new Intent(context, CallAudioService.class);
                context.stopService(serviceIntent);
            } else {
                Log.d(MyConnectionService.TAG, "Not stopping CallAudioService - other active or holding connections remain.");
            }

            MyConnectionService.onConnectionDisconnected(this.sessionId);
        }

        Log.d(MyConnectionService.TAG, "broadcasting connection_state_changed sessionId: " + this.sessionId + " state: " + state);
        Intent intent = new Intent("connection_state_changed");
        intent.putExtra("sessionId", this.sessionId);
        intent.putExtra("state", state);
        LocalBroadcastManager.getInstance(service.getApplicationContext()).sendBroadcast(intent);
    }

    protected void startCallAudioService() {
        // NOTE: CallAudioService should be started before mic access, in order to work.
        // IMPORTANT: This preserves the ability to use the mic when the app is in the background!
        // IMPORTANT: RECORD_AUDIO permission must be granted beforehand! (since this service is flagged with: FOREGROUND_SERVICE_TYPE_MICROPHONE)
        Log.d(MyConnectionService.TAG, "Starting CallAudioService...");
        Intent intent = new Intent(service.getApplicationContext(), CallAudioService.class);
        intent.putExtra("peerName", peerName);
        intent.putExtra("sessionId", this.sessionId);
        service.startForegroundService(intent);
    }
}
