#import "CordovaCall.h"
#import <Cordova/CDV.h>
#import <AVFoundation/AVFoundation.h>
#import "WebSocketAdvanced.h"
#import <SocketRocket/SocketRocket.h>

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
    providerConfiguration.maximumCallGroups = 1; // Max simultaneous active calls allowed
    providerConfiguration.maximumCallsPerCallGroup = 1; // Max calls allowed to be handled at once as a group, including held calls
    NSMutableSet *handleTypes = [[NSMutableSet alloc] init];
    [handleTypes addObject:@(CXHandleTypePhoneNumber)];
    providerConfiguration.supportedHandleTypes = handleTypes;
    providerConfiguration.supportsVideo = YES;
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
    providerConfiguration.maximumCallGroups = 1; // Max simultaneous active calls allowed
    providerConfiguration.maximumCallsPerCallGroup = 1; // Max calls allowed to be handled at once as a group, including held calls
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
      [sessionInstance setCategory:AVAudioSessionCategoryPlayAndRecord error:nil];
      [sessionInstance setMode:AVAudioSessionModeVoiceChat error:nil];
      NSTimeInterval bufferDuration = .005;
      [sessionInstance setPreferredIOBufferDuration:bufferDuration error:nil];
      [sessionInstance setPreferredSampleRate:44100 error:nil];
    //   [sessionInstance setActive:YES error:nil];
      [self logMessage:@"Configuring Audio"];
    }
    @catch (NSException *exception) {
      [self logMessage:@"Unknown error returned from setupAudioSession"];
    }
    return;
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
            @"callUUID": [[NSUUID alloc] init],
            @"muted": @NO
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
        callUpdate.supportsHolding = NO;
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
        @"callUUID": callUUID,
        @"muted": @NO
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
    CDVPluginResult* pluginResult = nil;
    NSString* sessionId = [command.arguments objectAtIndex:0];
    CXCall *call = [self callForSessionId:sessionId];

    if(call) {
        CXEndCallAction *endCallAction = [[CXEndCallAction alloc] initWithCallUUID:call.UUID];
        CXTransaction *transaction = [[CXTransaction alloc] initWithAction:endCallAction];
        [self.callController requestTransaction:transaction completion:^(NSError * _Nullable error) {
            if (error == nil) {
            } else {
                [self logMessage:[error localizedDescription]];
            }
        }];
        pluginResult = [CDVPluginResult resultWithStatus:CDVCommandStatus_OK messageAsString:@"Call ended successfully"];
    } else {
        pluginResult = [CDVPluginResult resultWithStatus:CDVCommandStatus_OK messageAsString:@"No call exists for you to connect"]; // Don't error if no call exists
    }

    [self.commandDelegate sendPluginResult:pluginResult callbackId:command.callbackId];
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
    [self logMessage:@"mute"];
    __block CDVPluginResult* pluginResult = nil;
    NSString* sessionId = [command.arguments objectAtIndex:0];
    CXCall *call = [self callForSessionId:sessionId];
    if (call) {
        CXSetMutedCallAction *muteAction = [[CXSetMutedCallAction alloc] initWithCallUUID:call.UUID muted:YES];
        CXTransaction *transaction = [[CXTransaction alloc] initWithAction:muteAction];
        [self logMessage:@"Programatically Muting Call"];
        [self.callController requestTransaction:transaction completion:^(NSError * _Nullable error) {
            if (error == nil) {
                self.activeCalls[sessionId][@"muted"] = @YES;
                pluginResult = [CDVPluginResult resultWithStatus:CDVCommandStatus_OK messageAsString:@"Muted Successfully"];
            } else {
            [self logMessage:@"Error occurred muting Call"];
                self.activeCalls[sessionId][@"muted"] = @NO;
                pluginResult = [CDVPluginResult resultWithStatus:CDVCommandStatus_ERROR messageAsString:@"An error occurred"];
            }
            [self.commandDelegate sendPluginResult:pluginResult callbackId:command.callbackId];
        }];
    } else {
        pluginResult = [CDVPluginResult resultWithStatus:CDVCommandStatus_OK messageAsString:@"No active call to mute"];
        [self.commandDelegate sendPluginResult:pluginResult callbackId:command.callbackId];
    }
}

