package dev.whynotac.analysis.evidence;

import dev.whynotac.analysis.evidence.Admissibility.IdentityTier;
import dev.whynotac.protocol.packet.ClientboundPacket;
import dev.whynotac.protocol.packet.ServerboundPacket;
import dev.whynotac.protocol.session.TimelineEvent;
import dev.whynotac.protocol.session.TimelineEvent.ClientFact;
import dev.whynotac.protocol.session.TimelineEvent.ClientFact.Trust;
import dev.whynotac.protocol.session.TimelineEvent.Observation.Source;
import java.util.ArrayList;
import java.util.List;

/**
 * Follows one session's timeline and answers, for a window ending now, whether evidence may come out
 * of it (docs/POLICY.md). A check feeds it every event of its session, in order, before using it.
 * Requirements that are not established yet (a fact not seen) make the window inadmissible.
 */
public final class AdmissibilityTracker {

    private Source inbound;
    private Source outbound;
    private long observationSequence = Long.MIN_VALUE;
    private String nativeProtocol;
    private String translator;
    private String transactionOrigin;
    private String topology;
    private boolean bedrock;
    private String identity;
    private long lastLossSequence = Long.MIN_VALUE;

    public void onEvent(TimelineEvent event) {
        switch (event) {
            case TimelineEvent.Observation o -> {
                inbound = o.inbound();
                outbound = o.outbound();
                observationSequence = o.sequence();
            }
            case ClientFact f -> onFact(f);
            case TimelineEvent.Gap g -> lastLossSequence = g.sequence();
            case TimelineEvent.Inbound in when in.packet() instanceof ServerboundPacket.Untranslatable -> lastLossSequence = in.sequence();
            case TimelineEvent.Outbound out when out.packet() instanceof ClientboundPacket.Untranslatable -> lastLossSequence = out.sequence();
            default -> { }
        }
    }

    private void onFact(ClientFact f) {
        boolean bound = f.trust() == Trust.AUTHENTIC || f.trust() == Trust.FORMAT_BOUND; // a claim never qualifies a window
        switch (f.fact()) {
            case NATIVE_PROTOCOL -> nativeProtocol = bound ? f.value() : null;
            case TRANSLATOR -> translator = bound ? f.value() : null;
            case TRANSACTION_ORIGIN -> transactionOrigin = bound ? f.value() : null;
            case TOPOLOGY -> topology = f.value();
            case BEDROCK -> bedrock |= !"false".equalsIgnoreCase(f.value());
            case IDENTITY -> identity = f.value();
        }
    }

    /** Whether the window that started at {@code fromSequence} and ends now may yield evidence. */
    public Admissibility evaluate(long fromSequence) {
        List<String> reasons = new ArrayList<>();
        if (inbound != Source.WIRE || outbound != Source.WIRE) {
            reasons.add("not observed at the network edge (" + inbound + "/" + outbound + ")");
        }
        if (observationSequence >= fromSequence) {
            reasons.add("the observation point changed inside the window");
        }
        if (!"774".equals(nativeProtocol)) {
            reasons.add("native protocol not established as 774 (" + nativeProtocol + ")");
        }
        if (!"none".equals(translator)) {
            reasons.add("a translator may sit between client and server (" + translator + ")");
        }
        if (!"CLIENT".equals(transactionOrigin)) {
            reasons.add("transactions are not known to be answered by the client (" + transactionOrigin + ")");
        }
        if (bedrock || "GEYSER_LOCAL".equals(topology)) {
            reasons.add("Bedrock client (no model yet)");
        }
        if (lastLossSequence >= fromSequence) {
            reasons.add("lost or untranslatable events inside the window");
        }
        return new Admissibility(reasons.isEmpty(), reasons, tier());
    }

    private IdentityTier tier() {
        if ("AUTHENTICATED_MOJANG".equals(identity) || "AUTHENTICATED_BY_PROXY".equals(identity)) {
            return IdentityTier.AUTHENTICATED;
        }
        return IdentityTier.UNAUTHENTICATED;
    }
}
