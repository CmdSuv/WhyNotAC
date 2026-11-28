package dev.whynotac.analysis.policy;

import dev.whynotac.analysis.evidence.Admissibility.IdentityTier;
import dev.whynotac.analysis.evidence.EvidenceClass;
import java.util.EnumMap;
import java.util.Map;

/**
 * docs/POLICY.md's action matrix, as data. Every action WhyNotAC could ever take goes through
 * {@link #permission}; an operator setting can only enable an {@link Permission#OPERATOR_OPT_IN} cell
 * ({@link #enabled}), never a forbidden or staff-only one. A test compares this table with the
 * document cell by cell, so the two cannot drift apart.
 */
public final class ActionPolicy {

    public enum Action { ALERT, REVIEW, HIDE_INFORMATION, NEUTRALIZE, KICK, TEMP_BAN, PERMANENT_BAN }

    public enum Permission {
        /** ✔ */ ALLOWED,
        /** ⚙ only if the operator enables it */ OPERATOR_OPT_IN,
        /** 👤 never automatic; staff may decide after review */ STAFF_ONLY,
        /** ✘ */ FORBIDDEN
    }

    /** A check starts in SHADOW (evidence is recorded and reviewed, nothing happens to the player). */
    public enum Lifecycle { SHADOW, ARMED }

    private static final Permission A = Permission.ALLOWED;
    private static final Permission O = Permission.OPERATOR_OPT_IN;
    private static final Permission P = Permission.STAFF_ONLY;
    private static final Permission X = Permission.FORBIDDEN;

    // columns: ALERT, REVIEW, HIDE_INFORMATION, NEUTRALIZE, KICK, TEMP_BAN, PERMANENT_BAN
    private static final Map<EvidenceClass, Permission[]> ARMED = new EnumMap<>(EvidenceClass.class);
    static final Permission[] SHADOW_ROW = {A, A, X, X, X, X, X};

    static {
        ARMED.put(EvidenceClass.ADVANTAGE_PROOF, new Permission[] {A, A, A, A, O, O, P});
        ARMED.put(EvidenceClass.MODIFICATION_PROOF, new Permission[] {A, A, A, A, O, P, P});
        ARMED.put(EvidenceClass.DESIGN_STATISTIC, new Permission[] {A, A, A, X, X, X, X});
        ARMED.put(EvidenceClass.NOMINAL_STATISTIC, new Permission[] {A, A, X, X, X, X, X});
        ARMED.put(EvidenceClass.CONTEXT, new Permission[] {X, A, X, X, X, X, X});
    }

    private ActionPolicy() {}

    /** What the policy allows for this action on evidence of this class, identity tier and check lifecycle. */
    public static Permission permission(EvidenceClass evidenceClass, Action action, IdentityTier identity, Lifecycle lifecycle) {
        Permission cell = (lifecycle == Lifecycle.SHADOW ? SHADOW_ROW : ARMED.get(evidenceClass))[action.ordinal()];
        // An automatic temporary ban needs an identity nobody else can claim (docs/POLICY.md, identity tiers).
        if (action == Action.TEMP_BAN && cell == Permission.OPERATOR_OPT_IN && identity == IdentityTier.UNAUTHENTICATED) {
            return Permission.STAFF_ONLY;
        }
        return cell;
    }

    /** True if WhyNotAC may take this action automatically, given whether the operator enabled it. */
    public static boolean enabled(EvidenceClass evidenceClass, Action action, IdentityTier identity, Lifecycle lifecycle, boolean operatorEnabled) {
        return switch (permission(evidenceClass, action, identity, lifecycle)) {
            case ALLOWED -> true;
            case OPERATOR_OPT_IN -> operatorEnabled;
            case STAFF_ONLY, FORBIDDEN -> false;
        };
    }

    /** The table row for a class (ARMED) or the SHADOW row, for documentation and tests. */
    static Permission[] row(EvidenceClass evidenceClass, Lifecycle lifecycle) {
        return (lifecycle == Lifecycle.SHADOW ? SHADOW_ROW : ARMED.get(evidenceClass)).clone();
    }
}
