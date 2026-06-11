#import "CordovaCall.h"
#import <Cordova/CDV.h>
#import <AVFoundation/AVFoundation.h>
#import "WebSocketAdvanced.h"
#import <SocketRocket/SocketRocket.h>
#import <WebRTC/RTCAudioSession.h>
#import <WebRTC/RTCAudioSessionConfiguration.h>

@implementation CordovaCall

@synthesize VoIPPushCallbackId, VoIPPushClassName, VoIPPushMethodName;

BOOL hasVideo = NO;
NSString* appName;
NSString* ringtone;
NSString* icon;
BOOL includeInRecents = NO;
NSMutableDictionary<NSString*, NSMutableArray*> *callbackIds;
NSDictionary* pendingCallFromRecents;
NSDictionary* pendingStartCallData;
BOOL monitorAudioRouteChange = NO;
BOOL enableDTMF = YES;
PKPushRegistry *_voipRegistry;

NSString* callBackUrl;
NSTimer *keepAlive;
BOOL keepAliveInBackground = NO;
double keepAliveInterval = 0.2;
NSMutableDictionary* webSockets;
UIBackgroundTaskIdentifier bgTask;

NSMutableArray* pendingCallResponses;
NSString* const PENDING_RESPONSE_ANSWER = @"pendingResponseAnswer";
NSString* const PENDING_RESPONSE_REJECT = @"pendingResponseReject";

// Saved audio session state captured before setupAudioSession; restored in teardownAudioSession.
AVAudioSessionCategory _savedAudioCategory;
AVAudioSessionMode _savedAudioMode;
AVAudioSessionCategoryOptions _savedAudioCategoryOptions = 0;
BOOL _audioSessionStateSaved = NO;

NSString* const KEY_VOIP_PUSH_TOKEN = @"PK_deviceToken";

- (void)pluginInitialize
{
    CXProviderConfiguration *providerConfiguration;
    appName = [[NSBundle mainBundle] objectForInfoDictionaryKey:@"CFBundleDisplayName"];
    providerConfiguration = [[CXProviderConfiguration alloc] initWithLocalizedName:appName];
    providerConfiguration.maximumCallGroups = 2; // Max calls allowed to be handled at once as a group, including held calls
    providerConfiguration.maximumCallsPerCallGroup = 5; // Max simultaneous active calls allowed
    NSMutableSet *handleTypes = [[NSMutableSet alloc] init];
    [handleTypes addObject:@(CXHandleTypePhoneNumber)];
    providerConfiguration.supportedHandleTypes = handleTypes;
    providerConfiguration.supportsVideo = hasVideo;
    if (@available(iOS 11.0, *)) {
        providerConfiguration.includesCallsInRecents = NO;
    }
    self.provider = [[CXProvider alloc] initWithConfiguration:providerConfiguration];
    [self.provider setDelegate:self queue:nil];
    self.callController = [[CXCallController alloc] init];
    self.activeCalls = [[NSMutableDictionary alloc] init];
    [[RTCAudioSession sharedInstance] addDelegate:self];
    //initialize callback dictionary
    callbackIds = [[NSMutableDictionary alloc]initWithCapacity:5];
    [callbackIds setObject:[NSMutableArray array] forKey:@"answer"];
    [callbackIds setObject:[NSMutableArray array] forKey:@"reject"];
    [callbackIds setObject:[NSMutableArray array] forKey:@"hangup"];
    [callbackIds setObject:[NSMutableArray array] forKey:@"sendCall"];
    [callbackIds setObject:[NSMutableArray array] forKey:@"receiveCall"];
    [callbackIds setObject:[NSMutableArray array] forKey:@"mute"];
    [callbackIds setObject:[NSMutableArray array] forKey:@"unmute"];
    [callbackIds setObject:[NSMutableArray array] forKey:@"speakerOn"];
    [callbackIds setObject:[NSMutableArray array] forKey:@"speakerOff"];
    [callbackIds setObject:[NSMutableArray array] forKey:@"DTMF"];
    [callbackIds setObject:[NSMutableArray array] forKey:@"hold"];
    [callbackIds setObject:[NSMutableArray array] forKey:@"unhold"];
    [callbackIds setObject:[NSMutableArray array] forKey:@"audioRouteChange"];

    // Add call response (answer or reject) to pending if event listeners are not added at the time of responding
    pendingCallResponses = [NSMutableArray new];

    //allows user to make call from recents
    [[NSNotificationCenter defaultCenter] addObserver:self selector:@selector(receiveCallFromRecents:) name:@"RecentsCallNotification" object:nil];
    //detect Audio Route Changes to make speakerOn and speakerOff event handlers
    [[NSNotificationCenter defaultCenter] addObserver:self selector:@selector(handleAudioRouteChange:) name:AVAudioSessionRouteChangeNotification object:nil];

    // Add a listener to keep the JS alive when it is in the background
    [[NSNotificationCenter defaultCenter] addObserver:self selector:@selector(_keepAliveInBackground) name:UIApplicationDidEnterBackgroundNotification object:nil];
    [[NSNotificationCenter defaultCenter] addObserver:self selector:@selector(stopKeepAliveInterval) name:UIApplicationWillEnterForegroundNotification object:nil];

    // Initialize PKPushRegistry
    //http://stackoverflow.com/questions/27245808/implement-pushkit-and-test-in-development-behavior/28562124#28562124
    dispatch_queue_t mainQueue = dispatch_get_main_queue();
    // Create a push registry object
    _voipRegistry = [[PKPushRegistry alloc] initWithQueue: mainQueue];
    // Set the registry's delegate to self
    [_voipRegistry setDelegate:(id<PKPushRegistryDelegate> _Nullable)self];
    // Set the push type to VoIP
    _voipRegistry.desiredPushTypes = [NSSet setWithObject:PKPushTypeVoIP];

    // Read VoIPPushToken from UserDefaults
    self.VoIPPushToken = [[NSUserDefaults standardUserDefaults] stringForKey:KEY_VOIP_PUSH_TOKEN];
    webSockets = [[NSMutableDictionary alloc] init];

    [[NSNotificationCenter defaultCenter] addObserver:self selector:@selector(handleRemotePushNotification:) name:@"CallkitHandleRemotePushNotification" object:nil];
}

// CallKit - Interface
- (void)updateProviderConfig
{
    CXProviderConfiguration *providerConfiguration;
    providerConfiguration = [[CXProviderConfiguration alloc] initWithLocalizedName:appName];
    providerConfiguration.maximumCallGroups = 2; // Max simultaneous active calls allowed
    providerConfiguration.maximumCallsPerCallGroup = 5; // Max calls allowed to be handled at once as a group, including held calls
    if(ringtone != nil) {
        providerConfiguration.ringtoneSound = ringtone;
    }
    if(icon != nil) {
        UIImage *iconImage = [UIImage imageNamed:icon];
        NSData *iconData = UIImagePNGRepresentation(iconImage);
        providerConfiguration.iconTemplateImageData = iconData;
    }
    NSMutableSet *handleTypes = [[NSMutableSet alloc] init];
    [handleTypes addObject:@(CXHandleTypePhoneNumber)];
    providerConfiguration.supportedHandleTypes = handleTypes;
    providerConfiguration.supportsVideo = hasVideo;
    if (@available(iOS 11.0, *)) {
        providerConfiguration.includesCallsInRecents = includeInRecents;
    }

    self.provider.configuration = providerConfiguration;
}

- (void)setupAudioSession
{
    @try {
        AVAudioSession *sessionInstance = [AVAudioSession sharedInstance];

        // Capture the host app's audio session state the first time we configure it for a call,
        // so teardownAudioSession can restore exactly what was there before.
        @synchronized(self) {
            if (!_audioSessionStateSaved) {
                _savedAudioCategory = sessionInstance.category;
                _savedAudioMode = sessionInstance.mode;
                _savedAudioCategoryOptions = sessionInstance.categoryOptions;
                _audioSessionStateSaved = YES;
            }
        }

        NSError *categoryError = nil;
        BOOL categoryConfigured = [sessionInstance setCategory:AVAudioSessionCategoryPlayAndRecord
                                                   withOptions: AVAudioSessionCategoryOptionAllowBluetooth
                                                              | AVAudioSessionCategoryOptionAllowAirPlay
                                                              | AVAudioSessionCategoryOptionAllowBluetoothA2DP
                                                         error:&categoryError];
        if (!categoryConfigured) {
            [self logMessage:[NSString stringWithFormat:@"Failed to set audio session category: %@", categoryError]];
        }

        NSError *modeError = nil;
        // AVAudioSessionModeVoiceChat enables iOS hardware AEC (via the Voice Processing I/O audio unit).
        // WebRTC software AEC/AGC/NS is disabled via audioSourceWithConstraints in PluginGetUserMedia
        // to prevent double-processing on top of the iOS hardware AEC.
        AVAudioSessionMode targetMode = hasVideo ? AVAudioSessionModeVideoChat : AVAudioSessionModeVoiceChat;
        BOOL modeConfigured = [sessionInstance setMode:targetMode error:&modeError];
        if (!modeConfigured) {
            [self logMessage:[NSString stringWithFormat:@"Failed to set audio session mode: %@", modeError]];
        }
        [self logMessage:[NSString stringWithFormat:@"setupAudioSession: mode set to %@", targetMode]];

        // Override the WebRTC audio session configuration so that when the native WebRTC layer
        // configures the audio session at peer connection time (around connectCall), it uses
        // our desired mode. cedeAudioSessionToCallKit=true stops iosrtc's own code from touching
        // the session, but RTCAudioSessionConfiguration.webRTCConfiguration is still applied by
        // libwebrtc internally — so we override it here to keep the mode consistent.
        RTCAudioSessionConfiguration *webRTCConfig = [RTCAudioSessionConfiguration webRTCConfiguration];
        webRTCConfig.category = AVAudioSessionCategoryPlayAndRecord;
        webRTCConfig.categoryOptions = AVAudioSessionCategoryOptionAllowBluetooth
                                     | AVAudioSessionCategoryOptionAllowAirPlay
                                     | AVAudioSessionCategoryOptionAllowBluetoothA2DP;
        webRTCConfig.mode = targetMode;  // AVAudioSessionMode is NSString* in ObjC, no .rawValue needed
        [RTCAudioSessionConfiguration setWebRTCConfiguration:webRTCConfig];
        [self logMessage:[NSString stringWithFormat:@"setupAudioSession: RTCAudioSessionConfiguration updated to mode=%@", targetMode]];
    }
    @catch (NSException *exception) {
        [self logMessage:@"Unknown error returned from setupAudioSession"];
    }
}

