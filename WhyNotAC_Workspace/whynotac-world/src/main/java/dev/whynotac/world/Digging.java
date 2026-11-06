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
    /**
     * The items whose class declares {@code canDestroyBlock} ({@code ItemPrototypesDataTest}): with one in hand the
     * client's {@code destroyBlock} may set nothing. {@code Item.canDestroyBlock} itself passes unless the stack has a tool
     * that cannot break in creative and the player's {@code abilities.instabuild} is true.
     */
    public static final Set<String> REFUSING_ITEMS = Set.of("minecraft:debug_stick");

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

    /**
     * The state the client's {@code STOP_DESTROY_BLOCK} set at the dug position, or -1 where that is not certain
     * (decompiled 1.21.11 {@code MultiPlayerGameMode.destroyBlock}: {@code setBlock(pos, getFluidState(pos).createLegacyBlock(), 11)}).
     * {@code continueDestroyBlock} sends it only for a block that is not air, so the air ones among the states the client
     * may hold there are left out; every other one must be breakable (a vanilla client never finishes an unbreakable one,
     * and every {@code GameMasterBlock}, which it may not break, is unbreakable), without hooks of its own
     * ({@link BlockStates#DIG_HOOKED}: {@code playerWillDestroy}, {@code destroy}), and leave the same: air where it holds
     * no fluid, a water source where it holds water (waterlogged: {@code WaterFluid.createLegacyBlock} of a source is level
     * 0). No stack the main hand may hold is one of {@link #REFUSING_ITEMS}. That the client may not be in adventure,
     * spectator or creative ({@code blockActionRestricted}) and that its {@code instabuild} is false
     * ({@code Item.canDestroyBlock}) is the caller's to know.
     */
    public static int brokenTo(BlockStates table, int[] states, Set<Stack> stacks) {
        if (states == null || stacks == null || stacks.isEmpty()) {
            return -1;
        }
        for (Stack stack : stacks) {
            if (!stack.isEmpty() && REFUSING_ITEMS.contains(stack.item())) {
                return -1;
            }
        }
        int out = -1;
        for (int state : states) {
            if (!table.isValid(state)) {
                return -1;
            }
            if (table.isAir(state)) {
                continue;
            }
            if (table.has(state, BlockStates.DIG_HOOKED) || table.destroySpeed(state) == -1.0F) {
                return -1;
            }
            String fluid = table.fluid(state);
            int left;
            if (fluid == null || fluid.isEmpty()) {
                left = table.firstStateOf("minecraft:air");
            } else if (fluid.startsWith("minecraft:water/")) {
                left = table.firstStateOf("minecraft:water");
                if (!table.properties(left).equals("level=0")) {
                    return -1;
                }
            } else {
                return -1;
            }
            if (out != -1 && out != left) {
                return -1;
            }
            out = left;
        }
        return out;
    }

    /** The client's block tags for one block ({@link ClientTags#holds}). */
    public interface TagLookupByBlock {
        TagLookup forBlock(int blockId);

        static TagLookupByBlock of(ClientTags tags) {
            return blockId -> tag -> tags.holds(ClientTags.BLOCK, tag, blockId);
        }
    }
}
