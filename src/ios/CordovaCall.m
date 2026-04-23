#import "CordovaCall.h"
#import <Cordova/CDV.h>
#import <AVFoundation/AVFoundation.h>
#import "WebSocketAdvanced.h"
#import <SocketRocket/SocketRocket.h>
#import <WebRTC/RTCAudioSession.h>

@implementation CordovaCall

@synthesize VoIPPushCallbackId, VoIPPushClassName, VoIPPushMethodName;

BOOL hasVideo = NO;
NSString* appName;
NSString* ringtone;
NSString* icon;
BOOL includeInRecents = NO;
NSMutableDictionary<NSString*, NSMutableArray*> *callbackIds;
NSDictionary* pendingCallFromRecents;
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
        NSError *categoryError = nil;
        BOOL categoryConfigured = [sessionInstance setCategory:AVAudioSessionCategoryPlayAndRecord
                                                   withOptions:AVAudioSessionCategoryOptionMixWithOthers
                                                              | AVAudioSessionCategoryOptionAllowBluetooth
                                                              | AVAudioSessionCategoryOptionAllowAirPlay
                                                              | AVAudioSessionCategoryOptionAllowBluetoothA2DP
                                                         error:&categoryError];
        if (!categoryConfigured) {
            [self logMessage:[NSString stringWithFormat:@"Failed to set audio session category: %@", categoryError]];
        }

        NSError *modeError = nil;
        BOOL modeConfigured = [sessionInstance setMode:AVAudioSessionModeVoiceChat error:&modeError];
        if (!modeConfigured) {
            [self logMessage:[NSString stringWithFormat:@"Failed to set audio session mode: %@", modeError]];
        }
    }
    @catch (NSException *exception) {
        [self logMessage:@"Unknown error returned from setupAudioSession"];
    }
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
    NSString* callId = hasId?[command.arguments objectAtIndex:1]:callName;
    NSString* sessionId = [command.arguments objectAtIndex:2];
    // We must always be provided a sessionId because we need to identify the call
    if (sessionId == nil) {
        [self.commandDelegate sendPluginResult:[CDVPluginResult resultWithStatus:CDVCommandStatus_ERROR messageAsString:@"sessionId cannot be nil"] callbackId:command.callbackId];
        return;
    }

    // If this was called from JS, we'll need to create a new activeCall entry
    NSMutableDictionary *call = self.activeCalls[sessionId];
    if (!call) {
        self.activeCalls[sessionId] = [@{
            @"callUUID": [[NSUUID alloc] init]
        } mutableCopy];
    }
    NSUUID *callUUID = self.activeCalls[sessionId][@"callUUID"];

    if (hasId) {
        [[NSUserDefaults standardUserDefaults] setObject:callName forKey:[command.arguments objectAtIndex:1]];
        [[NSUserDefaults standardUserDefaults] synchronize];
    }

    if (callName != nil && [callName length] > 0) {
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
                [self.commandDelegate sendPluginResult:[CDVPluginResult resultWithStatus:CDVCommandStatus_OK messageAsString:@"Incoming call successful"] callbackId:command.callbackId];
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
    } else {
        [self.commandDelegate sendPluginResult:[CDVPluginResult resultWithStatus:CDVCommandStatus_ERROR messageAsString:@"Caller id can't be empty"] callbackId:command.callbackId];
    }
}