- (void)teardownAudioSession
{
    @try {
        AVAudioSession *sessionInstance = [AVAudioSession sharedInstance];

        // Restore whatever category/options/mode the host app had before the call began.
        AVAudioSessionCategory categoryToRestore = _audioSessionStateSaved ? _savedAudioCategory : AVAudioSessionCategoryPlayback;
        AVAudioSessionCategoryOptions optionsToRestore = _audioSessionStateSaved ? _savedAudioCategoryOptions : AVAudioSessionCategoryOptionMixWithOthers;
        AVAudioSessionMode modeToRestore = _audioSessionStateSaved ? _savedAudioMode : AVAudioSessionModeDefault;

        NSError *categoryError = nil;
        BOOL categoryConfigured = [sessionInstance setCategory:categoryToRestore
                                                   withOptions:optionsToRestore
                                                         error:&categoryError];
        if (!categoryConfigured) {
            [self logMessage:[NSString stringWithFormat:@"Failed to reset audio session category: %@", categoryError]];
        }

        NSError *modeError = nil;
        BOOL modeConfigured = [sessionInstance setMode:modeToRestore error:&modeError];
        if (!modeConfigured) {
            [self logMessage:[NSString stringWithFormat:@"Failed to reset audio session mode: %@", modeError]];
        }
        [self logMessage:[NSString stringWithFormat:@"teardownAudioSession: audio session restored to %@/%@ (options: %lu)", categoryToRestore, modeToRestore, (unsigned long)optionsToRestore]];

        // Clear the saved state so the next call captures a fresh snapshot.
        _audioSessionStateSaved = NO;
        _savedAudioCategory = nil;
        _savedAudioMode = nil;
        _savedAudioCategoryOptions = 0;
    }
    @catch (NSException *exception) {
        [self logMessage:@"Unknown error returned from teardownAudioSession"];
    }
}

- (void)setupAudioSession:(CDVInvokedUrlCommand*)command
{
    [self setupAudioSession];
    CDVPluginResult* pluginResult = [CDVPluginResult resultWithStatus:CDVCommandStatus_OK messageAsString:@"Audio session setup complete"];
    [self.commandDelegate sendPluginResult:pluginResult callbackId:command.callbackId];
}

- (void)setAppName:(CDVInvokedUrlCommand*)command
{
    CDVPluginResult* pluginResult = nil;
    NSString* proposedAppName = [command.arguments objectAtIndex:0];

    if (proposedAppName != nil && [proposedAppName length] > 0) {
        appName = proposedAppName;
        [self updateProviderConfig];
        pluginResult = [CDVPluginResult resultWithStatus:CDVCommandStatus_OK messageAsString:@"App Name Changed Successfully"];
    } else {
        pluginResult = [CDVPluginResult resultWithStatus:CDVCommandStatus_ERROR messageAsString:@"App Name Can't Be Empty"];
    }

    [self.commandDelegate sendPluginResult:pluginResult callbackId:command.callbackId];
}

- (void)setIcon:(CDVInvokedUrlCommand*)command
{
    CDVPluginResult* pluginResult = nil;
    NSString* proposedIconName = [command.arguments objectAtIndex:0];

    if (proposedIconName == nil || [proposedIconName length] == 0) {
        pluginResult = [CDVPluginResult resultWithStatus:CDVCommandStatus_ERROR messageAsString:@"Icon Name Can't Be Empty"];
    } else if([UIImage imageNamed:proposedIconName] == nil) {
        pluginResult = [CDVPluginResult resultWithStatus:CDVCommandStatus_ERROR messageAsString:@"This icon does not exist. Make sure to add it to your project the right way."];
    } else {
        icon = proposedIconName;
        [self updateProviderConfig];
        pluginResult = [CDVPluginResult resultWithStatus:CDVCommandStatus_OK messageAsString:@"Icon Changed Successfully"];
    }

    [self.commandDelegate sendPluginResult:pluginResult callbackId:command.callbackId];
}

- (void)setRingtone:(CDVInvokedUrlCommand*)command
{
    CDVPluginResult* pluginResult = nil;
    NSString* proposedRingtoneName = [command.arguments objectAtIndex:0];

    if (proposedRingtoneName == nil || [proposedRingtoneName length] == 0) {
        pluginResult = [CDVPluginResult resultWithStatus:CDVCommandStatus_ERROR messageAsString:@"Ringtone Name Can't Be Empty"];
    } else {
        ringtone = [NSString stringWithFormat: @"%@.caf", proposedRingtoneName];
        [self updateProviderConfig];
        pluginResult = [CDVPluginResult resultWithStatus:CDVCommandStatus_OK messageAsString:@"Ringtone Changed Successfully"];
    }

    [self.commandDelegate sendPluginResult:pluginResult callbackId:command.callbackId];
}

- (void)setIncludeInRecents:(CDVInvokedUrlCommand*)command
{
    CDVPluginResult* pluginResult = nil;
    includeInRecents = [[command.arguments objectAtIndex:0] boolValue];
    [self updateProviderConfig];
    pluginResult = [CDVPluginResult resultWithStatus:CDVCommandStatus_OK messageAsString:@"includeInRecents Changed Successfully"];
    [self.commandDelegate sendPluginResult:pluginResult callbackId:command.callbackId];
}

- (void)setDTMFState:(CDVInvokedUrlCommand*)command
{
    CDVPluginResult* pluginResult = nil;
    enableDTMF = [[command.arguments objectAtIndex:0] boolValue];
    pluginResult = [CDVPluginResult resultWithStatus:CDVCommandStatus_OK messageAsString:@"enableDTMF Changed Successfully"];
    [self.commandDelegate sendPluginResult:pluginResult callbackId:command.callbackId];
}

- (void)setAllowUnmute:(CDVInvokedUrlCommand*)command
{
    NSString *sessionId = [command.arguments objectAtIndex:0];
    BOOL value = [[command.arguments objectAtIndex:1] boolValue];
    CDVPluginResult *pluginResult = nil;
    if (self.activeCalls[sessionId]) {
        self.activeCalls[sessionId][@"allowUnmute"] = @(value);
        pluginResult = [CDVPluginResult resultWithStatus:CDVCommandStatus_OK messageAsString:@"allowUnmute Changed Successfully"];
    } else {
        pluginResult = [CDVPluginResult resultWithStatus:CDVCommandStatus_ERROR messageAsString:@"No active call for sessionId"];
    }
    [self.commandDelegate sendPluginResult:pluginResult callbackId:command.callbackId];
}

- (void)setVideo:(CDVInvokedUrlCommand*)command
{
    CDVPluginResult* pluginResult = nil;
    hasVideo = [[command.arguments objectAtIndex:0] boolValue];
    [self updateProviderConfig];
    pluginResult = [CDVPluginResult resultWithStatus:CDVCommandStatus_OK messageAsString:@"hasVideo Changed Successfully"];
    [self.commandDelegate sendPluginResult:pluginResult callbackId:command.callbackId];
}

- (void)receiveCall:(CDVInvokedUrlCommand*)command
{
    [self logMessage:@"receiveCall"];
    BOOL hasId = ![[command.arguments objectAtIndex:1] isEqual:[NSNull null]];
    NSString* callName = [command.arguments objectAtIndex:0];
    if (![callName isKindOfClass:[NSString class]] || [callName length] == 0) {
        callName = nil;
    }
    NSString* callId = hasId?[command.arguments objectAtIndex:1]:callName;
    if (callId == nil) {
        callId = @"Unknown";
    }
    NSString* sessionId = [command.arguments objectAtIndex:2];
    // We must always be provided a sessionId because we need to identify the call
    if (sessionId == nil) {
        [self.commandDelegate sendPluginResult:[CDVPluginResult resultWithStatus:CDVCommandStatus_ERROR messageAsString:@"sessionId cannot be nil"] callbackId:command.callbackId];
        return;
    }

    // If this was called from JS, we'll need to create a new activeCall entry
    NSMutableDictionary *call = self.activeCalls[sessionId];
    if (!call) {
        self.activeCalls[sessionId] = [self newActiveCallEntryWithUUID:[[NSUUID alloc] init]];
    }
    NSUUID *callUUID = self.activeCalls[sessionId][@"callUUID"];

    CXHandle *handle = [[CXHandle alloc] initWithType:CXHandleTypePhoneNumber value:callId];
    CXCallUpdate *callUpdate = [[CXCallUpdate alloc] init];
    callUpdate.remoteHandle = handle;
    callUpdate.hasVideo = hasVideo;
    callUpdate.localizedCallerName = callName;
    callUpdate.supportsGrouping = NO;
    callUpdate.supportsUngrouping = NO;
    callUpdate.supportsHolding = YES;
    callUpdate.supportsDTMF = enableDTMF;
    [self.provider reportNewIncomingCallWithUUID:callUUID update:callUpdate completion:^(NSError * _Nullable error) {
        if(error == nil) {
            // If a dismiss arrived while reportNewIncomingCallWithUUID was in-flight,
            // end the call immediately now that CallKit has registered it.
            // This prevents the dismiss being silently dropped during cold launch.
            if ([self.activeCalls[sessionId][@"pendingDismiss"] boolValue]) {
                [self logMessage:[NSString stringWithFormat:@"receiveCall completion: pendingDismiss set, ending call for sessionId: %@", sessionId]];
                [self.commandDelegate sendPluginResult:[CDVPluginResult resultWithStatus:CDVCommandStatus_OK messageAsString:@"Incoming call dismissed before answer"] callbackId:command.callbackId];
                [self _dismissRingingCall:sessionId];
            } else {
                [self.commandDelegate sendPluginResult:[CDVPluginResult resultWithStatus:CDVCommandStatus_OK messageAsString:@"Incoming call successful"] callbackId:command.callbackId];
            }
        } else {
            [self.commandDelegate sendPluginResult:[CDVPluginResult resultWithStatus:CDVCommandStatus_ERROR messageAsString:[error localizedDescription]] callbackId:command.callbackId];
            return;
        }
    }];
    for (id callbackId in callbackIds[@"receiveCall"]) {
        CDVPluginResult* pluginResult = nil;
        pluginResult = [CDVPluginResult resultWithStatus:CDVCommandStatus_OK messageAsString:@"receiveCall event called successfully"];
        [pluginResult setKeepCallbackAsBool:YES];
        [self.commandDelegate sendPluginResult:pluginResult callbackId:callbackId];
    }
}

- (void)sendCall:(CDVInvokedUrlCommand*)command
{
    [self logMessage:@"sendCall"];
    NSString* callName = [command.arguments objectAtIndex:0];
    if (![callName isKindOfClass:[NSString class]] || [callName length] == 0) {
        callName = nil;
    }
    BOOL hasId = ![[command.arguments objectAtIndex:1] isEqual:[NSNull null]];
    NSString* callId = hasId ? [command.arguments objectAtIndex:1] : callName;
    if (![callId isKindOfClass:[NSString class]] || [callId length] == 0) {
        callId = @"Unknown";
    }
    NSString* sessionId = [command.arguments objectAtIndex:2];
    NSUUID *callUUID = [[NSUUID alloc] init];
    self.activeCalls[sessionId] = [self newActiveCallEntryWithUUID:callUUID];

    CXHandle *handle = [[CXHandle alloc] initWithType:CXHandleTypePhoneNumber value:callId];
    CXStartCallAction *startCallAction = [[CXStartCallAction alloc] initWithCallUUID:callUUID handle:handle];
    startCallAction.contactIdentifier = callName;
    startCallAction.video = hasVideo;
    CXTransaction *transaction = [[CXTransaction alloc] initWithAction:startCallAction];
    [self.callController requestTransaction:transaction completion:^(NSError * _Nullable error) {
        if (error == nil) {
            self.activeCalls[sessionId][@"callbackMap"][startCallAction.UUID.UUIDString] = [@{ @"callbackId": command.callbackId, @"event": @"sendCall" } mutableCopy];
        } else {
            [self.commandDelegate sendPluginResult:[CDVPluginResult resultWithStatus:CDVCommandStatus_ERROR messageAsString:[error localizedDescription]] callbackId:command.callbackId];
        }
    }];
}

