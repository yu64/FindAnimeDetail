package app.config;

import com.linecorp.bot.model.event.*;
import com.linecorp.bot.model.event.beacon.BeaconContent;
import com.linecorp.bot.model.event.link.LinkContent;
import com.linecorp.bot.model.event.message.*;
import com.linecorp.bot.model.event.postback.PostbackContent;
import com.linecorp.bot.model.event.source.*;
import com.linecorp.bot.model.event.things.*;
import com.linecorp.bot.model.event.things.result.*;
import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * LINE SDK内部のリフレクションをAOTコンパイルに対応させるための設定
 */
@RegisterForReflection(targets = {
    CallbackRequest.class, DeliveryContext.class, EventMode.class,
    AccountLinkEvent.class, BeaconEvent.class, FollowEvent.class, JoinEvent.class,
    LeaveEvent.class, MemberJoinedEvent.class, MemberLeftEvent.class, MessageEvent.class,
    PostbackEvent.class, ThingsEvent.class, UnfollowEvent.class, UnknownEvent.class,
    UnsendEvent.class, VideoPlayCompleteEvent.class,
    BeaconContent.class, LinkContent.class, PostbackContent.class,
    AudioMessageContent.class, ContentProvider.class, FileMessageContent.class,
    ImageMessageContent.class, ImageSet.class, LocationMessageContent.class,
    StickerMessageContent.class, TextMessageContent.class, UnknownMessageContent.class,
    VideoMessageContent.class,
    GroupSource.class, RoomSource.class, UnknownSource.class, UserSource.class,
    LinkThingsContent.class, ScenarioResultThingsContent.class, UnlinkThingsContent.class,
    UnknownLineThingsContent.class, BinaryActionResult.class, ScenarioResult.class,
    UnknownActionResult.class, VoidActionResult.class
})
public final class LineWebhookReflection {
    private LineWebhookReflection() {}
}
