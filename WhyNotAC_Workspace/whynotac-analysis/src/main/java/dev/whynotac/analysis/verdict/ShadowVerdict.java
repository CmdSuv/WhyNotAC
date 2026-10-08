package dev.whynotac.analysis.verdict;

import dev.whynotac.analysis.evidence.Admissibility;
import dev.whynotac.analysis.evidence.Evidence;
import dev.whynotac.analysis.evidence.EvidenceSink;
import dev.whynotac.analysis.policy.ActionPolicy;
import dev.whynotac.analysis.policy.ActionPolicy.Action;
import dev.whynotac.analysis.policy.ActionPolicy.Lifecycle;
import dev.whynotac.protocol.session.PlayerSession;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.LongAdder;

/**
 * The verdict layer's first version: every check is {@link Lifecycle#SHADOW}, so evidence opens a case
 * (recorded, listed for review, optionally announced to staff on the shadow channel) and nothing
 * happens to the player. The actions a case permits come from {@link ActionPolicy} alone.
 *
 * <p>Called on analysis threads; listeners must not block (the case store and alerts hand off).
 */
public final class ShadowVerdict implements EvidenceSink {

    /** One finding as it is presented and stored. The session is live; read its identity right away. */
    public record Case(String id, long createdEpochMillis, PlayerSession session, Evidence evidence, Lifecycle lifecycle,
            Set<Action> permittedActions) {}

    @FunctionalInterface
    public interface CaseListener {
        void opened(Case openedCase);
    }

    private final List<CaseListener> listeners = new CopyOnWriteArrayList<>();
    private final LongAdder cases = new LongAdder();
    private final Map<String, LongAdder> rejectedByReason = new ConcurrentHashMap<>();

    public void addListener(CaseListener listener) {
        listeners.add(listener);
    }

    /** The lifecycle of a check. Every check is SHADOW until it has passed its promotion criteria. */
    public Lifecycle lifecycle(String check) {
        return Lifecycle.SHADOW;
    }

    @Override
    public void accept(PlayerSession session, Evidence evidence) {
        Lifecycle lifecycle = lifecycle(evidence.check());
        Set<Action> permitted = EnumSet.noneOf(Action.class);
        for (Action action : Action.values()) {
            if (ActionPolicy.enabled(evidence.evidenceClass(), action, evidence.admissibility().identity(), lifecycle, false)) {
                permitted.add(action);
            }
        }
        Case opened = new Case(UUID.randomUUID().toString(), System.currentTimeMillis(), session, evidence, lifecycle, permitted);
        cases.increment();
        for (CaseListener listener : listeners) {
            listener.opened(opened);
        }
    }

    @Override
    public void rejected(PlayerSession session, String check, Admissibility admissibility) {
        for (String reason : admissibility.reasons()) {
            rejectedByReason.computeIfAbsent(check + ": " + reason, r -> new LongAdder()).increment();
        }
    }

    public long cases() {
        return cases.sum();
    }

    /** Candidates the admissibility gate turned away, by check and reason. */
    public List<String> rejections() {
        List<String> out = new ArrayList<>();
        rejectedByReason.forEach((reason, count) -> out.add(count.sum() + " x " + reason));
        out.sort(null);
        return out;
    }
}
