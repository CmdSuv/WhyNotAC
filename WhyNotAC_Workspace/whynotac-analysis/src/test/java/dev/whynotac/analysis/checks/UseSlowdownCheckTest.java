package dev.whynotac.analysis.checks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.whynotac.analysis.evidence.Admissibility;
import dev.whynotac.analysis.evidence.Evidence;
import dev.whynotac.analysis.evidence.EvidenceClass;
import dev.whynotac.analysis.evidence.EvidenceSink;
import dev.whynotac.protocol.packet.BlockFace;
import dev.whynotac.protocol.packet.BlockPosition;
import dev.whynotac.protocol.packet.ClientboundPacket;
import dev.whynotac.protocol.packet.Hand;
import dev.whynotac.protocol.packet.ServerboundPacket;
import dev.whynotac.protocol.packet.Stack;
import dev.whynotac.protocol.session.PlayerSession;
import dev.whynotac.protocol.session.SessionRegistry;
import dev.whynotac.protocol.session.TimelineEvent;
import dev.whynotac.protocol.session.TimelineEvent.ClientFact;
import dev.whynotac.protocol.session.TimelineEvent.ClientFact.Fact;
import dev.whynotac.protocol.session.TimelineEvent.ClientFact.Trust;
import dev.whynotac.protocol.session.TimelineEvent.Observation.Source;
import dev.whynotac.world.Replicas;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

final class UseSlowdownCheckTest {

    private final List<Evidence> evidence = new ArrayList<>();
    private PlayerSession session;
    private long seq;
    private long now = 1_000_000_000L;

    @BeforeEach
    void setUp() {
        evidence.clear();
        seq = 0;
        now = 1_000_000_000L;
        SessionRegistry registry = new SessionRegistry(Runnable::run, 1, (s, e, t) -> { throw new AssertionError(t); });
        Replicas replicas = Replicas.vanilla();
        replicas.listeners().forEach(registry::addListener);
        registry.addListener(new UseSlowdownCheck(new EvidenceSink() {
            @Override
            public void accept(PlayerSession s, Evidence e) {
                evidence.add(e);
            }

            @Override
            public void rejected(PlayerSession s, String check, Admissibility a) {}
        }, replicas));
        session = registry.openReplay("use-item-state-test");
        session.deliver(new TimelineEvent.Observation(seq++, now, Source.WIRE, Source.WIRE, 774, "test"));
        session.deliver(new ClientFact(seq++, now, Fact.IDENTITY, "AUTHENTICATED_MOJANG", Trust.AUTHENTIC, "test"));
        session.deliver(new ClientFact(seq++, now, Fact.NATIVE_PROTOCOL, "774", Trust.FORMAT_BOUND, "test"));
        session.deliver(new ClientFact(seq++, now, Fact.TRANSLATOR, "none", Trust.FORMAT_BOUND, "test"));
        session.deliver(new ClientFact(seq++, now, Fact.TRANSACTION_ORIGIN, "CLIENT", Trust.FORMAT_BOUND, "test"));
        UUID playerId = new UUID(9, 9);
        session.deliver(new TimelineEvent.Bound(seq++, now, playerId, "useTester", 1));
        session.deliver(new TimelineEvent.Outbound(seq++, now,
                new ClientboundPacket.Login(1, 8, new ClientboundPacket.Dimension("minecraft:overworld", -64, 384), 0)));
    }

    @Test
    void honestUseReleaseAndSprintAfterReleaseProducesZeroEvidence() {
        // Tick 1: start using item
        session.deliver(new TimelineEvent.Inbound(seq++, now, 0,
                new ServerboundPacket.UseItem(Hand.MAIN_HAND, 1, 0.0F, 0.0F)));
        session.deliver(new TimelineEvent.Inbound(seq++, now, 0, new ServerboundPacket.ClientTickEnd()));

        // Tick 2: release item in handleKeybinds, then start sprinting in aiStep/sendPosition
        now += 50_000_000L;
        session.deliver(new TimelineEvent.Inbound(seq++, now, 1,
                new ServerboundPacket.PlayerAction(
                        ServerboundPacket.PlayerAction.Action.RELEASE_USE_ITEM,
                        new BlockPosition(0, 0, 0),
                        BlockFace.DOWN,
                        0)));
        session.deliver(new TimelineEvent.Inbound(seq++, now, 1,
                new ServerboundPacket.PlayerCommand(1, ServerboundPacket.PlayerCommand.Action.START_SPRINTING, 0)));
        session.deliver(new TimelineEvent.Inbound(seq++, now, 1, new ServerboundPacket.ClientTickEnd()));

        assertTrue(evidence.isEmpty());
    }

