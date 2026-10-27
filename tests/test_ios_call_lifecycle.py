"""Run production lifecycle methods with deterministic CallKit/PushKit doubles on macOS."""

import pathlib
import re
import subprocess
import tempfile
import unittest


ROOT = pathlib.Path(__file__).resolve().parents[1]
SOURCE = ROOT / "src/ios/CordovaCall.m"

PREAMBLE = r"""
#import <Foundation/Foundation.h>

enum { CDVCommandStatus_OK = 1, CDVCommandStatus_ERROR = 9 };
enum { CXHandleTypePhoneNumber, CXCallEndedReasonRemoteEnded, CXCallEndedReasonFailed };
BOOL enableDTMF = YES, hasVideo = NO;
NSString *callBackUrl;
NSString *ANSWER_ERROR_CALL_ENDED = @"call ended";
NSMutableDictionary *callbackIds;
NSMutableArray *pendingCallResponses;
NSDictionary *pendingCallFromRecents;
NSString *PENDING_RESPONSE_ANSWER = @"pendingResponseAnswer";
NSString *PENDING_RESPONSE_REJECT = @"pendingResponseReject";
BOOL monitorAudioRouteChange, callKitAudioSessionActive;

@interface AVAudioSession : NSObject
@property AVAudioSession *currentRoute;
@property NSArray<AVAudioSession *> *outputs;
@property NSString *portType;
+ (instancetype)sharedInstance;
@end
@implementation AVAudioSession
+ (instancetype)sharedInstance { return [self new]; }
@end

@interface RTCAudioSession : NSObject
@property BOOL isAudioEnabled;
+ (instancetype)sharedInstance;
- (void)audioSessionDidDeactivate:(AVAudioSession *)session;
@end
@implementation RTCAudioSession
+ (instancetype)sharedInstance { return [self new]; }
- (void)audioSessionDidDeactivate:(AVAudioSession *)session {}
@end

@interface CDVPluginResult : NSObject
@property int status;
@property id value;
+ (instancetype)resultWithStatus:(int)status messageAsString:(NSString *)value;
+ (instancetype)resultWithStatus:(int)status messageAsDictionary:(NSDictionary *)value;
- (void)setKeepCallbackAsBool:(BOOL)value;
- (void)setKeepCallback:(NSNumber *)value;
@end
@implementation CDVPluginResult
+ (instancetype)resultWithStatus:(int)status messageAsString:(NSString *)value {
    CDVPluginResult *result = [self new]; result.status = status; result.value = value; return result;
}
+ (instancetype)resultWithStatus:(int)status messageAsDictionary:(NSDictionary *)value {
    return [self resultWithStatus:status messageAsString:(id)value];
}
- (void)setKeepCallbackAsBool:(BOOL)value {}
- (void)setKeepCallback:(NSNumber *)value {}
@end

@interface CDVInvokedUrlCommand : NSObject
@property NSArray *arguments;
@property NSString *callbackId;
- (instancetype)initWithArguments:(NSArray *)args callbackId:(NSString *)callbackId
                       className:(NSString *)className methodName:(NSString *)methodName;
- (id)argumentAtIndex:(NSUInteger)index;
@end
@implementation CDVInvokedUrlCommand
- (instancetype)initWithArguments:(NSArray *)args callbackId:(NSString *)callbackId
                       className:(NSString *)className methodName:(NSString *)methodName {
    self = [super init]; self.arguments = args; self.callbackId = callbackId; return self;
}
- (id)argumentAtIndex:(NSUInteger)index { return self.arguments[index]; }
@end

@interface CXCall : NSObject
@property NSUUID *UUID;
@property BOOL hasEnded, hasConnected, isOutgoing;
@end
@implementation CXCall
@end

@interface CXHandle : NSObject
- (instancetype)initWithType:(int)type value:(NSString *)value;
@end
@implementation CXHandle
- (instancetype)initWithType:(int)type value:(NSString *)value { return [super init]; }
@end

@interface CXCallUpdate : NSObject
@property CXHandle *remoteHandle;
@property NSString *localizedCallerName;
@property BOOL hasVideo, supportsGrouping, supportsUngrouping, supportsHolding, supportsDTMF;
@end
@implementation CXCallUpdate
@end

@interface CXAnswerCallAction : NSObject
@property NSUUID *UUID, *callUUID;
- (instancetype)initWithCallUUID:(NSUUID *)uuid;
- (void)fulfill;
@end
@implementation CXAnswerCallAction
- (void)fulfill {}
- (instancetype)initWithCallUUID:(NSUUID *)uuid {
    self = [super init]; self.UUID = [NSUUID UUID]; self.callUUID = uuid; return self;
}
@end

@interface CXStartCallAction : CXAnswerCallAction
@property NSString *contactIdentifier;
@property BOOL video;
- (instancetype)initWithCallUUID:(NSUUID *)uuid handle:(CXHandle *)handle;
@end
@implementation CXStartCallAction
- (instancetype)initWithCallUUID:(NSUUID *)uuid handle:(CXHandle *)handle {
    return [super initWithCallUUID:uuid];
}
@end

@interface CXEndCallAction : CXAnswerCallAction
@end
@implementation CXEndCallAction
@end

@interface CXTransaction : NSObject
@property CXAnswerCallAction *action;
- (instancetype)initWithAction:(CXAnswerCallAction *)action;
@end
@implementation CXTransaction
- (instancetype)initWithAction:(CXAnswerCallAction *)action {
    self = [super init]; self.action = action; return self;
}
@end

@interface FakeCallKit : NSObject
@property NSMutableArray *reports, *ended, *transactions;
@property NSArray *calls;
@property (readonly) FakeCallKit *callObserver;
@property (copy) void (^reportCompletion)(NSError *);
@property NSError *transactionError;
@property BOOL deferTransactionCompletion;
@property (copy) void (^transactionCompletion)(NSError *);
- (void)reportNewIncomingCallWithUUID:(NSUUID *)uuid update:(CXCallUpdate *)update
                         completion:(void (^)(NSError *))completion;
- (void)reportCallWithUUID:(NSUUID *)uuid endedAtDate:(NSDate *)date reason:(int)reason;
- (void)requestTransaction:(CXTransaction *)transaction completion:(void (^)(NSError *))completion;
@end
@implementation FakeCallKit
- (FakeCallKit *)callObserver { return self; }
- (instancetype)init {
    self = [super init]; self.reports = [NSMutableArray new];
    self.ended = [NSMutableArray new]; self.transactions = [NSMutableArray new]; return self;
}
- (void)reportNewIncomingCallWithUUID:(NSUUID *)uuid update:(CXCallUpdate *)update
                         completion:(void (^)(NSError *))completion {
    [self.reports addObject:uuid]; self.reportCompletion = completion;
}
- (void)reportCallWithUUID:(NSUUID *)uuid endedAtDate:(NSDate *)date reason:(int)reason {
    [self.ended addObject:uuid];
}
- (void)requestTransaction:(CXTransaction *)transaction completion:(void (^)(NSError *))completion {
    [self.transactions addObject:transaction];
    if (self.deferTransactionCompletion) {
        self.transactionCompletion = completion;
    } else {
        completion(self.transactionError);
    }
}
@end
@compatibility_alias CXProvider FakeCallKit;

@interface FakeDelegate : NSObject
@property NSMutableDictionary<NSString *, CDVPluginResult *> *results;
- (void)sendPluginResult:(CDVPluginResult *)result callbackId:(NSString *)callbackId;
@end
@implementation FakeDelegate
- (instancetype)init { self = [super init]; self.results = [NSMutableDictionary new]; return self; }
- (void)sendPluginResult:(CDVPluginResult *)result callbackId:(NSString *)callbackId {
    if (callbackId.length) self.results[callbackId] = result;
}
@end

typedef NSString *PKPushType;
@interface PKPushRegistry : NSObject
@end
@interface PKPushPayload : NSObject
@property NSDictionary *dictionaryPayload;
@end
@implementation PKPushPayload
@end

@interface CordovaCall : NSObject
@property NSMutableDictionary *activeCalls;
@property NSMutableSet *endedSessionIds;
@property FakeCallKit *provider, *callController;
@property FakeDelegate *commandDelegate;
@property NSString *VoIPPushClassName, *VoIPPushMethodName, *VoIPPushCallbackId;
@property CXCall *observedCall;
- (void)logMessage:(NSString *)message;
- (void)stopKeepAlive:(CDVInvokedUrlCommand *)command;
- (void)teardownAudioSession;
- (NSString *)sessionIdForUUID:(NSUUID *)uuid;
- (CXCall *)callForSessionId:(NSString *)sessionId;
- (void)rejectPendingCommandsForSessionId:(NSString *)sessionId;
- (void)triggerCordovaEventForCallResponse:(NSString *)type sessionId:(NSString *)sessionId;
@end
@implementation CordovaCall
- (instancetype)init {
    self = [super init]; self.activeCalls = [NSMutableDictionary new];
    self.endedSessionIds = [NSMutableSet new]; self.provider = [FakeCallKit new];
    self.callController = self.provider; self.commandDelegate = [FakeDelegate new];
    self.VoIPPushCallbackId = @"push"; return self;
}
- (void)logMessage:(NSString *)message {}
- (void)stopKeepAlive:(CDVInvokedUrlCommand *)command {}
- (void)teardownAudioSession {}
- (NSString *)sessionIdForUUID:(NSUUID *)uuid {
    for (NSString *sessionId in self.activeCalls) {
        if ([self.activeCalls[sessionId][@"callUUID"] isEqual:uuid]) return sessionId;
    }
    return nil;
}
- (void)triggerCordovaEventForCallResponse:(NSString *)type sessionId:(NSString *)sessionId {}
- (CXCall *)callForSessionId:(NSString *)sessionId { return self.observedCall; }
"""

