package dev.whynotac.paper.wire;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.ChannelPromise;
import io.papermc.paper.network.ConnectionEvent;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The two Netty handlers at the network edge, and where they must sit.
 *
 * <pre>
 *   network ─ splitter ─ [decompress] ─ <b>whynotac_wire_in</b> ─ (other plugins' decoders) ─ decoder ─ … ─ packet_handler
 *   network ─ prepender ─ [compress] ─ <b>whynotac_wire_out</b> ─ (other plugins' encoders) ─ encoder ─ … ─ packet_handler
 * </pre>
 * Inbound frames reach {@code whynotac_wire_in} before any other plugin can touch them; outbound frames
 * reach {@code whynotac_wire_out} after every other plugin has. Paper announces compression with a
 * {@link ConnectionEvent}; ViaVersion reacts by moving its handlers next to {@code (de)compress}, so the
 * taps move back to the outer edge one event-loop task later, after everyone has reacted. A periodic
 * audit ({@link #ensurePlacement}) repairs any other plugin that inserts itself outside the taps.
 *
 * <p>Both handlers are fail-open: whatever happens inside WhyNotAC, every frame is forwarded unchanged
 * and exactly once. They are {@code @Sharable} only so that they can be moved (Netty refuses to re-add
 * a non-sharable handler); each instance belongs to one connection.
 */
public final class WireTaps {

    public static final String INBOUND = "whynotac_wire_in";
    public static final String OUTBOUND = "whynotac_wire_out";

    private WireTaps() {}

    /** Adds both taps to a fresh connection (right after the game built its pipeline). */
    public static void install(ChannelPipeline pipeline, WireObserver observer, Logger logger) {
        Inbound inbound = new Inbound(observer, logger);
        Outbound outbound = new Outbound(observer, logger);
        pipeline.addAfter(inboundAnchor(pipeline), INBOUND, inbound);
        pipeline.addAfter(outboundAnchor(pipeline), OUTBOUND, outbound);
    }

    /** Removes both taps if present (e.g. a server-list ping, or a client the edge cannot decode). */
    public static void remove(ChannelPipeline pipeline) {
        if (pipeline.get(INBOUND) != null) {
            pipeline.remove(INBOUND);
        }
        if (pipeline.get(OUTBOUND) != null) {
            pipeline.remove(OUTBOUND);
        }
    }

    /**
     * Moves the taps back to the outer edge if anything got between them and (de)compression. Returns
     * true if something was repaired. Must run on the connection's event loop.
     */
    public static boolean ensurePlacement(ChannelPipeline pipeline) {
        ChannelHandler inbound = pipeline.get(INBOUND);
        ChannelHandler outbound = pipeline.get(OUTBOUND);
        if (!(inbound instanceof Inbound tap) || outbound == null) {
            return false; // absent, or left over from another plugin instance (it removes itself)
        }
        WireObserver observer = tap.observer;
        observer.moving(true);
        try {
            return repair(pipeline, observer);
        } finally {
            observer.moving(false);
        }
    }

    private static boolean repair(ChannelPipeline pipeline, WireObserver observer) {
        String inAnchor = inboundAnchor(pipeline);
        String outAnchor = outboundAnchor(pipeline);
        if (inAnchor == null || outAnchor == null) {
            // Another plugin replaced the game's framing handlers: the edge can no longer be located.
            observer.edgeLost("the game's framing handlers were replaced by another plugin", System.nanoTime());
            remove(pipeline);
            return true;
        }
        boolean repaired = false;
        repaired |= move(pipeline, INBOUND, inAnchor, observer);
        repaired |= move(pipeline, OUTBOUND, outAnchor, observer);
        if (repaired) {
            observer.placementRepaired();
        }
        return repaired;
    }

    /** Moves {@code name} directly after {@code anchor} if it is not already there; never leaves it removed. */
    private static boolean move(ChannelPipeline pipeline, String name, String anchor, WireObserver observer) {
        List<String> names = pipeline.names();
        if (names.indexOf(name) == names.indexOf(anchor) + 1) {
            return false;
        }
        ChannelHandler handler = pipeline.remove(name);
        try {
            pipeline.addAfter(anchor, name, handler);
        } catch (RuntimeException e) {
            observer.edgeLost("edge tap " + name + " could not be re-placed: " + e, System.nanoTime());
            remove(pipeline);
        }
        return true;
    }

    /** The handler the inbound tap must follow, or null if neither framing handler exists any more. */
    private static String inboundAnchor(ChannelPipeline pipeline) {
        return pipeline.get("decompress") != null ? "decompress" : pipeline.get("splitter") != null ? "splitter" : null;
    }

    private static String outboundAnchor(ChannelPipeline pipeline) {
        return pipeline.get("compress") != null ? "compress" : pipeline.get("prepender") != null ? "prepender" : null;
    }

    @ChannelHandler.Sharable
    static final class Inbound extends ChannelInboundHandlerAdapter {
        final WireObserver observer;
        private final Logger logger;
        private int failures;

        Inbound(WireObserver observer, Logger logger) {
            this.observer = observer;
            this.logger = logger;
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
            if (!observer.active()) {
                observer.detaching(); // WhyNotAC was disabled (or reloaded) while this connection was being accepted
                ctx.fireChannelRead(msg);
                remove(ctx.pipeline());
                return;
            }
            WireObserver.InboundAction action = WireObserver.InboundAction.CONTINUE;
            if (msg instanceof ByteBuf frame) {
                try {
                    action = observer.onWireInbound(frame, System.nanoTime());
                } catch (RuntimeException | LinkageError e) {
                    failures = report(logger, failures, "inbound frame", e);
                    try {
                        observer.inboundFailure(frame, System.nanoTime());
                    } catch (RuntimeException | LinkageError ignored) {
                        // already reported; the frame is forwarded regardless
                    }
                }
            }
            ctx.fireChannelRead(msg);
            if (action == WireObserver.InboundAction.DETACH) {
                remove(ctx.pipeline());
            }
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) throws Exception {
            try {
                observer.channelInactive(System.nanoTime());
            } catch (RuntimeException | LinkageError e) {
                failures = report(logger, failures, "disconnect", e);
            }
            ctx.fireChannelInactive();
        }

        @Override
        public void handlerRemoved(ChannelHandlerContext ctx) {
            observer.tapRemoved(INBOUND, System.nanoTime());
        }

        @Override
        public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
            if (evt instanceof ConnectionEvent) {
                // Let every handler react first (ViaVersion moves itself synchronously), then restore the edge.
                ChannelPipeline pipeline = ctx.pipeline();
                ctx.channel().eventLoop().execute(() -> {
                    try {
                        ensurePlacement(pipeline);
                    } catch (RuntimeException e) {
                        failures = report(logger, failures, "tap placement", e);
                    }
                });
            }
            ctx.fireUserEventTriggered(evt);
        }
    }

    @ChannelHandler.Sharable
    static final class Outbound extends ChannelOutboundHandlerAdapter {
        private final WireObserver observer;
        private final Logger logger;
        private int failures;

        Outbound(WireObserver observer, Logger logger) {
            this.observer = observer;
            this.logger = logger;
        }

        @Override
        public void handlerRemoved(ChannelHandlerContext ctx) {
            observer.tapRemoved(OUTBOUND, System.nanoTime());
        }

        @Override
        public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
            if (!(msg instanceof ByteBuf frame)) {
                ctx.write(msg, promise);
                return;
            }
            WireObserver.Outgoing outgoing = WireObserver.Outgoing.STRUCTURAL;
            try {
                outgoing = observer.beforeWireOutbound(frame, System.nanoTime());
                if (outgoing.sandwich()) {
                    observer.writeEdgeTransaction(ctx, System.nanoTime());
                }
            } catch (RuntimeException | LinkageError e) {
                failures = report(logger, failures, "outbound frame", e);
                try {
                    outgoing = observer.outboundFailure(frame, System.nanoTime());
                } catch (RuntimeException | LinkageError ignored) {
                    outgoing = WireObserver.Outgoing.STRUCTURAL;
                }
            }
            long sent = System.nanoTime();
            ctx.write(msg, promise);
            try {
                observer.commitOutbound(outgoing, sent);
                if (outgoing.sandwich()) {
                    observer.writeEdgeTransaction(ctx, System.nanoTime());
                }
            } catch (RuntimeException | LinkageError e) {
                failures = report(logger, failures, "outbound frame", e);
            }
        }
    }

    /** Rate-limited warning; returns the new failure count. */
    private static int report(Logger logger, int failures, String what, Throwable error) {
        int count = failures + 1;
        if (count <= 5 || Integer.bitCount(count) == 1) {
            logger.log(Level.WARNING, "WhyNotAC edge tap failed on " + what + " (failure #" + count + "); forwarded unchanged", error);
        }
        return count;
    }
}