- (void)unmute:(CDVInvokedUrlCommand*)command
{
    [self logMessage:@"unmute"];
    __block CDVPluginResult* pluginResult = nil;
    NSString* sessionId = [command.arguments objectAtIndex:0];
    CXCall *call = [self callForSessionId:sessionId];
    if (call) {
        CXSetMutedCallAction *unmuteAction = [[CXSetMutedCallAction alloc] initWithCallUUID:call.UUID muted:NO];
        CXTransaction *transaction = [[CXTransaction alloc] initWithAction:unmuteAction];
        [self logMessage:@"Programatically Unmuting Call"];
        [self.callController requestTransaction:transaction completion:^(NSError * _Nullable error) {
            if (error == nil) {
                self.activeCalls[sessionId][@"muted"] = @NO;
                pluginResult = [CDVPluginResult resultWithStatus:CDVCommandStatus_OK messageAsString:@"Unmuted Successfully"];
            } else {
            [self logMessage:@"Error occurred unmuting Call"];
                self.activeCalls[sessionId][@"muted"] = @YES;
                pluginResult = [CDVPluginResult resultWithStatus:CDVCommandStatus_ERROR messageAsString:@"An error occurred"];
            }
            [self.commandDelegate sendPluginResult:pluginResult callbackId:command.callbackId];
        }];
    } else {
        pluginResult = [CDVPluginResult resultWithStatus:CDVCommandStatus_OK messageAsString:@"No active call to unmute"];
        [self.commandDelegate sendPluginResult:pluginResult callbackId:command.callbackId];
    }
}