TESTS = r"""
static void check(BOOL condition, NSString *message) {
    if (!condition) { NSLog(@"FAIL: %@", message); exit(1); }
}
static void answer(CordovaCall *plugin, NSString *callbackId) {
    [plugin answerCall:[[CDVInvokedUrlCommand alloc]
        initWithArguments:@[@"session", @"Caller", @"123", [NSNull null]]
        callbackId:callbackId className:@"" methodName:@""]];
}
static void receive(CordovaCall *plugin, NSString *callbackId) {
    [plugin receiveCall:[[CDVInvokedUrlCommand alloc]
        initWithArguments:@[@"Caller", @"123", @"session"]
        callbackId:callbackId className:@"" methodName:@""]];
}
static PKPushPayload *push(void) {
    PKPushPayload *payload = [PKPushPayload new];
    payload.dictionaryPayload = @{@"data": @{@"payload":
        @"{\"session_id\":\"session\",\"from\":\"Caller\",\"cid\":\"123\"}"}};
    return payload;
}
int main(int argc, const char *argv[]) {
    @autoreleasepool {
        callbackIds = [NSMutableDictionary new];
        pendingCallResponses = [NSMutableArray new];
        NSString *scenario = @(argv[1]);
        CordovaCall *p = [CordovaCall new];
        if ([scenario isEqual:@"report-answer-retry"]) {
            answer(p, @"first");
            check(p.provider.reports.count == 1, @"one report");
            NSUUID *uuid = p.activeCalls[@"session"][@"callUUID"];
            p.provider.reportCompletion(nil);
            check(p.provider.transactions.count == 1, @"answer despite empty observer");
            check([p.provider.transactions[0] action].callUUID == uuid, @"registered UUID");
            check(!p.commandDelegate.results[@"first"], @"wait for audio activation");
            answer(p, @"retry");
            check(p.provider.reports.count == 1, @"retry must not re-report");
            check(p.provider.transactions.count == 1, @"deduplicate answer action");
            [p _settleAnswerWaitersForSessionId:@"session" errorMessage:nil];
            check([p.commandDelegate.results[@"first"] status] == CDVCommandStatus_OK, @"first resolves");
            check([p.commandDelegate.results[@"retry"] status] == CDVCommandStatus_OK, @"retry resolves");
        } else if ([scenario isEqual:@"retry-after-activation-empty-observer"] ||
                   [scenario isEqual:@"retry-after-activation-unconnected-observer"]) {
            answer(p, @"first"); p.provider.reportCompletion(nil);
            CXAnswerCallAction *action = [p.provider.transactions[0] action];
            [p.activeCalls[@"session"][@"pendingActivateAudioSessionEmits"]
                addObject:@{@"uuid": action.UUID.UUIDString, @"type": @"answer"}];
            answer(p, @"in-flight");
            check(!p.commandDelegate.results[@"first"] && !p.commandDelegate.results[@"in-flight"],
                @"waiters stay pending until activation");
            [p processPendingActivateEmitsSkippingTypes:[NSSet setWithObject:@"sendCall"]
                source:@"didActivateAudioSession"];
            check([p.commandDelegate.results[@"first"] status] == CDVCommandStatus_OK, @"first resolves");
            check([p.commandDelegate.results[@"in-flight"] status] == CDVCommandStatus_OK, @"in-flight retry resolves");
            check([p.activeCalls[@"session"][@"pendingActivateAudioSessionEmits"] count] == 0,
                @"activation drained");
            if ([scenario isEqual:@"retry-after-activation-unconnected-observer"]) {
                p.observedCall = [CXCall new];
                p.observedCall.UUID = action.callUUID;
            }
            answer(p, @"after-activation");
            check(p.commandDelegate.results[@"after-activation"] != nil, @"retry settles without observer connection");
            check([p.commandDelegate.results[@"after-activation"] status] == CDVCommandStatus_OK, @"retry succeeds");
            check(p.provider.reports.count == 1, @"no new report");
            check(p.provider.transactions.count == 1, @"no new answer action");
            check(pendingCallResponses.count == 0, @"no duplicate UI answer event");
        } else if ([scenario isEqual:@"report-failure"]) {
            answer(p, @"first");
            p.provider.reportCompletion([NSError errorWithDomain:@"test" code:1 userInfo:nil]);
            check([p.commandDelegate.results[@"first"] status] == CDVCommandStatus_ERROR, @"report failure rejects");
            check(p.provider.transactions.count == 0, @"no answer after failed report");
            check(!p.activeCalls[@"session"], @"failed report removed");
        } else if ([scenario isEqual:@"receive-canceled-report-failure"]) {
            receive(p, @"receive");
            [p endCall:[[CDVInvokedUrlCommand alloc] initWithArguments:@[@"session"]
                callbackId:@"end" className:@"" methodName:@""]];
            p.provider.reportCompletion([NSError errorWithDomain:@"test" code:1 userInfo:nil]);
            check([p.commandDelegate.results[@"receive"] status] == CDVCommandStatus_ERROR, @"original receive rejects");
            check(!p.activeCalls[@"session"], @"shared helper removes failed initial report");
            check([p.endedSessionIds containsObject:@"session"], @"preserve ended marker");
            receive(p, @"new-receive");
            check(![p.endedSessionIds containsObject:@"session"], @"explicit receive clears marker");
            p.provider.reportCompletion(nil);
            check([p.commandDelegate.results[@"new-receive"] status] == CDVCommandStatus_OK, @"new receive resolves");
            check([p.activeCalls[@"session"][@"reportSucceeded"] boolValue], @"new call stays registered");
            check(p.provider.ended.count == 0, @"new call is not canceled by old pendingEnd");
        } else if ([scenario isEqual:@"coalesced-incoming-reports"]) {
            for (NSString *outcome in @[@"success", @"failure", @"dismiss", @"end"]) {
                for (NSNumber *fromAnswer in @[@YES, @NO]) {
                    p = [CordovaCall new];
                    if (fromAnswer.boolValue) answer(p, @"first");
                    else receive(p, @"first");
                    NSMutableDictionary *entry = p.activeCalls[@"session"];
                    void (^originalCompletion)(NSError *) = p.provider.reportCompletion;
                    receive(p, @"second");
                    receive(p, @"third");
                    answer(p, @"answer");
                    check(p.provider.reports.count == 1, @"concurrent receives share one report");
                    check(!p.commandDelegate.results[@"second"] && !p.commandDelegate.results[@"third"],
                        @"coalesced callers wait for original report");
                    if ([outcome isEqual:@"dismiss"]) [p _dismissRingingCall:@"session"];
                    if ([outcome isEqual:@"end"]) {
                        [p endCall:[[CDVInvokedUrlCommand alloc] initWithArguments:@[@"session"]
                            callbackId:@"end" className:@"" methodName:@""]];
                    }
                    BOOL failed = [outcome isEqual:@"failure"];
                    BOOL ended = [outcome isEqual:@"dismiss"] || [outcome isEqual:@"end"];
                    originalCompletion(failed ? [NSError errorWithDomain:@"test" code:1 userInfo:nil] : nil);
                    for (NSString *callbackId in @[@"second", @"third"]) {
                        check(p.commandDelegate.results[callbackId] != nil, @"each receive callback settles");
                        check(p.commandDelegate.results[callbackId].status ==
                            (failed ? CDVCommandStatus_ERROR : CDVCommandStatus_OK), @"shared report result");
                    }
                    if (failed || ended) {
                        check(!p.activeCalls[@"session"], @"failed or canceled report removed");
                        check(p.commandDelegate.results[@"answer"].status == CDVCommandStatus_ERROR,
                            @"pending answer rejects");
                        check(p.provider.transactions.count == 0, @"no answer after failure or cancellation");
                        if (ended) {
                            check([p.endedSessionIds containsObject:@"session"], @"cancellation remembered");
                            check(p.provider.ended.count == 1, @"registered canceled call ended once");
                        }
                    } else {
                        check(p.activeCalls[@"session"] == entry, @"original entry retained");
                        check(p.provider.transactions.count == 1, @"original report answers once");
                        CXAnswerCallAction *action = [p.provider.transactions[0] action];
                        [entry[@"pendingActivateAudioSessionEmits"]
                            addObject:@{@"uuid": action.UUID.UUIDString, @"type": @"answer"}];
                        [p processPendingActivateEmitsSkippingTypes:[NSSet setWithObject:@"sendCall"]
                            source:@"didActivateAudioSession"];
                        check(p.commandDelegate.results[@"answer"].status == CDVCommandStatus_OK,
                            @"answer resolves on activation");
                    }
                    check(p.commandDelegate.results[@"first"] != nil, @"original caller settles");
                }
            }
        } else if ([scenario isEqual:@"duplicate-report-failure"]) {
            answer(p, @"first"); p.provider.reportCompletion(nil);
            NSMutableDictionary *entry = p.activeCalls[@"session"];
            receive(p, @"duplicate");
            p.provider.reportCompletion([NSError errorWithDomain:@"test" code:3 userInfo:nil]);
            check([p.commandDelegate.results[@"duplicate"] status] == CDVCommandStatus_ERROR, @"duplicate report rejects");
            check(p.activeCalls[@"session"] == entry, @"registered entry preserved");
            check(!p.commandDelegate.results[@"first"], @"valid answer waiter is not rejected");
            CXAnswerCallAction *action = [p.provider.transactions[0] action];
            [entry[@"pendingActivateAudioSessionEmits"]
                addObject:@{@"uuid": action.UUID.UUIDString, @"type": @"answer"}];
            [p processPendingActivateEmitsSkippingTypes:[NSSet setWithObject:@"sendCall"]
                source:@"didActivateAudioSession"];
            check([p.commandDelegate.results[@"first"] status] == CDVCommandStatus_OK, @"answer resolves on activation");
        } else if ([scenario isEqual:@"end-during-registered-duplicate"]) {
            for (NSNumber *failed in @[@YES, @NO]) {
                for (NSNumber *observed in @[@YES, @NO]) {
                    p = [CordovaCall new];
                    receive(p, @"first"); p.provider.reportCompletion(nil);
                    NSMutableDictionary *entry = p.activeCalls[@"session"];
                    if (observed.boolValue) {
                        p.observedCall = [CXCall new];
                        p.observedCall.UUID = entry[@"callUUID"];
                    }
                    receive(p, @"duplicate");
                    [p endCall:[[CDVInvokedUrlCommand alloc] initWithArguments:@[@"session"]
                        callbackId:@"end" className:@"" methodName:@""]];
                    check(p.provider.transactions.count == 1, @"registered call ends without waiting for duplicate");
                    CXEndCallAction *action = (id)[p.provider.transactions[0] action];
                    check([action.callUUID isEqual:entry[@"callUUID"]], @"end uses registered UUID");
                    check(!p.commandDelegate.results[@"end"], @"end waits for action instead of returning early success");
                    check(![entry[@"pendingEndAfterReport"] boolValue], @"no deferred end for registered call");
                    p.provider.reportCompletion(failed.boolValue ?
                        [NSError errorWithDomain:@"duplicate" code:2 userInfo:nil] : nil);
                    check(p.activeCalls[@"session"] == entry, @"duplicate completion preserves pending end");
                    [p provider:p.provider performEndCallAction:action];
                    check(p.commandDelegate.results[@"end"].status == CDVCommandStatus_OK, @"end resolves");
                    check(!p.activeCalls[@"session"], @"end removes entry");
                    check([p.endedSessionIds containsObject:@"session"], @"end remembered");
                }
            }
        } else if ([scenario isEqual:@"replacement-report-completion"]) {
            for (NSNumber *failed in @[@YES, @NO]) {
                for (NSNumber *fromAnswer in @[@YES, @NO]) {
                    p = [CordovaCall new];
                    if (fromAnswer.boolValue) answer(p, @"old");
                    else receive(p, @"old");
                    void (^oldCompletion)(NSError *) = p.provider.reportCompletion;
                    [p _removeEndedCallForSessionId:@"session"];
                    receive(p, @"replacement");
                    NSMutableDictionary *replacement = p.activeCalls[@"session"];
                    answer(p, @"replacement-answer");
                    oldCompletion(failed.boolValue ? [NSError errorWithDomain:@"test" code:1 userInfo:nil] : nil);
                    check(p.activeCalls[@"session"] == replacement, @"old completion cannot remove replacement");
                    check([replacement[@"reportInFlight"] boolValue], @"replacement report still in flight");
                    check(![replacement[@"reportSucceeded"] boolValue], @"old success cannot register replacement");
                    check(!p.commandDelegate.results[@"replacement-answer"], @"replacement answer still pending");
                    check(!p.commandDelegate.results[@"replacement"], @"replacement receive still pending");
                    if (!fromAnswer.boolValue) {
                        check([p.commandDelegate.results[@"old"] status] ==
                            (failed.boolValue ? CDVCommandStatus_ERROR : CDVCommandStatus_OK), @"old report callback settles");
                    }
                    p.provider.reportCompletion(nil);
                    check(p.provider.transactions.count == 1, @"replacement answers after its own report");
                }
            }
        } else if ([scenario isEqual:@"connected-end-during-report"] ||
                   [scenario isEqual:@"ui-hangup-blocks-answer"]) {
            for (NSNumber *reportFirst in @[@YES, @NO]) {
                p = [CordovaCall new];
                callbackIds[@"hangup"] = [NSMutableArray arrayWithObject:@"hangup"];
                receive(p, @"receive");
                NSMutableDictionary *entry = p.activeCalls[@"session"];
                p.observedCall = [CXCall new];
                p.observedCall.UUID = entry[@"callUUID"];
                p.observedCall.hasConnected = YES;
                entry[@"answerActivationCompleted"] = @YES;
                BOOL programmatic = [scenario isEqual:@"connected-end-during-report"];
                CXEndCallAction *action;
                if (programmatic) {
                    [p endCall:[[CDVInvokedUrlCommand alloc] initWithArguments:@[@"session"]
                        callbackId:@"end" className:@"" methodName:@""]];
                    check(p.provider.transactions.count == 1, @"connected end uses normal transaction");
                    check(!p.commandDelegate.results[@"end"], @"end waits for deactivation");
                    action = (id)[p.provider.transactions.lastObject action];
                } else {
                    action = [[CXEndCallAction alloc] initWithCallUUID:entry[@"callUUID"]];
                }
                [p provider:p.provider performEndCallAction:action];
                p.observedCall = nil;
                answer(p, @"late");
                check([p.commandDelegate.results[@"late"] status] == CDVCommandStatus_ERROR, @"queued hangup rejects answer");
                check([[p.commandDelegate.results[@"late"] value][@"callEnded"] boolValue], @"queued hangup returns ended");
                if (reportFirst.boolValue) {
                    p.provider.reportCompletion(nil);
                    check(p.activeCalls[@"session"] == entry, @"report completion preserves queued hangup");
                    check(!p.commandDelegate.results[@"hangup"], @"hangup waits for deactivation");
                }
                [p provider:p.provider didDeactivateAudioSession:nil];
                check([p.commandDelegate.results[@"hangup"] status] == CDVCommandStatus_OK, @"hangup emitted after deactivation");
                if (programmatic) {
                    check([p.commandDelegate.results[@"end"] status] == CDVCommandStatus_OK, @"end resolves after deactivation");
                }
                check(!p.activeCalls[@"session"], @"deactivation removes ended call");
                if (!reportFirst.boolValue) p.provider.reportCompletion(nil);
                check(!p.activeCalls[@"session"], @"late report cannot recreate ended call");
                check([p.commandDelegate.results[@"receive"] status] == CDVCommandStatus_OK, @"receive callback settles");
                check(p.provider.ended.count == 0, @"normal end action owns connected teardown");
            }
        } else if ([scenario isEqual:@"pending-ui-answer"]) {
            for (NSNumber *observed in @[@YES, @NO]) {
                p = [CordovaCall new];
                [pendingCallResponses removeAllObjects];
                receive(p, @"receive"); p.provider.reportCompletion(nil);
                NSMutableDictionary *entry = p.activeCalls[@"session"];
                if (observed.boolValue) {
                    p.observedCall = [CXCall new];
                    p.observedCall.UUID = entry[@"callUUID"];
                }
                [entry[@"pendingActivateAudioSessionEmits"]
                    addObject:@{@"uuid": [NSUUID UUID].UUIDString, @"type": @"answer"}];
                p.provider.transactionError = [NSError errorWithDomain:@"already-answered" code:1 userInfo:nil];
                answer(p, @"first"); answer(p, @"retry");
                check(p.provider.transactions.count == 0, @"pending UI answer does not submit duplicate transaction");
                check(!p.commandDelegate.results[@"first"] && !p.commandDelegate.results[@"retry"],
                    @"API callers wait for existing UI answer activation");
                [p processPendingActivateEmitsSkippingTypes:[NSSet setWithObject:@"sendCall"]
                    source:@"didActivateAudioSession"];
                for (NSString *callbackId in @[@"first", @"retry"]) {
                    check(p.commandDelegate.results[callbackId].status == CDVCommandStatus_OK,
                        @"UI activation resolves API waiters");
                }
                check(pendingCallResponses.count == 1, @"original UI answer event is preserved once");
                answer(p, @"after-activation");
                check(p.commandDelegate.results[@"after-activation"].status == CDVCommandStatus_OK,
                    @"retry after activation resolves");
                check(p.provider.transactions.count == 0, @"no extra transactions after activation");
            }
        } else if ([scenario isEqual:@"replacement-answer-completion"]) {
            p.provider.deferTransactionCompletion = YES;
            answer(p, @"old"); p.provider.reportCompletion(nil);
            void (^oldCompletion)(NSError *) = p.provider.transactionCompletion;
            [p _dismissRingingCall:@"session"];
            check([p.commandDelegate.results[@"old"].value[@"callEnded"] boolValue],
                @"original waiter rejected during dismissal");
            receive(p, @"replacement"); p.provider.reportCompletion(nil);
            answer(p, @"new");
            NSMutableDictionary *entry = p.activeCalls[@"session"];
            oldCompletion([NSError errorWithDomain:@"old-answer" code:1 userInfo:nil]);
            check(!p.commandDelegate.results[@"new"], @"old failure cannot reject replacement waiter");
            check([entry[@"answerRequested"] boolValue], @"replacement answer remains in flight");
            check([entry[@"callbackMap"] count] == 1, @"replacement answer callback preserved");
            check([entry[@"answerWaiters"] count] == 1, @"replacement waiter preserved");
            p.provider.transactionCompletion(nil);
            [entry[@"pendingActivateAudioSessionEmits"] addObject:@{
                @"uuid": [p.provider.transactions.lastObject action].UUID.UUIDString, @"type": @"answer"}];
            [p processPendingActivateEmitsSkippingTypes:[NSSet setWithObject:@"sendCall"]
                source:@"didActivateAudioSession"];
            check(p.commandDelegate.results[@"new"].status == CDVCommandStatus_OK,
                @"replacement activation resolves waiter");
        } else if ([scenario isEqual:@"replacement-end-completion"]) {
            p.provider.deferTransactionCompletion = YES;
            receive(p, @"old"); p.provider.reportCompletion(nil);
            [p endCall:[[CDVInvokedUrlCommand alloc] initWithArguments:@[@"session"]
                callbackId:@"old-end" className:@"" methodName:@""]];
            void (^oldCompletion)(NSError *) = p.provider.transactionCompletion;
            [p _dismissRingingCall:@"session"];
            receive(p, @"replacement"); p.provider.reportCompletion(nil);
            [p endCall:[[CDVInvokedUrlCommand alloc] initWithArguments:@[@"session"]
                callbackId:@"new-end" className:@"" methodName:@""]];
            NSMutableDictionary *entry = p.activeCalls[@"session"];
            oldCompletion([NSError errorWithDomain:@"old-end" code:1 userInfo:nil]);
            check(p.commandDelegate.results[@"old-end"].status == CDVCommandStatus_ERROR,
                @"original end command still receives error");
            check([entry[@"pendingEnd"] boolValue], @"old failure cannot clear replacement cancellation");
            check([entry[@"callbackMap"] count] == 1, @"replacement end callback preserved");
            check(!p.commandDelegate.results[@"new-end"], @"replacement end remains pending");
            NSUInteger count = p.provider.transactions.count;
            answer(p, @"late");
            check([p.commandDelegate.results[@"late"].value[@"callEnded"] boolValue],
                @"replacement teardown rejects answer");
            check(p.provider.transactions.count == count, @"no answer during replacement teardown");
            p.provider.transactionCompletion([NSError errorWithDomain:@"new-end" code:2 userInfo:nil]);
            check(![entry[@"pendingEnd"] boolValue], @"current end failure clears its cancellation");
            check([entry[@"callbackMap"] count] == 0, @"current end failure removes callback");
            check(p.commandDelegate.results[@"new-end"].status == CDVCommandStatus_ERROR,
                @"replacement end receives its own error");
        } else if ([scenario isEqual:@"ui-answer-during-transaction"]) {
            for (NSNumber *observed in @[@YES, @NO]) {
                for (NSString *state in @[@"pending", @"activated", @"ended", @"canceled"]) {
                    p = [CordovaCall new];
                    [pendingCallResponses removeAllObjects];
                    p.provider.deferTransactionCompletion = YES;
                    answer(p, @"first"); p.provider.reportCompletion(nil);
                    void (^answerCompletion)(NSError *) = p.provider.transactionCompletion;
                    answer(p, @"retry");
                    NSMutableDictionary *entry = p.activeCalls[@"session"];
                    if (observed.boolValue || [state isEqual:@"ended"]) {
                        p.observedCall = [CXCall new];
                        p.observedCall.UUID = entry[@"callUUID"];
                    }
                    [entry[@"pendingActivateAudioSessionEmits"] addObject:@{
                        @"uuid": [NSUUID UUID].UUIDString, @"type": @"answer"}];
                    if ([state isEqual:@"activated"]) {
                        [p processPendingActivateEmitsSkippingTypes:[NSSet setWithObject:@"sendCall"]
                            source:@"didActivateAudioSession"];
                        answer(p, @"after-activation");
                    } else if ([state isEqual:@"ended"]) {
                        p.observedCall.hasEnded = YES;
                    } else if ([state isEqual:@"canceled"]) {
                        [p endCall:[[CDVInvokedUrlCommand alloc] initWithArguments:@[@"session"]
                            callbackId:@"end" className:@"" methodName:@""]];
                    }
                    answerCompletion([NSError errorWithDomain:@"already-answered" code:1 userInfo:nil]);
                    if ([state isEqual:@"pending"]) {
                        check(!p.commandDelegate.results[@"first"] && !p.commandDelegate.results[@"retry"],
                            @"UI answer queued during transaction keeps waiters pending");
                        [p processPendingActivateEmitsSkippingTypes:[NSSet setWithObject:@"sendCall"]
                            source:@"didActivateAudioSession"];
                    }
                    BOOL ended = [state isEqual:@"ended"] || [state isEqual:@"canceled"];
                    for (NSString *callbackId in @[@"first", @"retry"]) {
                        CDVPluginResult *result = p.commandDelegate.results[callbackId];
                        check(result && result.status == (ended ? CDVCommandStatus_ERROR : CDVCommandStatus_OK),
                            @"UI answer resolves waiters unless call ended");
                        check([result.value[@"callEnded"] boolValue] == ended, @"correct ended flag");
                    }
                    if ([state isEqual:@"activated"]) {
                        check(p.commandDelegate.results[@"after-activation"].status == CDVCommandStatus_OK,
                            @"activation remains successful after transaction failure");
                    }
                }
            }
        } else if ([scenario isEqual:@"transaction-failure"]) {
            p.provider.transactionError = [NSError errorWithDomain:@"test" code:2 userInfo:nil];
            answer(p, @"first"); p.provider.reportCompletion(nil);
            check([p.commandDelegate.results[@"first"] status] == CDVCommandStatus_ERROR, @"transaction failure rejects");
            check(![[p.commandDelegate.results[@"first"] value][@"callEnded"] boolValue], @"not falsely ended");
        } else if ([scenario isEqual:@"transaction-failure-after-end"]) {
            p.provider.deferTransactionCompletion = YES;
            answer(p, @"first"); p.provider.reportCompletion(nil);
            answer(p, @"retry");
            p.observedCall = [CXCall new];
            p.observedCall.hasEnded = YES;
            p.observedCall.hasConnected = YES;
            p.provider.transactionCompletion([NSError errorWithDomain:@"test" code:2 userInfo:nil]);
            for (NSString *callbackId in @[@"first", @"retry"]) {
                CDVPluginResult *result = p.commandDelegate.results[callbackId];
                check(result.status == CDVCommandStatus_ERROR, @"ended transaction rejects");
                check([result.value[@"callEnded"] boolValue], @"ended flag");
                check([result.value[@"message"] isEqual:ANSWER_ERROR_CALL_ENDED], @"ended message");
            }
        } else if ([scenario isEqual:@"dismiss-during-report"]) {
            answer(p, @"first"); [p _dismissRingingCall:@"session"];
            p.provider.reportCompletion(nil);
            check(p.provider.ended.count == 1, @"dismiss registered UUID without observer");
            check(p.provider.transactions.count == 0, @"no answer after dismiss");
            check([p.endedSessionIds containsObject:@"session"], @"remember dismiss");
            check([[p.commandDelegate.results[@"first"] value][@"callEnded"] boolValue], @"ended result");
        } else if ([scenario isEqual:@"answer-after-pending-dismiss"]) {
            answer(p, @"first");
            NSUUID *uuid = p.activeCalls[@"session"][@"callUUID"];
            [p _dismissRingingCall:@"session"];
            check([[p.commandDelegate.results[@"first"] value][@"callEnded"] boolValue], @"dismiss cancels waiter immediately");
            p.observedCall = [CXCall new];
            p.observedCall.UUID = uuid;
            answer(p, @"late");
            check([p.commandDelegate.results[@"late"] status] == CDVCommandStatus_ERROR, @"pending dismiss rejects answer");
            check([[p.commandDelegate.results[@"late"] value][@"callEnded"] boolValue], @"ended result");
            check([p.activeCalls[@"session"][@"answerWaiters"] count] == 0, @"no new waiter");
            check(p.provider.transactions.count == 0, @"no answer action before completion");
            p.provider.reportCompletion(nil);
            check([p.provider.ended.lastObject isEqual:uuid], @"deferred dismiss ends original UUID");
            check(!p.activeCalls[@"session"], @"dismiss removes session");
        } else if ([scenario isEqual:@"outgoing-before-observer"]) {
            [p sendCall:[[CDVInvokedUrlCommand alloc]
                initWithArguments:@[@"Caller", @"123", @"session"]
                callbackId:@"send" className:@"" methodName:@""]];
            NSMutableDictionary *entry = p.activeCalls[@"session"];
            CXStartCallAction *action = (id)[p.provider.transactions[0] action];
            answer(p, @"first");
            check([p.commandDelegate.results[@"first"] status] == CDVCommandStatus_ERROR, @"outgoing answer rejected");
            check([[p.commandDelegate.results[@"first"] value][@"message"] isEqual:@"Not an incoming call"], @"direction error");
            check(p.activeCalls[@"session"] == entry, @"preserve outgoing entry");
            check(entry[@"callUUID"] == action.callUUID, @"preserve start UUID");
            check(p.provider.reports.count == 0 && p.provider.transactions.count == 1, @"no incoming report or answer");
            check(!p.commandDelegate.results[@"send"], @"outgoing command remains pending");
            [p resolveCommandForSessionId:@"session" actionUUIDString:action.UUID.UUIDString];
            check([p.commandDelegate.results[@"send"] status] == CDVCommandStatus_OK, @"outgoing command still resolves");
        } else if ([scenario isEqual:@"activated-end-observer-lag"]) {
            for (NSNumber *observed in @[@YES, @NO]) {
                for (NSNumber *otherActive in @[@YES, @NO]) {
                    for (NSNumber *programmatic in @[@YES, @NO]) {
                        p = [CordovaCall new];
                        [pendingCallResponses removeAllObjects];
                        callbackIds[@"hangup"] = [NSMutableArray arrayWithObject:@"hangup"];
                        answer(p, @"answer"); p.provider.reportCompletion(nil);
                        NSMutableDictionary *entry = p.activeCalls[@"session"];
                        CXAnswerCallAction *answerAction = [p.provider.transactions[0] action];
                        [entry[@"pendingActivateAudioSessionEmits"]
                            addObject:@{@"uuid": answerAction.UUID.UUIDString, @"type": @"answer"}];
                        [p processPendingActivateEmitsSkippingTypes:[NSSet setWithObject:@"sendCall"]
                            source:@"didActivateAudioSession"];
                        if (observed.boolValue) {
                            p.observedCall = [CXCall new];
                            p.observedCall.UUID = entry[@"callUUID"];
                        }
                        if (otherActive.boolValue) {
                            CXCall *other = [CXCall new];
                            other.UUID = [NSUUID UUID];
                            p.provider.calls = @[other];
                        }
                        CXEndCallAction *action;
                        if (programmatic.boolValue) {
                            [p endCall:[[CDVInvokedUrlCommand alloc] initWithArguments:@[@"session"]
                                callbackId:@"end" className:@"" methodName:@""]];
                            check(p.provider.transactions.count == 2, @"activated call uses end transaction");
                            action = (id)[p.provider.transactions.lastObject action];
                        } else {
                            action = [[CXEndCallAction alloc] initWithCallUUID:entry[@"callUUID"]];
                        }
                        [p provider:p.provider performEndCallAction:action];
                        if (!otherActive.boolValue) {
                            check(p.activeCalls[@"session"] == entry, @"retain activated call until deactivation");
                            check(!p.commandDelegate.results[@"hangup"] && !p.commandDelegate.results[@"end"],
                                @"hangup and end await deactivation");
                            [p provider:p.provider didDeactivateAudioSession:nil];
                        }
                        check(p.commandDelegate.results[@"hangup"] != nil, @"activated call emits hangup");
                        if (programmatic.boolValue) {
                            check(p.commandDelegate.results[@"end"].status == CDVCommandStatus_OK, @"end resolves");
                        }
                        check(pendingCallResponses.count == 0, @"activated call must not emit reject");
                        check(!p.activeCalls[@"session"], @"ended call removed");
                        check([p.endedSessionIds containsObject:@"session"], @"ended call remembered");
                    }
                }
            }
        } else if ([scenario isEqual:@"end-during-report"] ||
                   [scenario isEqual:@"end-during-report-after-activation"]) {
            answer(p, @"first");
            NSUUID *uuid = p.activeCalls[@"session"][@"callUUID"];
            if ([scenario isEqual:@"end-during-report-after-activation"]) {
                p.observedCall = [CXCall new];
                p.observedCall.UUID = uuid;
                answer(p, @"retry");
                CXAnswerCallAction *action = [p.provider.transactions[0] action];
                [p.activeCalls[@"session"][@"pendingActivateAudioSessionEmits"]
                    addObject:@{@"uuid": action.UUID.UUIDString, @"type": @"answer"}];
                [p processPendingActivateEmitsSkippingTypes:[NSSet setWithObject:@"sendCall"]
                    source:@"didActivateAudioSession"];
                p.observedCall = nil;
            }
            NSUInteger transactionsBeforeEnd = p.provider.transactions.count;
            [p endCall:[[CDVInvokedUrlCommand alloc] initWithArguments:@[@"session"]
                callbackId:@"end" className:@"" methodName:@""]];
            if ([scenario isEqual:@"end-during-report-after-activation"]) {
                check(!p.commandDelegate.results[@"end"], @"activated end waits for action and deactivation");
            } else {
                check([p.commandDelegate.results[@"end"] status] == CDVCommandStatus_OK, @"end accepted");
            }
            check(![p.activeCalls[@"session"][@"pendingAnswer"] boolValue], @"pending answer canceled");
            check(p.provider.ended.count == 0, @"end waits for registration");
            answer(p, @"late");
            check([[p.commandDelegate.results[@"late"] value][@"callEnded"] boolValue], @"end blocks answer retry");
            if ([scenario isEqual:@"end-during-report"]) {
                check([[p.commandDelegate.results[@"first"] value][@"callEnded"] boolValue], @"pending answer rejected");
            }
            p.provider.reportCompletion(nil);
            if ([scenario isEqual:@"end-during-report-after-activation"]) {
                check(p.provider.transactions.count == transactionsBeforeEnd + 1, @"activated end uses transaction");
                check(!p.commandDelegate.results[@"end"], @"activated end awaits deactivation");
                CXEndCallAction *action = (id)[p.provider.transactions.lastObject action];
                check([action.callUUID isEqual:uuid], @"end uses activated UUID");
                [p provider:p.provider performEndCallAction:action];
                [p provider:p.provider didDeactivateAudioSession:nil];
                check(p.commandDelegate.results[@"end"].status == CDVCommandStatus_OK, @"activated end resolves");
                check(p.provider.ended.count == 0, @"end action owns activated teardown");
            } else {
                check(p.provider.transactions.count == transactionsBeforeEnd, @"report cannot submit another answer");
                check([p.provider.ended.lastObject isEqual:uuid], @"end registered UUID");
            }
            check(!p.activeCalls[@"session"] && [p.endedSessionIds containsObject:@"session"], @"end tears down session");
        } else if ([scenario isEqual:@"end-after-report-empty-observer"]) {
            answer(p, @"first"); p.provider.reportCompletion(nil);
            NSUUID *uuid = p.activeCalls[@"session"][@"callUUID"];
            [p endCall:[[CDVInvokedUrlCommand alloc] initWithArguments:@[@"session"]
                callbackId:@"end" className:@"" methodName:@""]];
            check([[p.commandDelegate.results[@"first"] value][@"callEnded"] boolValue], @"end rejects answer waiter");
            check(p.provider.transactions.count == 2, @"submit end transaction");
            CXEndCallAction *action = (id)[p.provider.transactions.lastObject action];
            check([action isKindOfClass:[CXEndCallAction class]], @"end action");
            check(action.callUUID == uuid, @"use stored UUID");
            answer(p, @"late");
            check([[p.commandDelegate.results[@"late"] value][@"callEnded"] boolValue], @"end blocks retries");
        } else if ([scenario isEqual:@"canceled-report-failure"]) {
            for (NSString *cancel in @[@"end", @"dismiss"]) {
                p = [CordovaCall new];
                answer(p, @"first");
                if ([cancel isEqual:@"end"]) {
                    [p endCall:[[CDVInvokedUrlCommand alloc] initWithArguments:@[@"session"]
                        callbackId:@"end" className:@"" methodName:@""]];
                } else {
                    [p _dismissRingingCall:@"session"];
                }
                p.provider.reportCompletion([NSError errorWithDomain:@"test" code:1 userInfo:nil]);
                check(!p.activeCalls[@"session"], @"failed report removed");
                answer(p, @"late");
                check([[p.commandDelegate.results[@"late"] value][@"callEnded"] boolValue], @"cancellation survives report failure");
                check(p.provider.reports.count == 1, @"no resurrection after failed report");
            }
        } else if ([scenario isEqual:@"end-before-entry"]) {
            [p endCall:[[CDVInvokedUrlCommand alloc] initWithArguments:@[@"session"]
                callbackId:@"end" className:@"" methodName:@""]];
            check(p.commandDelegate.results[@"end"].status == CDVCommandStatus_OK, @"unknown end remains successful");
            check([p.endedSessionIds containsObject:@"session"], @"end records canceled session");
            answer(p, @"late");
            check([p.commandDelegate.results[@"late"].value[@"callEnded"] boolValue], @"reject stale answer");
            check(p.provider.reports.count == 0, @"stale answer cannot report canceled call");
            __block int completed = 0;
            [p pushRegistry:nil didReceiveIncomingPushWithPayload:push() forType:nil
                withCompletionHandler:^{ completed++; }];
            check(completed == 1 && !p.commandDelegate.results[@"push"], @"stale push discarded");
            check(!p.activeCalls[@"session"] && [p.endedSessionIds containsObject:@"session"],
                @"stale push cannot recreate canceled entry");
            NSUUID *dummy = p.provider.reports.lastObject;
            p.provider.reportCompletion(nil);
            check([p.provider.ended.lastObject isEqual:dummy], @"dummy call ended");
            receive(p, @"fresh"); p.provider.reportCompletion(nil);
            check(![p.endedSessionIds containsObject:@"session"], @"explicit receive clears cancellation");
            answer(p, @"fresh-answer");
            check(p.provider.transactions.count == 1 && !p.commandDelegate.results[@"fresh-answer"],
                @"explicit receive permits a fresh answer");
        } else if ([scenario isEqual:@"dismiss-before-entry"]) {
            check(![p _dismissRingingCall:nil] && ![p _dismissRingingCall:@""] &&
                ![p _dismissRingingCall:(id)[NSNull null]], @"invalid dismiss ignored");
            check([p _dismissRingingCall:@"session"], @"remember valid dismiss");
            check([p.endedSessionIds containsObject:@"session"], @"record ended session");
            answer(p, @"late");
            check([[p.commandDelegate.results[@"late"] value][@"callEnded"] boolValue], @"reject stale answer");
            check(p.provider.reports.count == 0, @"answer does not resurrect call");
            __block int completed = 0;
            [p pushRegistry:nil didReceiveIncomingPushWithPayload:push() forType:nil
                withCompletionHandler:^{ completed++; }];
            check(completed == 1 && !p.commandDelegate.results[@"push"], @"stale push discarded");
            NSUUID *dummy = p.provider.reports.lastObject;
            p.provider.reportCompletion(nil);
            check([p.provider.ended.lastObject isEqual:dummy], @"dummy call ended");
            check(!p.activeCalls[@"session"], @"no entry created");
        } else if ([scenario isEqual:@"dismiss-connected"]) {
            p.activeCalls[@"session"] = [p newActiveCallEntryWithUUID:[NSUUID UUID]];
            p.observedCall = [CXCall new];
            p.observedCall.hasConnected = YES;
            check(![p _dismissRingingCall:@"session"], @"connected call untouched");
            check(![p.endedSessionIds containsObject:@"session"], @"connected session not marked ended");
            check(p.activeCalls[@"session"] && p.provider.ended.count == 0, @"connected session preserved");
        } else if ([scenario isEqual:@"ended-answer"]) {
            [p _removeEndedCallForSessionId:@"session"]; answer(p, @"late");
            check([p.commandDelegate.results[@"late"] status] == CDVCommandStatus_ERROR, @"reject ended session");
            check([[p.commandDelegate.results[@"late"] value][@"callEnded"] boolValue], @"ended flag");
            check(p.provider.reports.count == 0, @"no resurrection");
        } else if ([scenario isEqual:@"push-after-report"]) {
            answer(p, @"first"); p.provider.reportCompletion(nil);
            NSUUID *uuid = p.activeCalls[@"session"][@"callUUID"];
            __block int completed = 0;
            [p pushRegistry:nil didReceiveIncomingPushWithPayload:push() forType:nil
                withCompletionHandler:^{ completed++; }];
            check(p.activeCalls[@"session"][@"callUUID"] == uuid, @"late observer push preserves UUID");
            check(p.provider.reports.lastObject == uuid, @"push re-reports existing UUID");
            check(completed == 1, @"push completes once");
            check(p.commandDelegate.results[@"push"] != nil, @"active push forwarded");
        } else if ([scenario isEqual:@"push-after-end"]) {
            answer(p, @"first"); p.provider.reportCompletion(nil);
            NSUUID *uuid = p.activeCalls[@"session"][@"callUUID"];
            [p _removeEndedCallForSessionId:@"session"];
            callBackUrl = @"current-call-url";
            __block int completed = 0;
            for (int attempt = 0; attempt < 2; attempt++) {
                [p pushRegistry:nil didReceiveIncomingPushWithPayload:push() forType:nil
                    withCompletionHandler:^{ completed++; }];
                check(!p.activeCalls[@"session"], @"stale push must not recreate entry");
                check([p.endedSessionIds containsObject:@"session"], @"retain ended marker");
                check(!p.commandDelegate.results[@"push"], @"do not forward stale push");
                check([callBackUrl isEqual:@"current-call-url"], @"do not overwrite callback URL");
                check(completed == attempt + 1, @"stale push completes once");
                check(![p.provider.reports.lastObject isEqual:uuid], @"dummy UUID");
                NSUUID *dummy = p.provider.reports.lastObject;
                p.provider.reportCompletion(nil);
                check([p.provider.ended.lastObject isEqual:dummy], @"dummy ended");
            }
            answer(p, @"late");
            check([[p.commandDelegate.results[@"late"] value][@"callEnded"] boolValue], @"retry stays rejected");
        } else if ([scenario isEqual:@"new-push"]) {
            [p.endedSessionIds addObject:@"another-session"];
            __block int completed = 0;
            [p pushRegistry:nil didReceiveIncomingPushWithPayload:push() forType:nil
                withCompletionHandler:^{ completed++; }];
            check(p.activeCalls[@"session"] != nil, @"new session reported");
            check(p.provider.reports.count == 1, @"one incoming report");
            check(p.commandDelegate.results[@"push"] != nil, @"new push forwarded");
            check(completed == 1, @"new push completes once");
            check([p.endedSessionIds containsObject:@"another-session"], @"unrelated ended session preserved");
        } else if ([scenario isEqual:@"already-connected"] || [scenario isEqual:@"outgoing"]) {
            p.activeCalls[@"session"] = [p newActiveCallEntryWithUUID:[NSUUID UUID]];
            p.observedCall = [CXCall new];
            p.observedCall.hasConnected = YES;
            p.observedCall.isOutgoing = [scenario isEqual:@"outgoing"];
            answer(p, @"first");
            int expected = p.observedCall.isOutgoing ? CDVCommandStatus_ERROR : CDVCommandStatus_OK;
            check([p.commandDelegate.results[@"first"] status] == expected, @"preserve connected/outgoing result");
            check(p.provider.reports.count == 0, @"no new report");
            check(p.provider.transactions.count == 0, @"no answer transaction");
        } else {
            check(NO, @"unknown scenario");
        }
        NSLog(@"PASS: %@", scenario);
    }
}
"""


