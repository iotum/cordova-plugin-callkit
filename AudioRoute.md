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
  EARPIECE: 'EARPIECE',         // Phone earpiece/receiver
  BLUETOOTH: 'BLUETOOTH',       // Bluetooth headset/device  
  SPEAKER: 'SPEAKER',           // Built-in speaker
  WIRED_HEADSET: 'WIRED_HEADSET', // Wired headphones/headset
  UNKNOWN: 'UNKNOWN'            // Other or unrecognized routes
}
```

### ✅ Change Type Classification
```javascript
CordovaCall.AudioRouteChangeType = {
  DEVICE_CHANGED: 'DEVICE_CHANGED',           // Physical device connect/disconnect
  PROGRAMMATIC_CHANGE: 'PROGRAMMATIC_CHANGE', // Via setAudioRoute() method
  ROUTE_CHANGED: 'ROUTE_CHANGED'              // General route change
}
```

## Usage

### Event Registration
```javascript
document.addEventListener('audioRouteChange', function(event) {
  const data = JSON.parse(event.data);
  
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
CordovaCall.speakerOn();   // -> route: "SPEAKER"
CordovaCall.speakerOff();  // -> route: "EARPIECE" (typically)
```

## Event Data Format

### Common Properties (Both Platforms)
```javascript
{
  "route": "SPEAKER",                    // Standardized route constant
  "changeType": "DEVICE_CHANGED",        // Type of change
  "message": "audioRouteChange event called successfully"
}
```

### Android-Specific Properties
```javascript
{
  "route": "BLUETOOTH",
  "changeType": "DEVICE_CHANGED", 
  "supportedRoutes": ["EARPIECE", "SPEAKER", "BLUETOOTH"],
  "isMuted": false,
  "message": "audioRouteChange event called successfully"
}
```

### iOS-Specific Properties
```javascript
{
  "route": "WIRED_HEADSET",
  "changeType": "ROUTE_CHANGED",
  "reason": 2,                           // Numeric reason code
  "reasonString": "NewDeviceAvailable",  // Human-readable reason
  "previousOutputType": "Receiver",      // iOS-specific previous route
  "currentOutputType": "HeadphonesAndMicrophone", // iOS-specific current route
  "message": "audioRouteChange event called successfully"
}
```

## Utility Functions

### Cross-Platform Event Parsing
```javascript
const parsedData = CordovaCall.parseAudioRouteChangeEvent(event.data);
console.log('Platform:', parsedData.platform); // 'android' or 'ios'
console.log('Output type:', parsedData.outputType);
```

### Route Conversion
```javascript
// Convert standardized route to iOS output type
const iosOutputType = CordovaCall.androidRouteToOutputType(CordovaCall.AudioRoute.SPEAKER);
// Returns: "Speaker"
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
  RECEIVER: 'Receiver',                    // Maps to EARPIECE
  SPEAKER: 'Speaker',                      // Maps to SPEAKER
  HEADPHONES: 'HeadphonesAndMicrophone',   // Maps to WIRED_HEADSET
  BLUETOOTH_HFP: 'BluetoothHFP',          // Maps to BLUETOOTH
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
4. **Cross-Platform Utilities**: Helper functions for platform differences
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