- (void)connectCall:(CDVInvokedUrlCommand*)command
{
    [self logMessage:@"connectCall"];
    CDVPluginResult* pluginResult = nil;
    NSString* sessionId = [command.arguments objectAtIndex:0];
    NSString* recentsSessionId = ([command.arguments count] > 1 && ![[command.arguments objectAtIndex:1] isEqual:[NSNull null]])
                                  ? [command.arguments objectAtIndex:1] : nil;

    // If recentsSessionId provided, remap the activeCalls entry to the real sessionId
    if (recentsSessionId && self.activeCalls[recentsSessionId]) {
        self.activeCalls[sessionId] = self.activeCalls[recentsSessionId];
        [self.activeCalls removeObjectForKey:recentsSessionId];
    }

    CXCall *call = [self callForSessionId:sessionId];

    if(call && !call.hasConnected) {
        [self.provider reportOutgoingCallWithUUID:call.UUID connectedAtDate:nil];
        pluginResult = [CDVPluginResult resultWithStatus:CDVCommandStatus_OK messageAsString:@"Call connected successfully"];
    } else {
        pluginResult = [CDVPluginResult resultWithStatus:CDVCommandStatus_OK messageAsString:@"No call exists for you to connect"];
    }

    [self.commandDelegate sendPluginResult:pluginResult callbackId:command.callbackId];
}

- (void)updateCallName:(CDVInvokedUrlCommand*)command
{
    [self logMessage:@"updateCallName"];
    NSString* sessionId = [command.arguments objectAtIndex:0];
    id callNameArg = [command.arguments objectAtIndex:1];
    NSString* callName = ([callNameArg isKindOfClass:[NSString class]] && [callNameArg length] > 0) ? callNameArg : nil;
    CDVPluginResult* pluginResult = nil;

    if (!callName) {
        pluginResult = [CDVPluginResult resultWithStatus:CDVCommandStatus_OK messageAsString:@"No callName provided, nothing to update"];
        [self.commandDelegate sendPluginResult:pluginResult callbackId:command.callbackId];
        return;
    }

    CXCall *call = [self callForSessionId:sessionId];
    if (call) {
        CXCallUpdate *update = [[CXCallUpdate alloc] init];
        update.localizedCallerName = callName;
        [self.provider reportCallWithUUID:call.UUID updated:update];
        pluginResult = [CDVPluginResult resultWithStatus:CDVCommandStatus_OK messageAsString:@"Call name updated successfully"];
    } else {
        pluginResult = [CDVPluginResult resultWithStatus:CDVCommandStatus_OK messageAsString:@"No call exists for the given sessionId"];
    }

    [self.commandDelegate sendPluginResult:pluginResult callbackId:command.callbackId];
}

- (void)endCall:(CDVInvokedUrlCommand*)command
{
    [self logMessage:@"endCall"];
    [self stopKeepAlive:nil];
    NSString* sessionId = [command.arguments objectAtIndex:0];
    CXCall *call = [self callForSessionId:sessionId];

    if(call) {
        CXEndCallAction *endCallAction = [[CXEndCallAction alloc] initWithCallUUID:call.UUID];
        CXTransaction *transaction = [[CXTransaction alloc] initWithAction:endCallAction];
        [self.callController requestTransaction:transaction completion:^(NSError * _Nullable error) {
            if (error == nil) {
                // Persist UUID→callbackId in the shared map so didDeactivateAudioSession can
                // resolve the JS promise once CallKit confirms the end.
                self.activeCalls[sessionId][@"callbackMap"][endCallAction.UUID.UUIDString] = [@{ @"callbackId": command.callbackId, @"event": @"endCall" } mutableCopy];
            } else {
                [self logMessage:[error localizedDescription]];
                NSDictionary *resultDict = @{ @"message": [error localizedDescription], @"sessionId": sessionId };
                CDVPluginResult *pluginResult = [CDVPluginResult resultWithStatus:CDVCommandStatus_ERROR messageAsDictionary:resultDict];
                [self.commandDelegate sendPluginResult:pluginResult callbackId:command.callbackId];
            }
        }];
    } else {
        NSDictionary *resultDict = @{ @"message": @"No call exists to end", @"sessionId": sessionId };
        CDVPluginResult* pluginResult = [CDVPluginResult resultWithStatus:CDVCommandStatus_OK messageAsDictionary:resultDict];
        [self.commandDelegate sendPluginResult:pluginResult callbackId:command.callbackId];
    }
}

- (void)registerEvent:(CDVInvokedUrlCommand*)command
{
    NSString* eventName = [command.arguments objectAtIndex:0];
    if(callbackIds[eventName] != nil) {
        [callbackIds[eventName] addObject:command.callbackId];
    }
    if(pendingCallFromRecents && [eventName isEqual:@"sendCall"]) {
        NSDictionary *callData = pendingCallFromRecents;
        CDVPluginResult* pluginResult = nil;
        pluginResult = [CDVPluginResult resultWithStatus:CDVCommandStatus_OK messageAsDictionary:callData];
        [pluginResult setKeepCallbackAsBool:YES];
        [self.commandDelegate sendPluginResult:pluginResult callbackId:command.callbackId];
    }

    // In case of registerEvent answer or reject called after responding to call, trigger cordova event for the appropriate answer
    if ([eventName isEqualToString:@"answer"]) {
        // Gets all of the pending answer call responses, actions on each one and then deletes them all from pendingCallResponses
        NSArray *answers = [pendingCallResponses filteredArrayUsingPredicate:[NSPredicate predicateWithFormat:@"type == %@", PENDING_RESPONSE_ANSWER]];
        for (NSDictionary *answer in answers) {
            [self triggerCordovaEventForCallResponse:@"answer" sessionId:answer[@"sessionId"]];
        }
        [pendingCallResponses removeObjectsInArray:answers];
    }
    if ([eventName isEqualToString:@"reject"]) {
        // Gets all of the pending reject call responses, actions on each one and then deletes them all from pendingCallResponses
        NSArray *rejects = [pendingCallResponses filteredArrayUsingPredicate:[NSPredicate predicateWithFormat:@"type == %@", PENDING_RESPONSE_REJECT]];
        for (NSDictionary *reject in rejects) {
            [self triggerCordovaEventForCallResponse:@"reject" sessionId:reject[@"sessionId"]];
        }
        [pendingCallResponses removeObjectsInArray:rejects];
    }
}

- (void)mute:(CDVInvokedUrlCommand*)command
{
    [self logMessage:@"facetalk initiated mute"];
    NSString* sessionId = [command.arguments objectAtIndex:0];
    CXCall *call = [self callForSessionId:sessionId];
    if (call) {
        CXSetMutedCallAction *muteAction = [[CXSetMutedCallAction alloc] initWithCallUUID:call.UUID muted:YES];
        CXTransaction *transaction = [[CXTransaction alloc] initWithAction:muteAction];
        [self logMessage:[NSString stringWithFormat:@"Programmatically Muting Call: sessionId=%@, actionUUID=%@", sessionId, muteAction.UUID.UUIDString]];
        // Pre-populate callbackMap before requestTransaction: performSetMutedCallAction fires
        // before the requestTransaction completion block, so the entry must already be present.
        self.activeCalls[sessionId][@"callbackMap"][muteAction.UUID.UUIDString] = [@{ @"callbackId": command.callbackId, @"event": @"mute" } mutableCopy];
        [self.callController requestTransaction:transaction completion:^(NSError * _Nullable error) {
            if (error != nil) {
                // Transaction was rejected before reaching performSetMutedCallAction — clean up.
                [self.activeCalls[sessionId][@"callbackMap"] removeObjectForKey:muteAction.UUID.UUIDString];
                [self logMessage:[NSString stringWithFormat:@"Error occurred muting Call: sessionId=%@, actionUUID=%@, error=%@", sessionId, muteAction.UUID.UUIDString, error.localizedDescription]];
                NSDictionary *resultDict = @{ @"message": @"An error occurred", @"sessionId": sessionId };
                CDVPluginResult *pluginResult = [CDVPluginResult resultWithStatus:CDVCommandStatus_ERROR messageAsDictionary:resultDict];
                [self.commandDelegate sendPluginResult:pluginResult callbackId:command.callbackId];
            }
        }];
    } else {
        NSDictionary *resultDict = @{ @"message": @"No active call to mute", @"sessionId": sessionId };
        CDVPluginResult *pluginResult = [CDVPluginResult resultWithStatus:CDVCommandStatus_OK messageAsDictionary:resultDict];
        [self.commandDelegate sendPluginResult:pluginResult callbackId:command.callbackId];
    }
}

- (void)unmute:(CDVInvokedUrlCommand*)command
{
    [self logMessage:@"facetalk initiated unmute"];
    NSString* sessionId = [command.arguments objectAtIndex:0];
    CXCall *call = [self callForSessionId:sessionId];
    if (call) {
        CXSetMutedCallAction *unmuteAction = [[CXSetMutedCallAction alloc] initWithCallUUID:call.UUID muted:NO];
        CXTransaction *transaction = [[CXTransaction alloc] initWithAction:unmuteAction];
        [self logMessage:[NSString stringWithFormat:@"Programmatically Unmuting Call: sessionId=%@, actionUUID=%@", sessionId, unmuteAction.UUID.UUIDString]];
        // Pre-populate callbackMap before requestTransaction: performSetMutedCallAction fires
        // before the requestTransaction completion block, so the entry must already be present.
        self.activeCalls[sessionId][@"callbackMap"][unmuteAction.UUID.UUIDString] = [@{ @"callbackId": command.callbackId, @"event": @"unmute" } mutableCopy];
        [self.callController requestTransaction:transaction completion:^(NSError * _Nullable error) {
            if (error != nil) {
                // Transaction was rejected before reaching performSetMutedCallAction — clean up.
                [self.activeCalls[sessionId][@"callbackMap"] removeObjectForKey:unmuteAction.UUID.UUIDString];
                [self logMessage:[NSString stringWithFormat:@"Error occurred unmuting Call: sessionId=%@, actionUUID=%@, error=%@", sessionId, unmuteAction.UUID.UUIDString, error.localizedDescription]];
                NSDictionary *resultDict = @{ @"message": @"An error occurred", @"sessionId": sessionId };
                CDVPluginResult *pluginResult = [CDVPluginResult resultWithStatus:CDVCommandStatus_ERROR messageAsDictionary:resultDict];
                [self.commandDelegate sendPluginResult:pluginResult callbackId:command.callbackId];
            }
        }];
    } else {
        NSDictionary *resultDict = @{ @"message": @"No active call to unmute", @"sessionId": sessionId };
        CDVPluginResult *pluginResult = [CDVPluginResult resultWithStatus:CDVCommandStatus_OK messageAsDictionary:resultDict];
        [self.commandDelegate sendPluginResult:pluginResult callbackId:command.callbackId];
    }
}

