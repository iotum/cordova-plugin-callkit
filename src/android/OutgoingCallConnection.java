package com.dmarc.cordovacall;

import android.telecom.Connection;

class OutgoingCallConnection extends CallConnection {
    OutgoingCallConnection(MyConnectionService service, String peerName, String sessionId) {
        super(service, peerName, sessionId);
    }

    @Override
    public void onStateChanged(int state) {
        if (state == Connection.STATE_DIALING) {
            // For outgoing connections specifically, permissions are requested (including the necessary RECORD_AUDIO)
            // before facetalk calls sendCall so when the OutoingCallConnection is created and dialing,
            // we can safely proceed to start the call audio service.
            this.startCallAudioService();
        }
        super.onStateChanged(state);
    }
}
