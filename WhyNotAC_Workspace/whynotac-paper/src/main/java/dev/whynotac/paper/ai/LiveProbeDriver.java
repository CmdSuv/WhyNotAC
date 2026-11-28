package dev.whynotac.paper.ai;

import dev.whynotac.ai.AiAdvisor;
import dev.whynotac.ai.DesignProbeEngine;
import dev.whynotac.ai.ProbeScheduler;
import dev.whynotac.paper.network.PacketInterceptor;
import dev.whynotac.protocol.session.PlayerSession;
import dev.whynotac.world.ClientEntities;
import dev.whynotac.world.ClientEntitiesTracker;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.phys.Vec3;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/**
 * Paper server driver for randomized, non-invasive behind-FOV decoy probes (AI-04 / AI-05).
 *
 * <p>Enabled only when {@code ai.active-probes.enabled: true} in {@code config.yml}. Spawns clientbound-only
 * polymorphic decoys with unpredictable virtual entity IDs in {@code [VIRTUAL_DECOY_MIN_ID, VIRTUAL_DECOY_MAX_ID)}
 * within vanilla 3.0-block attack reach behind combat players' camera yaw without touching server world chunks
 * or polluting {@link ClientEntities}.
 */
public final class LiveProbeDriver implements Runnable {

    private static final int VIRTUAL_DECOY_MAX_ID = 2_140_000_000;
    private static final List<EntityType<?>> POLYMORPHIC_TYPES = List.of(
            EntityType.HUSK,
            EntityType.ZOMBIE,
            EntityType.SKELETON,
            EntityType.ARMOR_STAND);

    private final Plugin plugin;
    private final PacketInterceptor interceptor;
    private final AiAdvisor aiAdvisor;
    private final ClientEntitiesTracker entitiesTracker;
    private final long ttlTicks;

    public LiveProbeDriver(Plugin plugin, PacketInterceptor interceptor, AiAdvisor aiAdvisor, long ttlTicks) {
        this(plugin, interceptor, aiAdvisor, null, ttlTicks);
    }

    public LiveProbeDriver(
            Plugin plugin,
            PacketInterceptor interceptor,
            AiAdvisor aiAdvisor,
            ClientEntitiesTracker entitiesTracker,
            long ttlTicks) {
        this.plugin = plugin;
        this.interceptor = interceptor;
        this.aiAdvisor = aiAdvisor;
        this.entitiesTracker = entitiesTracker;
        this.ttlTicks = Math.max(4L, ttlTicks);
    }

    static int nextRandomVirtualId() {
        return ThreadLocalRandom.current().nextInt(ClientEntities.VIRTUAL_DECOY_MIN_ID, VIRTUAL_DECOY_MAX_ID);
    }

    static EntityType<?> selectPolymorphicType(ProbeScheduler.ProbePlan plan, int virtualId) {
        if (plan.type() == ProbeScheduler.ProbeType.RANDOMIZED_HITBOX_OFFSET) {
            return EntityType.ARMOR_STAND;
        }
        int idx = Math.floorMod(virtualId, POLYMORPHIC_TYPES.size() - 1);
        return POLYMORPHIC_TYPES.get(idx);
    }

    @Override
    public void run() {
        DesignProbeEngine engine = aiAdvisor.designProbeEngine();
        ProbeScheduler scheduler = aiAdvisor.probeScheduler();
        for (Player player : Bukkit.getOnlinePlayers()) {
            Optional<PlayerSession> maybeSession = interceptor.session(player);
            if (maybeSession.isEmpty()) {
                continue;
            }
            PlayerSession session = maybeSession.get();
            if (!engine.isInCombat(session)) {
                continue;
            }
            long currentTick = engine.currentTick(session);
            ProbeScheduler.ProbePlan plan = scheduler.maybeSchedule(player.getUniqueId(), currentTick, true);
            if (plan == null) {
                continue;
            }
            spawnBehindFovDecoy(player, session, engine, plan);
        }
    }

    private void spawnBehindFovDecoy(
            Player player, PlayerSession session, DesignProbeEngine engine, ProbeScheduler.ProbePlan plan) {
        Location base = player.getLocation();
        double yawRad = Math.toRadians(base.getYaw() + plan.yawOffsetDeg());
        double dist = plan.distanceBlocks();
        double x = base.getX() - Math.sin(yawRad) * dist;
        double y = base.getY() + plan.yOffsetBlocks();
        double z = base.getZ() + Math.cos(yawRad) * dist;
        long effectiveTtl = plan.effectiveTtlTicks(ttlTicks);

        int virtualId = nextRandomVirtualId();
        if (!engine.registerDecoy(session, virtualId, effectiveTtl, plan.expectedNullHitRate())) {
            return;
        }

        ServerPlayer nmsPlayer = ((CraftPlayer) player).getHandle();
        if (nmsPlayer.connection == null) {
            return;
        }
        EntityType<?> chosenType = selectPolymorphicType(plan, virtualId);
        float lookBackYaw = (base.getYaw() + 180.0f) % 360.0f;
        ArmorStand phantom = new ArmorStand(((CraftWorld) player.getWorld()).getHandle(), x, y, z);
        phantom.setInvisible(true);
        phantom.setSmall(plan.type() == ProbeScheduler.ProbeType.RANDOMIZED_HITBOX_OFFSET);
        phantom.setNoGravity(true);

        if (entitiesTracker != null) {
            entitiesTracker.registerDecoy(session, virtualId);
        }
        nmsPlayer.connection.send(new ClientboundAddEntityPacket(
                virtualId, UUID.randomUUID(), x, y, z, 0.0f, lookBackYaw, chosenType, 0, Vec3.ZERO, lookBackYaw));
        List<SynchedEntityData.DataValue<?>> meta = phantom.getEntityData().getNonDefaultValues();
        if (meta != null && !meta.isEmpty()) {
            nmsPlayer.connection.send(new ClientboundSetEntityDataPacket(virtualId, meta));
        }

        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (player.isOnline()) {
                ServerPlayer current = ((CraftPlayer) player).getHandle();
                if (current.connection != null) {
                    current.connection.send(new ClientboundRemoveEntitiesPacket(virtualId));
                }
            }
            if (entitiesTracker != null) {
                Bukkit.getScheduler().runTaskLater(plugin, () -> entitiesTracker.unregisterDecoy(session, virtualId), 40L);
            }
        }, effectiveTtl);
    }
}