// Resolves the JS promise for a programmatic command (mute/unmute/hold/unhold/sendCall/endCall).
// Called from performSetMutedCallAction, performSetHeldCallAction, and didActivate/DeactivateAudioSession.
- (void)resolveCommandForSessionId:(NSString *)sessionId actionUUIDString:(NSString *)uuidStr
{
    NSMutableDictionary *entry = self.activeCalls[sessionId][@"callbackMap"][uuidStr];
    if (!entry) return;
    [self.activeCalls[sessionId][@"callbackMap"] removeObjectForKey:uuidStr];
    NSString *callbackId = entry[@"callbackId"];
    if (!callbackId) return;
    [self logMessage:[NSString stringWithFormat:@"resolved %@ promise for sessionId: %@", entry[@"event"], sessionId]];
    NSDictionary *resultDict = @{ @"message": [NSString stringWithFormat:@"%@ event called successfully", entry[@"event"]], @"sessionId": sessionId };
    CDVPluginResult *result = [CDVPluginResult resultWithStatus:CDVCommandStatus_OK messageAsDictionary:resultDict];
    [self.commandDelegate sendPluginResult:result callbackId:callbackId];
}

// Rejects any pending programmatic-command promises for a session with a "call ended" error.
// Called when a session is torn down so outstanding JS promises always settle.
- (void)rejectPendingCommandsForSessionId:(NSString *)sessionId
{
    NSMutableDictionary *callbackMap = self.activeCalls[sessionId][@"callbackMap"];
    for (NSString *uuidStr in [callbackMap allKeys]) {
        NSMutableDictionary *entry = callbackMap[uuidStr];
        if (![entry isKindOfClass:[NSMutableDictionary class]]) continue;
        NSString *cbId = entry[@"callbackId"];
        if (cbId) {
            [self logMessage:[NSString stringWithFormat:@"rejectPendingCommandsForSessionId: rejecting %@ promise for sessionId: %@", entry[@"event"], sessionId]];
            NSDictionary *resultDict = @{ @"message": @"call ended", @"sessionId": sessionId };
            CDVPluginResult *result = [CDVPluginResult resultWithStatus:CDVCommandStatus_ERROR messageAsDictionary:resultDict];
            [self.commandDelegate sendPluginResult:result callbackId:cbId];
        }
    }
    [self.activeCalls[sessionId] removeObjectForKey:@"callbackMap"];
}

- (void)speakerOn:(CDVInvokedUrlCommand*)command
{
    CDVPluginResult* pluginResult = nil;
    AVAudioSession *sessionInstance = [AVAudioSession sharedInstance];
    [self logMessage:@"Programmatically turning speaker on"];
    BOOL success = [sessionInstance overrideOutputAudioPort:AVAudioSessionPortOverrideSpeaker error:nil];
    if(success) {
      pluginResult = [CDVPluginResult resultWithStatus:CDVCommandStatus_OK messageAsString:@"Speakerphone is on"];
    } else {
      pluginResult = [CDVPluginResult resultWithStatus:CDVCommandStatus_ERROR messageAsString:@"An error occurred"];
    }
    [self.commandDelegate sendPluginResult:pluginResult callbackId:command.callbackId];
}

- (void)speakerOff:(CDVInvokedUrlCommand*)command
{
    CDVPluginResult* pluginResult = nil;
    AVAudioSession *sessionInstance = [AVAudioSession sharedInstance];
    [self logMessage:@"Programmatically turning speaker off"];
    BOOL success = [sessionInstance overrideOutputAudioPort:AVAudioSessionPortOverrideNone error:nil];
    if(success) {
      pluginResult = [CDVPluginResult resultWithStatus:CDVCommandStatus_OK messageAsString:@"Speakerphone is off"];
    } else {
      pluginResult = [CDVPluginResult resultWithStatus:CDVCommandStatus_ERROR messageAsString:@"An error occurred"];
    }
    [self.commandDelegate sendPluginResult:pluginResult callbackId:command.callbackId];
}

- (void)getAudioRoute:(CDVInvokedUrlCommand*)command
{
    CDVPluginResult* pluginResult = nil;
    @try {
        AVAudioSession *sessionInstance = [AVAudioSession sharedInstance];
        AVAudioSessionRouteDescription* currentRoute = [sessionInstance currentRoute];

        NSString* currentOutputType = @"Unknown";
        if([currentRoute.outputs count] > 0) {
            currentOutputType = [currentRoute.outputs[0] portType];
        }

        NSString* standardRoute = [self convertIOSOutputTypeToStandardRoute:currentOutputType];
        pluginResult = [CDVPluginResult resultWithStatus:CDVCommandStatus_OK messageAsString:standardRoute];
    }
    @catch (NSException *exception) {
        [self logMessage:[NSString stringWithFormat:@"Error getting audio route: %@", exception.reason]];
        pluginResult = [CDVPluginResult resultWithStatus:CDVCommandStatus_ERROR messageAsString:@"Failed to get current audio route"];
    }
    [self.commandDelegate sendPluginResult:pluginResult callbackId:command.callbackId];
}

- (void)callNumber:(CDVInvokedUrlCommand*)command
{
    CDVPluginResult* pluginResult = nil;
    NSString* phoneNumber = [command.arguments objectAtIndex:0];
    NSString* telNumber = [@"tel://" stringByAppendingString:phoneNumber];
    if (@available(iOS 10.0, *)) {
      [[UIApplication sharedApplication] openURL:[NSURL URLWithString:telNumber]
                                         options:nil
                                         completionHandler:^(BOOL success) {
                                           if(success) {
                                             CDVPluginResult* pluginResult = [CDVPluginResult resultWithStatus:CDVCommandStatus_OK messageAsString:@"Call Successful"];
                                             [self.commandDelegate sendPluginResult:pluginResult callbackId:command.callbackId];
                                           } else {
                                             CDVPluginResult* pluginResult = [CDVPluginResult resultWithStatus:CDVCommandStatus_ERROR messageAsString:@"Call Failed"];
                                             [self.commandDelegate sendPluginResult:pluginResult callbackId:command.callbackId];
                                           }
                                         }];
    } else {
      BOOL success = [[UIApplication sharedApplication] openURL:[NSURL URLWithString:telNumber]];
      if(success) {
        pluginResult = [CDVPluginResult resultWithStatus:CDVCommandStatus_OK messageAsString:@"Call Successful"];
      } else {
        pluginResult = [CDVPluginResult resultWithStatus:CDVCommandStatus_ERROR messageAsString:@"Call Failed"];
      }
      [self.commandDelegate sendPluginResult:pluginResult callbackId:command.callbackId];
    }

}

- (void)receiveCallFromRecents:(NSNotification *) notification
{
    NSString* callID = notification.object[@"callId"];
    NSString* callName = notification.object[@"callName"];
    NSUUID *callUUID = [[NSUUID alloc] init];
    NSString *recentsSessionId = [NSString stringWithFormat:@"recents:%@", callID];
    self.activeCalls[recentsSessionId] = [self newActiveCallEntryWithUUID:callUUID];
    CXHandle *handle = [[CXHandle alloc] initWithType:CXHandleTypePhoneNumber value:callID];
    CXStartCallAction *startCallAction = [[CXStartCallAction alloc] initWithCallUUID:callUUID handle:handle];
    startCallAction.video = [notification.object[@"isVideo"] boolValue]?YES:NO;
    startCallAction.contactIdentifier = callName;
    CXTransaction *transaction = [[CXTransaction alloc] initWithAction:startCallAction];
    [self.callController requestTransaction:transaction completion:^(NSError * _Nullable error) {
        if (error == nil) {
        } else {
            [self logMessage:[error localizedDescription]];
        }
    }];
}

- (void)handleAudioRouteChange:(NSNotification *) notification
{
    // AVAudioSessionRouteChangeNotification is delivered on an internal AVAudioSession background
    // thread. Dispatch to the main thread so that route-change handling is serialized with UI
    // callbacks (which also run on the main thread).
    if (!NSThread.isMainThread) {
        dispatch_async(dispatch_get_main_queue(), ^{
            [self handleAudioRouteChange:notification];
        });
        return;
    }

    if(monitorAudioRouteChange) {
        NSNumber* reasonValue = notification.userInfo[AVAudioSessionRouteChangeReasonKey];
        int reason = [reasonValue intValue];

        // Filter out truly unimportant route changes
        if (reason == AVAudioSessionRouteChangeReasonUnknown || reason == AVAudioSessionRouteChangeReasonWakeFromSleep) {
            return;
        }

        AVAudioSessionRouteDescription* previousRouteKey = notification.userInfo[AVAudioSessionRouteChangePreviousRouteKey];
        AVAudioSessionRouteDescription* currentRoute = [[AVAudioSession sharedInstance] currentRoute];

        NSString* currentOutputType = ([currentRoute.outputs count] > 0) ? [currentRoute.outputs[0] portType] : @"Unknown";
        NSString* prevOutputType = ([previousRouteKey.outputs count] > 0) ? [previousRouteKey.outputs[0] portType] : @"Unknown";
        NSString* reasonString = [self getRouteChangeReasonString:reason];

        // Always log every route change so we can diagnose reverts even when monitoring is off.
        [self logMessage:[NSString stringWithFormat:@"audioRouteChange: %@ → %@ (reason: %@, monitorActive=%d, category=%@, mode=%@)",
            prevOutputType, currentOutputType, reasonString,
            monitorAudioRouteChange,
            [AVAudioSession sharedInstance].category,
            [AVAudioSession sharedInstance].mode]];

        if([previousRouteKey.outputs count] > 0) {
            AVAudioSessionPortDescription *output = previousRouteKey.outputs[0];

            // Track speaker state based on user-initiated Override events (covers CallKit UI speaker button).
            // Only do this during an active call (monitorAudioRouteChange=YES) so that system-teardown
            // Override notifications after performEndCallAction don't incorrectly re-activate speaker state.
            // Emit legacy speakerOn/speakerOff events unconditionally so JS always learns of
            // route changes regardless of whether monitorAudioRouteChange is set. The monitoring
            // flag only gates speaker-tracking — not event delivery.
            if(![output.portType isEqual:AVAudioSessionPortBuiltInSpeaker] && [currentOutputType isEqual:AVAudioSessionPortBuiltInSpeaker]) {
                for (id callbackId in callbackIds[@"speakerOn"]) {
                    CDVPluginResult* pluginResult = nil;
                    pluginResult = [CDVPluginResult resultWithStatus:CDVCommandStatus_OK messageAsString:@"speakerOn event called successfully"];
                    [pluginResult setKeepCallbackAsBool:YES];
                    [self.commandDelegate sendPluginResult:pluginResult callbackId:callbackId];
                }
            } else if([output.portType isEqual:AVAudioSessionPortBuiltInSpeaker] && ![currentOutputType isEqual:AVAudioSessionPortBuiltInSpeaker]) {
                for (id callbackId in callbackIds[@"speakerOff"]) {
                    CDVPluginResult* pluginResult = nil;
                    pluginResult = [CDVPluginResult resultWithStatus:CDVCommandStatus_OK messageAsString:@"speakerOff event called successfully"];
                    [pluginResult setKeepCallbackAsBool:YES];
                    [self.commandDelegate sendPluginResult:pluginResult callbackId:callbackId];
                }
            }
        }

        // Always emit audioRouteChange to JS listeners — not gated by monitorAudioRouteChange.
        // performEndCallAction clears the flag immediately (to prevent teardown events from
        // triggering speaker-polling logic), but concurrent or overlapping calls may still be
        // active and JS needs to stay informed about route changes.
        NSDictionary *routeChangeData = @{
            @"reason": reasonValue,
            @"reasonString": reasonString,
            @"currentOutputType": currentOutputType,
            @"route": [self convertIOSOutputTypeToStandardRoute:currentOutputType],
            @"changeType": @"routeChanged"
        };

        [self logMessage:[NSString stringWithFormat:@"Audio route changed to: %@ (reason: %@)", currentOutputType, reasonString]];

        for (id callbackId in callbackIds[@"audioRouteChange"]) {
            CDVPluginResult* pluginResult = nil;
            pluginResult = [CDVPluginResult resultWithStatus:CDVCommandStatus_OK messageAsDictionary:routeChangeData];
            [pluginResult setKeepCallbackAsBool:YES];
            [self.commandDelegate sendPluginResult:pluginResult callbackId:callbackId];
        }
    }
}

