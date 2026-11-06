package dev.whynotac.world;

import dev.whynotac.protocol.packet.Stack;
import java.util.Set;

/**
 * The client's dig arithmetic, decompiled 1.21.11: {@code BlockBehaviour.getDestroyProgress} is
 * {@code Player.getDestroySpeed(state) / hardness / (correct tool ? 30 : 100)}, in floats; the speed is the held item's
 * tool speed ({@code Tool.getMiningSpeed}; 1 without a tool) plus {@code mining_efficiency} when above 1, times
 * {@code 1 + (amplifier + 1) * 0.2} under haste or conduit power, times the mining fatigue factor, times
 * {@code block_break_speed}, times {@code submerged_mining_speed} with the eye in water, divided by 5 off the ground.
 * Taken at the largest factors the replicas allow, every step rounds monotonically: the client's own progress is at most
 * what these give.
 */
public final class Digging {

    static final String TOOL = "minecraft:tool";

    private Digging() {}

    /** Whether a tag holds the block in what the client may hold: TRUE, FALSE, or null (either). */
    public interface TagLookup {
        Boolean holds(String tag);
    }

    /** {@code BlockBehaviour.getDestroyProgress} with {@code Player.getDestroySpeed} at the largest factors, in its float order. */
    public static float increment(float toolSpeed, float hardness, boolean correct, ClientSelf.MiningBounds mining, boolean offGround) {
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
        if (offGround) {
            f /= 5.0F;
        }
        return f / hardness / (float) (correct ? 30 : 100);
    }

    /**
     * The largest speed {@code Tool.getMiningSpeed} may give the block: the first rule with a speed whose blocks hold it,
     * every rule up to it whose tag may hold it, else the default.
     */
    public static float maxSpeed(ItemPrototypes.Tool tool, String block, TagLookup tags) {
        float max = Float.NEGATIVE_INFINITY;
        for (ItemPrototypes.Rule rule : tool.rules()) {
            if (rule.speed() == null) {
                continue;
            }
            Boolean holds = holds(rule, block, tags);
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
    public static boolean mayBeCorrect(ItemPrototypes.Tool tool, String block, TagLookup tags) {
        for (ItemPrototypes.Rule rule : tool.rules()) {
            if (rule.correctForDrops() == null) {
                continue;
            }
            Boolean holds = holds(rule, block, tags);
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

    /** Whether the rule's blocks hold the block: a list says, a tag as the client's tags say. */
    private static Boolean holds(ItemPrototypes.Rule rule, String block, TagLookup tags) {
        return rule.tag() == null ? Boolean.valueOf(rule.blocks().contains(block)) : tags.holds(rule.tag()); // boxed: null stays null
    }

    /**
     * Whether a {@code START_DESTROY_BLOCK} of a survival or adventure client certainly set no block: its
     * {@code startDestroyBlock} took the branch that only starts the dig. That branch is taken unless the block is not air
     * and {@code getDestroyProgress} reaches 1 (then {@code destroyBlock} breaks it at once); before it, {@code attack} runs
     * on a block that is not air. So: for every state the client may hold there ({@code states}) and every stack its main
     * hand may hold, the state is air, unbreakable (hardness -1: progress 0), or reaches less than 1 at the largest factors
     * on the ground, and no such block has {@link BlockStates#DIG_HOOKED} (a redstone ore lights itself in
     * {@code attack}). False where any of that is not known: a state or stack not known, a tool of the stack's own, a
     * dig-speed effect not followed. The game mode is the caller's to know.
     */
    public static boolean certainlyStartsOnly(BlockStates table, int[] states, Set<Stack> stacks, ClientSelf.MiningBounds mining,
            TagLookupByBlock tags, ItemPrototypes prototypes) {
        if (states == null || stacks == null || stacks.isEmpty() || mining.maxDigSpeedAmplifier() == Integer.MAX_VALUE) {
            return false;
        }
        for (int state : states) {
            if (!table.isValid(state)) {
                return false;
            }
            if (table.isAir(state)) {
                continue; // neither attack nor the break: !blockState.isAir() guards both
            }
            if (table.has(state, BlockStates.DIG_HOOKED)) {
                return false;
            }
            float hardness = table.destroySpeed(state);
            if (hardness == -1.0F) {
                continue; // getDestroyProgress: 0
            }
            String block = table.block(state);
            TagLookup lookup = tags.forBlock(table.blockId(state));
            boolean required = table.has(state, BlockStates.TOOL_REQUIRED);
            for (Stack stack : stacks) {
                ItemPrototypes.Tool tool;
                if (stack.isEmpty() || stack.removed().contains(TOOL)) {
                    tool = null;
                } else if (stack.added().contains(TOOL)) {
                    return false; // a tool of the stack's own
                } else {
                    ItemPrototypes.Prototype prototype = prototypes.get(stack.item());
                    if (prototype == null) {
                        return false;
                    }
                    tool = prototype.tool();
                }
                float speed = tool == null ? 1.0F : maxSpeed(tool, block, lookup);
                boolean correct = !required || tool != null && mayBeCorrect(tool, block, lookup);
                if (!(increment(speed, hardness, correct, mining, false) < 1.0F)) {
                    return false; // may break at once (hardness 0 gives infinity)
                }
            }
        }
        return true;
    }

    /** The client's block tags for one block ({@link ClientTags#holds}). */
    public interface TagLookupByBlock {
        TagLookup forBlock(int blockId);

        static TagLookupByBlock of(ClientTags tags) {
            return blockId -> tag -> tags.holds(ClientTags.BLOCK, tag, blockId);
        }
    }
}
