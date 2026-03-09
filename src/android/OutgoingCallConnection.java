package com.dmarc.cordovacall;

class OutgoingCallConnection extends CallConnection {
    OutgoingCallConnection(MyConnectionService service, String peerName, String sessionId) {
        super(service, peerName, sessionId);
    }

    @Override
    public void onStateChanged(int state) {
        super.onStateChanged(state);
    }
}