- (NSString*)convertIOSOutputTypeToStandardRoute:(NSString*)iosOutputType
{
    if ([iosOutputType isEqualToString:AVAudioSessionPortBuiltInReceiver]) {
        return @"earpiece";
    } else if ([iosOutputType isEqualToString:AVAudioSessionPortBuiltInSpeaker]) {
        return @"speaker";
    } else if ([iosOutputType isEqualToString:AVAudioSessionPortBluetoothHFP] || 
               [iosOutputType isEqualToString:AVAudioSessionPortBluetoothA2DP] ||
               [iosOutputType isEqualToString:AVAudioSessionPortBluetoothLE]) {
        return @"bluetooth";
    } else if ([iosOutputType isEqualToString:AVAudioSessionPortHeadphones] || 
               [iosOutputType isEqualToString:AVAudioSessionPortHeadsetMic]) {
        return @"wired_headset";
    } else {
        return @"unknown";
    }
}

- (NSString*)getRouteChangeReasonString:(int)reason
{
    switch(reason) {
        case AVAudioSessionRouteChangeReasonUnknown: return @"Unknown";
        case AVAudioSessionRouteChangeReasonNewDeviceAvailable: return @"NewDeviceAvailable";
        case AVAudioSessionRouteChangeReasonOldDeviceUnavailable: return @"OldDeviceUnavailable"; 
        case AVAudioSessionRouteChangeReasonCategoryChange: return @"CategoryChange";
        case AVAudioSessionRouteChangeReasonOverride: return @"Override"; // This fires when overrideOutputAudioPort is called
        case AVAudioSessionRouteChangeReasonWakeFromSleep: return @"WakeFromSleep";
        case AVAudioSessionRouteChangeReasonNoSuitableRouteForCategory: return @"NoSuitableRouteForCategory";
        case AVAudioSessionRouteChangeReasonRouteConfigurationChange: return @"RouteConfigurationChange";
        default: return [NSString stringWithFormat:@"Reason%d", reason];
    }
}

- (void)hold:(CDVInvokedUrlCommand*)command
{
    [self logMessage:@"facetalk initiated hold"];
    NSString* sessionId = [command.arguments objectAtIndex:0];
    CXCall *call = [self callForSessionId:sessionId];
    if (call) {
        CXSetHeldCallAction *holdAction = [[CXSetHeldCallAction alloc] initWithCallUUID:call.UUID onHold:YES];
        CXTransaction *transaction = [[CXTransaction alloc] initWithAction:holdAction];
        [self logMessage:[NSString stringWithFormat:@"Programmatically Holding Call: %@", sessionId]];
        [self.callController requestTransaction:transaction completion:^(NSError * _Nullable error) {
            if (error == nil) {
                self.activeCalls[sessionId][@"callbackMap"][holdAction.UUID.UUIDString] = [@{ @"callbackId": command.callbackId, @"event": @"hold" } mutableCopy];
            } else {
                [self logMessage:@"Error occurred holding Call"];
                NSDictionary *resultDict = @{ @"message": @"hold event error", @"sessionId": sessionId };
                CDVPluginResult *pluginResult = [CDVPluginResult resultWithStatus:CDVCommandStatus_ERROR messageAsDictionary:resultDict];
                [self.commandDelegate sendPluginResult:pluginResult callbackId:command.callbackId];
            }
        }];
    } else {
        NSDictionary *resultDict = @{ @"message": @"no active call to hold", @"sessionId": sessionId };
        CDVPluginResult *pluginResult = [CDVPluginResult resultWithStatus:CDVCommandStatus_OK messageAsDictionary:resultDict];
        [self.commandDelegate sendPluginResult:pluginResult callbackId:command.callbackId];
    }
}

- (void)unhold:(CDVInvokedUrlCommand*)command
{
    [self logMessage:@"facetalk initiated unhold"];
    NSString* sessionId = [command.arguments objectAtIndex:0];
    CXCall *call = [self callForSessionId:sessionId];
    if (call) {
        CXSetHeldCallAction *unholdAction = [[CXSetHeldCallAction alloc] initWithCallUUID:call.UUID onHold:NO];
        CXTransaction *transaction = [[CXTransaction alloc] initWithAction:unholdAction];
        [self logMessage:[NSString stringWithFormat:@"Programmatically Unholding Call: %@", sessionId]];
        [self.callController requestTransaction:transaction completion:^(NSError * _Nullable error) {
            if (error == nil) {
                self.activeCalls[sessionId][@"callbackMap"][unholdAction.UUID.UUIDString] = [@{ @"callbackId": command.callbackId, @"event": @"unhold" } mutableCopy];
            } else {
                [self logMessage:@"Error occurred unholding Call"];
                NSDictionary *resultDict = @{ @"message": @"unhold event error", @"sessionId": sessionId };
                CDVPluginResult *pluginResult = [CDVPluginResult resultWithStatus:CDVCommandStatus_ERROR messageAsDictionary:resultDict];
                [self.commandDelegate sendPluginResult:pluginResult callbackId:command.callbackId];
            }
        }];
    } else {
        NSDictionary *resultDict = @{ @"message": @"no active call to unhold", @"sessionId": sessionId };
        CDVPluginResult *pluginResult = [CDVPluginResult resultWithStatus:CDVCommandStatus_OK messageAsDictionary:resultDict];
        [self.commandDelegate sendPluginResult:pluginResult callbackId:command.callbackId];
    }
}

// CallKit - Provider
- (void)providerDidReset:(CXProvider *)provider
{
    [self logMessage:@"providerDidReset"];
}

- (void)provider:(CXProvider *)provider performStartCallAction:(CXStartCallAction *)action
{
    [self logMessage:@"performStartCallAction"];
    [self setupAudioSession];
    CXCallUpdate *callUpdate = [[CXCallUpdate alloc] init];
    callUpdate.remoteHandle = action.handle;
    callUpdate.hasVideo = hasVideo;
    callUpdate.localizedCallerName = action.contactIdentifier;
    callUpdate.supportsGrouping = NO;
    callUpdate.supportsUngrouping = NO;
    callUpdate.supportsHolding = YES;
    callUpdate.supportsDTMF = enableDTMF;

    [self.provider reportCallWithUUID:action.callUUID updated:callUpdate];
    [action fulfill];
    NSString *recentsSessionId = [NSString stringWithFormat:@"recents:%@", action.handle.value];
    BOOL isRecentsCall = self.activeCalls[recentsSessionId] != nil;
    // Store the sendCall payload; it will be emitted in didActivateAudioSession via pendingActivateAudioSessionEmits.
    pendingStartCallData = @{@"callName":action.contactIdentifier ?: action.handle.value ?: @"", @"callId": action.handle.value ?: @"", @"isVideo": action.video?@YES:@NO, @"message": @"sendCall event called successfully", @"recentsSessionId": isRecentsCall ? recentsSessionId : [NSNull null]};
    NSString *sessionId = [self sessionIdForUUID:action.callUUID];
    if (sessionId) {
        [self.activeCalls[sessionId][@"pendingActivateAudioSessionEmits"] addObject:@{@"uuid": action.UUID.UUIDString, @"type": @"sendCall"}];
    }
}

- (void)provider:(CXProvider *)provider didActivateAudioSession:(AVAudioSession *)audioSession
{
    NSString *routeOnActivate = ([[AVAudioSession sharedInstance].currentRoute.outputs count] > 0)
        ? [AVAudioSession sharedInstance].currentRoute.outputs[0].portType : @"none";
    [self logMessage:[NSString stringWithFormat:@"didActivateAudioSession: route=%@, category=%@, mode=%@",
        routeOnActivate,
        [AVAudioSession sharedInstance].category,
        [AVAudioSession sharedInstance].mode]];
    [[RTCAudioSession sharedInstance] audioSessionDidActivate:audioSession];
    [RTCAudioSession sharedInstance].isAudioEnabled = YES;
    monitorAudioRouteChange = YES;
    // Pending activate emits (answer, unhold, sendCall) are deferred to
    // audioSessionDidStartPlayOrRecord: so JS is not notified until the WebRTC
    // audio unit has started and the ADM is fully initialized.
}

// RTCAudioSessionDelegate — fires on the WebRTC audio thread once the audio unit
// has started and the ADM is fully initialized.
- (void)audioSessionDidStartPlayOrRecord:(RTCAudioSession *)session
{
    // Callbacks must reach JS on the main thread.
    dispatch_async(dispatch_get_main_queue(), ^{
        [self logMessage:@"audioSessionDidStartPlayOrRecord: WebRTC ADM ready, emitting deferred activate callbacks"];

        // Process deferred callbacks for didActivateAudioSession (answer, unhold, sendCall).
        // UUID in callbackMap = programmatic → resolve promise; else = UI-initiated → emit event.
        NSArray *sessionIds = [self.activeCalls allKeys];
        for (NSString *sessionId in sessionIds) {
            NSMutableArray *pendingEmits = self.activeCalls[sessionId][@"pendingActivateAudioSessionEmits"];
            if (!pendingEmits || pendingEmits.count == 0) continue;
            NSArray *pendingItems = [pendingEmits copy];
            [pendingEmits removeAllObjects];
            for (NSDictionary *item in pendingItems) {
                NSString *uuidStr = item[@"uuid"];
                NSString *eventType = item[@"type"];
                if (self.activeCalls[sessionId][@"callbackMap"][uuidStr]) {
                    // Programmatic action: resolve the JS promise only.
                    [self resolveCommandForSessionId:sessionId actionUUIDString:uuidStr];
                } else if ([eventType isEqualToString:@"answer"]) {
                    [self logMessage:[NSString stringWithFormat:@"audioSessionDidStartPlayOrRecord: emitting deferred answer for sessionId=%@", sessionId]];
                    if ([callbackIds[@"answer"] count] == 0) {
                        [pendingCallResponses addObject:@{ @"type": PENDING_RESPONSE_ANSWER, @"sessionId": sessionId }];
                    } else {
                        [self triggerCordovaEventForCallResponse:@"answer" sessionId:sessionId];
                    }
                } else if ([eventType isEqualToString:@"sendCall"]) {
                    // UI-initiated (recents): emit to sendCall event listeners.
                    NSDictionary *callData = pendingStartCallData;
                    pendingStartCallData = nil;
                    if ([callbackIds[@"sendCall"] count] == 0) {
                        pendingCallFromRecents = callData;
                    } else {
                        for (id callbackId in callbackIds[@"sendCall"]) {
                            CDVPluginResult *pluginResult = [CDVPluginResult resultWithStatus:CDVCommandStatus_OK messageAsDictionary:callData];
                            [pluginResult setKeepCallbackAsBool:YES];
                            [self.commandDelegate sendPluginResult:pluginResult callbackId:callbackId];
                        }
                    }
                } else {
                    // UI-initiated action: emit to event listeners only.
                    NSDictionary *resultDict = @{ @"message": [NSString stringWithFormat:@"%@ event called successfully", eventType], @"sessionId": sessionId };
                    for (id callbackId in callbackIds[eventType]) {
                        CDVPluginResult *pluginResult = [CDVPluginResult resultWithStatus:CDVCommandStatus_OK messageAsDictionary:resultDict];
                        [pluginResult setKeepCallbackAsBool:YES];
                        [self.commandDelegate sendPluginResult:pluginResult callbackId:callbackId];
                    }
                }
            }
        }
    });
}