    @Test
    void detectsAutoBlockReleaseBeforeAttackSameTickAndNoSlowActionAfterSprintSameTick() {
        // 1. AutoBlock: RELEASE_USE_ITEM followed by InteractEntity(ATTACK) in the same tick
        session.deliver(new TimelineEvent.Inbound(seq++, now, 0,
                new ServerboundPacket.PlayerAction(
                        ServerboundPacket.PlayerAction.Action.RELEASE_USE_ITEM,
                        new BlockPosition(0, 0, 0),
                        BlockFace.DOWN,
                        0)));
        session.deliver(new TimelineEvent.Inbound(seq++, now, 0,
                new ServerboundPacket.InteractEntity(2, ServerboundPacket.InteractEntity.Kind.ATTACK, null, null, false)));
        session.deliver(new TimelineEvent.Inbound(seq++, now, 0, new ServerboundPacket.ClientTickEnd()));

        assertEquals(1, evidence.size());
        assertEquals("release-before-attack-same-tick", evidence.get(0).details().get("reason"));
        assertEquals(EvidenceClass.MODIFICATION_PROOF, evidence.get(0).evidenceClass());

        // 2. NoSlow pre-motion hook: START_SPRINTING followed by UseItem in the same tick
        now += 2_000_000_000L;
        session.deliver(new TimelineEvent.Inbound(seq++, now, 1,
                new ServerboundPacket.PlayerCommand(1, ServerboundPacket.PlayerCommand.Action.START_SPRINTING, 0)));
        session.deliver(new TimelineEvent.Inbound(seq++, now, 1,
                new ServerboundPacket.UseItem(Hand.MAIN_HAND, 2, 0.0F, 0.0F)));
        session.deliver(new TimelineEvent.Inbound(seq++, now, 1, new ServerboundPacket.ClientTickEnd()));

        assertEquals(2, evidence.size());
        assertEquals("action-after-sprint-command-same-tick", evidence.get(1).details().get("reason"));
    }

    @Test
    void registeredInChecksAllAndCreate() {
        assertEquals(UseSlowdownCheck.VERSION, Checks.version(UseSlowdownCheck.ID));
        assertNotNull(Checks.create(UseSlowdownCheck.ID, (s, e) -> {}));
        assertFalse(new UseSlowdownCheck((s, e) -> {}, Replicas.vanilla()).readsEntityMotion());
    }

    private void giveOffhandShield() {
        List<Stack> items = new ArrayList<>();
        Stack shield = new Stack("minecraft:shield", 1, List.of(), List.of(), null, false);
        for (int i = 0; i < 46; i++) {
            items.add(i == 45 ? shield : Stack.EMPTY);
        }
        session.deliver(new TimelineEvent.Outbound(seq++, now, new ClientboundPacket.ContainerSetContent(0, 1, items, Stack.EMPTY)));
        session.deliver(new TimelineEvent.Outbound(seq++, now, new ClientboundPacket.Ping(77)));
        session.deliver(new TimelineEvent.Inbound(seq++, now, 0, new ServerboundPacket.Pong(77)));
        session.deliver(new TimelineEvent.Acknowledged(seq++, now, 77, now, 0));
    }

