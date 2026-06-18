#import <Foundation/Foundation.h>
#import "PanelClient.h"
@import UIKit;

@class PanelClient, Panel;

typedef NS_ENUM(NSInteger, PanelState) {
  PanelStateReady
};

@protocol PanelStore
- (Panel *)loadPanel:(NSString *)panelId;
@end

@interface PanelService : NSObject <PanelStore>
+ (instancetype)sharedService;
- (Panel *)loadPanel:(NSString *)panelId;
@end

@implementation PanelService
+ (instancetype)sharedService {
  return [[self alloc] init];
}

- (Panel *)loadPanel:(NSString *)panelId {
  return [Panel new];
}
@end

@implementation PanelService (Testing)
- (void)resetForTesting {
}
@end