- (void)provider:(CXProvider *)provider didDeactivateAudioSession:(AVAudioSession *)audioSession
{
    NSString *routeOnDeactivate = ([[AVAudioSession sharedInstance].currentRoute.outputs count] > 0)
        ? [AVAudioSession sharedInstance].currentRoute.outputs[0].portType : @"none";
    [self logMessage:[NSString stringWithFormat:@"didDeactivateAudioSession: route=%@",
        routeOnDeactivate]];
    [RTCAudioSession sharedInstance].isAudioEnabled = NO;
    [[RTCAudioSession sharedInstance] audioSessionDidDeactivate:audioSession];
    monitorAudioRouteChange = NO;

    // Process deferred callbacks for didDeactivateAudioSession (hold, hangup/endCall).

    // Collect terminating sessions (those with a pending "hangup" emit) to handle separately.
    NSMutableArray *terminatingSessions = [NSMutableArray array];
    for (NSString *sessionId in self.activeCalls) {
        NSArray *pendingEmits = self.activeCalls[sessionId][@"pendingDeactivateAudioSessionEmits"];
        if (pendingEmits && [[pendingEmits valueForKey:@"type"] containsObject:@"hangup"]) {
            [terminatingSessions addObject:sessionId];
        }
    }

    // Non-terminating sessions: process hold emits.
    // UUID in callbackMap = programmatic → resolve promise; else = UI-initiated → emit event.
    for (NSString *sessionId in self.activeCalls) {
        if ([terminatingSessions containsObject:sessionId]) continue;
        NSMutableArray *pendingEmits = self.activeCalls[sessionId][@"pendingDeactivateAudioSessionEmits"];
        if (!pendingEmits || pendingEmits.count == 0) continue;
        NSArray *pendingEmitsSnapshot = [pendingEmits copy];
        [pendingEmits removeAllObjects];
        for (NSDictionary *item in pendingEmitsSnapshot) {
            NSString *uuidStr = item[@"uuid"];
            NSString *eventType = item[@"type"];
            if (self.activeCalls[sessionId][@"callbackMap"][uuidStr]) {
                [self resolveCommandForSessionId:sessionId actionUUIDString:uuidStr];
            } else {
                NSDictionary *resultDict = @{ @"message": [NSString stringWithFormat:@"%@ event called successfully", eventType], @"sessionId": sessionId };
                for (id callbackId in callbackIds[eventType]) {
                    CDVPluginResult *pluginResult = [CDVPluginResult resultWithStatus:CDVCommandStatus_OK messageAsDictionary:resultDict];
                    [pluginResult setKeepCallbackAsBool:YES];
                    [self.commandDelegate sendPluginResult:pluginResult callbackId:callbackId];
                }
            }
        }
    }

    // Terminating sessions: emit hangup, resolve endCall promise if programmatic, then tear down.
    for (NSString *sessionId in terminatingSessions) {
        NSMutableArray *pendingEmits = self.activeCalls[sessionId][@"pendingDeactivateAudioSessionEmits"];
        NSArray *pendingEmitsSnapshot = [pendingEmits copy];
        [pendingEmits removeAllObjects];
        for (NSDictionary *item in pendingEmitsSnapshot) {
            NSString *uuidStr = item[@"uuid"];
            NSString *eventType = item[@"type"];
            if ([eventType isEqualToString:@"hangup"]) {
                // Always emit hangup to listeners, and additionally resolve the endCall JS promise if programmatic.
                for (id callbackId in callbackIds[@"hangup"]) {
                    NSDictionary *resultDict = @{ @"message": @"hangup event called successfully", @"sessionId": sessionId };
                    CDVPluginResult *pluginResult = [CDVPluginResult resultWithStatus:CDVCommandStatus_OK messageAsDictionary:resultDict];
                    [pluginResult setKeepCallbackAsBool:YES];
                    [self.commandDelegate sendPluginResult:pluginResult callbackId:callbackId];
                }
                if (self.activeCalls[sessionId][@"callbackMap"][uuidStr]) {
                    [self resolveCommandForSessionId:sessionId actionUUIDString:uuidStr];
                }
            } else {
                if (self.activeCalls[sessionId][@"callbackMap"][uuidStr]) {
                    [self resolveCommandForSessionId:sessionId actionUUIDString:uuidStr];
                } else {
                    NSDictionary *resultDict = @{ @"message": [NSString stringWithFormat:@"%@ event called successfully", eventType], @"sessionId": sessionId };
                    for (id callbackId in callbackIds[eventType]) {
                        CDVPluginResult *pluginResult = [CDVPluginResult resultWithStatus:CDVCommandStatus_OK messageAsDictionary:resultDict];
                        [pluginResult setKeepCallbackAsBool:YES];
                        [self.commandDelegate sendPluginResult:pluginResult callbackId:callbackId];
                    }
                }
            }
        }
        [self rejectPendingCommandsForSessionId:sessionId];
        [self.activeCalls removeObjectForKey:sessionId];
    }

    // Once all calls have ended, reset the audio session to a mixing-friendly state
    // so subsequent media playback behaves normally and getAudioRoute reflects reality.
    if (self.callController.callObserver.calls.count == 0) {
        [self teardownAudioSession];
    }
}

- (void)provider:(CXProvider *)provider performAnswerCallAction:(CXAnswerCallAction *)action
{
    [self logMessage:@"performAnswerCallAction"];
    [self setupAudioSession];
    [action fulfill];

    NSString *sessionId = [self sessionIdForUUID:action.callUUID];
    // Defer the answer callback until didActivateAudioSession so that JsSIP's gUM
    // runs only after the audio session is fully active and owned by CallKit.
    [self.activeCalls[sessionId][@"pendingActivateAudioSessionEmits"] addObject:@{@"uuid": action.UUID.UUIDString, @"type": @"answer"}];

    UIApplication *app = [UIApplication sharedApplication];
    bgTask = [app beginBackgroundTaskWithExpirationHandler:^{
        // Have iOS kill the background task after 30s, we don't want this to run
        [self _endBackgroundTask];
    }];

    dispatch_after(dispatch_time(DISPATCH_TIME_NOW, (int64_t)(29 * NSEC_PER_SEC)), dispatch_get_global_queue(DISPATCH_QUEUE_PRIORITY_DEFAULT, 0), ^{
        [self logMessage:@"29 seconds elapsed, ending background task"];
        [self _endBackgroundTask];
    });
}

- (void)provider:(CXProvider *)provider performEndCallAction:(CXEndCallAction *)action
{
    [self logMessage:@"performEndCallAction"];
    [self stopKeepAlive:nil];
    NSString *sessionId = [self sessionIdForUUID:action.callUUID];
    CXCall *call = [self callForSessionId:sessionId];
    if(call) {
        if(call.hasConnected) {
            // Defer the hangup event and endCall promise resolution to didDeactivateAudioSession
            // so that audio is fully torn down before JS is notified.
            [self.activeCalls[sessionId][@"pendingDeactivateAudioSessionEmits"] addObject:@{@"uuid": action.UUID.UUIDString, @"type": @"hangup"}];
        } else {
            if ([callbackIds[@"reject"] count] == 0) {
                // callbackId for event not registered, add to pending to trigger on registration
                NSDictionary *pendingResponse = @{
                    @"type": PENDING_RESPONSE_REJECT,
                    @"sessionId": sessionId
                };
                [pendingCallResponses addObject:pendingResponse];
            } else {
                [self triggerCordovaEventForCallResponse:@"reject" sessionId:sessionId];
            }
            // Resolve the programmatic endCall promise immediately — didDeactivateAudioSession
            // will never fire for a call that was never connected.
            if (self.activeCalls[sessionId][@"callbackMap"][action.UUID.UUIDString]) {
                [self resolveCommandForSessionId:sessionId actionUUIDString:action.UUID.UUIDString];
            }
            [self rejectPendingCommandsForSessionId:sessionId];
            [self.activeCalls removeObjectForKey:sessionId]; // clear out the call once it's ended
        }
    }
    monitorAudioRouteChange = NO;
    [action fulfill];
}

- (void)triggerCordovaEventForCallResponse:(NSString*) response sessionId:(NSString*)sessionId {
    if ([@[@"answer", @"reject"] containsObject:response]) {
        for (id callbackId in callbackIds[response]) {
            CDVPluginResult* pluginResult = [CDVPluginResult resultWithStatus:CDVCommandStatus_OK messageAsString:self.activeCalls[sessionId][@"callData"]];
            [pluginResult setKeepCallbackAsBool:YES];
            [self.commandDelegate sendPluginResult:pluginResult callbackId:callbackId];
        }
    }
}

- (void)provider:(CXProvider *)provider performSetMutedCallAction:(CXSetMutedCallAction *)action
{
    BOOL isMuted = action.muted;
    NSString *sessionId = [self sessionIdForUUID:action.callUUID];
    if (!sessionId) {
        [self logMessage:[NSString stringWithFormat:@"performSetMutedCallAction: no sessionId found for callUUID=%@, actionUUID=%@, ignoring %@ event", action.callUUID.UUIDString, action.UUID.UUIDString, isMuted ? @"mute" : @"unmute"]];
        [action fulfill];
        return;
    }
    [self logMessage:[NSString stringWithFormat:@"CallKit performSetMutedCallAction received %@ event, sessionId=%@, actionUUID=%@", isMuted ? @"mute" : @"unmute", sessionId, action.UUID.UUIDString]];

    if (self.activeCalls[sessionId][@"callbackMap"][action.UUID.UUIDString]) {
        [action fulfill];

        // Programmatic mute/unmute: resolve the JS promise only.
        [self resolveCommandForSessionId:sessionId actionUUIDString:action.UUID.UUIDString];
        return;
    }

    // UI-initiated mute/unmute: emit to event listeners only.
    BOOL allowUnmute = [self.activeCalls[sessionId][@"allowUnmute"] boolValue];
    if (!isMuted && !allowUnmute) {
        // If this is an unmute request and allowUnmute is NO for this session, fail the action.
        [action fail];

        [self logMessage:@"performSetMutedCallAction: allowUnmute is NO, reject unmute action"];
        return;
    } else {
        [action fulfill];

        for (id callbackId in callbackIds[isMuted ? @"mute" : @"unmute"]) {
            [self logMessage:[NSString stringWithFormat:@"Sending %@ event to JS", isMuted ? @"mute" : @"unmute"]];
            NSDictionary *resultDict = @{ @"message": [NSString stringWithFormat:@"%@ event called successfully", isMuted ? @"mute" : @"unmute"], @"sessionId": sessionId };
            CDVPluginResult *pluginResult = [CDVPluginResult resultWithStatus:CDVCommandStatus_OK messageAsDictionary:resultDict];
            [pluginResult setKeepCallbackAsBool:YES];
            [self.commandDelegate sendPluginResult:pluginResult callbackId:callbackId];
        }
    }
}

