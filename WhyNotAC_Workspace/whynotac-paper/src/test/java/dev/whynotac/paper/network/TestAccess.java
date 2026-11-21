package dev.whynotac.paper.network;

import dev.whynotac.paper.wire.WireObserver;
import dev.whynotac.protocol.session.PlayerSession;
import io.netty.channel.ChannelHandler;
import java.util.logging.Logger;

/** Lets tests in other packages build the package-private game-side handler. */
public final class TestAccess {

    private TestAccess() {}

    public static ChannelHandler handler(PlayerSession session, WireObserver observer, int selfEntityId) {
        WhyNotChannelHandler handler = new WhyNotChannelHandler(session, new PacketTranslator(), observer, Logger.getAnonymousLogger());
        handler.selfEntityId(selfEntityId);
        return handler;
    }
}
