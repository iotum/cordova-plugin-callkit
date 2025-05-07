#import "AppDelegate.h"
#import "Intents/Intents.h"
#import <CallKit/CallKit.h>
#import <objc/runtime.h>
#import "AppDelegate+APPAppEvent.h"

@implementation AppDelegate (CordovaCall)

- (void)pluginInitialize {
  [[NSNotificationCenter defaultCenter] 
    addObserver:self 
    selector:@selector(cordovaCallContinueUserActivityHandler:) 
    name:UIApplicationContinueUserActivity object:nil
  ];
}

- (void) cordovaCallContinueUserActivityHandler:(NSNotification*)notification
{
    NSUserActivity* userActivity = notification.object;
    INInteraction *interaction = userActivity.interaction;
    INIntent *intent = interaction.intent;
    BOOL isVideo = [intent isKindOfClass:[INStartVideoCallIntent class]];
    INPerson *contact;
    if(isVideo) {
        INStartVideoCallIntent *startCallIntent = (INStartVideoCallIntent *)intent;
        contact = startCallIntent.contacts.firstObject;
    } else {
        INStartAudioCallIntent *startCallIntent = (INStartAudioCallIntent *)intent;
        contact = startCallIntent.contacts.firstObject;
    }
    INPersonHandle *personHandle = contact.personHandle;
    NSString *callId = personHandle.value;
    NSString *callName = [[NSUserDefaults standardUserDefaults] stringForKey:callId];
    if(!callName) {
        callName = callId;
    }
    NSDictionary *intentInfo = @{ @"callName" : callName, @"callId" : callId, @"isVideo" : isVideo?@YES:@NO};
    [[NSNotificationCenter defaultCenter] postNotificationName:@"RecentsCallNotification" object:intentInfo];
}
@end
