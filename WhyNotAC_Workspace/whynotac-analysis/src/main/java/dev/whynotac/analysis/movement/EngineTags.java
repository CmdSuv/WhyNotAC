package dev.whynotac.analysis.movement;

import dev.whynotac.physics.OnFoot;
import dev.whynotac.world.BlockStates;
import dev.whynotac.world.ClientTags;
import java.util.ArrayList;
import java.util.List;

/**
 * The tags the on-foot engine takes as vanilla 1.21.11's (decompiled: what {@code Entity}, {@code LivingEntity} and the
 * blocks it models read), and whether the client may hold others. A server's datapacks can change any of them: then
 * the engine's step is not the client's, and the movement check makes no claim.
 *
 * <ul>
 *   <li>{@code minecraft:climbable} ({@code LivingEntity.onClimbable}): exactly {@link OnFoot#CLIMBABLE}.
 *   <li>{@code minecraft:can_glide_through} ({@code onClimbable} while gliding): exactly {@link OnFoot#CAN_GLIDE_THROUGH}.
 *   <li>{@code minecraft:fall_damage_resetting} ({@code Entity.move}'s ray in a fast fall): exactly
 *       {@link OnFoot#FALL_DAMAGE_RESETTING}.
 *   <li>{@code minecraft:fences} and {@code minecraft:walls} ({@code Entity.getOnPos}): exactly the blocks named
 *       {@code *_fence} and {@code *_wall}, as the engine finds them.
 *   <li>The fluid tags {@code minecraft:water} and {@code minecraft:lava} ({@code updateFluidHeightAndDoFluidPushing},
 *       {@code isEyeInFluid}): water and flowing water, lava and flowing lava.
 *   <li>{@code minecraft:powder_snow_walkable_mobs} ({@code PowderSnowBlock.canEntityWalkOnPowderSnow}): not the player.
 * </ul>
 */
public final class EngineTags {

    /** {@code BuiltInRegistries.FLUID}: flowing water, water, flowing lava, lava ({@code EngineTagsTest} in whynotac-paper). */
    public static final int FLOWING_WATER = 1;
    public static final int WATER = 2;
    public static final int FLOWING_LAVA = 3;
    public static final int LAVA = 4;
    /** {@code BuiltInRegistries.ENTITY_TYPE}: the player. */
    public static final int PLAYER = 155;

    private final int[] climbable;
    private final int[] canGlideThrough;
    private final int[] fallDamageResetting;
    private final int[] fences;
    private final int[] walls;

    public EngineTags(BlockStates states) {
        climbable = ids(states, OnFoot.CLIMBABLE);
        canGlideThrough = ids(states, OnFoot.CAN_GLIDE_THROUGH);
        fallDamageResetting = ids(states, OnFoot.FALL_DAMAGE_RESETTING);
        List<Integer> f = new ArrayList<>();
        List<Integer> w = new ArrayList<>();
        for (int id = 0; id < states.blockCount(); id++) {
            String key = states.blockKey(id);
            if (key.endsWith("_fence")) {
                f.add(id);
            } else if (key.endsWith("_wall")) {
                w.add(id);
            }
        }
        fences = f.stream().mapToInt(Integer::intValue).toArray();
        walls = w.stream().mapToInt(Integer::intValue).toArray();
    }

    private static int[] ids(BlockStates states, java.util.Set<String> blocks) {
        return blocks.stream().mapToInt(block -> states.blockId(states.firstStateOf(block))).toArray();
    }

    /** The first of these tags the client may hold otherwise now, or null if it certainly holds them all as the engine takes them. */
    public String mayDiffer(ClientTags tags) {
        if (!tags.certainlyExactly(ClientTags.BLOCK, "minecraft:climbable", climbable)) {
            return "minecraft:climbable";
        }
        if (!tags.certainlyExactly(ClientTags.BLOCK, "minecraft:can_glide_through", canGlideThrough)) {
            return "minecraft:can_glide_through";
        }
        if (!tags.certainlyExactly(ClientTags.BLOCK, "minecraft:fall_damage_resetting", fallDamageResetting)) {
            return "minecraft:fall_damage_resetting";
        }
        if (!tags.certainlyExactly(ClientTags.BLOCK, "minecraft:fences", fences)) {
            return "minecraft:fences";
        }
        if (!tags.certainlyExactly(ClientTags.BLOCK, "minecraft:walls", walls)) {
            return "minecraft:walls";
        }
        if (!tags.certainlyExactly(ClientTags.FLUID, "minecraft:water", new int[] {FLOWING_WATER, WATER})) {
            return "minecraft:water";
        }
        if (!tags.certainlyExactly(ClientTags.FLUID, "minecraft:lava", new int[] {FLOWING_LAVA, LAVA})) {
            return "minecraft:lava";
        }
        if (!Boolean.FALSE.equals(tags.holds(ClientTags.ENTITY_TYPE, "minecraft:powder_snow_walkable_mobs", PLAYER))) {
            return "minecraft:powder_snow_walkable_mobs";
        }
        return null;
    }
}
