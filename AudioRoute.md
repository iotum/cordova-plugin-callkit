# AudioRoute Implementation Summary

## Overview

This document summarizes the implementation of the `audioRouteChange` event in the Cordova CallKit plugin, providing comprehensive audio route detection across both Android and iOS platforms.

## Features Implemented

### ✅ Cross-Platform audioRouteChange Event
- **Android**: Uses `TelecomManager`/`CallAudioState` with `BroadcastReceiver` for comprehensive detection
- **iOS**: Leverages `AVAudioSessionRouteChangeNotification` with enhanced filtering
- **Detection Scope**: Both physical changes (device connect/disconnect) and programmatic changes (via `setAudioRoute()`)

### ✅ Standardized Constants
Both platforms return identical route constants:
```javascript
CordovaCall.AudioRoute = {
  EARPIECE: 'earpiece',         // Phone earpiece/receiver
  BLUETOOTH: 'bluetooth',       // Bluetooth headset/device  
  SPEAKER: 'speaker',           // Built-in speaker
  WIRED_HEADSET: 'wired_headset', // Wired headphones/headset
  UNKNOWN: 'unknown'            // Other or unrecognized routes
}
```

### ✅ Change Type Classification
```javascript
CordovaCall.AudioRouteChangeType = {
  DEVICE_CHANGED: 'deviceChanged',           // Physical device connect/disconnect
  PROGRAMMATIC_CHANGE: 'programmaticChange', // Via setAudioRoute() method
  ROUTE_CHANGED: 'routeChanged'              // General route change
}
```

## Usage

### Event Registration
```javascript
CordovaCall.on('audioRouteChange', function(data) {
  console.log('Audio route changed to:', data.route);
  console.log('Change type:', data.changeType);
  
  // Handle route changes
  switch(data.route) {
    case CordovaCall.AudioRoute.BLUETOOTH:
      console.log('Switched to Bluetooth headset');
      break;
    case CordovaCall.AudioRoute.SPEAKER:
      console.log('Switched to speaker phone');
      break;
    case CordovaCall.AudioRoute.EARPIECE:
      console.log('Switched to earpiece');
      break;
    case CordovaCall.AudioRoute.WIRED_HEADSET:
      console.log('Switched to wired headset');
      break;
  }
});
```

### Programmatic Route Changes
```javascript
// These will trigger audioRouteChange events
CordovaCall.speakerOn();   // -> route: "speaker"
CordovaCall.speakerOff();  // -> route: "earpiece" (typically)
```

### Get Current Audio Route
```javascript
// Get the current audio route without triggering events
CordovaCall.getAudioRoute(
  function(route) {
    console.log('Current audio route:', route); // "speaker", "bluetooth", etc.
    
    switch(route) {
      case CordovaCall.AudioRoute.SPEAKER:
        console.log('Currently using speaker');
        break;
      case CordovaCall.AudioRoute.BLUETOOTH:
        console.log('Currently using Bluetooth');
        break;
      case CordovaCall.AudioRoute.EARPIECE:
        console.log('Currently using earpiece');
        break;
      case CordovaCall.AudioRoute.WIRED_HEADSET:
        console.log('Currently using wired headset');
        break;
      default:
        console.log('Unknown route:', route);
    }
  },
  function(error) {
    console.error('Failed to get audio route:', error);
  }
);
```

## Event Data Format

### Common Properties (Both Platforms)
```javascript
{
  "route": "speaker",                    // Standardized route constant
  "changeType": "DEVICE_CHANGED"         // Type of change
}
```

### Android-Specific Properties
```javascript
{
  "route": "bluetooth",
  "changeType": "DEVICE_CHANGED", 
  "supportedRoutes": ["earpiece", "speaker", "bluetooth"],
  "isMuted": false
}
```

### iOS-Specific Properties
```javascript
{
  "route": "wired_headset",
  "changeType": "routeChanged",
  "reason": 2,                           // Numeric reason code
  "reasonString": "NewDeviceAvailable",  // Human-readable reason
  "previousOutputType": "Receiver",      // iOS-specific previous route
  "currentOutputType": "HeadphonesAndMicrophone" // iOS-specific current route
}
```

## Platform Implementation Details

### Android Implementation
- **File**: `src/android/CordovaCall.java`
- **Detection Method**: Dual system using:
  - `BroadcastReceiver` for physical device changes
  - `Connection.onCallAudioStateChanged()` for programmatic changes
- **Integration**: Works with `MyConnectionService.java` for complete coverage

### iOS Implementation  
- **File**: `src/ios/CordovaCall.m`
- **Detection Method**: `AVAudioSessionRouteChangeNotification`
- **Filtering**: Enhanced reason filtering to capture all relevant changes
- **Route Mapping**: Converts iOS-specific output types to standardized constants

## Constants Reference

### iOS Audio Route Change Reasons
```javascript
CordovaCall.AudioRouteChangeReason = {
  UNKNOWN: 1,
  NEW_DEVICE_AVAILABLE: 2,        // Physical device connected
  OLD_DEVICE_UNAVAILABLE: 3,      // Physical device disconnected  
  CATEGORY_CHANGE: 4,
  OVERRIDE: 5,                    // Programmatic change via speakerOn/Off
  WAKE_FROM_SLEEP: 6,
  NO_SUITABLE_ROUTE: 7,
  ROUTE_CONFIG_CHANGE: 8
}
```

### iOS Output Types (Platform-Specific)
```javascript
CordovaCall.AudioOutputType = {
  RECEIVER: 'Receiver',                    // Maps to earpiece
  SPEAKER: 'Speaker',                      // Maps to speaker
  HEADPHONES: 'HeadphonesAndMicrophone',   // Maps to wired_headset
  BLUETOOTH_HFP: 'BluetoothHFP',          // Maps to bluetooth
  BLUETOOTH_A2DP: 'BluetoothA2DPOutput',
  AIRPLAY: 'AirPlay',
  USB_AUDIO: 'USBAudio',
  UNKNOWN: 'Unknown'
}
```

## Benefits

1. **Consistent API**: Same constants and event format across platforms
2. **Comprehensive Detection**: Captures all route changes regardless of cause  
3. **Easy Integration**: Simple event listener with standardized data
4. **Reactive & Proactive**: Both event-based monitoring and current state queries
5. **Developer Friendly**: JavaScript enum definitions for easy usage

## Testing

The implementation has been designed to capture:
- ✅ Physical headphone plug/unplug
- ✅ Bluetooth device connect/disconnect
- ✅ Programmatic route changes via `speakerOn()`/`speakerOff()`
- ✅ iOS CallKit route switching
- ✅ System-initiated route changes

---

*Implementation completed: March 2026*
*Supports: Android API 23+, iOS 10+*
