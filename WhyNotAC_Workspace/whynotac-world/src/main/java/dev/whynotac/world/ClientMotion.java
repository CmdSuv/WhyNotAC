package dev.whynotac.world;

import java.util.Set;

/**
 * Entity types whose position the 1.21.11 client does not take from the packets alone, for an entity it
 * does not control: {@link ClientEntities} does not claim to know where they are. Read from the decompiled
 * client (every tick, aiStep and baseTick override, and what they call), with these results:
 * <ul>
 *   <li>Every other living entity moves on the client only by interpolation: {@code LivingEntity.aiStep}
 *       interpolates, and travel, AI, goals, navigation and the brain run only for an entity the client
 *       controls ({@code canSimulateMovement}, {@code isEffectiveAi}, {@code Mob.serverAiStep});
 *       {@code RemotePlayer.aiStep} only interpolates. Fluids and pushing change velocity only.</li>
 *   <li>Most non-living entities run their own physics on the client (items, experience orbs, TNT,
 *       falling blocks, arrows, tridents, thrown items, fireballs, fireworks, fishing hooks, eyes of ender,
 *       llama spit, shulker bullets); minecarts also depend on who controls them and on feature flags. Only
 *       the types in {@link #STATIC} never move.</li>
 *   <li>Boats and rafts ({@link #interpolatesOnly}) move on the client only by interpolation unless its own player
 *       controls them: {@code AbstractBoat.tick} interpolates, then runs its physics only where
 *       {@code isLocalInstanceAuthoritative} (its controlling passenger is the client's own player) and otherwise
 *       sets the motion to zero without moving; nothing else in its tick moves it.</li>
 * </ul>
 * Not followed yet, and therefore never to be relied on near them: a moving piston
 * ({@code PistonMovingBlockEntity} moves every entity on the client) and a peeking shulker
 * ({@code Shulker.onPeekAmountChange} moves entities next to it).
 */
final class ClientMotion {

    private ClientMotion() {}

    /** Living entities that the client moves besides interpolating. */
    static final Set<String> LIVING = Set.of(
            "minecraft:bat",          // Bat.tick: a resting bat is put under the block above it (setPosRaw), on both sides
            "minecraft:ender_dragon", // EnderDragon.tickDeath: moves up while dying; its parts are placed in aiStep
            "minecraft:shulker");     // Shulker.setPos: snapped to the block centre; no interpolation; and a shulker box opening
                                      // next to it teleports it up to 8 blocks on the client (move SHULKER_BOX: teleportSomewhere)

    /** Non-living entities whose client tick never moves them and whose position is the packet's. */
    static final Set<String> STATIC = Set.of(
            "minecraft:interaction", "minecraft:marker", "minecraft:end_crystal", "minecraft:area_effect_cloud", "minecraft:evoker_fangs",
            "minecraft:lightning_bolt", "minecraft:ominous_item_spawner");

    static boolean movesItself(String type, EntityTypes.Kind kind) {
        return kind.living() ? LIVING.contains(type) : !STATIC.contains(type) && !interpolatesOnly(type);
    }

    /** Every boat, chest boat and raft ({@code AbstractBoat}): moved by interpolation alone unless the client's own player controls it. */
    static boolean interpolatesOnly(String type) {
        return type.endsWith("_boat") || type.endsWith("_raft");
    }

    /**
     * The horses the client's own player steers with a saddle ({@code AbstractHorse} but the llamas and the camels):
     * like any living entity they move by interpolation alone until then, and the replica keeps what their first ride
     * tick reads ({@link ClientEntities#horseMounted}).
     */
    static final Set<String> HORSES = Set.of("minecraft:horse", "minecraft:donkey", "minecraft:mule", "minecraft:skeleton_horse",
            "minecraft:zombie_horse");

    /** A vehicle whose client state the replica keeps for its player's mount: a boat or a horse. */
    static boolean keptAtMount(String type) {
        return type != null && (interpolatesOnly(type) || HORSES.contains(type));
    }
}