    @Test
    void detectsAttackAndSprintAcrossTicksWhileShieldHeld() {
        giveOffhandShield();

        // Tick 0: raise offhand shield
        session.deliver(new TimelineEvent.Inbound(seq++, now, 0,
                new ServerboundPacket.UseItem(Hand.OFF_HAND, 1, 0.0F, 0.0F)));
        session.deliver(new TimelineEvent.Inbound(seq++, now, 0, new ServerboundPacket.ClientTickEnd()));

        // Tick 1 (no RELEASE_USE_ITEM): attack while shield still raised -> AutoBlock KeepUsing
        now += 50_000_000L;
        session.deliver(new TimelineEvent.Inbound(seq++, now, 1,
                new ServerboundPacket.InteractEntity(4, ServerboundPacket.InteractEntity.Kind.ATTACK, null, null, false)));
        session.deliver(new TimelineEvent.Inbound(seq++, now, 1, new ServerboundPacket.ClientTickEnd()));

        assertEquals(1, evidence.size());
        assertEquals("attack-while-using", evidence.get(0).details().get("reason"));
        assertEquals("minecraft:shield", evidence.get(0).details().get("item"));
        assertEquals(EvidenceClass.MODIFICATION_PROOF, evidence.get(0).evidenceClass());

        // Tick 2+Cooldown clear, then raise shield again and START_SPRINTING on next tick -> NoSlow sprint-while-using
        now += 2_000_000_000L;
        session.deliver(new TimelineEvent.Inbound(seq++, now, 2,
                new ServerboundPacket.UseItem(Hand.OFF_HAND, 2, 0.0F, 0.0F)));
        session.deliver(new TimelineEvent.Inbound(seq++, now, 2, new ServerboundPacket.ClientTickEnd()));
        now += 50_000_000L;
        session.deliver(new TimelineEvent.Inbound(seq++, now, 3,
                new ServerboundPacket.PlayerCommand(1, ServerboundPacket.PlayerCommand.Action.START_SPRINTING, 0)));
        session.deliver(new TimelineEvent.Inbound(seq++, now, 3, new ServerboundPacket.ClientTickEnd()));

        assertEquals(2, evidence.size());
        assertEquals("sprint-while-using", evidence.get(1).details().get("reason"));
        assertEquals("minecraft:shield", evidence.get(1).details().get("item"));
    }

    @Test
    void shieldCooldownOrReleasePermitsSubsequentAttackWithZeroEvidence() {
        giveOffhandShield();

        // Raise shield tick 0
        session.deliver(new TimelineEvent.Inbound(seq++, now, 0,
                new ServerboundPacket.UseItem(Hand.OFF_HAND, 1, 0.0F, 0.0F)));
        session.deliver(new TimelineEvent.Inbound(seq++, now, 0, new ServerboundPacket.ClientTickEnd()));

        // Server disables shield via Cooldown packet -> clears active hold
        now += 50_000_000L;
        session.deliver(new TimelineEvent.Outbound(seq++, now, new ClientboundPacket.Cooldown("minecraft:shield", 100)));
        session.deliver(new TimelineEvent.Inbound(seq++, now, 1,
                new ServerboundPacket.InteractEntity(4, ServerboundPacket.InteractEntity.Kind.ATTACK, null, null, false)));
        session.deliver(new TimelineEvent.Inbound(seq++, now, 1, new ServerboundPacket.ClientTickEnd()));

        assertTrue(evidence.isEmpty(), () -> "Unexpected evidence: " + evidence);
    }

    @Test
    void detectsGoldenAppleNoSlowSprintDuringGuaranteedHoldWindowAndExpiresAfter24Ticks() {
        List<Stack> items = new ArrayList<>();
        Stack gapple = new Stack("minecraft:golden_apple", 4, List.of(), List.of(), null, false);
        for (int i = 0; i < 46; i++) {
            items.add(i == 36 ? gapple : Stack.EMPTY);
        }
        session.deliver(new TimelineEvent.Outbound(seq++, now, new ClientboundPacket.ContainerSetContent(0, 1, items, Stack.EMPTY)));
        session.deliver(new TimelineEvent.Outbound(seq++, now, new ClientboundPacket.Ping(88)));
        session.deliver(new TimelineEvent.Inbound(seq++, now, 0, new ServerboundPacket.Pong(88)));
        session.deliver(new TimelineEvent.Acknowledged(seq++, now, 88, now, 0));

        // Start eating golden_apple in main hand, then send START_SPRINTING on tick 2 -> caught!
        session.deliver(new TimelineEvent.Inbound(seq++, now, 0,
                new ServerboundPacket.UseItem(Hand.MAIN_HAND, 1, 0.0F, 0.0F)));
        session.deliver(new TimelineEvent.Inbound(seq++, now, 0, new ServerboundPacket.ClientTickEnd()));
        now += 50_000_000L;
        session.deliver(new TimelineEvent.Inbound(seq++, now, 1,
                new ServerboundPacket.PlayerCommand(1, ServerboundPacket.PlayerCommand.Action.START_SPRINTING, 0)));
        session.deliver(new TimelineEvent.Inbound(seq++, now, 1, new ServerboundPacket.ClientTickEnd()));

        assertEquals(1, evidence.size());
        assertEquals("sprint-while-using", evidence.get(0).details().get("reason"));
        assertEquals("minecraft:golden_apple", evidence.get(0).details().get("item"));

        // Start eating golden_apple again, wait 25 ticks (hold auto-expires before 32-tick finish), then sprint -> 0 FP!
        now += 2_000_000_000L;
        session.deliver(new TimelineEvent.Inbound(seq++, now, 2,
                new ServerboundPacket.UseItem(Hand.MAIN_HAND, 2, 0.0F, 0.0F)));
        for (int t = 0; t < 25; t++) {
            now += 50_000_000L;
            session.deliver(new TimelineEvent.Inbound(seq++, now, 3 + t, new ServerboundPacket.ClientTickEnd()));
        }
        session.deliver(new TimelineEvent.Inbound(seq++, now, 30,
                new ServerboundPacket.PlayerCommand(1, ServerboundPacket.PlayerCommand.Action.START_SPRINTING, 0)));
        session.deliver(new TimelineEvent.Inbound(seq++, now, 30, new ServerboundPacket.ClientTickEnd()));
        assertEquals(1, evidence.size(), "Sprint after 24-tick consumable window expiry must produce 0 false positives");
    }

