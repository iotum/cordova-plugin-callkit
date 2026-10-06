#import <Cordova/Cordova.h>
#import <Foundation/Foundation.h>
#import <UIKit/UIKit.h>
#import <PushKit/PushKit.h>
#import <CallKit/CallKit.h>

@protocol RTCAudioSessionDelegate;

@interface CordovaCall : CDVPlugin <PKPushRegistryDelegate, CXProviderDelegate, RTCAudioSessionDelegate>

// PushKit
@property (nonatomic, copy) NSString *VoIPPushCallbackId;
@property (nonatomic, copy) NSString *VoIPPushClassName;
@property (nonatomic, copy) NSString *VoIPPushMethodName;
@property (nonatomic, copy) NSString *VoIPPushToken;

- (void)init:(CDVInvokedUrlCommand*)command;

// CallKit
@property (nonatomic, strong) CXProvider *provider;
@property (nonatomic, strong) CXCallController *callController;
@property (nonatomic, strong) NSMutableDictionary<NSString *, NSMutableDictionary *> *activeCalls; // Stores each active call, mapped by call ID

- (void)updateProviderConfig;
- (void)setupAudioSession;
- (void)teardownAudioSession;
- (void)setupAudioSession:(CDVInvokedUrlCommand*)command;

- (void)setAppName:(CDVInvokedUrlCommand*)command;
- (void)setIcon:(CDVInvokedUrlCommand*)command;
- (void)setRingtone:(CDVInvokedUrlCommand*)command;
- (void)setIncludeInRecents:(CDVInvokedUrlCommand*)command;
- (void)setDTMFState:(CDVInvokedUrlCommand*)command;
- (void)setAllowUnmute:(CDVInvokedUrlCommand*)command;
- (void)setVideo:(CDVInvokedUrlCommand*)command;

- (void)receiveCall:(CDVInvokedUrlCommand*)command;
- (void)sendCall:(CDVInvokedUrlCommand*)command;
- (void)connectCall:(CDVInvokedUrlCommand*)command;
- (void)updateCallName:(CDVInvokedUrlCommand*)command;
- (void)endCall:(CDVInvokedUrlCommand*)command;
- (void)registerEvent:(CDVInvokedUrlCommand*)command;
- (void)mute:(CDVInvokedUrlCommand*)command;
- (void)unmute:(CDVInvokedUrlCommand*)command;
- (void)speakerOn:(CDVInvokedUrlCommand*)command;
- (void)speakerOff:(CDVInvokedUrlCommand*)command;
- (void)getAudioRoute:(CDVInvokedUrlCommand*)command;
- (void)callNumber:(CDVInvokedUrlCommand*)command;
- (void)hold:(CDVInvokedUrlCommand*)command;
- (void)unhold:(CDVInvokedUrlCommand*)command;
- (void)group:(CDVInvokedUrlCommand*)command;
- (void)dismissRingingCall:(CDVInvokedUrlCommand*)command;
- (void)keepAlive:(CDVInvokedUrlCommand*)command;
- (void)stopKeepAlive:(CDVInvokedUrlCommand*)command;
- (void)keepAliveInBackground:(CDVInvokedUrlCommand*)command;
- (void)stopKeepAliveInBackground:(CDVInvokedUrlCommand*)command;
- (void)wsConnect:(CDVInvokedUrlCommand*)command;
- (void)wsAddListeners:(CDVInvokedUrlCommand*)command;
- (void)wsSend:(CDVInvokedUrlCommand*)command;
- (void)wsClose:(CDVInvokedUrlCommand*)command;
- (void)log:(CDVInvokedUrlCommand*)command;

- (void)receiveCallFromRecents:(NSNotification *) notification;
- (void)handleAudioRouteChange:(NSNotification *) notification;

@end