- (void)sendCall:(CDVInvokedUrlCommand*)command
{
    [self logMessage:@"sendCall"];
    BOOL hasId = ![[command.arguments objectAtIndex:1] isEqual:[NSNull null]];
    NSString* callName = [command.arguments objectAtIndex:0];
    NSString* callId = hasId?[command.arguments objectAtIndex:1]:callName;
    NSString* sessionId = [command.arguments objectAtIndex:2];
    NSUUID *callUUID = [[NSUUID alloc] init];
    self.activeCalls[sessionId] = [@{
        @"callUUID": callUUID
    } mutableCopy];

    if (hasId) {
        [[NSUserDefaults standardUserDefaults] setObject:callName forKey:[command.arguments objectAtIndex:1]];
        [[NSUserDefaults standardUserDefaults] synchronize];
    }

    if (callName != nil && [callName length] > 0) {
        CXHandle *handle = [[CXHandle alloc] initWithType:CXHandleTypePhoneNumber value:callId];
        CXStartCallAction *startCallAction = [[CXStartCallAction alloc] initWithCallUUID:callUUID handle:handle];
        startCallAction.contactIdentifier = callName;
        startCallAction.video = hasVideo;
        CXTransaction *transaction = [[CXTransaction alloc] initWithAction:startCallAction];
        [self.callController requestTransaction:transaction completion:^(NSError * _Nullable error) {
            if (error == nil) {
                [self.commandDelegate sendPluginResult:[CDVPluginResult resultWithStatus:CDVCommandStatus_OK messageAsString:@"Outgoing call successful"] callbackId:command.callbackId];
            } else {
                [self.commandDelegate sendPluginResult:[CDVPluginResult resultWithStatus:CDVCommandStatus_ERROR messageAsString:[error localizedDescription]] callbackId:command.callbackId];
            }
        }];
    } else {
        [self.commandDelegate sendPluginResult:[CDVPluginResult resultWithStatus:CDVCommandStatus_ERROR messageAsString:@"The caller id can't be empty"] callbackId:command.callbackId];
    }
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

- (void)endCall:(CDVInvokedUrlCommand*)command
{
    [self logMessage:@"endCall"];
    [self stopKeepAlive:nil];
    NSString* sessionId = [command.arguments objectAtIndex:0];
    CXCall *call = [self callForSessionId:sessionId];

    if(call) {
        // Store the callbackId so performEndCallAction (or didDeactivateAudioSession for
        // connected calls) can resolve the JS promise once CallKit confirms the end.
        self.activeCalls[sessionId][@"pendingEndCallCommandCallbackId"] = command.callbackId;
        CXEndCallAction *endCallAction = [[CXEndCallAction alloc] initWithCallUUID:call.UUID];
        CXTransaction *transaction = [[CXTransaction alloc] initWithAction:endCallAction];
        [self.callController requestTransaction:transaction completion:^(NSError * _Nullable error) {
            if (error != nil) {
                [self logMessage:[error localizedDescription]];
                // Transaction failed — reject the promise and clean up the stored callbackId.
                NSString *pendingCb = self.activeCalls[sessionId][@"pendingEndCallCommandCallbackId"];
                if (pendingCb) {
                    NSDictionary *resultDict = @{ @"message": [error localizedDescription], @"sessionId": sessionId };
                    CDVPluginResult *pluginResult = [CDVPluginResult resultWithStatus:CDVCommandStatus_ERROR messageAsDictionary:resultDict];
                    [self.commandDelegate sendPluginResult:pluginResult callbackId:pendingCb];
                    [self.activeCalls[sessionId] removeObjectForKey:@"pendingEndCallCommandCallbackId"];
                }
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
    // Reject if a programmatic mute/unmute is already in flight for this session.
    if (self.activeCalls[sessionId][@"pendingMuteActionUUID"]) {
        [self logMessage:@"mute: rejecting call — a mute/unmute action is already in flight"];
        NSDictionary *resultDict = @{ @"message": @"mute action already in flight", @"sessionId": sessionId };
        CDVPluginResult *pluginResult = [CDVPluginResult resultWithStatus:CDVCommandStatus_ERROR messageAsDictionary:resultDict];
        [self.commandDelegate sendPluginResult:pluginResult callbackId:command.callbackId];
        return;
    }
    CXCall *call = [self callForSessionId:sessionId];
    if (call) {
        CXSetMutedCallAction *muteAction = [[CXSetMutedCallAction alloc] initWithCallUUID:call.UUID muted:YES];
        CXTransaction *transaction = [[CXTransaction alloc] initWithAction:muteAction];
        [self logMessage:[NSString stringWithFormat:@"Programmatically Muting Call: %@", sessionId]];
        [self.callController requestTransaction:transaction completion:^(NSError * _Nullable error) {
            if (error == nil) {
                // Persist UUID→callbackId mapping for the lifetime of the call.
                if (!self.activeCalls[sessionId][@"muteActionCallbackMap"]) {
                    self.activeCalls[sessionId][@"muteActionCallbackMap"] = [NSMutableDictionary dictionary];
                }
                self.activeCalls[sessionId][@"muteActionCallbackMap"][muteAction.UUID.UUIDString] = command.callbackId;
                // Mark as in-flight so concurrent commands are rejected.
                self.activeCalls[sessionId][@"pendingMuteActionUUID"] = muteAction.UUID;
                self.activeCalls[sessionId][@"isMuted"] = @YES;
                // Defer promise resolution until performSetMutedCallAction confirms the action.
                // Fall back after a modest timeout in case the callback never arrives.
                dispatch_async(dispatch_get_main_queue(), ^{
                    if (!self.activeCalls[sessionId]) {
                        // Session was removed before the timer could be stored — nothing to do.
                        return;
                    }
                    NSTimer *timer = [NSTimer scheduledTimerWithTimeInterval:5.0 repeats:NO block:^(NSTimer *t) {
                        [self resolvePendingMuteCommandForSessionId:sessionId];
                    }];
                    self.activeCalls[sessionId][@"pendingMuteCommandTimer"] = timer;
                });
            } else {
                [self logMessage:@"Error occurred muting Call"];
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
    // Reject if a programmatic mute/unmute is already in flight for this session.
    if (self.activeCalls[sessionId][@"pendingMuteActionUUID"]) {
        [self logMessage:@"unmute: rejecting call — a mute/unmute action is already in flight"];
        NSDictionary *resultDict = @{ @"message": @"unmute action already in flight", @"sessionId": sessionId };
        CDVPluginResult *pluginResult = [CDVPluginResult resultWithStatus:CDVCommandStatus_ERROR messageAsDictionary:resultDict];
        [self.commandDelegate sendPluginResult:pluginResult callbackId:command.callbackId];
        return;
    }
    CXCall *call = [self callForSessionId:sessionId];
    if (call) {
        CXSetMutedCallAction *unmuteAction = [[CXSetMutedCallAction alloc] initWithCallUUID:call.UUID muted:NO];
        CXTransaction *transaction = [[CXTransaction alloc] initWithAction:unmuteAction];
        [self logMessage:[NSString stringWithFormat:@"Programmatically Unmuting Call: %@", sessionId]];
        [self.callController requestTransaction:transaction completion:^(NSError * _Nullable error) {
            if (error == nil) {
                // Persist UUID→callbackId mapping for the lifetime of the call.
                if (!self.activeCalls[sessionId][@"muteActionCallbackMap"]) {
                    self.activeCalls[sessionId][@"muteActionCallbackMap"] = [NSMutableDictionary dictionary];
                }
                self.activeCalls[sessionId][@"muteActionCallbackMap"][unmuteAction.UUID.UUIDString] = command.callbackId;
                // Mark as in-flight so concurrent commands are rejected.
                self.activeCalls[sessionId][@"pendingMuteActionUUID"] = unmuteAction.UUID;
                self.activeCalls[sessionId][@"isMuted"] = @NO;
                // Defer promise resolution until performSetMutedCallAction confirms the action.
                dispatch_async(dispatch_get_main_queue(), ^{
                    if (!self.activeCalls[sessionId]) {
                        return;
                    }
                    NSTimer *timer = [NSTimer scheduledTimerWithTimeInterval:5.0 repeats:NO block:^(NSTimer *t) {
                        [self resolvePendingMuteCommandForSessionId:sessionId];
                    }];
                    self.activeCalls[sessionId][@"pendingMuteCommandTimer"] = timer;
                });
            } else {
                [self logMessage:@"Error occurred unmuting Call"];
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

// Resolves the JS promise for the currently in-flight programmatic mute/unmute command.
// Looks up the callbackId from the persistent muteActionCallbackMap (kept until call ends).
// Called from performSetMutedCallAction (on action confirmation) and from the safety
// timeout timer so the JS promise always settles.
- (void)resolvePendingMuteCommandForSessionId:(NSString *)sessionId
{
    // Cancel the safety timer so it cannot fire again after we return.
    NSTimer *timer = self.activeCalls[sessionId][@"pendingMuteCommandTimer"];
    if (timer) {
        [timer invalidate];
        [self.activeCalls[sessionId] removeObjectForKey:@"pendingMuteCommandTimer"];
    }
    // Retrieve and clear the in-flight UUID (this unblocks new mute/unmute commands).
    NSUUID *uuid = self.activeCalls[sessionId][@"pendingMuteActionUUID"];
    if (!uuid) return;
    [self.activeCalls[sessionId] removeObjectForKey:@"pendingMuteActionUUID"];
    // Look up the callbackId from the persistent map; the map entry is kept until call end.
    NSString *callbackId = self.activeCalls[sessionId][@"muteActionCallbackMap"][uuid.UUIDString];
    if (!callbackId) return;
    BOOL isMuted = [self.activeCalls[sessionId][@"isMuted"] boolValue];
    NSDictionary *resultDict = @{ @"message": [NSString stringWithFormat:@"%@ event called successfully", isMuted ? @"mute" : @"unmute"], @"sessionId": sessionId };
    CDVPluginResult *result = [CDVPluginResult resultWithStatus:CDVCommandStatus_OK messageAsDictionary:resultDict];
    [self.commandDelegate sendPluginResult:result callbackId:callbackId];
}

// Cancels any outstanding safety-timeout timers and rejects any pending command
// callbackIds for a session (called when the call ends so JS promises always settle).
- (void)cancelPendingCommandTimersForSessionId:(NSString *)sessionId
{
    // Mute/unmute: cancel timer and reject any in-flight command
    NSTimer *muteTimer = self.activeCalls[sessionId][@"pendingMuteCommandTimer"];
    if (muteTimer) {
        [muteTimer invalidate];
        [self.activeCalls[sessionId] removeObjectForKey:@"pendingMuteCommandTimer"];
    }
    NSUUID *pendingMuteUUID = self.activeCalls[sessionId][@"pendingMuteActionUUID"];
    if (pendingMuteUUID) {
        NSString *muteCallbackId = self.activeCalls[sessionId][@"muteActionCallbackMap"][pendingMuteUUID.UUIDString];
        if (muteCallbackId) {
            NSDictionary *resultDict = @{ @"message": @"call ended", @"sessionId": sessionId };
            CDVPluginResult *result = [CDVPluginResult resultWithStatus:CDVCommandStatus_ERROR messageAsDictionary:resultDict];
            [self.commandDelegate sendPluginResult:result callbackId:muteCallbackId];
        }
    }
    // Clear all mute state for this session
    [self.activeCalls[sessionId] removeObjectForKey:@"pendingMuteActionUUID"];
    [self.activeCalls[sessionId] removeObjectForKey:@"muteActionCallbackMap"];
    // Hold
    NSTimer *holdTimer = self.activeCalls[sessionId][@"pendingHoldCommandTimer"];
    if (holdTimer) {
        [holdTimer invalidate];
        [self.activeCalls[sessionId] removeObjectForKey:@"pendingHoldCommandTimer"];
    }
    NSString *holdCallbackId = self.activeCalls[sessionId][@"pendingHoldCommandCallbackId"];
    if (holdCallbackId) {
        [self.activeCalls[sessionId] removeObjectForKey:@"pendingHoldCommandCallbackId"];
        NSDictionary *resultDict = @{ @"message": @"call ended", @"sessionId": sessionId };
        CDVPluginResult *result = [CDVPluginResult resultWithStatus:CDVCommandStatus_ERROR messageAsDictionary:resultDict];
        [self.commandDelegate sendPluginResult:result callbackId:holdCallbackId];
    }
    // Unhold
    NSTimer *unholdTimer = self.activeCalls[sessionId][@"pendingUnholdCommandTimer"];
    if (unholdTimer) {
        [unholdTimer invalidate];
        [self.activeCalls[sessionId] removeObjectForKey:@"pendingUnholdCommandTimer"];
    }
    NSString *unholdCallbackId = self.activeCalls[sessionId][@"pendingUnholdCommandCallbackId"];
    if (unholdCallbackId) {
        [self.activeCalls[sessionId] removeObjectForKey:@"pendingUnholdCommandCallbackId"];
        NSDictionary *resultDict = @{ @"message": @"call ended", @"sessionId": sessionId };
        CDVPluginResult *result = [CDVPluginResult resultWithStatus:CDVCommandStatus_ERROR messageAsDictionary:resultDict];
        [self.commandDelegate sendPluginResult:result callbackId:unholdCallbackId];
    }
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
    self.activeCalls[recentsSessionId] = [@{ @"callUUID": callUUID } mutableCopy];
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
    if(monitorAudioRouteChange) {
        NSNumber* reasonValue = notification.userInfo[AVAudioSessionRouteChangeReasonKey];
        int reason = [reasonValue intValue];

        // Filter out unimportant route changes
        if (reason == AVAudioSessionRouteChangeReasonUnknown || reason == AVAudioSessionRouteChangeReasonWakeFromSleep || reason == AVAudioSessionRouteChangeReasonRouteConfigurationChange) {
            return;
        }

        AVAudioSessionRouteDescription* previousRouteKey = notification.userInfo[AVAudioSessionRouteChangePreviousRouteKey];
        AVAudioSessionRouteDescription* currentRoute = [[AVAudioSession sharedInstance] currentRoute];

        // Get current output type
        NSString* currentOutputType = @"Unknown";
        NSString* reasonString = [self getRouteChangeReasonString:reason];

        if([currentRoute.outputs count] > 0) {
            currentOutputType = [currentRoute.outputs[0] portType];
        }

        NSArray* outputs = [previousRouteKey outputs];
        if([outputs count] > 0) {
            AVAudioSessionPortDescription *output = outputs[0];

            // Legacy speakerOn/speakerOff events for backward compatibility
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

        // Enhanced audioRouteChange event with comprehensive information
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
    // Reject if a programmatic hold or unhold is already in flight for this session.
    if (self.activeCalls[sessionId][@"pendingHoldCommandCallbackId"] || self.activeCalls[sessionId][@"pendingUnholdCommandCallbackId"]) {
        [self logMessage:@"hold: rejecting call — a hold/unhold action is already in flight"];
        NSDictionary *resultDict = @{ @"message": @"hold action already in flight", @"sessionId": sessionId };
        CDVPluginResult *pluginResult = [CDVPluginResult resultWithStatus:CDVCommandStatus_ERROR messageAsDictionary:resultDict];
        [self.commandDelegate sendPluginResult:pluginResult callbackId:command.callbackId];
        return;
    }
    CXCall *call = [self callForSessionId:sessionId];
    if (call) {
        CXSetHeldCallAction *holdAction = [[CXSetHeldCallAction alloc] initWithCallUUID:call.UUID onHold:YES];
        CXTransaction *transaction = [[CXTransaction alloc] initWithAction:holdAction];
        [self logMessage:[NSString stringWithFormat:@"Programmatically Holding Call: %@", sessionId]];
        [self.callController requestTransaction:transaction completion:^(NSError * _Nullable error) {
            if (error == nil) {
                self.activeCalls[sessionId][@"pendingHoldCommandCallbackId"] = command.callbackId;
                // Defer promise resolution until didDeactivateAudioSession confirms the hold.
                dispatch_async(dispatch_get_main_queue(), ^{
                    if (!self.activeCalls[sessionId]) {
                        return;
                    }
                    NSTimer *timer = [NSTimer scheduledTimerWithTimeInterval:5.0 repeats:NO block:^(NSTimer *t) {
                        [self resolvePendingHoldCommandForSessionId:sessionId onHold:YES];
                    }];
                    self.activeCalls[sessionId][@"pendingHoldCommandTimer"] = timer;
                });
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
    // Reject if a programmatic unhold or hold is already in flight for this session.
    if (self.activeCalls[sessionId][@"pendingUnholdCommandCallbackId"] || self.activeCalls[sessionId][@"pendingHoldCommandCallbackId"]) {
        [self logMessage:@"unhold: rejecting call — a hold/unhold action is already in flight"];
        NSDictionary *resultDict = @{ @"message": @"unhold action already in flight", @"sessionId": sessionId };
        CDVPluginResult *pluginResult = [CDVPluginResult resultWithStatus:CDVCommandStatus_ERROR messageAsDictionary:resultDict];
        [self.commandDelegate sendPluginResult:pluginResult callbackId:command.callbackId];
        return;
    }
    CXCall *call = [self callForSessionId:sessionId];
    if (call) {
        CXSetHeldCallAction *unholdAction = [[CXSetHeldCallAction alloc] initWithCallUUID:call.UUID onHold:NO];
        CXTransaction *transaction = [[CXTransaction alloc] initWithAction:unholdAction];
        [self logMessage:[NSString stringWithFormat:@"Programmatically Unholding Call: %@", sessionId]];
        [self.callController requestTransaction:transaction completion:^(NSError * _Nullable error) {
            if (error == nil) {
                self.activeCalls[sessionId][@"pendingUnholdCommandCallbackId"] = command.callbackId;
                // Defer promise resolution until didActivateAudioSession confirms the unhold.
                dispatch_async(dispatch_get_main_queue(), ^{
                    if (!self.activeCalls[sessionId]) {
                        return;
                    }
                    NSTimer *timer = [NSTimer scheduledTimerWithTimeInterval:5.0 repeats:NO block:^(NSTimer *t) {
                        [self resolvePendingHoldCommandForSessionId:sessionId onHold:NO];
                    }];
                    self.activeCalls[sessionId][@"pendingUnholdCommandTimer"] = timer;
                });
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

// Resolves and clears a pending programmatic hold/unhold command callbackId.
// Called from didDeactivateAudioSession (hold) / didActivateAudioSession (unhold)
// and from the safety-timeout timer so the JS promise always settles.
- (void)resolvePendingHoldCommandForSessionId:(NSString *)sessionId onHold:(BOOL)onHold
{
    NSString *callbackKey = onHold ? @"pendingHoldCommandCallbackId" : @"pendingUnholdCommandCallbackId";
    NSString *timerKey    = onHold ? @"pendingHoldCommandTimer"      : @"pendingUnholdCommandTimer";
    // Always cancel the timer first so it cannot fire after we return.
    NSTimer *timer = self.activeCalls[sessionId][timerKey];
    if (timer) {
        [timer invalidate];
        [self.activeCalls[sessionId] removeObjectForKey:timerKey];
    }
    NSString *callbackId  = self.activeCalls[sessionId][callbackKey];
    if (!callbackId) return;
    [self.activeCalls[sessionId] removeObjectForKey:callbackKey];
    NSDictionary *resultDict = @{ @"message": [NSString stringWithFormat:@"%@ event called successfully", onHold ? @"hold" : @"unhold"], @"sessionId": sessionId };
    CDVPluginResult *result = [CDVPluginResult resultWithStatus:CDVCommandStatus_OK messageAsDictionary:resultDict];
    [self.commandDelegate sendPluginResult:result callbackId:callbackId];
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
    NSDictionary *callData = @{@"callName":action.contactIdentifier, @"callId": action.handle.value, @"isVideo": action.video?@YES:@NO, @"message": @"sendCall event called successfully", @"recentsSessionId": isRecentsCall ? recentsSessionId : [NSNull null]};
    for (id callbackId in callbackIds[@"sendCall"]) {
        CDVPluginResult* pluginResult = nil;
        pluginResult = [CDVPluginResult resultWithStatus:CDVCommandStatus_OK messageAsDictionary:callData];
        [pluginResult setKeepCallbackAsBool:YES];
        [self.commandDelegate sendPluginResult:pluginResult callbackId:callbackId];
    }
    if([callbackIds[@"sendCall"] count] == 0) {
        pendingCallFromRecents = callData;
    }
}

- (void)provider:(CXProvider *)provider didActivateAudioSession:(AVAudioSession *)audioSession
{
    [self logMessage:@"activated audio"];
    [[RTCAudioSession sharedInstance] audioSessionDidActivate:audioSession];
    [RTCAudioSession sharedInstance].isAudioEnabled = YES;
    monitorAudioRouteChange = YES;

    // Emit answer callback deferred from performAnswerCallAction
    for (NSString *sessionId in self.activeCalls) {
        NSNumber *pendingAnswer = self.activeCalls[sessionId][@"pendingAnswerEmit"];
        if (pendingAnswer != nil && [pendingAnswer boolValue] == YES) {
            [self logMessage:[NSString stringWithFormat:@"didActivateAudioSession: emitting deferred answer for sessionId=%@", sessionId]];
            [self.activeCalls[sessionId] removeObjectForKey:@"pendingAnswerEmit"];
            if ([callbackIds[@"answer"] count] == 0) {
                NSDictionary *pendingResponse = @{
                    @"type": PENDING_RESPONSE_ANSWER,
                    @"sessionId": sessionId
                };
                [pendingCallResponses addObject:pendingResponse];
            } else {
                [self triggerCordovaEventForCallResponse:@"answer" sessionId:sessionId];
            }
        }
    }

    // Resolve unhold callback deferred from performSetHeldCallAction
    for (NSString *sessionId in self.activeCalls) {
        NSNumber *pendingHold = self.activeCalls[sessionId][@"pendingHoldEmit"];
        if (pendingHold != nil && [pendingHold boolValue] == NO) {
            [self.activeCalls[sessionId] removeObjectForKey:@"pendingHoldEmit"];
            if (self.activeCalls[sessionId][@"pendingUnholdCommandCallbackId"]) {
                // Programmatic unhold: resolve the JS promise only.
                [self resolvePendingHoldCommandForSessionId:sessionId onHold:NO];
            } else {
                // UI-initiated unhold: emit to event listeners only.
                for (id callbackId in callbackIds[@"unhold"]) {
                    NSDictionary *resultDict = @{ @"message": @"unhold event called successfully", @"sessionId": sessionId };
                    CDVPluginResult *pluginResult = [CDVPluginResult resultWithStatus:CDVCommandStatus_OK messageAsDictionary:resultDict];
                    [pluginResult setKeepCallbackAsBool:YES];
                    [self.commandDelegate sendPluginResult:pluginResult callbackId:callbackId];
                }
            }
        }
    }
}

- (void)provider:(CXProvider *)provider didDeactivateAudioSession:(AVAudioSession *)audioSession
{
    [self logMessage:@"deactivated audio"];
    [RTCAudioSession sharedInstance].isAudioEnabled = NO;
    [[RTCAudioSession sharedInstance] audioSessionDidDeactivate:audioSession];
    monitorAudioRouteChange = NO;

    // Resolve hold callback deferred from performSetHeldCallAction
    for (NSString *sessionId in self.activeCalls) {
        NSNumber *pendingHold = self.activeCalls[sessionId][@"pendingHoldEmit"];
        if (pendingHold != nil && [pendingHold boolValue] == YES) {
            [self.activeCalls[sessionId] removeObjectForKey:@"pendingHoldEmit"];
            if (self.activeCalls[sessionId][@"pendingHoldCommandCallbackId"]) {
                // Programmatic hold: resolve the JS promise only.
                [self resolvePendingHoldCommandForSessionId:sessionId onHold:YES];
            } else {
                // UI-initiated hold: emit to event listeners only.
                for (id callbackId in callbackIds[@"hold"]) {
                    NSDictionary *resultDict = @{ @"message": @"hold event called successfully", @"sessionId": sessionId };
                    CDVPluginResult *pluginResult = [CDVPluginResult resultWithStatus:CDVCommandStatus_OK messageAsDictionary:resultDict];
                    [pluginResult setKeepCallbackAsBool:YES];
                    [self.commandDelegate sendPluginResult:pluginResult callbackId:callbackId];
                }
            }
        }
    }

    // Emit hangup callback and resolve the endCall JS promise for connected calls that ended.
    // Collect keys first to avoid mutating activeCalls while iterating.
    NSMutableArray *hangupSessions = [NSMutableArray array];
    for (NSString *sessionId in self.activeCalls) {
        NSNumber *pendingHangup = self.activeCalls[sessionId][@"pendingHangupEmit"];
        if (pendingHangup != nil && [pendingHangup boolValue] == YES) {
            [hangupSessions addObject:sessionId];
        }
    }
    for (NSString *sessionId in hangupSessions) {
        [self.activeCalls[sessionId] removeObjectForKey:@"pendingHangupEmit"];
        for (id callbackId in callbackIds[@"hangup"]) {
            NSDictionary *resultDict = @{ @"message": @"hangup event called successfully", @"sessionId": sessionId };
            CDVPluginResult *pluginResult = [CDVPluginResult resultWithStatus:CDVCommandStatus_OK messageAsDictionary:resultDict];
            [pluginResult setKeepCallbackAsBool:YES];
            [self.commandDelegate sendPluginResult:pluginResult callbackId:callbackId];
        }
        NSString *endCallCb = self.activeCalls[sessionId][@"pendingEndCallCommandCallbackId"];
        if (endCallCb) {
            NSDictionary *resultDict = @{ @"message": @"endCall event called successfully", @"sessionId": sessionId };
            CDVPluginResult *result = [CDVPluginResult resultWithStatus:CDVCommandStatus_OK messageAsDictionary:resultDict];
            [self.commandDelegate sendPluginResult:result callbackId:endCallCb];
        }
        [self cancelPendingCommandTimersForSessionId:sessionId];
        [self.activeCalls removeObjectForKey:sessionId];
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
    self.activeCalls[sessionId][@"pendingAnswerEmit"] = @YES;

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
            self.activeCalls[sessionId][@"pendingHangupEmit"] = @YES;
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
        [self logMessage:[NSString stringWithFormat:@"performSetMutedCallAction: no sessionId found for callUUID %@, ignoring %@ event", action.callUUID.UUIDString, isMuted ? @"mute" : @"unmute"]];
        [action fulfill];
        return;
    }
    [self logMessage:[NSString stringWithFormat:@"CallKit received %@ event, sessionId: %@", isMuted ? @"mute" : @"unmute", sessionId]];

    // Sync our isMuted state to match what CallKit just told us.
    self.activeCalls[sessionId][@"isMuted"] = @(isMuted);

    [action fulfill];

    NSUUID *pendingUUID = self.activeCalls[sessionId][@"pendingMuteActionUUID"];
    if (pendingUUID && [pendingUUID isEqual:action.UUID]) {
        // Programmatic mute/unmute: resolve the JS promise only.
        [self resolvePendingMuteCommandForSessionId:sessionId];
    } else {
        // UI-initiated mute/unmute: emit to event listeners only.
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
    [self logMessage:[NSString stringWithFormat:@"Callkit UI received %@ event, callkit says: %@, sessionId: %@", isOnHold ? @"hold" : @"unhold", [call isOnHold] ? @"on hold" : @"not on hold", sessionId]];
    if (!sessionId) {
        [self logMessage:[NSString stringWithFormat:@"performSetHeldCallAction: no sessionId found for callUUID %@, ignoring %@ event", action.callUUID.UUIDString, isOnHold ? @"hold" : @"unhold"]];
        [action fulfill];
        return;
    }
    self.activeCalls[sessionId][@"pendingHoldEmit"] = @(isOnHold); // Callback emitted via didActivateAudioSession / didDeactivateAudioSession
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
    CXHandle *handle = [[CXHandle alloc] initWithType:CXHandleTypePhoneNumber value:@"unknown"];
    CXCallUpdate *callUpdate = [[CXCallUpdate alloc] init];
    callUpdate.remoteHandle = handle;
    callUpdate.localizedCallerName = @"Unknown";
    [self.provider reportNewIncomingCallWithUUID:dummyUUID update:callUpdate completion:^(NSError * _Nullable error) {
        if (error != nil) {
            [self logMessage:[NSString stringWithFormat:@"_reportAndEndDummyCall: failed to report dummy incoming call: %@", error.localizedDescription]];
            return;
        }
        [self.provider reportCallWithUUID:dummyUUID endedAtDate:nil reason:CXCallEndedReasonFailed];
    }];
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

#define PushKit Delegate Methods
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
    NSString *from = [fromValue isKindOfClass:[NSString class]] ? fromValue : @"Unknown";
    NSArray* args = [NSArray arrayWithObjects:from, [NSNull null], sessionId, nil];
    CDVInvokedUrlCommand* newCommand = [[CDVInvokedUrlCommand alloc] initWithArguments:args callbackId:@"" className:self.VoIPPushClassName methodName:self.VoIPPushMethodName];

    // Store URL and Call Id so they can be used for call Answer/Reject
    callBackUrl = [payloadObj valueForKey:@"callback_url"];
    NSString *Type = [payloadObj valueForKey:@"type"];
    hasVideo = ![Type isEqualToString:@"incoming_phone_call"];
    self.activeCalls[sessionId] = [@{
        @"callData": [[NSString alloc] initWithData:payloadJsonData encoding:NSUTF8StringEncoding],
        @"callUUID": [[NSUUID alloc] init]
    } mutableCopy];
    // Notify Webhook that VOIP Push Has been received and app is started
    // NSURL *statusUpdateUrl = [NSURL URLWithString:[NSString stringWithFormat:@"%@?id=%@&input=%@", callBackUrl, callId, @"connected"]];
    // NSURLSession *session = [NSURLSession sharedSession];
    // [[session dataTaskWithURL:statusUpdateUrl
    //           completionHandler:^(NSData *statusUpdateData,
    //                               NSURLResponse *statusUpdateResponse,
    //                               NSError *statusUpdateError) {
    //             // handle response
    // }] resume];

    [self receiveCall:newCommand];

    @try {
        NSError * err;
        NSData * jsonData = [NSJSONSerialization dataWithJSONObject:payloadObj options:0 error:&err];
        NSString * dataString = [[NSString alloc] initWithData:jsonData encoding:NSUTF8StringEncoding];
        [results setObject:dataString forKey:@"extra"];
    }
    @catch (NSException *exception) {
        [self logMessage:[NSString stringWithFormat:@"error: %@", exception.reason]];
        CDVPluginResult* pluginResult = [CDVPluginResult resultWithStatus:CDVCommandStatus_ERROR messageAsString:exception.reason];
        [pluginResult setKeepCallback:[NSNumber numberWithBool:YES]];
        [self.commandDelegate sendPluginResult:pluginResult callbackId:self.VoIPPushCallbackId];
        return;
    }
    @finally {
        CDVPluginResult* pluginResult = [CDVPluginResult resultWithStatus:CDVCommandStatus_OK messageAsDictionary:results];
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