- (void)provider:(CXProvider *)provider performPlayDTMFCallAction:(CXPlayDTMFCallAction *)action
{
    [self logMessage:@"DTMF Event"];
    NSString *digits = action.digits;
    [action fulfill];
    for (id callbackId in callbackIds[@"DTMF"]) {
        CDVPluginResult* pluginResult = nil;
        pluginResult = [CDVPluginResult resultWithStatus:CDVCommandStatus_OK messageAsString:digits];
        [pluginResult setKeepCallbackAsBool:YES];
        [self.commandDelegate sendPluginResult:pluginResult callbackId:callbackId];
    }
}

- (void)provider:(CXProvider *)provider performSetHeldCallAction:(CXSetHeldCallAction *)action
{
    NSString *sessionId = [self sessionIdForUUID:action.callUUID];
    CXCall *call = [self callForUUID:action.callUUID];
    BOOL isOnHold = action.onHold;
    [self logMessage:[NSString stringWithFormat:@"Callkit performSetHeldCallAction received %@ event, callkit says: %@, sessionId: %@", isOnHold ? @"hold" : @"unhold", [call isOnHold] ? @"on hold" : @"not on hold", sessionId]];
    if (!sessionId) {
        [self logMessage:[NSString stringWithFormat:@"performSetHeldCallAction: no sessionId found for callUUID %@, ignoring %@ event", action.callUUID.UUIDString, isOnHold ? @"hold" : @"unhold"]];
        [action fulfill];
        return;
    }
    NSString *pendingEmitKey = isOnHold ? @"pendingDeactivateAudioSessionEmits" : @"pendingActivateAudioSessionEmits";
    [self.activeCalls[sessionId][pendingEmitKey] addObject:@{@"uuid": action.UUID.UUIDString, @"type": isOnHold ? @"hold" : @"unhold"}];
    [action fulfill];
}

- (void) dismissRingingCall:(CDVInvokedUrlCommand*)command
{
    [self logMessage:@"dismissRingingCall"];

    NSString *sessionId = [command.arguments objectAtIndex:0];
    BOOL didDismiss = [self _dismissRingingCall:sessionId];
    CDVPluginResult* pluginResult = nil;
    if (didDismiss) {
        pluginResult = [CDVPluginResult resultWithStatus:CDVCommandStatus_OK
                                          messageAsString:@"dismissRingingCall event called successfully"];
    } else {
        pluginResult = [CDVPluginResult resultWithStatus:CDVCommandStatus_OK
                                          messageAsString:@"No ringing call to dismiss"];
    }

    [self.commandDelegate sendPluginResult:pluginResult callbackId:command.callbackId];
}

// Internal method that can be called from within the plugin
- (BOOL)_dismissRingingCall:(NSString *)sessionId {
    [self logMessage:@"_dismissRingingCall"];

    CXCall *call = [self callForSessionId:sessionId];
    if (call && !call.hasConnected) {
        [self.provider reportCallWithUUID:call.UUID endedAtDate:nil reason:CXCallEndedReasonRemoteEnded];
        [self rejectPendingCommandsForSessionId:sessionId];
        [self.activeCalls removeObjectForKey:sessionId];
        return YES;
    } else if (self.activeCalls[sessionId] && !call) {
        // The activeCalls entry exists but CallKit hasn't registered it yet
        // (reportNewIncomingCallWithUUID completion hasn't fired).
        // Flag it so the completion block ends the call immediately once registered.
        [self logMessage:[NSString stringWithFormat:@"_dismissRingingCall: call not yet registered with CallKit, setting pendingDismiss for sessionId: %@", sessionId]];
        self.activeCalls[sessionId][@"pendingDismiss"] = @YES;
        return YES;
    }
    return NO;
}

// Reports a dummy incoming call with a random UUID and immediately ends it.
// Must be called when a VoIP push is received but the payload is malformed,
// because iOS 13+ will terminate the app if no call is reported.
- (void)_reportAndEndDummyCall {
    [self logMessage:@"_reportAndEndDummyCall: reporting dummy call for malformed VoIP push"];
    NSUUID *dummyUUID = [[NSUUID alloc] init];
    CXHandle *handle = [[CXHandle alloc] initWithType:CXHandleTypePhoneNumber value:@"Unknown"];
    CXCallUpdate *callUpdate = [[CXCallUpdate alloc] init];
    callUpdate.remoteHandle = handle;
    callUpdate.localizedCallerName = nil;
    [self.provider reportNewIncomingCallWithUUID:dummyUUID update:callUpdate completion:^(NSError * _Nullable error) {
        if (error != nil) {
            [self logMessage:[NSString stringWithFormat:@"_reportAndEndDummyCall: failed to report dummy incoming call: %@", error.localizedDescription]];
            return;
        }
        [self.provider reportCallWithUUID:dummyUUID endedAtDate:nil reason:CXCallEndedReasonFailed];
    }];
}

// Creates a new activeCalls entry with all required keys pre-initialized.
- (NSMutableDictionary *)newActiveCallEntryWithUUID:(NSUUID *)callUUID {
    return [@{
        @"callUUID": callUUID,
        @"callbackMap": [NSMutableDictionary dictionary],
        @"pendingActivateAudioSessionEmits": [NSMutableArray array],
        @"pendingDeactivateAudioSessionEmits": [NSMutableArray array],
        @"pendingDismiss": @NO,
        @"allowUnmute": @YES
    } mutableCopy];
}

// Returns the Callkit CXCall instance for a CallUUID
- (CXCall *)callForUUID:(NSUUID *)callUUID {
    if (!callUUID) return nil;
    NSArray<CXCall *> *calls = self.callController.callObserver.calls;
    for (CXCall *call in calls) {
        if ([call.UUID isEqual:callUUID]) {
            return call;
        }
    }
    return nil;
}

// Returns the Callkit CXCall instance for a sessionId
- (CXCall *)callForSessionId:(NSString *)sessionId {
    if (!sessionId) return nil;

    NSMutableDictionary *call = self.activeCalls[sessionId];
    if (!call) return nil;

    NSUUID *callUUID = call[@"callUUID"];
    if (!callUUID) return nil;

    return [self callForUUID:callUUID];
}

// Maps the callkit internal callUUID with the facetalk sessionId
// Needed because any actions from callkit UI returns only callkit internal callUUID
- (nullable NSString *)sessionIdForUUID:(NSUUID *)callUUID {
    if (!callUUID) return nil;

    for (NSString *sessionId in self.activeCalls) {
        if ([self.activeCalls[sessionId][@"callUUID"] isEqual:callUUID]) {
            return sessionId;
        }
    }
    return nil;
}

- (void) log:(CDVInvokedUrlCommand*)command
{
    NSString* message = [command.arguments objectAtIndex:0];
    if (message != nil && [message length] > 0) {
        [self logMessage:message];
    }
}

-(void) _keepWKWebViewActive:(NSTimer*) timer {
    if ([self.webView isKindOfClass:[WKWebView class]]) {
        [self logMessage:@"keepingAlive"];
        WKWebView *wkWebView = (WKWebView *)self.webView;
        [wkWebView evaluateJavaScript:@"1+1" completionHandler:nil];
    }
}

// Manually call to keep the JS alive
- (void) keepAlive:(CDVInvokedUrlCommand*)command
{
    [self logMessage:@"keepAlive"];
    [self startKeepAliveInterval];
}

// Manually call to stop keeping JS alive
- (void) stopKeepAlive:(CDVInvokedUrlCommand*)command
{
    [self logMessage:@"stopKeepAlive"];
    [self stopKeepAliveInterval];
}

// Keeps the JS alive with default interval
- (void) startKeepAliveInterval
{
    [self startKeepAliveInterval:0.2];
}

// Keeps the JS alive with a defined interval
- (void) startKeepAliveInterval:(double)interval;
{
    // Invalidate any existing timer
    [keepAlive invalidate];
    keepAlive = nil;

    // defaults to 200ms
    if (interval <= 0) {
        interval = 0.2;
    }

    [self _keepWKWebViewActive:nil];
    keepAlive = [NSTimer scheduledTimerWithTimeInterval:interval
                                     target:self
                                     selector:@selector(_keepWKWebViewActive:)
                                     userInfo:nil
                                     repeats:YES];

    // Every 29 seconds check whether CallKit still has active calls.
    // If there are none, stop the keep-alive so it doesn't run indefinitely in the background.
    dispatch_after(dispatch_time(DISPATCH_TIME_NOW, (int64_t)(29 * NSEC_PER_SEC)), dispatch_get_main_queue(), ^{
        if (self.callController.callObserver.calls.count == 0) {
            [self logMessage:@"startKeepAliveInterval: no active calls after 29s, stopping keep-alive"];
            [self stopKeepAliveInterval];
        }
    });
}

- (void) stopKeepAliveInterval;
{
    if (keepAlive) {
        [self logMessage:@"stopKeepAliveInterval"];
        [keepAlive invalidate];
        keepAlive = nil;
        // End background task also
        [self _endBackgroundTask];
    }
}

// Sets if the app should keep the JS alive in the background
- (void) keepAliveInBackground:(CDVInvokedUrlCommand*)command
{
    [self logMessage:@"keepAliveInBackground"];
    keepAliveInBackground = YES;
    double argVal = [[command.arguments objectAtIndex:0] doubleValue];
    if (argVal > 0) {
        keepAliveInterval = argVal;
    }
}

// Method that gets called if the app is put into the background, will keep JS alive if set
- (void) _keepAliveInBackground;
{
    [self logMessage:@"_keepAliveInBackground"];
    if (keepAliveInBackground) {
        [self startKeepAliveInterval:keepAliveInterval];
    }
}

- (void) stopKeepAliveInBackground:(CDVInvokedUrlCommand*)command
{
    [self logMessage:@"stopKeepAliveInBackground"];
    keepAliveInBackground = NO;
    [self stopKeepAliveInterval];
}

- (void)_endBackgroundTask;
{
  if (bgTask != UIBackgroundTaskInvalid) {
    [self logMessage:@"Ending Background Task"];
    UIApplication *app = [UIApplication sharedApplication];
    [app endBackgroundTask:bgTask];
    bgTask = UIBackgroundTaskInvalid;
  }
  // Stop keepAlive just in case we don't call it from JS
  [self stopKeepAlive:nil];
}

