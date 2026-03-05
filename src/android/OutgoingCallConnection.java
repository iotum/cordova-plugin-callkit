package com.dmarc.cordovacall;

import android.telecom.Connection;

class OutgoingCallConnection extends CallConnection {
    OutgoingCallConnection(MyConnectionService service, String peerName) {
        super(service, peerName);
    }

    @Override
    public void onStateChanged(int state) {
        if (state == Connection.STATE_ACTIVE) {
            MyConnectionService.activeOutgoingConnection = this;
        } else if (state == Connection.STATE_DISCONNECTED) {
            // In all cases when connection transitions to STATE_DISCONNECTED (both onAbort() and onDisconnect())
            // Ensure the connection is destroyed, etc.
            MyConnectionService.activeOutgoingConnection = null;
        }
        super.onStateChanged(state);
    }
}
