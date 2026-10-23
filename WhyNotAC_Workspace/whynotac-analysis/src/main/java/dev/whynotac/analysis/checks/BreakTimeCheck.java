package dev.whynotac.analysis.checks;

import dev.whynotac.analysis.evidence.Admissibility;
import dev.whynotac.analysis.evidence.AdmissibilityTracker;
import dev.whynotac.analysis.evidence.Evidence;
import dev.whynotac.analysis.evidence.EvidenceClass;
import dev.whynotac.analysis.evidence.EvidenceSink;
import dev.whynotac.protocol.packet.BlockPosition;
import dev.whynotac.protocol.packet.ClientboundPacket;
import dev.whynotac.protocol.packet.ServerboundPacket;
import dev.whynotac.protocol.packet.Stack;
import dev.whynotac.protocol.session.PlayerSession;
import dev.whynotac.protocol.session.TimelineEvent;
import dev.whynotac.protocol.session.TimelineListener;
import dev.whynotac.world.BlockStates;
import dev.whynotac.world.ClientSelf;
import dev.whynotac.world.ClientWorld;
import dev.whynotac.world.ItemPrototypes;
import dev.whynotac.world.Replicas;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Break time (W1, {@code break-time}, P-M): a {@code STOP_DESTROY_BLOCK} sooner than the vanilla client's own progress
 * allows.
 *
 * <p>Decompiled 1.21.11 ({@code MultiPlayerGameMode}): {@code START_DESTROY_BLOCK} sets the progress to zero (a block that
 * breaks at once is destroyed in that call and sends no finish). Each tick in which the attack key is held over the same
 * block with the same item and components ({@code sameDestroyTarget}), {@code continueDestroyBlock} adds
 * {@code BlockState.getDestroyProgress} to it, the first time in the start's own tick ({@code continueAttack} follows
 * {@code startAttack}); the tick it reaches 1 it sends {@code STOP_DESTROY_BLOCK}. That is at most one increment per tick,
 * and every tick that runs the key handling ends with {@code ClientTickEnd}: by the finish the client added at most one
 * increment per tick end since the start, and one for the finish's own tick.
 *
 * <p>The increment is {@code Player.getDestroySpeed(state) / hardness / (correct tool ? 30 : 100)}, in floats: the held
 * item's tool speed ({@code Tool.getMiningSpeed}; 1 without a tool) plus {@code mining_efficiency} when above 1, times
 * {@code 1 + (amplifier + 1) * 0.2} under haste or conduit power, times the mining fatigue factor, times
 * {@code block_break_speed}, times {@code submerged_mining_speed} with the eye in water, divided by 5 off the ground.
 * Every factor is taken at its largest over what the client may hold at that tick (the replicas' windows): the item at
 * the finish (it has the start's item and components, or the dig would have restarted), the block's states before the
 * start's own prediction, the effects and attributes, on the ground, the eye in water only where that is faster, and,
 * the server's tags not being followed, any rule naming a tag as possibly holding the block. Floats round monotonically,
 * so the client's sum is at most the sum of those largest increments: a finish before that sum reaches 1 is impossible
 * for a vanilla client.
 *
 * <p>No claim while anything it reads is not known: the block (unsettled chunk, an earlier prediction there, an item use
 * whose blocks are not located, any other block action during the dig), the item (a slot not followed, a patched tool,
 * an item the table does not know), a dig-speed effect not followed, creative or spectator. At most one finding per
 * {@link #REPORT_INTERVAL_NANOS} per session; the next one counts what came between.
 */
public final class BreakTimeCheck implements TimelineListener {

    public static final String ID = "break-time";
    public static final int VERSION = 1;
    static final long REPORT_INTERVAL_NANOS = 1_000_000_000L;
    static final String TOOL = "minecraft:tool";

    private static final Object STATE = new Object();

    private final EvidenceSink sink;
    private final Replicas replicas;

    public BreakTimeCheck(EvidenceSink sink, Replicas replicas) {
        this.sink = sink;
        this.replicas = replicas;
    }

    /** The dig the client may be making: the block, the start's prediction sequence and where it is in the timeline. */
    private static final class Dig {
        final BlockPosition pos;
        final int sequence;
        final long from;
        final long fromNanos;
        /** The largest progress the client may have reached by the last tick end. */
        float progress;
        int tickEnds;
        /** The largest increment of any tick, and its factors, for the report. */
        Bound largest;

        Dig(BlockPosition pos, int sequence, long from, long fromNanos) {
            this.pos = pos;
            this.sequence = sequence;
            this.from = from;
            this.fromNanos = fromNanos;
        }
    }

    private static final class State {
        final AdmissibilityTracker admissibility = new AdmissibilityTracker();
        Dig dig;
        long lastReportNanos = Long.MIN_VALUE;
        long sinceReport;
    }

    /** The largest increment of one tick and what gave it. */
    record Bound(float increment, String block, float hardness, String item, float toolSpeed, boolean correct, ClientSelf.MiningBounds mining) {}

    @Override
    public void onEvent(PlayerSession session, TimelineEvent event) {
        State state = session.attachment(STATE, State::new);
        state.admissibility.onEvent(event);
        switch (event) {
            case TimelineEvent.Outbound out when out.packet() instanceof ClientboundPacket.Login
                    || out.packet() instanceof ClientboundPacket.Respawn -> state.dig = null; // a new player, a new MultiPlayerGameMode at login
            case TimelineEvent.Gap ignored -> state.dig = null;
            case TimelineEvent.Inbound in -> onInbound(session, state, in);
            default -> { }
        }
    }

    private void onInbound(PlayerSession session, State state, TimelineEvent.Inbound in) {
        switch (in.packet()) {
            case ServerboundPacket.PlayerAction action -> {
                switch (action.action()) {
                    case START_DESTROY_BLOCK -> state.dig = new Dig(action.position(), action.sequence(), in.sequence(), in.nanoTime());
                    case ABORT_DESTROY_BLOCK -> state.dig = null;
                    case STOP_DESTROY_BLOCK -> {
                        Dig dig = state.dig;
                        state.dig = null;
                        if (dig != null && dig.pos.equals(action.position())) {
                            Bound bound = bound(session, dig);
                            if (bound != null) {
                                float progress = dig.progress + bound.increment(); // MultiPlayerGameMode: destroyProgress += ...
                                if (progress < 1.0F) {
                                    report(session, state, dig, bound, progress, in);
                                }
                            }
                        }
                    }
                    default -> { }
                }
            }
            case ServerboundPacket.UseItemOn ignored -> state.dig = null; // its prediction may change the block
            case ServerboundPacket.UseItem ignored -> state.dig = null;
            case ServerboundPacket.ClientTickEnd ignored -> {
                Dig dig = state.dig;
                if (dig != null) {
                    Bound bound = bound(session, dig);
                    if (bound == null) {
                        state.dig = null;
                    } else {
                        dig.progress = dig.progress + bound.increment();
                        dig.tickEnds++;
                        if (dig.largest == null || bound.increment() > dig.largest.increment()) {
                            dig.largest = bound;
                        }
                    }
                }
            }
            default -> { }
        }
    }

    /** The largest increment the client may have added in the tick now, or null if something it reads is not known. */
    private Bound bound(PlayerSession session, Dig dig) {
        ClientSelf self = replicas.self().self(session);
        if (self.mayBeCreativeOrSpectator()) {
            return null;
        }
        ClientWorld world = replicas.world().world(session);
        int[] states = world.possibleStatesBefore(dig.pos.x(), dig.pos.y(), dig.pos.z(), dig.sequence);
        if (states == null || world.hasUnlocatedPredictionBefore(dig.sequence)) {
            return null;
        }
        Set<Stack> stacks = replicas.inventory().inventory(session).mainHandStacks();
        ClientSelf.MiningBounds mining = self.miningBounds();
        if (stacks == null || stacks.isEmpty() || mining.maxDigSpeedAmplifier() == Integer.MAX_VALUE) {
            return null;
        }
        BlockStates table = world.states();
        ItemPrototypes prototypes = ItemPrototypes.vanilla();
        Bound largest = null;
        for (int state : states) {
            if (!table.isValid(state)) {
                return null;
            }
            if (table.isAir(state)) {
                continue; // Minecraft.continueAttack: nothing on air
            }
            float hardness = table.destroySpeed(state);
            if (hardness == -1.0F) {
                continue; // BlockBehaviour.getDestroyProgress: 0
            }
            String block = table.block(state);
            boolean required = table.has(state, BlockStates.TOOL_REQUIRED);
            for (Stack stack : stacks) {
                ItemPrototypes.Tool tool;
                if (stack.isEmpty() || stack.removed().contains(TOOL)) {
                    tool = null;
                } else if (stack.added().contains(TOOL)) {
                    return null; // a tool of the stack's own
                } else {
                    ItemPrototypes.Prototype prototype = prototypes.get(stack.item());
                    if (prototype == null) {
                        return null;
                    }
                    tool = prototype.tool();
                }
                float speed = tool == null ? 1.0F : maxSpeed(tool, block);
                boolean correct = !required || tool != null && mayBeCorrect(tool, block);
                float increment = increment(speed, hardness, correct, mining);
                if (largest == null || increment > largest.increment()) {
                    largest = new Bound(increment, block, hardness, stack.isEmpty() ? "empty" : stack.item(), speed, correct, mining);
                }
            }
        }
        return largest != null ? largest : new Bound(0.0F, "air", Float.NaN, "-", Float.NaN, false, mining);
    }

    /** {@code BlockBehaviour.getDestroyProgress} with {@code Player.getDestroySpeed} at the largest factors, in its float order. */
    static float increment(float toolSpeed, float hardness, boolean correct, ClientSelf.MiningBounds mining) {
        float f = toolSpeed;
        if (f > 1.0F) {
            f += (float) mining.maxMiningEfficiency();
        }
        if (mining.maxDigSpeedAmplifier() >= 0) {
            f *= 1.0F + (mining.maxDigSpeedAmplifier() + 1) * 0.2F;
        }
        if (mining.minFatigueAmplifier() >= 0) {
            f *= switch (mining.minFatigueAmplifier()) {
                case 0 -> 0.3F;
                case 1 -> 0.09F;
                case 2 -> 0.0027F;
                default -> 8.1E-4F;
            };
        }
        f *= (float) mining.maxBlockBreakSpeed();
        float submerged = (float) mining.maxSubmergedMiningSpeed();
        if (submerged > 1.0F) {
            f *= submerged; // the eye may be in water
        }
        // on the ground: no division by 5
        return f / hardness / (float) (correct ? 30 : 100);
    }

    /**
     * The largest speed {@code Tool.getMiningSpeed} may give the block: the first rule with a speed whose blocks hold it,
     * a rule naming a tag possibly holding it (the server's tags are not followed), else the default.
     */
    static float maxSpeed(ItemPrototypes.Tool tool, String block) {
        float max = Float.NEGATIVE_INFINITY;
        for (ItemPrototypes.Rule rule : tool.rules()) {
            if (rule.speed() == null) {
                continue;
            }
            Boolean holds = holds(rule, block);
            if (Boolean.FALSE.equals(holds)) {
                continue;
            }
            max = Math.max(max, rule.speed());
            if (Boolean.TRUE.equals(holds)) {
                return max; // the first rule that holds it decides
            }
        }
        return Math.max(max, tool.defaultMiningSpeed());
    }

    /** Whether {@code Tool.isCorrectForDrops} may be true for the block, the same way. */
    static boolean mayBeCorrect(ItemPrototypes.Tool tool, String block) {
        for (ItemPrototypes.Rule rule : tool.rules()) {
            if (rule.correctForDrops() == null) {
                continue;
            }
            Boolean holds = holds(rule, block);
            if (Boolean.FALSE.equals(holds)) {
                continue;
            }
            if (rule.correctForDrops()) {
                return true;
            }
            if (Boolean.TRUE.equals(holds)) {
                return false;
            }
        }
        return false;
    }

    /** Whether the rule's blocks hold the block: a list says, a tag may (null). */
    private static Boolean holds(ItemPrototypes.Rule rule, String block) {
        return rule.tag() == null ? rule.blocks().contains(block) : null;
    }

    private void report(PlayerSession session, State state, Dig dig, Bound bound, float progress, TimelineEvent.Inbound at) {
        state.sinceReport++;
        if (state.lastReportNanos != Long.MIN_VALUE && at.nanoTime() - state.lastReportNanos < REPORT_INTERVAL_NANOS) {
            return;
        }
        state.lastReportNanos = at.nanoTime();
        Admissibility admissibility = state.admissibility.evaluate(dig.from);
        if (!admissibility.admissible()) {
            sink.rejected(session, ID, admissibility);
            return;
        }
        Bound largest = dig.largest == null || bound.increment() > dig.largest.increment() ? bound : dig.largest;
        String block = dig.pos.x() + "," + dig.pos.y() + "," + dig.pos.z();
        Map<String, String> details = new TreeMap<>();
        details.put("block", block);
        details.put("state", largest.block());
        details.put("hardness", Float.toString(largest.hardness()));
        details.put("item", largest.item());
        details.put("toolSpeed", Float.toString(largest.toolSpeed()));
        details.put("correctTool", Boolean.toString(largest.correct()));
        details.put("ticks", Integer.toString(dig.tickEnds + 1));
        details.put("maxProgress", Float.toString(progress));
        details.put("maxIncrement", Float.toString(largest.increment()));
        ClientSelf.MiningBounds m = largest.mining();
        details.put("digSpeedAmplifier", Integer.toString(m.maxDigSpeedAmplifier()));
        details.put("fatigueAmplifier", Integer.toString(m.minFatigueAmplifier()));
        details.put("miningEfficiency", Double.toString(m.maxMiningEfficiency()));
        details.put("blockBreakSpeed", Double.toString(m.maxBlockBreakSpeed()));
        details.put("submergedMiningSpeed", Double.toString(m.maxSubmergedMiningSpeed()));
        details.put("earlyFinishes", Long.toString(state.sinceReport));
        state.sinceReport = 0;
        sink.accept(session, new Evidence(ID, VERSION, EvidenceClass.MODIFICATION_PROOF, dig.from, at.sequence(), dig.fromNanos, at.nanoTime(),
                "finished breaking " + largest.block() + " at " + block + " after " + (dig.tickEnds + 1) + " ticks, by which at most "
                        + String.format(Locale.ROOT, "%.4f", progress) + " of it could be broken (1 needed)", details, admissibility));
    }
}
