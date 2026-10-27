"""Run production lifecycle methods with deterministic CallKit/PushKit doubles on macOS."""

import pathlib
import subprocess
import tempfile
import unittest


ROOT = pathlib.Path(__file__).resolve().parents[1]
SOURCE = ROOT / "src/ios/CordovaCall.m"

PREAMBLE = r"""
#import <Foundation/Foundation.h>

enum { CDVCommandStatus_OK, CDVCommandStatus_ERROR };
enum { CXHandleTypePhoneNumber, CXCallEndedReasonRemoteEnded, CXCallEndedReasonFailed };
BOOL enableDTMF = YES, hasVideo = NO;
NSString *callBackUrl;
NSString *ANSWER_ERROR_CALL_ENDED = @"call ended";
NSMutableDictionary *callbackIds;

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
@end
@implementation CXAnswerCallAction
- (instancetype)initWithCallUUID:(NSUUID *)uuid {
    self = [super init]; self.UUID = [NSUUID UUID]; self.callUUID = uuid; return self;
}
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
@property (copy) void (^reportCompletion)(NSError *);
@property NSError *transactionError;
- (void)reportNewIncomingCallWithUUID:(NSUUID *)uuid update:(CXCallUpdate *)update
                         completion:(void (^)(NSError *))completion;
- (void)reportCallWithUUID:(NSUUID *)uuid endedAtDate:(NSDate *)date reason:(int)reason;
- (void)requestTransaction:(CXTransaction *)transaction completion:(void (^)(NSError *))completion;
@end
@implementation FakeCallKit
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
    [self.transactions addObject:transaction]; completion(self.transactionError);
}
@end

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
- (CXCall *)callForSessionId:(NSString *)sessionId;
- (void)rejectPendingCommandsForSessionId:(NSString *)sessionId;
@end
@implementation CordovaCall
- (instancetype)init {
    self = [super init]; self.activeCalls = [NSMutableDictionary new];
    self.endedSessionIds = [NSMutableSet new]; self.provider = [FakeCallKit new];
    self.callController = self.provider; self.commandDelegate = [FakeDelegate new];
    self.VoIPPushCallbackId = @"push"; return self;
}
- (void)logMessage:(NSString *)message {}
- (CXCall *)callForSessionId:(NSString *)sessionId { return self.observedCall; }
- (void)rejectPendingCommandsForSessionId:(NSString *)sessionId {
    [self _settleAnswerWaitersForSessionId:sessionId errorMessage:ANSWER_ERROR_CALL_ENDED];
}
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
static PKPushPayload *push(void) {
    PKPushPayload *payload = [PKPushPayload new];
    payload.dictionaryPayload = @{@"data": @{@"payload":
        @"{\"session_id\":\"session\",\"from\":\"Caller\",\"cid\":\"123\"}"}};
    return payload;
}
int main(int argc, const char *argv[]) {
    @autoreleasepool {
        callbackIds = [NSMutableDictionary new];
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
        } else if ([scenario isEqual:@"report-failure"]) {
            answer(p, @"first");
            p.provider.reportCompletion([NSError errorWithDomain:@"test" code:1 userInfo:nil]);
            check([p.commandDelegate.results[@"first"] status] == CDVCommandStatus_ERROR, @"report failure rejects");
            check(p.provider.transactions.count == 0, @"no answer after failed report");
            check(!p.activeCalls[@"session"], @"failed report removed");
        } else if ([scenario isEqual:@"transaction-failure"]) {
            p.provider.transactionError = [NSError errorWithDomain:@"test" code:2 userInfo:nil];
            answer(p, @"first"); p.provider.reportCompletion(nil);
            check([p.commandDelegate.results[@"first"] status] == CDVCommandStatus_ERROR, @"transaction failure rejects");
            check(![[p.commandDelegate.results[@"first"] value][@"callEnded"] boolValue], @"not falsely ended");
        } else if ([scenario isEqual:@"dismiss-during-report"]) {
            answer(p, @"first"); [p _dismissRingingCall:@"session"];
            p.provider.reportCompletion(nil);
            check(p.provider.ended.count == 1, @"dismiss registered UUID without observer");
            check(p.provider.transactions.count == 0, @"no answer after dismiss");
            check([p.endedSessionIds containsObject:@"session"], @"remember dismiss");
            check([[p.commandDelegate.results[@"first"] value][@"callEnded"] boolValue], @"ended result");
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
                if line.startswith("- (") and name in line
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

    def test_transaction_failure(self):
        self.run_scenario("transaction-failure")

    def test_dismiss_during_report(self):
        self.run_scenario("dismiss-during-report")

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
