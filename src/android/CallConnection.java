package com.dmarc.cordovacall;

import org.apache.cordova.PluginResult;

import android.content.Context;
import android.content.Intent;
import android.telecom.CallAudioState;
import android.telecom.Connection;
import android.telecom.DisconnectCause;
import android.util.Log;

/**
 * Base class for both incoming and outgoing connections.
 * Holds the common logic shared between IncomingCallConnection and OutgoingCallConnection.
 */
class CallConnection extends Connection {
    protected final MyConnectionService service;

    CallConnection(MyConnectionService service) {
        this.service = service;
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
        if (state == Connection.STATE_ACTIVE) {
            AudioRouteMonitoring.onCallConnected();
        } else if (state == Connection.STATE_DISCONNECTED) {
            this.destroy();
            AudioRouteMonitoring.onCallEnded();
            Log.d(MyConnectionService.TAG, "Stopping CallAudioService...");
            Context context = service.getApplicationContext();
            Intent serviceIntent = new Intent(context, CallAudioService.class);
            context.stopService(serviceIntent);
        }
    }
}
