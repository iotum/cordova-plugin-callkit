var exec = require('cordova/exec');

// Standardized Audio Route Constants (used by both platforms)
exports.AudioRoute = {
  EARPIECE: 'EARPIECE',
  BLUETOOTH: 'BLUETOOTH', 
  SPEAKER: 'SPEAKER',
  WIRED_HEADSET: 'WIRED_HEADSET',
  UNKNOWN: 'UNKNOWN'
};

// Audio Route Change Types
exports.AudioRouteChangeType = {
  DEVICE_CHANGED: 'DEVICE_CHANGED',
  PROGRAMMATIC_CHANGE: 'PROGRAMMATIC_CHANGE',
  ROUTE_CHANGED: 'ROUTE_CHANGED'
};

// iOS Audio Route Change Reasons (iOS specific)
exports.AudioRouteChangeReason = {
  UNKNOWN: 1,
  NEW_DEVICE_AVAILABLE: 2,
  OLD_DEVICE_UNAVAILABLE: 3,
  CATEGORY_CHANGE: 4,
  OVERRIDE: 5, // Programmatic change via speakerOn/speakerOff
  WAKE_FROM_SLEEP: 6,
  NO_SUITABLE_ROUTE: 7,
  ROUTE_CONFIG_CHANGE: 8
};

// iOS Audio Route Change Reason Strings (iOS specific)
exports.AudioRouteChangeReasonString = {
  1: 'Unknown',
  2: 'NewDeviceAvailable',
  3: 'OldDeviceUnavailable', 
  4: 'CategoryChange',
  5: 'Override',
  6: 'WakeFromSleep',
  7: 'NoSuitableRouteForCategory',
  8: 'RouteConfigurationChange'
};

// Common Audio Output Types (for iOS compatibility)
exports.AudioOutputType = {
  RECEIVER: 'Receiver', // Earpiece
  SPEAKER: 'Speaker',
  HEADPHONES: 'HeadphonesAndMicrophone',
  BLUETOOTH_HFP: 'BluetoothHFP',
  BLUETOOTH_A2DP: 'BluetoothA2DPOutput',
  AIRPLAY: 'AirPlay',
  USB_AUDIO: 'USBAudio',
  UNKNOWN: 'Unknown'
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
    error("Value Must Be True Or False");
  }
};

exports.setDTMFState = function (value, success, error) {
  if (typeof value == "boolean") {
    exec(success, error, "CordovaCall", "setDTMFState", [value]);
  } else {
    error("Value Must Be True Or False");
  }
};

exports.setVideo = function (value, success, error) {
  if (typeof value == "boolean") {
    exec(success, error, "CordovaCall", "setVideo", [value]);
  } else {
    error("Value Must Be True Or False");
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

exports.connectCall = function (sessionId, success, error) {
  exec(success, error, "CordovaCall", "connectCall", [sessionId]);
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

exports.callNumber = function (to, success, error) {
  exec(success, error, "CordovaCall", "callNumber", [to]);
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

// Utility Functions
exports.parseAudioRouteChangeEvent = function(eventData) {
  try {
    // Handle both string and object data
    var data = typeof eventData === 'string' ? JSON.parse(eventData) : eventData;
    
    var result = {
      changeType: data.changeType,
      message: data.message
    };
    
    // Android format
    if (data.route) {
      result.platform = 'android';
      result.route = data.route;
      result.supportedRoutes = data.supportedRoutes;
      result.isMuted = data.isMuted;
      
      // Convert Android route to common format
      result.outputType = exports.androidRouteToOutputType(data.route);
    }
    
    // iOS format  
    if (data.reason !== undefined) {
      result.platform = 'ios';
      result.reason = data.reason;
      result.reasonString = data.reasonString;
      result.previousOutputType = data.previousOutputType;
      result.currentOutputType = data.currentOutputType;
      
      // Add convenience properties
      result.outputType = data.currentOutputType;
      result.isPhysicalChange = (data.reason === exports.AudioRouteChangeReason.NEW_DEVICE_AVAILABLE || 
                                data.reason === exports.AudioRouteChangeReason.OLD_DEVICE_UNAVAILABLE);
      result.isProgrammaticChange = (data.reason === exports.AudioRouteChangeReason.OVERRIDE);
    }
    
    return result;
  } catch (e) {
    console.error('Error parsing audio route change event:', e);
    return eventData;
  }
};

// Convert Android route constants to iOS-style output types
exports.androidRouteToOutputType = function(androidRoute) {
  switch(androidRoute) {
    case exports.AudioRoute.EARPIECE:
      return exports.AudioOutputType.RECEIVER;
    case exports.AudioRoute.SPEAKER:
      return exports.AudioOutputType.SPEAKER;
    case exports.AudioRoute.BLUETOOTH:
      return exports.AudioOutputType.BLUETOOTH_HFP;
    case exports.AudioRoute.WIRED_HEADSET:
      return exports.AudioOutputType.HEADPHONES;
    default:
      return exports.AudioOutputType.UNKNOWN;
  }
};