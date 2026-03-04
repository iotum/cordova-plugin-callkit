# AudioRoute Implementation Summary

## Overview

This document summarizes the implementation of the `audioRouteChange` event in the Cordova CallKit plugin, providing comprehensive audio route detection across both Android and iOS platforms.

## Features Implemented

### ✅ Cross-Platform audioRouteChange Event
- **Android**: Uses `TelecomManager`/`CallAudioState` with modern `AudioManager.getDevices()` API (API 23+) for enhanced device detection
- **iOS**: Leverages `AVAudioSessionRouteChangeNotification` with Apple's system constants
- **Detection Scope**: Both physical changes (device connect/disconnect) and programmatic changes (via `setAudioRoute()`)
- **Concurrent Calls**: Properly handles multiple active calls with shared audio route monitoring

### ✅ Enhanced Device Detection
- **Modern Android API**: Uses `AudioManager.getDevices()` with `AudioDeviceInfo` for reliable detection (API 23+)
- **USB-C & USB-A Support**: Detects USB headsets that older methods miss
- **Wired Headset Types**: Comprehensive detection including `TYPE_WIRED_HEADSET`, `TYPE_WIRED_HEADPHONES`, `TYPE_USB_HEADSET`
- **Backward Compatibility**: Falls back to deprecated methods for Android < API 23
- **Multiple Call Support**: Audio route monitoring scales with active call count

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
  "changeType": "deviceChanged"         // Type of change
}
```

### Android-Specific Properties
```javascript
{
  "route": "bluetooth",
  "changeType": "deviceChanged", 
  "supportedRoutes": 7,                // Integer bitmask from CallAudioState.getSupportedRouteMask()
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
  "currentOutputType": "AVAudioSessionPortHeadphones" // iOS AVAudioSessionPortDescription.portType value
}
```

## Platform Implementation Details

### Android Implementation
- **File**: `src/android/CordovaCall.java`
- **Detection Method**: Hybrid approach using:
  - Modern `AudioManager.getDevices()` API with `AudioDeviceInfo` (API 23+)
  - `BroadcastReceiver` for physical device changes (headset plug, Bluetooth, USB)
  - `Connection.onCallAudioStateChanged()` for programmatic changes
  - Fallback to deprecated `isWiredHeadsetOn()` for older Android versions
- **USB Support**: Enhanced detection for USB-C headsets, USB-A adapters, and USB audio devices
- **Concurrent Calls**: Active call counting ensures monitoring starts/stops appropriately
- **Integration**: Works with `MyConnectionService.java` for complete coverage

### iOS Implementation  
- **File**: `src/ios/CordovaCall.m`
- **Detection Method**: `AVAudioSessionRouteChangeNotification`
- **System Constants**: Uses Apple's official `AVAudioSessionRouteChangeReason*` constants
- **Filtering**: Enhanced reason filtering to capture all relevant changes
- **Route Mapping**: Converts iOS-specific output types to standardized constants
- **Optimized Data**: Streamlined event data focusing on current route information

## Benefits

1. **Consistent API**: Same constants and event format across platforms
2. **Modern Android Support**: Uses latest APIs for reliable USB-C and USB headset detection  
3. **Comprehensive Detection**: Captures all route changes regardless of cause
4. **Multiple Call Aware**: Properly manages audio monitoring across concurrent calls
5. **Easy Integration**: Simple event listener with standardized data
6. **Reactive & Proactive**: Both event-based monitoring and current state queries
7. **Developer Friendly**: JavaScript enum definitions for easy usage
8. **Performance Optimized**: Efficient monitoring that scales with call activity

## Testing

The implementation has been designed to capture:
- ✅ Physical headphone plug/unplug (3.5mm and USB-C)
- ✅ USB headset and adapter detection (USB-A, USB-C)
- ✅ Bluetooth device connect/disconnect
- ✅ Programmatic route changes via `speakerOn()`/`speakerOff()`
- ✅ iOS CallKit route switching
- ✅ System-initiated route changes
- ✅ Multiple concurrent call scenarios
- ✅ Modern Android device compatibility (API 23+)

---

*Implementation updated: March 2026*
*Supports: Android API 23+ (with fallback to API 21+), iOS 10+*
*Enhanced: Modern Android API support, USB-C detection, concurrent call handling*