METHODS = [
    "receiveCall:",
    "_reportIncomingCallForSessionId:",
    "answerCall:",
    "_requestAnswerCallActionForSessionId:",
    "_hasPendingAnswerEmitForSessionId:",
    "_settleAnswerWaitersForSessionId:",
    "sendCall:",
    "endCall:",
    "rejectPendingCommandsForSessionId:",
    "processPendingActivateEmitsSkippingTypes:",
    "performEndCallAction:",
    "didDeactivateAudioSession:",
    "resolveCommandForSessionId:",
    "_removeEndedCallForSessionId:",
    "_dismissRingingCall:",
    "_reportAndEndDummyCall",
    "newActiveCallEntryWithUUID:",
    "didReceiveIncomingPushWithPayload:",
]


class IOSCallLifecycleTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        source = SOURCE.read_text()
        methods = []
        for name in METHODS:
            start = next(
                source.index(line)
                for line in source.splitlines()
                if line.startswith("- (") and re.search(r"\b" + re.escape(name), line)
            )
            # Include only the selected production method, not comments before the next method.
            end = source.index("\n}\n", start) + len("\n}\n")
            methods.append(source[start:end])
        cls.temp = tempfile.TemporaryDirectory()
        cls.addClassCleanup(cls.temp.cleanup)
        harness = pathlib.Path(cls.temp.name) / "lifecycle.m"
        cls.binary = pathlib.Path(cls.temp.name) / "lifecycle"
        harness.write_text(PREAMBLE + "\n".join(methods) + "\n@end\n" + TESTS)
        subprocess.run(
            ["xcrun", "clang", "-fobjc-arc", "-fblocks", "-Werror",
             "-framework", "Foundation", str(harness), "-o", str(cls.binary)],
            check=True,
        )

    def test_report_answer_retry(self):
        self.run_scenario("report-answer-retry")

    def test_report_failure(self):
        self.run_scenario("report-failure")

    def test_receive_canceled_report_failure(self):
        self.run_scenario("receive-canceled-report-failure")

    def test_duplicate_report_failure(self):
        self.run_scenario("duplicate-report-failure")

    def test_coalesced_incoming_reports(self):
        self.run_scenario("coalesced-incoming-reports")

    def test_end_during_registered_duplicate(self):
        self.run_scenario("end-during-registered-duplicate")

    def test_replacement_report_completion(self):
        self.run_scenario("replacement-report-completion")

    def test_connected_end_during_report(self):
        self.run_scenario("connected-end-during-report")

    def test_ui_hangup_blocks_answer(self):
        self.run_scenario("ui-hangup-blocks-answer")

    def test_transaction_failure(self):
        self.run_scenario("transaction-failure")

    def test_replacement_answer_completion(self):
        self.run_scenario("replacement-answer-completion")

    def test_replacement_end_completion(self):
        self.run_scenario("replacement-end-completion")

    def test_ui_answer_during_transaction(self):
        self.run_scenario("ui-answer-during-transaction")

    def test_end_before_entry(self):
        self.run_scenario("end-before-entry")

    def test_pending_ui_answer(self):
        self.run_scenario("pending-ui-answer")

    def test_retry_after_activation_empty_observer(self):
        self.run_scenario("retry-after-activation-empty-observer")

    def test_retry_after_activation_unconnected_observer(self):
        self.run_scenario("retry-after-activation-unconnected-observer")

    def test_transaction_failure_after_end(self):
        self.run_scenario("transaction-failure-after-end")

    def test_dismiss_during_report(self):
        self.run_scenario("dismiss-during-report")

    def test_answer_after_pending_dismiss(self):
        self.run_scenario("answer-after-pending-dismiss")

    def test_outgoing_before_observer(self):
        self.run_scenario("outgoing-before-observer")

    def test_end_during_report(self):
        self.run_scenario("end-during-report")

    def test_end_during_report_after_activation(self):
        self.run_scenario("end-during-report-after-activation")

    def test_activated_end_observer_lag(self):
        self.run_scenario("activated-end-observer-lag")

    def test_end_after_report_empty_observer(self):
        self.run_scenario("end-after-report-empty-observer")

    def test_canceled_report_failure(self):
        self.run_scenario("canceled-report-failure")

    def test_dismiss_before_entry(self):
        self.run_scenario("dismiss-before-entry")

    def test_dismiss_connected(self):
        self.run_scenario("dismiss-connected")

    def test_ended_answer(self):
        self.run_scenario("ended-answer")

    def test_push_after_report(self):
        self.run_scenario("push-after-report")

    def test_already_connected(self):
        self.run_scenario("already-connected")

    def test_outgoing(self):
        self.run_scenario("outgoing")

    def test_push_after_end(self):
        self.run_scenario("push-after-end")

    def test_new_push(self):
        self.run_scenario("new-push")

    def run_scenario(self, name):
        subprocess.run([str(self.binary), name], check=True)


if __name__ == "__main__":
    unittest.main()
