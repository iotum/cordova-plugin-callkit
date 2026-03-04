package com.dmarc.cordovacall;

import android.telecom.Connection;

class OutgoingCallConnection extends CallConnection {
    OutgoingCallConnection(MyConnectionService service) {
        super(service);
    }

    @Override
    public void onStateChanged(int state) {
        if (state == Connection.STATE_DISCONNECTED) {
            // In all cases when connection transitions to STATE_DISCONNECTED (both onAbort() and onDisconnect())
            // Ensure the connection is destroyed, etc.
            MyConnectionService.activeOutgoingConnection = null;
            MyConnectionService.activeConnectionUUID = null;
        }
        super.onStateChanged(state);
    }
}