- (void)wsConnect:(CDVInvokedUrlCommand*)command;
{
    NSDictionary* wsOptions = [command argumentAtIndex:0];
    WebSocketAdvanced* ws = [[WebSocketAdvanced alloc] initWithOptions:wsOptions
                                                       commandDelegate:self.commandDelegate
                                                       callbackId:command.callbackId];
    [webSockets setObject:ws forKey:ws.webSocketId];
}

- (void)wsAddListeners:(CDVInvokedUrlCommand*)command;
{
    NSString* webSocketId = [command argumentAtIndex:0];
    BOOL flushRecvBuffer = [command argumentAtIndex:1];
    WebSocketAdvanced* ws = [webSockets valueForKey:webSocketId];
    if (ws != nil) {
        [ws wsAddListeners:command.callbackId flushRecvBuffer:flushRecvBuffer];
    }
}

- (void)wsSend:(CDVInvokedUrlCommand*)command;
{
    NSString* webSocketId = [command argumentAtIndex:0];
    NSString* message = [command argumentAtIndex:1];
    WebSocketAdvanced* ws = [webSockets valueForKey:webSocketId];
    if (ws != nil) {
        [ws wsSendMessage:message];
    }
}

- (void)wsClose:(CDVInvokedUrlCommand*)command;
{
    NSString* webSocketId = [command argumentAtIndex:0];
    NSNumber* code = [command argumentAtIndex:1];
    NSString* reason = [command argumentAtIndex:2];
    WebSocketAdvanced* ws = [webSockets valueForKey:webSocketId];
    if (ws != nil) {
        [ws wsClose:code.integerValue reason:reason];
    }
}

- (void)dealloc;
{
    [self _closeAllSockets];
    [[RTCAudioSession sharedInstance] removeDelegate:self];
}

- (void)onReset;
{
    [super onReset];
}

- (void)_closeAllSockets;
{
    for(id wsId in webSockets) {
        WebSocketAdvanced* ws = [webSockets objectForKey:wsId];
        [ws wsClose];
    }
    [webSockets removeAllObjects];
}

// PushKit
- (void)init:(CDVInvokedUrlCommand*)command
{
    self.VoIPPushCallbackId = command.callbackId;
    [self logMessage:[NSString stringWithFormat:@"callbackId: %@", self.VoIPPushCallbackId]];

    [self sendTokenPluginResult];
}

- (void)sendTokenPluginResult {
    if (!self.VoIPPushCallbackId || !self.VoIPPushToken) {
        return;
    }

    NSMutableDictionary* results = [NSMutableDictionary dictionaryWithCapacity:2];
    [results setObject:self.VoIPPushToken forKey:@"deviceToken"];
    [results setObject:@"true" forKey:@"registration"];

    CDVPluginResult* pluginResult = [CDVPluginResult resultWithStatus:CDVCommandStatus_OK messageAsDictionary:results];
    [pluginResult setKeepCallbackAsBool:YES];
    [self.commandDelegate sendPluginResult:pluginResult callbackId:self.VoIPPushCallbackId];
}

#pragma mark PushKit Delegate Methods
- (void)pushRegistry:(PKPushRegistry *)registry didUpdatePushCredentials:(PKPushCredentials *)credentials forType:(PKPushType)type{
    if([credentials.token length] == 0) {
        [self logMessage:@"No device token!"];
        return;
    }

    //http://stackoverflow.com/a/9372848/534755
    [self logMessage:[NSString stringWithFormat:@"Device token: %@", credentials.token]];
    const unsigned *tokenBytes = [credentials.token bytes];
    self.VoIPPushToken = [NSString stringWithFormat:@"%08x%08x%08x%08x%08x%08x%08x%08x",
                         ntohl(tokenBytes[0]), ntohl(tokenBytes[1]), ntohl(tokenBytes[2]),
                         ntohl(tokenBytes[3]), ntohl(tokenBytes[4]), ntohl(tokenBytes[5]),
                         ntohl(tokenBytes[6]), ntohl(tokenBytes[7])];

    // Store VoIPPushToken in UserDefaults
    [[NSUserDefaults standardUserDefaults] setObject:self.VoIPPushToken forKey:KEY_VOIP_PUSH_TOKEN];

    [self sendTokenPluginResult];
}
- (void)pushRegistry:(PKPushRegistry *)registry didReceiveIncomingPushWithPayload:(PKPushPayload *)payload forType:(PKPushType)type withCompletionHandler:(void (^)(void))completion
{
    [self logMessage:[NSString stringWithFormat:@"didReceiveIncomingPush: %@", payload]];
    // apsDict and apsMessage seems to be unused
    id apsValue = payload.dictionaryPayload[@"aps"];
    NSDictionary *apsDict = [apsValue isKindOfClass:[NSDictionary class]] ? apsValue : @{};
    id alertValue = apsDict[@"alert"];
    NSString *apsMessage = [alertValue isKindOfClass:[NSString class]] ? alertValue : @"";

    NSDictionary *data = payload.dictionaryPayload[@"data"];
    [self logMessage:[NSString stringWithFormat:@"received data: %@", data]];

    // guard against empty, nil, or non-dictionary data payload
    if (!data || ![data isKindOfClass:[NSDictionary class]] || data.count == 0) {
        [self logMessage:@"didReceiveIncomingPush: data is empty, discarding as dummy call"];
        [self _reportAndEndDummyCall];
        completion();
        return;
    }

    NSString *payloadString = data[@"payload"];
    if (![payloadString isKindOfClass:[NSString class]] || payloadString.length == 0) {
        [self logMessage:@"didReceiveIncomingPush: data has no payload key, discarding as dummy call"];
        [self _reportAndEndDummyCall];
        completion();
        return;
    }

    NSMutableDictionary* results = [NSMutableDictionary dictionaryWithCapacity:2];
    [results setObject:apsMessage forKey:@"function"];
    [results setObject:@"" forKey:@"extra"];

    NSError *error = nil;
    NSData *payloadJsonData = [payloadString dataUsingEncoding:NSUTF8StringEncoding];
    NSDictionary *payloadObj = [NSJSONSerialization JSONObjectWithData:payloadJsonData options:0 error:&error];
    if (error || ![payloadObj isKindOfClass:[NSDictionary class]]) {
        [self logMessage:[NSString stringWithFormat:@"Error parsing payload JSON: %@", error]];
        [self _reportAndEndDummyCall];
        completion();
        return;
    }
    // session_id param is the parsed call_uuid for NS PBX calls, it's session_id = callId + ftag, where call_uuid = callId;ftag;ttag
    id sessionIdValue = [payloadObj valueForKey:@"session_id"] ?: [payloadObj valueForKey:@"call_uuid"];
    NSString *sessionId = [sessionIdValue isKindOfClass:[NSString class]] ? sessionIdValue : nil;
    if (!sessionId || sessionId.length == 0) {
        [self logMessage:@"didReceiveIncomingPush: no session_id or call_uuid (deprecated) in payload, discarding as dummy call"];
        [self _reportAndEndDummyCall];
        completion();
        return;
    }
    id fromValue = [payloadObj valueForKey:@"from"];
    NSString *from = [fromValue isKindOfClass:[NSString class]] && [fromValue length] > 0 ? fromValue : nil;
    id cidValue = [payloadObj valueForKey:@"cid"];
    NSString *callId = nil;
    if ([cidValue isKindOfClass:[NSString class]]) {
        NSString *sanitizedCallId = [[cidValue componentsSeparatedByCharactersInSet:[[NSCharacterSet decimalDigitCharacterSet] invertedSet]] componentsJoinedByString:@""];
        callId = sanitizedCallId.length > 0 ? sanitizedCallId : nil;
    }
    NSArray* args = @[from ?: [NSNull null], callId ?: [NSNull null], sessionId];
    CDVInvokedUrlCommand* newCommand = [[CDVInvokedUrlCommand alloc] initWithArguments:args callbackId:@"" className:self.VoIPPushClassName methodName:self.VoIPPushMethodName];

    // Store URL and Call Id so they can be used for call Answer/Reject
    callBackUrl = [payloadObj valueForKey:@"callback_url"];
    NSString *Type = [payloadObj valueForKey:@"type"];
    hasVideo = ![Type isEqualToString:@"incoming_phone_call"];
    self.activeCalls[sessionId] = [self newActiveCallEntryWithUUID:[[NSUUID alloc] init]];
    self.activeCalls[sessionId][@"callData"] = [[NSString alloc] initWithData:payloadJsonData encoding:NSUTF8StringEncoding];

    [self receiveCall:newCommand];

    @try {
        NSError * err;
        NSData * jsonData = [NSJSONSerialization dataWithJSONObject:payloadObj options:0 error:&err];
        NSString * dataString = [[NSString alloc] initWithData:jsonData encoding:NSUTF8StringEncoding];
        [results setObject:dataString forKey:@"extra"];
        CDVPluginResult* pluginResult = [CDVPluginResult resultWithStatus:CDVCommandStatus_OK messageAsDictionary:results];
        [pluginResult setKeepCallback:[NSNumber numberWithBool:YES]];
        [self.commandDelegate sendPluginResult:pluginResult callbackId:self.VoIPPushCallbackId];
        completion();
    }
    @catch (NSException *exception) {
        [self logMessage:[NSString stringWithFormat:@"error: %@", exception.reason]];
        CDVPluginResult* pluginResult = [CDVPluginResult resultWithStatus:CDVCommandStatus_ERROR messageAsString:exception.reason];
        [pluginResult setKeepCallback:[NSNumber numberWithBool:YES]];
        [self.commandDelegate sendPluginResult:pluginResult callbackId:self.VoIPPushCallbackId];
        completion();
    }
}

// Handles all remote push notifications sent from forked FCM, only action on a dismiss notification
- (void)handleRemotePushNotification:(NSNotification *)notification {
    NSDictionary *userInfo = notification.object;
    [self logMessage:[NSString stringWithFormat:@"Received remote notification: %@", userInfo]];

    // Checks if payload param is in notification
    NSString *payloadString = userInfo[@"payload"];
    if (![payloadString isKindOfClass:[NSString class]] || payloadString.length == 0) {
        [self logMessage:@"No valid payload string found in notification"];
        return;
    }

    NSData *payloadData = [payloadString dataUsingEncoding:NSUTF8StringEncoding];
    NSError *error = nil;

    // Parse JSON payload
    NSDictionary *payloadDict = [NSJSONSerialization JSONObjectWithData:payloadData options:0 error:&error];
    if (error || ![payloadDict isKindOfClass:[NSDictionary class]]) {
        [self logMessage:[NSString stringWithFormat:@"Error parsing payload JSON: %@", error]];
        return;
    }

    // Do something if dismiss key is present and true
    if (payloadDict[@"dismiss"] == nil || payloadDict[@"dismiss"] == [NSNull null] || ![payloadDict[@"dismiss"] boolValue]) {
        [self logMessage:@"Dismiss key not found in payload or is false"];
        return;
    } else {
        [self _dismissRingingCall:payloadDict[@"session_id"]];
    }
}

- (void)logMessage:(NSString *)message
{
    NSLog(@"[CordovaCall]: %@", message);
}
@end
