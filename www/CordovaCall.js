var exec = require('cordova/exec');

// Standardized Audio Route Constants (used by both platforms)
exports.AudioRoute = {
  EARPIECE: 'earpiece',
  BLUETOOTH: 'bluetooth', 
  SPEAKER: 'speaker',
  WIRED_HEADSET: 'wired_headset',
  UNKNOWN: 'unknown'
};

// Audio Route Change Types
exports.AudioRouteChangeType = {
  DEVICE_CHANGED: 'deviceChanged',           // Android: Physical device connect/disconnect
  PROGRAMMATIC_CHANGE: 'programmaticChange', // Android: Via setAudioRoute() method
  ROUTE_CHANGED: 'routeChanged'              // iOS: General route change
};

exports.setAppName = function (appName, success, error) {
  exec(success, error, "CordovaCall", "setAppName", [appName]);
};

exports.setIcon = function (iconName, success, error) {
  exec(success, error, "CordovaCall", "setIcon", [iconName]);
};

exports.setRingtone = function (ringtoneName, success, error) {
  exec(success, error, "CordovaCall", "setRingtone", [ringtoneName]);
};

exports.setIncludeInRecents = function (value, success, error) {
  if (typeof value == "boolean") {
    exec(success, error, "CordovaCall", "setIncludeInRecents", [value]);
  } else {
    if (typeof error == "function") { error("Value Must Be True Or False"); }
  }
};

exports.setDTMFState = function (value, success, error) {
  if (typeof value == "boolean") {
    exec(success, error, "CordovaCall", "setDTMFState", [value]);
  } else {
    if (typeof error == "function") { error("Value Must Be True Or False"); }
  }
};

exports.setVideo = function (value, success, error) {
  if (typeof value == "boolean") {
    exec(success, error, "CordovaCall", "setVideo", [value]);
  } else {
    if (typeof error == "function") { error("Value Must Be True Or False"); }
  }
};

exports.receiveCall = function (sessionId, from, id, success, error) {
  if (typeof id == "function") {
    error = success;
    success = id;
    id = undefined;
  } else if (id) {
    id = id.toString();
  }
  exec(success, error, "CordovaCall", "receiveCall", [from, id, sessionId]);
};

exports.sendCall = function (sessionId, to, id, success, error) {
  if (typeof id == "function") {
    error = success;
    success = id;
    id = undefined;
  } else if (id) {
    id = id.toString();
  }
  exec(success, error, "CordovaCall", "sendCall", [to, id, sessionId]);
};

exports.connectCall = function (sessionId, recentsSessionId, success, error) {
  if (typeof recentsSessionId == "function") {
    error = success;
    success = recentsSessionId;
    recentsSessionId = undefined;
  }
  exec(success, error, "CordovaCall", "connectCall", [sessionId, recentsSessionId || null]);
};

exports.updateCallName = function (sessionId, callName, success, error) {
  exec(success, error, "CordovaCall", "updateCallName", [sessionId, callName || null]);
};

exports.endCall = function (sessionId, success, error) {
  exec(success, error, "CordovaCall", "endCall", [sessionId]);
};

exports.mute = function (sessionId, success, error) {
  exec(success, error, "CordovaCall", "mute", [sessionId]);
};

exports.unmute = function (sessionId, success, error) {
  exec(success, error, "CordovaCall", "unmute", [sessionId]);
};

exports.speakerOn = function (success, error) {
  exec(success, error, "CordovaCall", "speakerOn", []);
};

exports.speakerOff = function (success, error) {
  exec(success, error, "CordovaCall", "speakerOff", []);
};

exports.getAudioRoute = function (success, error) {
  exec(success, error, "CordovaCall", "getAudioRoute", []);
};

exports.callNumber = function (to, success, error) {
  exec(success, error, "CordovaCall", "callNumber", [to]);
};

exports.hold = function (sessionId, success, error) {
  exec(success, error, "CordovaCall", "hold", [sessionId]);
};

exports.unhold = function (sessionId, success, error) {
  exec(success, error, "CordovaCall", "unhold", [sessionId]);
};

exports.group = function (sessionId, groupWithSessionId, success, error) {
  exec(success, error, "CordovaCall", "group", [sessionId, groupWithSessionId]);
};

exports.ungroup = function (sessionId, success, error) {
  exec(success, error, "CordovaCall", "ungroup", [sessionId]);
};

exports.on = function (e, f) {
  var success = function (message) {
    f(message);
  };
  var error = function () {
  };
  exec(success, error, "CordovaCall", "registerEvent", [e]);
};

exports.checkCallPermission = function (error) {
  exec(null, error, "CordovaCall", "checkCallPermission", []);
};

exports.canUseFullScreenIntent = function (success, error) {
  exec(success, error, "CordovaCall", "canUseFullScreenIntent", []);
};

exports.openFullScreenIntentSettings = function (success, error) {
  exec(success, error, "CordovaCall", "openFullScreenIntentSettings", []);
};

exports.dismissRingingCall = function (sessionId, success) {
  exec(success, null, "CordovaCall", "dismissRingingCall", [sessionId]);
}

// iOS Only Functions

exports.log = function (message) {
  exec(null, null, "CordovaCall", "log", [message]);
}

exports.setupAudioSession = function (success, error) {
  exec(success, error, "CordovaCall", "setupAudioSession", []);
}

exports.setAllowUnmute = function (sessionId, value, success, error) {
  if (typeof value == "boolean") {
    exec(success, error, "CordovaCall", "setAllowUnmute", [sessionId, value]);
  } else {
    if (typeof error == "function") { error("Value Must Be True Or False"); }
  }
};

exports.keepAlive = function (callback) {
  exec(callback, null, "CordovaCall", "keepAlive", []);
}

exports.stopKeepAlive = function () {
  exec(null, null, "CordovaCall", "stopKeepAlive", []);
}

exports.keepAliveInBackground = function (interval) {
  exec(null, null, "CordovaCall", "keepAliveInBackground", [interval]);
}

exports.stopKeepAliveInBackground = function () {
  exec(null, null, "CordovaCall", "stopKeepAliveInBackground", []);
}

exports.wsConnect = function (wsOptions, listener, success, error) {
  if (listener === undefined) {
    listener = function (data) {
      console.log(data);
    };
  }

  var connectSuccess = function (data) {
    if (success !== undefined && typeof success === "function") {
      success(data);
    }
    var flushRecvBuffer = true;
    exec(listener, listener, "CordovaCall", 'wsAddListeners', [data.webSocketId, flushRecvBuffer]);
  };

  exec(connectSuccess, error, "CordovaCall", 'wsConnect', [wsOptions]);
};

exports.wsSend = function (wsId, message) {
  exec(null, null, "CordovaCall", 'wsSend', [wsId, message]);
};

exports.wsClose = function (wsId, code, reason) {
  exec(null, null, "CordovaCall", 'wsClose', [wsId, code, reason]);
};