#import <Cordova/CDVAppDelegate.h>
#import <Foundation/Foundation.h>
#import <UIKit/UIKit.h>
#import "Intents/Intents.h"
#import <CallKit/CallKit.h>
#import <objc/runtime.h>

@implementation CDVAppDelegate (CordovaCall)

// Comment out the following as it's overriding continueUserActivity in the Branch plugin
// Have to implement swizzle via cordova-plugin-ios-app-delegate-events but Branch's plugin has a custom declaration of continueUserActivity that gets called when not intended.
// - (BOOL)application:(UIApplication *)application continueUserActivity:(NSUserActivity *)userActivity restorationHandler:(void (^)(NSArray *restorableObjects))restorationHandler
// {
//     INInteraction *interaction = userActivity.interaction;
//     INIntent *intent = interaction.intent;
//     BOOL isVideo = [intent isKindOfClass:[INStartVideoCallIntent class]];
//     INPerson *contact;
//     if(isVideo) {
//         INStartVideoCallIntent *startCallIntent = (INStartVideoCallIntent *)intent;
//         contact = startCallIntent.contacts.firstObject;
//     } else {
//         INStartAudioCallIntent *startCallIntent = (INStartAudioCallIntent *)intent;
//         contact = startCallIntent.contacts.firstObject;
//     }
//     INPersonHandle *personHandle = contact.personHandle;
//     NSString *callId = personHandle.value;
//     NSString *callName = [[NSUserDefaults standardUserDefaults] stringForKey:callId];
//     if(!callName) {
//         callName = callId;
//     }
//     NSDictionary *intentInfo = @{ @"callName" : callName, @"callId" : callId, @"isVideo" : isVideo?@YES:@NO};
//     [[NSNotificationCenter defaultCenter] postNotificationName:@"RecentsCallNotification" object:intentInfo];
//     return YES;
// }
@end