    @Test
    void hungerVerifiedPvpFoodDetectsNoSlowWhenHungryAndProducesZeroFpWhenFull() {
        List<Stack> slots = new ArrayList<>();
        for (int i = 0; i < 46; i++) slots.add(Stack.EMPTY);
        slots.set(36, new Stack("minecraft:cooked_beef", 16, List.of(), List.of(), null, false));
        session.deliver(new TimelineEvent.Outbound(seq++, now, new ClientboundPacket.SetHeldSlot(0)));
        session.deliver(new TimelineEvent.Outbound(seq++, now, new ClientboundPacket.ContainerSetContent(0, 1, slots, Stack.EMPTY)));
        // 1. Full hunger (food == 20): client cannot eat cooked_beef -> UseItem + START_SPRINTING must produce 0 FP!
        session.deliver(new TimelineEvent.Outbound(seq++, now, new ClientboundPacket.SetHealth(20.0F, 20, 5.0F)));
        session.deliver(new TimelineEvent.Outbound(seq++, now, new ClientboundPacket.Ping(101)));
        session.deliver(new TimelineEvent.Inbound(seq++, now, 0, new ServerboundPacket.Pong(101)));
        session.deliver(new TimelineEvent.Acknowledged(seq++, now, 101, now, 0));

        session.deliver(new TimelineEvent.Inbound(seq++, now, 0, new ServerboundPacket.UseItem(Hand.MAIN_HAND, 10, 0.0F, 0.0F)));
        session.deliver(new TimelineEvent.Inbound(seq++, now, 0, new ServerboundPacket.ClientTickEnd()));
        now += 50_000_000L;
        session.deliver(new TimelineEvent.Inbound(seq++, now, 1,
                new ServerboundPacket.PlayerCommand(1, ServerboundPacket.PlayerCommand.Action.START_SPRINTING, 0)));
        session.deliver(new TimelineEvent.Inbound(seq++, now, 1, new ServerboundPacket.ClientTickEnd()));
        assertTrue(evidence.isEmpty(), "Full-hunger UseItem(cooked_beef) + sprint must produce 0 FP");

        // 2. Confirmed hungry (food == 14, acked): eating cooked_beef + START_SPRINTING -> caught!
        session.deliver(new TimelineEvent.Outbound(seq++, now, new ClientboundPacket.SetHealth(20.0F, 14, 2.0F)));
        session.deliver(new TimelineEvent.Outbound(seq++, now, new ClientboundPacket.Ping(102)));
        session.deliver(new TimelineEvent.Inbound(seq++, now, 0, new ServerboundPacket.Pong(102)));
        session.deliver(new TimelineEvent.Acknowledged(seq++, now, 102, now, 0));

        now += 50_000_000L;
        session.deliver(new TimelineEvent.Inbound(seq++, now, 2, new ServerboundPacket.UseItem(Hand.MAIN_HAND, 11, 0.0F, 0.0F)));
        session.deliver(new TimelineEvent.Inbound(seq++, now, 2, new ServerboundPacket.ClientTickEnd()));
        now += 50_000_000L;
        session.deliver(new TimelineEvent.Inbound(seq++, now, 3,
                new ServerboundPacket.PlayerCommand(1, ServerboundPacket.PlayerCommand.Action.START_SPRINTING, 0)));
        session.deliver(new TimelineEvent.Inbound(seq++, now, 3, new ServerboundPacket.ClientTickEnd()));
        assertEquals(1, evidence.size());
        assertEquals("sprint-while-using", evidence.get(0).details().get("reason"));
        assertEquals("minecraft:cooked_beef", evidence.get(0).details().get("item"));
    }

