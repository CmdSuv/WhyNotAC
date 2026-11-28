package dev.whynotac.analysis.checks;

import dev.whynotac.analysis.evidence.Admissibility;
import dev.whynotac.analysis.evidence.AdmissibilityTracker;
import dev.whynotac.analysis.evidence.Evidence;
import dev.whynotac.analysis.evidence.EvidenceClass;
import dev.whynotac.analysis.evidence.EvidenceSink;
import dev.whynotac.protocol.packet.ClientboundPacket;
import dev.whynotac.protocol.packet.ServerboundPacket;
import dev.whynotac.protocol.session.PlayerSession;
import dev.whynotac.protocol.session.TimelineEvent;
import dev.whynotac.protocol.session.TimelineListener;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * Transaction causality ({@code transaction-order}, P-M, SHADOW): detects selective transaction holding,
 * skipped transactions, and duplicate/out-of-order transaction answers (anti-knockback / ping-spoof / blink
 * cheats that hold, drop, reorder, or replay {@code Pong} packets).
 *
 * <p>Soundness (decompiled 1.21.11 client):
 * <ul>
 *   <li>All server packets arrive over one ordered TCP stream and are queued to {@code PacketProcessor}.
 *       {@code Minecraft.runTick(true)} drains {@code processQueuedPackets()} completely at the start of every frame,
 *       before running {@code min(10, m)} ticks (each ending with {@code ClientTickEnd}).</li>
 *   <li>{@code handlePing} and {@code handleMovePlayer} both run inside {@code processQueuedPackets()} on the main
 *       thread. If the client sends {@code AcceptTeleportation} for a teleport sent after an earlier {@code Ping},
 *       that {@code Ping} was already processed and its {@code Pong} already queued before {@code AcceptTeleportation}.
 *       Receiving its {@code Pong} afterwards ({@code ping-after-later-teleport-accept}) violates vanilla order.</li>
 *   <li>{@code handleKeepAlive} runs on the client's network thread. When the client returns {@code KeepAlive(k)}, any
 *       earlier {@code Ping(p)} has already entered the client's socket ahead of {@code KeepAlive(k)}. At most one
 *       frame already in progress ({@code <= 10} ticks, plus {@code 2} ticks slack for network- vs main-thread write
 *       ordering) can end with {@code ClientTickEnd} before the next frame's {@code processQueuedPackets()} drains and
 *       answers {@code Ping(p)} ({@code ping-delayed-past-keepalive}). Client freezes drop 0 {@code ClientTickEnd}s
 *       during the freeze and never violate this bound.</li>
 *   <li>Every WhyNotAC {@code Ping} carries a unique {@code SecureRandom} 32-bit id outside {@code [-32768, 32767]}.
 *       Over ordered TCP, {@code handlePing} answers every {@code Ping} strictly once in FIFO order: answering a later
 *       WhyNotAC {@code Ping} while skipping an earlier one sent in the same play lifecycle
 *       ({@code skipped-transaction}), or answering the same WhyNotAC {@code Ping} twice / out of order after it was
 *       already acknowledged or skipped ({@code duplicate-transaction}), is impossible for vanilla 1.21.11.</li>
 * </ul>
 */
public final class TransactionOrderCheck implements TimelineListener {

    public static final String ID = "transaction-order";
    public static final int VERSION = 2;
    static final int MAX_TICKS_AFTER_KEEPALIVE = 12;
    static final int MAX_PENDING_PINGS = 4_096;
    static final int MAX_ACKNOWLEDGED_PINGS = 512;
    static final int MAX_PENDING_TELEPORTS = 1_024;
    static final int MAX_PENDING_KEEPALIVES = 256;
    static final long REPORT_INTERVAL_NANOS = 1_000_000_000L;

    private static final PlayerSession.AttachmentKey STATE = new PlayerSession.AttachmentKey();

    private final EvidenceSink sink;

    public TransactionOrderCheck(EvidenceSink sink) {
        this.sink = sink;
    }

    private static final class TrackedPing {
        final long sentSeq;
        final long sentNano;
        int ticksAfterKeepAlive = -1;
        long keepAliveSeq = Long.MIN_VALUE;

        TrackedPing(long sentSeq, long sentNano) {
            this.sentSeq = sentSeq;
            this.sentNano = sentNano;
        }
    }