- (void)speakerOn:(CDVInvokedUrlCommand*)command
{
    CDVPluginResult* pluginResult = nil;
    AVAudioSession *sessionInstance = [AVAudioSession sharedInstance];
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
    BOOL success = [sessionInstance overrideOutputAudioPort:AVAudioSessionPortOverrideNone error:nil];
    if(success) {
      pluginResult = [CDVPluginResult resultWithStatus:CDVCommandStatus_OK messageAsString:@"Speakerphone is off"];
    } else {
      pluginResult = [CDVPluginResult resultWithStatus:CDVCommandStatus_ERROR messageAsString:@"An error occurred"];
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
        NSNumber* reasonValue = notification.userInfo[@"AVAudioSessionRouteChangeReasonKey"];
        AVAudioSessionRouteDescription* previousRouteKey = notification.userInfo[@"AVAudioSessionRouteChangePreviousRouteKey"];
        NSArray* outputs = [previousRouteKey outputs];
        if([outputs count] > 0) {
            AVAudioSessionPortDescription *output = outputs[0];
            if(![output.portType isEqual: @"Speaker"] && [reasonValue isEqual:@4]) {
                for (id callbackId in callbackIds[@"speakerOn"]) {
                    CDVPluginResult* pluginResult = nil;
                    pluginResult = [CDVPluginResult resultWithStatus:CDVCommandStatus_OK messageAsString:@"speakerOn event called successfully"];
                    [pluginResult setKeepCallbackAsBool:YES];
                    [self.commandDelegate sendPluginResult:pluginResult callbackId:callbackId];
                }
            } else if([output.portType isEqual: @"Speaker"] && [reasonValue isEqual:@3]) {
                for (id callbackId in callbackIds[@"speakerOff"]) {
                    CDVPluginResult* pluginResult = nil;
                    pluginResult = [CDVPluginResult resultWithStatus:CDVCommandStatus_OK messageAsString:@"speakerOff event called successfully"];
                    [pluginResult setKeepCallbackAsBool:YES];
                    [self.commandDelegate sendPluginResult:pluginResult callbackId:callbackId];
                }
            }
        }
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
    callUpdate.hasVideo = action.video;
    callUpdate.localizedCallerName = action.contactIdentifier;
    callUpdate.supportsGrouping = NO;
    callUpdate.supportsUngrouping = NO;
    callUpdate.supportsHolding = NO;
    callUpdate.supportsDTMF = enableDTMF;
    
    [self.provider reportCallWithUUID:action.callUUID updated:callUpdate];
    [action fulfill];
    NSDictionary *callData = @{@"callName":action.contactIdentifier, @"callId": action.handle.value, @"isVideo": action.video?@YES:@NO, @"message": @"sendCall event called successfully"};
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
    monitorAudioRouteChange = YES;
}

- (void)provider:(CXProvider *)provider didDeactivateAudioSession:(AVAudioSession *)audioSession
{
    [self logMessage:@"deactivated audio"];
}

- (void)provider:(CXProvider *)provider performAnswerCallAction:(CXAnswerCallAction *)action
{
    [self logMessage:@"performAnswerCallAction"];
    [self setupAudioSession];
    [action fulfill];

    NSString *sessionId = [self sessionIdForUUID:action.callUUID];
    if ([callbackIds[@"answer"] count] == 0) {
        // callbackId for event not registered, add to pending to trigger on registration
        NSDictionary *pendingResponse = @{
            @"type": PENDING_RESPONSE_ANSWER,
            @"sessionId": sessionId
        };
        [pendingCallResponses addObject:pendingResponse];
    } else {
        [self triggerCordovaEventForCallResponse:@"answer" sessionId:sessionId];
    }

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
            for (id callbackId in callbackIds[@"hangup"]) {
                CDVPluginResult* pluginResult = nil;
                // Send to facetalk whihc call was ended
                NSDictionary *resultDict = @{ @"message": @"hangup event called successfully", @"sessionId": sessionId };
                pluginResult = [CDVPluginResult resultWithStatus:CDVCommandStatus_OK messageAsDictionary:resultDict];
                [pluginResult setKeepCallbackAsBool:YES];
                [self.commandDelegate sendPluginResult:pluginResult callbackId:callbackId];
            }
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
        }
        [self.activeCalls removeObjectForKey:sessionId]; // clear out the call once it's ended
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
    [self logMessage:[NSString stringWithFormat:@"Callkit UI received %@ event, currently %@", isMuted ? @"mute" : @"unmute", [self.activeCalls[sessionId][@"muted"] boolValue] ? @"muted" : @"unmuted"]];
    [action fulfill];

    // Ignore the duplicate mute/unmute events, somehow 2 events get sent for every action
    if ([self.activeCalls[sessionId][@"muted"] boolValue] == isMuted) {
        [self logMessage:[NSString stringWithFormat:@"Ignoring duplicate %@ event.", isMuted ? @"mute" : @"unmute"]];
        return;
    }
    self.activeCalls[sessionId][@"muted"] = @(isMuted); // Update the internal state with the new value
    for (id callbackId in callbackIds[isMuted?@"mute":@"unmute"]) {
        [self logMessage:[NSString stringWithFormat:@"Sending %@ event to JS", isMuted ? @"mute" : @"unmute"]];
        CDVPluginResult* pluginResult = nil;
        // Send to facetalk which call was muted/unmuted
        NSDictionary *resultDict = @{ @"message": [NSString stringWithFormat:@"%@ event called successfully", isMuted ? @"mute" : @"unmute"], @"sessionId": sessionId };
        pluginResult = [CDVPluginResult resultWithStatus:CDVCommandStatus_OK messageAsDictionary:resultDict];
        [pluginResult setKeepCallbackAsBool:YES];
        [self.commandDelegate sendPluginResult:pluginResult callbackId:callbackId];
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

// Returns the Callkit CXCall instance for a sessionId
- (CXCall *)callForSessionId:(NSString *)sessionId {
    NSUUID *callUUID = self.activeCalls[sessionId][@"callUUID"];
    if (!callUUID) return nil;

    NSArray<CXCall *> *calls = self.callController.callObserver.calls;
    for (CXCall *call in calls) {
        if ([call.UUID isEqual:callUUID]) {
            return call;
        }
    }
    return nil;
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
    NSDictionary *payloadDict = payload.dictionaryPayload[@"aps"];
    [self logMessage:[NSString stringWithFormat:@"didReceiveIncomingPushWithPayload: %@", payloadDict]];

    NSString *message = payloadDict[@"alert"];
    [self logMessage:[NSString stringWithFormat:@"received VoIP message: %@", message]];
    
    NSDictionary *data = payload.dictionaryPayload[@"data"];
    [self logMessage:[NSString stringWithFormat:@"received data: %@", data]];
    
    NSMutableDictionary* results = [NSMutableDictionary dictionaryWithCapacity:2];
    [results setObject:message forKey:@"function"];
    [results setObject:@"" forKey:@"extra"];

    NSError *error = nil;
    NSData *payloadJsonData = [[data objectForKey:@"payload"] dataUsingEncoding:NSUTF8StringEncoding];
    NSDictionary *payloadObj = [NSJSONSerialization JSONObjectWithData:payloadJsonData options:0 error:&error];
    if (error || ![payloadObj isKindOfClass:[NSDictionary class]]) {
        [self logMessage:[NSString stringWithFormat:@"Error parsing payload JSON: %@", error]];
        return;
    }
    // sessionId is the first part of the call_uuid separated by ;  IE: call_id;ftag;ttag
    NSString *sessionId = [[[payloadObj valueForKey:@"call_uuid"] componentsSeparatedByString:@";"] firstObject];
    NSArray* args = [NSArray arrayWithObjects:[payloadObj valueForKey:@"from"], nil, sessionId];
    CDVInvokedUrlCommand* newCommand = [[CDVInvokedUrlCommand alloc] initWithArguments:args callbackId:@"" className:self.VoIPPushClassName methodName:self.VoIPPushMethodName];
    
    // Store URL and Call Id so they can be used for call Answer/Reject
    callBackUrl = [payloadObj valueForKey:@"callback_url"];
    NSString *Type = [payloadObj valueForKey:@"type"];
    hasVideo = ![Type isEqualToString:@"incoming_phone_call"];
    self.activeCalls[sessionId] = [@{
        @"callData": [[NSString alloc] initWithData:payloadJsonData encoding:NSUTF8StringEncoding],
        @"callUUID": [[NSUUID alloc] init],
        @"muted": @NO
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
    if (payloadDict[@"dismiss"] == nil || payloadDict[@"dismiss"] == false) {
        [self logMessage:@"Dismiss key not found in payload or is false"];
        return;
    } else {
        [self _dismissRingingCall:payloadDict[@"call_uuid"]];
    }
}

- (void)logMessage:(NSString *)message
{
    NSLog(@"[CordovaCall]: %@", message);
}
@end