    @Test
    void selfSprintFlagEchoSuppressesSprintWhileUsingUntilAckPlusOneTick() {
        giveOffhandShield();

        // 1. Stale server sprint echo (index 0, FLAG_SPRINTING bit 3 = 0x08) arrives while shield is raised;
        // START_SPRINTING before ping ack must produce 0 evidence.
        session.deliver(new TimelineEvent.Inbound(seq++, now, 0,
                new ServerboundPacket.UseItem(Hand.OFF_HAND, 1, 0.0F, 0.0F)));
        session.deliver(new TimelineEvent.Inbound(seq++, now, 0, new ServerboundPacket.ClientTickEnd()));

        now += 50_000_000L;
        session.deliver(new TimelineEvent.Outbound(seq++, now, new ClientboundPacket.SetEntityData(
                1, List.of(new ClientboundPacket.SetEntityData.DataEntry(0, ClientboundPacket.SetEntityData.Kind.BYTE, 0x08L)), 0)));
        session.deliver(new TimelineEvent.Inbound(seq++, now, 1,
                new ServerboundPacket.PlayerCommand(1, ServerboundPacket.PlayerCommand.Action.START_SPRINTING, 0)));
        session.deliver(new TimelineEvent.Inbound(seq++, now, 1, new ServerboundPacket.ClientTickEnd()));
        assertTrue(evidence.isEmpty(), () -> "Sprint echo before ack must not flag: " + evidence);

        // 2. Subsequent ping is acknowledged; START_SPRINTING on the tick after ack (+1 tick grace) must still produce 0 evidence.
        now += 50_000_000L;
        session.deliver(new TimelineEvent.Outbound(seq++, now, new ClientboundPacket.Ping(201)));
        session.deliver(new TimelineEvent.Inbound(seq++, now, 2, new ServerboundPacket.Pong(201)));
        session.deliver(new TimelineEvent.Acknowledged(seq++, now, 201, now, 0));
        session.deliver(new TimelineEvent.Inbound(seq++, now, 2,
                new ServerboundPacket.UseItem(Hand.OFF_HAND, 2, 0.0F, 0.0F)));
        session.deliver(new TimelineEvent.Inbound(seq++, now, 2, new ServerboundPacket.ClientTickEnd()));

        now += 50_000_000L;
        session.deliver(new TimelineEvent.Inbound(seq++, now, 3,
                new ServerboundPacket.PlayerCommand(1, ServerboundPacket.PlayerCommand.Action.START_SPRINTING, 0)));
        session.deliver(new TimelineEvent.Inbound(seq++, now, 3, new ServerboundPacket.ClientTickEnd()));
        assertTrue(evidence.isEmpty(), () -> "Sprint echo within ack + 1 tick grace must not flag: " + evidence);

        // 3. After the grace tick expires with no new sprint echo, START_SPRINTING while holding shield must flag.
        now += 50_000_000L;
        session.deliver(new TimelineEvent.Inbound(seq++, now, 4,
                new ServerboundPacket.UseItem(Hand.OFF_HAND, 3, 0.0F, 0.0F)));
        session.deliver(new TimelineEvent.Inbound(seq++, now, 4, new ServerboundPacket.ClientTickEnd()));

        now += 50_000_000L;
        session.deliver(new TimelineEvent.Inbound(seq++, now, 5,
                new ServerboundPacket.PlayerCommand(1, ServerboundPacket.PlayerCommand.Action.START_SPRINTING, 0)));
        session.deliver(new TimelineEvent.Inbound(seq++, now, 5, new ServerboundPacket.ClientTickEnd()));
        assertEquals(1, evidence.size());
        assertEquals("sprint-while-using", evidence.get(0).details().get("reason"));
        assertEquals("minecraft:shield", evidence.get(0).details().get("item"));
    }
}
