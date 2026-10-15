package dev.whynotac.analysis.checks;

import dev.whynotac.analysis.evidence.Admissibility;
import dev.whynotac.analysis.evidence.AdmissibilityTracker;
import dev.whynotac.analysis.evidence.Evidence;
import dev.whynotac.analysis.evidence.EvidenceClass;
import dev.whynotac.analysis.evidence.EvidenceSink;
import dev.whynotac.protocol.packet.ServerboundPacket;
import dev.whynotac.protocol.session.PlayerSession;
import dev.whynotac.protocol.session.TimelineEvent;
import dev.whynotac.protocol.session.TimelineListener;
import dev.whynotac.world.ClientEntities;
import dev.whynotac.world.ClientInventory;
import dev.whynotac.world.ClientSelf;
import dev.whynotac.world.ClientWorld;
import dev.whynotac.world.EntitySizes;
import dev.whynotac.world.Replicas;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Attack reach (P-A): the client attacked an entity that no vanilla 1.21.11 client could have reached.
 *
 * <p>An attack ({@code ServerboundInteractPacket} ATTACK) comes from {@code Minecraft.startAttack} in the
 * tick whose start picked the target ({@link AttackReach} has the rules). Every input of that rule is taken
 * from the replicas, in the order the client processed what it was sent, as a set of possibilities:
 * <ul>
 *   <li>the eye ({@link ClientSelf#eye}): the last position the client sent, the pose envelope and its
 *       scale;</li>
 *   <li>the main hand's attack range at the pick and at the attack ({@link ClientInventory#pickReach},
 *       {@link ClientInventory#mainHandReach});</li>
 *   <li>the target's box in every state the client may hold ({@link ClientEntities#forEachPossibleState});</li>
 *   <li>{@code entity_interaction_range} ({@link ClientSelf#maxInteractionRange}).</li>
 * </ul>
 * The finding needs every combination to be out of reach. No claim is made where a replica cannot bound
 * its part: an unknown eye (a vehicle, another camera, a new player, a rollback or teleport since the last
 * position), creative or spectator mode, an unknown reach, a target whose box or pickability is not known
 * in some state, or something that may have pushed the player after the position it sent: a piston, its
 * moving blocks or a shulker box near it ({@link ClientWorld#mayMoveEntitiesIn}), a shulker near it
 * ({@link ClientEntities#mayHaveNear}).
 *
 * <p>At most one finding per {@link #REPORT_INTERVAL_NANOS} per session; the next one counts the attacks
 * out of reach in between.
 */
public final class AttackReachCheck implements TimelineListener {

    public static final String ID = "attack-reach";
    public static final int VERSION = 1;
    /** Blocks around the player's box searched for pistons and shulker boxes (they push what they touch). */
    static final int MOVER_BLOCKS_MARGIN = 2;
    /** Shulkers within this of the player, per axis (one push reach plus a teleport of 8 blocks). */
    static final double SHULKER_RADIUS = 12.0;
    static final String SHULKER = "minecraft:shulker";
    static final long REPORT_INTERVAL_NANOS = 1_000_000_000L;

    private static final Object STATE = new Object();

    private final EvidenceSink sink;
    private final Replicas replicas;

    public AttackReachCheck(EvidenceSink sink, Replicas replicas) {
        this.sink = sink;
        this.replicas = replicas;
    }

    private static final class State {
        final AdmissibilityTracker admissibility = new AdmissibilityTracker();
        long lastReportNanos = Long.MIN_VALUE;
        long outOfReachSinceReport;
    }

    @Override
    public void onEvent(PlayerSession session, TimelineEvent event) {
        State state = session.attachment(STATE, State::new);
        state.admissibility.onEvent(event);
        if (event instanceof TimelineEvent.Inbound in && in.packet() instanceof ServerboundPacket.InteractEntity interact
                && interact.kind() == ServerboundPacket.InteractEntity.Kind.ATTACK) {
            onAttack(session, state, in, interact);
        }
    }

    /** What {@link #assess} concluded about one attack. */
    public enum Outcome {
        /** Out of reach for every possibility: a finding. */
        OUT_OF_REACH,
        /** Some possibility explains it. */
        IN_REACH,
        /** A replica cannot bound its part: no claim either way ({@link Assessment#reason} says which). */
        NO_CLAIM
    }

    /**
     * One attack, assessed. {@code distance}: the smallest distance from any eye position to the target's
     * (inflated) box over its states; {@code allowed}: the farthest any pair of stacks allows (both NaN
     * without a claim).
     */
    public record Assessment(Outcome outcome, String reason, int target, String type, double distance, double allowed, AttackReach.Eye eye,
            Set<ClientInventory.Reach> pick, Set<ClientInventory.Reach> check, double interactionRange, int states) {

        static Assessment noClaim(int target, String reason) {
            return new Assessment(Outcome.NO_CLAIM, reason, target, null, Double.NaN, Double.NaN, null, Set.of(), Set.of(), Double.NaN, 0);
        }
    }

    private void onAttack(PlayerSession session, State state, TimelineEvent.Inbound in, ServerboundPacket.InteractEntity interact) {
        ClientSelf self = replicas.self().self(session);
        Assessment finding = assess(self, replicas.inventory().inventory(session), replicas.entities().entities(session),
                replicas.world().world(session), interact.entityId());
        if (finding.outcome() != Outcome.OUT_OF_REACH) {
            return;
        }
        state.outOfReachSinceReport++;
        if (state.lastReportNanos != Long.MIN_VALUE && in.nanoTime() - state.lastReportNanos < REPORT_INTERVAL_NANOS) {
            return;
        }
        long from = self.eyeSequence();
        Admissibility admissibility = state.admissibility.evaluate(from);
        if (!admissibility.admissible()) {
            sink.rejected(session, ID, admissibility);
            return;
        }
        Map<String, String> details = new TreeMap<>();
        details.put("target", Integer.toString(finding.target()));
        details.put("targetType", finding.type());
        details.put("distance", format(finding.distance()));
        details.put("allowed", format(finding.allowed()));
        details.put("eye", format(finding.eye().x()) + "," + format(finding.eye().y0()) + ".." + format(finding.eye().y1()) + "," + format(finding.eye().z()));
        details.put("pickReach", describe(finding.pick()));
        details.put("attackReach", describe(finding.check()));
        details.put("interactionRange", format(finding.interactionRange()));
        details.put("targetStates", Integer.toString(finding.states()));
        details.put("outOfReachAttacks", Long.toString(state.outOfReachSinceReport));
        sink.accept(session, new Evidence(ID, VERSION, EvidenceClass.ADVANTAGE_PROOF, from, in.sequence(), self.eyeNanoTime(), in.nanoTime(),
                "attacked " + finding.type() + " " + finding.target() + " from at least " + format(finding.distance()) + " blocks (at most "
                        + format(finding.allowed()) + " possible)",
                details, admissibility));
        state.lastReportNanos = in.nanoTime();
        state.outOfReachSinceReport = 0;
    }

    /** An attack on {@code target} now, as the replicas see it (also for audits over a recording). */
    public static Assessment assess(ClientSelf self, ClientInventory inventory, ClientEntities entities, ClientWorld world, int target) {
        if (target == self.selfId()) {
            return Assessment.noClaim(target, "self");
        }
        if (self.mayBeCreativeOrSpectator()) {
            return Assessment.noClaim(target, "creative or spectator");
        }
        ClientSelf.Eye own = self.eye();
        if (own == null) {
            return Assessment.noClaim(target, "eye unknown");
        }
        AttackReach.Eye eye = new AttackReach.Eye(own.x(), own.z(), own.y0(), own.y1(), own.radius());
        Set<ClientInventory.Reach> pick = inventory.pickReach();
        Set<ClientInventory.Reach> check = inventory.mainHandReach();
        if (pick.isEmpty() || check.isEmpty() || pick.contains(ClientInventory.Reach.UNKNOWN) || check.contains(ClientInventory.Reach.UNKNOWN)) {
            return Assessment.noClaim(target, "reach unknown");
        }
        double range = self.maxInteractionRange();
        // the target, in every state the client may hold
        List<ClientEntities.PossibleState> states = new ArrayList<>();
        entities.forEachPossibleState(target, states::add);
        if (states.isEmpty()) {
            return Assessment.noClaim(target, "target unknown");
        }
        String type = null;
        double distance = Double.POSITIVE_INFINITY;
        boolean out = true;
        for (ClientEntities.PossibleState s : states) {
            if (!s.present() || !s.known() || !s.positionKnown() || s.size() == null || s.pickable() == 0) {
                return Assessment.noClaim(target, !s.present() || !s.known() ? "target may be absent or unknown"
                        : !s.positionKnown() ? "target position unknown" : s.size() == null ? "target size unknown" : "target not pickable");
            }
            EntitySizes.Box b = s.size().box(s.x(), s.y(), s.z());
            AttackReach.Box box = new AttackReach.Box(b.minX(), b.minY(), b.minZ(), b.maxX(), b.maxY(), b.maxZ()).inflate(s.positionRadius());
            out &= AttackReach.outOfReach(eye, box, s.pickRadius(), pick, check, range, false);
            distance = Math.min(distance, AttackReach.distance(eye, box.inflate(s.pickRadius())));
            type = s.type();
        }
        double allowed = AttackReach.allowed(pick, check, range, false);
        if (!out) {
            return new Assessment(Outcome.IN_REACH, "in reach", target, type, distance, allowed, eye, pick, check, range, states.size());
        }
        // nothing pushed the player after the position it sent
        double feet = self.sentPosition().y();
        double scale = self.maxScale();
        double half = 0.3 * Math.max(1.0, scale) + own.radius();
        int minX = (int) Math.floor(own.x() - half) - MOVER_BLOCKS_MARGIN;
        int maxX = (int) Math.floor(own.x() + half) + MOVER_BLOCKS_MARGIN;
        int minZ = (int) Math.floor(own.z() - half) - MOVER_BLOCKS_MARGIN;
        int maxZ = (int) Math.floor(own.z() + half) + MOVER_BLOCKS_MARGIN;
        int minY = (int) Math.floor(feet - own.radius()) - MOVER_BLOCKS_MARGIN;
        int maxY = (int) Math.floor(feet + 1.8 * Math.max(1.0, scale) + own.radius()) + MOVER_BLOCKS_MARGIN;
        long since = self.eyeCertain();
        if (world.mayMoveEntitiesIn(minX, minY, minZ, maxX, maxY, maxZ, since)) {
            return Assessment.noClaim(target, "a piston or shulker box may have pushed the player");
        }
        if (entities.mayHaveNear(SHULKER, own.x(), feet, own.z(), SHULKER_RADIUS + half, since)) {
            return Assessment.noClaim(target, "a shulker may have pushed the player");
        }
        return new Assessment(Outcome.OUT_OF_REACH, "out of reach", target, type, distance, allowed, eye, pick, check, range, states.size());
    }

    private static String format(double value) {
        return String.format(Locale.ROOT, "%.4f", value);
    }

    private static String describe(Set<ClientInventory.Reach> reaches) {
        List<String> out = new ArrayList<>();
        for (ClientInventory.Reach reach : reaches) {
            out.add(!reach.known() ? "?" : reach.range() == null ? "none" : reach.range().maxRange() + "+" + reach.range().hitboxMargin());
        }
        out.sort(null);
        return String.join(",", out);
    }
}