    private static final class State {
        final AdmissibilityTracker admissibility = new AdmissibilityTracker();
        final Map<Integer, TrackedPing> pendingPings = boundedMap(MAX_PENDING_PINGS);
        final Map<Integer, TrackedPing> rememberedPings = boundedMap(MAX_ACKNOWLEDGED_PINGS);
        final Map<Integer, Long> pendingTeleports = boundedMap(MAX_PENDING_TELEPORTS);
        final Map<Long, Long> pendingKeepAlives = boundedMap(MAX_PENDING_KEEPALIVES);
        boolean loaded;
        boolean hasLastPongId;
        int lastPongId;
        long acceptedTeleportSeq = Long.MIN_VALUE;
        long lastReportNanos = Long.MIN_VALUE;
    }

    private static <K, V> Map<K, V> boundedMap(int max) {
        return new LinkedHashMap<>(16, 0.75F, false) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
                return size() > max;
            }
        };
    }

    @Override
    public boolean readsEntityMotion() {
        return false;
    }

    @Override
    public void onEvent(PlayerSession session, TimelineEvent event) {
        State state = session.attachment(STATE, State::new);
        state.admissibility.onEvent(event);
        switch (event) {
            case TimelineEvent.Gap ignored -> clearPendingAndRemembered(state);
            case TimelineEvent.Outbound out -> onOutbound(state, out);
            case TimelineEvent.Inbound in -> onInbound(session, state, in);
            case TimelineEvent.Acknowledged ack -> onAcknowledged(session, state, ack);
            case TimelineEvent.Anomaly anomaly -> onAnomaly(session, state, anomaly);
            default -> { }
        }
    }

    private static void onOutbound(State state, TimelineEvent.Outbound out) {
        switch (out.packet()) {
            case ClientboundPacket.Login ignored -> reset(state);
            case ClientboundPacket.Respawn ignored -> reset(state);
            case ClientboundPacket.Unmodeled u when "minecraft:start_configuration".equals(u.type()) -> reset(state);
            case ClientboundPacket.Ping ping -> state.pendingPings.put(ping.id(), new TrackedPing(out.sequence(), out.nanoTime()));
            case ClientboundPacket.PlayerPosition pos -> state.pendingTeleports.put(pos.teleportId(), out.sequence());
            case ClientboundPacket.KeepAlive ka -> state.pendingKeepAlives.put(ka.id(), out.sequence());
            default -> { }
        }
    }

    private void onInbound(PlayerSession session, State state, TimelineEvent.Inbound in) {
        switch (in.packet()) {
            case ServerboundPacket.PlayerLoaded ignored -> state.loaded = true;
            case ServerboundPacket.Pong pong -> {
                state.hasLastPongId = true;
                state.lastPongId = pong.id();
            }
            case ServerboundPacket.AcceptTeleportation accept -> {
                Long teleportSeq = state.pendingTeleports.remove(accept.teleportId());
                if (teleportSeq != null && teleportSeq > state.acceptedTeleportSeq) {
                    state.acceptedTeleportSeq = teleportSeq;
                }
            }
            case ServerboundPacket.KeepAlive ka -> {
                Long kaSeq = state.pendingKeepAlives.remove(ka.id());
                if (kaSeq != null && state.loaded) {
                    for (TrackedPing ping : state.pendingPings.values()) {
                        if (ping.sentSeq < kaSeq && ping.ticksAfterKeepAlive < 0) {
                            ping.ticksAfterKeepAlive = 0;
                            ping.keepAliveSeq = in.sequence();
                        }
                    }
                }
            }
            case ServerboundPacket.ClientTickEnd ignored -> onTickEnd(session, state, in);
            default -> { }
        }
    }

    private void onTickEnd(PlayerSession session, State state, TimelineEvent.Inbound tickEnd) {
        if (!state.loaded) {
            return;
        }
        Iterator<Map.Entry<Integer, TrackedPing>> it = state.pendingPings.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Integer, TrackedPing> entry = it.next();
            TrackedPing ping = entry.getValue();
            if (ping.ticksAfterKeepAlive < 0) {
                continue;
            }
            ping.ticksAfterKeepAlive++;
            if (ping.ticksAfterKeepAlive > MAX_TICKS_AFTER_KEEPALIVE) {
                int id = entry.getKey();
                it.remove();
                Map<String, String> details = new TreeMap<>();
                details.put("reason", "ping-delayed-past-keepalive");
                details.put("ticksAfterKeepAlive", Integer.toString(ping.ticksAfterKeepAlive));
                details.put("pingSentSequence", Long.toString(ping.sentSeq));
                details.put("keepAliveSequence", Long.toString(ping.keepAliveSeq));
                report(session, state, ping.sentSeq, ping.sentNano, tickEnd.sequence(), tickEnd.nanoTime(),
                        "held transaction " + id + " for " + ping.ticksAfterKeepAlive + " client ticks past a later KeepAlive (at most "
                                + MAX_TICKS_AFTER_KEEPALIVE + " possible)",
                        details);
            }
        }
    }

    private void onAcknowledged(PlayerSession session, State state, TimelineEvent.Acknowledged ack) {
        TrackedPing ping = state.pendingPings.remove(ack.transactionId());
        if (ping == null) {
            return;
        }
        state.rememberedPings.put(ack.transactionId(), ping);
        TrackedPing earliestSkipped = null;
        Iterator<Map.Entry<Integer, TrackedPing>> it = state.pendingPings.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Integer, TrackedPing> entry = it.next();
            if (entry.getValue().sentSeq < ping.sentSeq) {
                if (earliestSkipped == null || entry.getValue().sentSeq < earliestSkipped.sentSeq) {
                    earliestSkipped = entry.getValue();
                }
                state.rememberedPings.put(entry.getKey(), entry.getValue());
                it.remove();
            }
        }
        if (state.loaded && ack.skipped() > 0 && earliestSkipped != null) {
            Map<String, String> details = new TreeMap<>();
            details.put("reason", "skipped-transaction");
            details.put("skippedCount", Integer.toString(ack.skipped()));
            details.put("skippedPingSentSequence", Long.toString(earliestSkipped.sentSeq));
            details.put("answeredPingSequence", Long.toString(ping.sentSeq));
            report(session, state, earliestSkipped.sentSeq, earliestSkipped.sentNano, ack.sequence(), ack.nanoTime(),
                    "answered a newer transaction while skipping " + ack.skipped() + " earlier unanswered transaction(s)",
                    details);
        }
        if (ping.sentSeq < state.acceptedTeleportSeq) {
            Map<String, String> details = new TreeMap<>();
            details.put("reason", "ping-after-later-teleport-accept");
            details.put("pingSentSequence", Long.toString(ping.sentSeq));
            details.put("acceptedTeleportSequence", Long.toString(state.acceptedTeleportSeq));
            report(session, state, ping.sentSeq, ping.sentNano, ack.sequence(), ack.nanoTime(),
                    "answered transaction after accepting a teleport that was sent after it", details);
        }
    }

    private void onAnomaly(PlayerSession session, State state, TimelineEvent.Anomaly anomaly) {
        if (!state.loaded || anomaly.kind() != TimelineEvent.Anomaly.Kind.DUPLICATE_TRANSACTION) {
            return;
        }
        Integer dupId = state.hasLastPongId ? state.lastPongId : parseDuplicatePongId(anomaly.detail());
        if (dupId == null) {
            return;
        }
        TrackedPing prior = state.rememberedPings.get(dupId);
        if (prior == null) {
            return;
        }
        Map<String, String> details = new TreeMap<>();
        details.put("reason", "duplicate-transaction");
        details.put("transactionId", Integer.toHexString(dupId));
        details.put("firstSentSequence", Long.toString(prior.sentSeq));
        report(session, state, prior.sentSeq, prior.sentNano, anomaly.sequence(), anomaly.nanoTime(),
                "answered transaction " + Integer.toHexString(dupId) + " more than once or out of FIFO order",
                details);
    }

    private static Integer parseDuplicatePongId(String detail) {
        if (detail == null || !detail.startsWith("pong ")) {
            return null;
        }
        try {
            return (int) Long.parseLong(detail.substring(5).trim(), 16);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private void report(PlayerSession session, State state, long fromSeq, long fromNano, long toSeq, long toNano,
                        String summary, Map<String, String> details) {
        if (state.lastReportNanos != Long.MIN_VALUE && toNano - state.lastReportNanos < REPORT_INTERVAL_NANOS) {
            return;
        }
        Admissibility admissibility = state.admissibility.evaluateTranslatorSafe(fromSeq);
        if (!admissibility.admissible()) {
            sink.rejected(session, ID, admissibility);
            return;
        }
        sink.accept(session, new Evidence(ID, VERSION, EvidenceClass.MODIFICATION_PROOF, fromSeq, toSeq,
                fromNano, toNano, summary, details, admissibility));
        state.lastReportNanos = toNano;
    }

    private static void clearPendingAndRemembered(State state) {
        state.pendingPings.clear();
        state.rememberedPings.clear();
        state.pendingTeleports.clear();
        state.pendingKeepAlives.clear();
        state.hasLastPongId = false;
        state.acceptedTeleportSeq = Long.MIN_VALUE;
    }

    private static void reset(State state) {
        state.loaded = false;
        clearPendingAndRemembered(state);
    }
}
