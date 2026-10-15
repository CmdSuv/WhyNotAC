package dev.whynotac.paper.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import dev.whynotac.protocol.packet.BlockFace;
import dev.whynotac.protocol.packet.ClientboundPacket;
import dev.whynotac.protocol.packet.Hand;
import dev.whynotac.protocol.packet.RelativeFlag;
import dev.whynotac.protocol.packet.ServerboundPacket;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import net.minecraft.SharedConstants;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ClientboundGameEventPacket;
import net.minecraft.network.protocol.game.GameProtocols;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Pins WhyNotAC to the exact protocol surface of the game it runs on. Every play packet type of the
 * running server must be classified below, and every game enum WhyNotAC maps must match its model.
 * A Paper or Mojang change (new packet, new enum constant) therefore fails the build instead of
 * silently degrading observation: someone has to decide whether it matters for analysis.
 */
class ProtocolSurfaceTest {

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    // --- serverbound ---------------------------------------------------------------------------

    /** Translated into a dedicated model record (the handshake's "intention" is not a play packet). */
    private static final Set<String> SERVERBOUND_MODELLED = PacketTranslator.SERVERBOUND_MODELLED.stream()
            .filter(id -> !id.equals("intention")).collect(Collectors.toUnmodifiableSet()); // client_information/custom_payload are common

    /** Relevant for analysis, to be modelled next: item picking. */
    private static final Set<String> SERVERBOUND_PLANNED = Set.of("pick_item_from_block", "pick_item_from_entity");

    /** No bearing on movement, combat or timing beyond what Unmodeled already records (type and time). */
    private static final Set<String> SERVERBOUND_IGNORED = Set.of(
            "block_entity_tag_query", "bundle_item_selected", "change_difficulty", "change_game_mode", "chat", "chat_ack", "chat_command",
            "chat_command_signed", "chat_session_update", "client_command", "command_suggestion", "configuration_acknowledged",
            "container_button_click", "container_slot_state_changed", "cookie_response", "custom_click_action", "debug_subscription_request",
            "edit_book", "entity_tag_query", "jigsaw_generate", "lock_difficulty", "ping_request", "place_recipe",
            "recipe_book_change_settings", "recipe_book_seen_recipe", "rename_item", "resource_pack", "seen_advancements", "select_trade",
            "set_beacon", "set_command_block", "set_command_minecart", "set_jigsaw_block", "set_structure_block",
            "set_test_block", "sign_update", "teleport_to_entity", "test_instance_block_action");

    // --- clientbound -----------------------------------------------------------------------------

    private static final Set<String> CLIENTBOUND_MODELLED = Stream.concat(PacketTranslator.CLIENTBOUND_MODELLED.stream(),
            Stream.of("bundle_delimiter")).collect(Collectors.toUnmodifiableSet()); // bundles are flattened

    /**
     * Changes what the client simulates or collides with, or its combat state, but is not modelled yet.
     * This is the Phase 2b backlog (leashes, new minecarts, inventory, world border, tick rate, combat state).
     */
    private static final Set<String> CLIENTBOUND_PHYSICS_PENDING = Set.of(
            "set_entity_link", "move_minecart_along_track", "projectile_power", "block_event", "initialize_border", "set_border_center", "set_border_lerp_size",
            "set_border_size", "cooldown", "set_equipment", "set_health", "player_combat_kill", "player_look_at",
            "damage_event", "hurt_animation", "start_configuration");

    private static final Set<String> CLIENTBOUND_IGNORED = Set.of(
            "animate", "award_stats", "block_destruction", "block_entity_data", "boss_event", "change_difficulty", "chunk_batch_finished",
            "chunk_batch_start", "chunks_biomes", "clear_dialog", "clear_titles", "command_suggestions", "commands",
            "container_set_data", "cookie_request", "custom_chat_completions", "custom_payload", "custom_report_details", "debug/block_value",
            "debug/chunk_value", "debug/entity_value", "debug/event", "debug_sample", "delete_chat", "disconnect", "disguised_chat",
            "game_test_highlight_pos", "level_event", "level_particles", "light_update", "map_item_data", "merchant_offers",
            "open_book", "open_sign_editor", "place_ghost_recipe", "player_chat", "player_combat_end",
            "player_combat_enter", "pong_response", "recipe_book_add", "recipe_book_remove",
            "recipe_book_settings", "reset_score", "resource_pack_pop", "resource_pack_push", "select_advancements_tab", "server_data",
            "server_links", "set_action_bar_text", "set_border_warning_delay", "set_border_warning_distance", "set_default_spawn_position",
            "set_display_objective", "set_experience", "set_objective", "set_player_team", "set_score", "set_simulation_distance",
            "set_subtitle_text", "set_time", "set_title_text", "set_titles_animation", "show_dialog", "sound", "sound_entity", "stop_sound",
            "store_cookie", "system_chat", "tab_list", "tag_query", "take_item_entity", "test_instance_block_status", "transfer",
            "update_advancements", "update_recipes", "update_tags", "waypoint");

    private static Set<String> packetIds(net.minecraft.network.ProtocolInfo.DetailsProvider protocol) {
        Set<String> ids = new TreeSet<>();
        protocol.details().listPackets((type, id) -> ids.add(type.id().getPath()));
        return ids;
    }

    private static void assertClassified(String flow, Set<String> actual, List<Set<String>> categories) {
        Set<String> classified = new HashSet<>();
        for (Set<String> category : categories) {
            for (String id : category) {
                if (!classified.add(id)) {
                    fail(flow + " packet '" + id + "' is classified twice");
                }
            }
        }
        Set<String> unclassified = new TreeSet<>(actual);
        unclassified.removeAll(classified);
        Set<String> gone = new TreeSet<>(classified);
        gone.removeAll(actual);
        if (!unclassified.isEmpty() || !gone.isEmpty()) {
            fail(flow + " protocol surface changed. New (decide: model, plan or ignore): " + unclassified
                    + "; no longer present: " + gone);
        }
    }

    @Test
    void everyServerboundPlayPacketIsClassified() {
        Set<String> actual = packetIds(GameProtocols.SERVERBOUND_TEMPLATE);
        assertEquals(66, actual.size(), "1.21.11 has 66 serverbound play packets: " + actual);
        assertClassified("serverbound", actual, List.of(SERVERBOUND_MODELLED, SERVERBOUND_PLANNED, SERVERBOUND_IGNORED));
    }

    @Test
    void everyClientboundPlayPacketIsClassified() {
        Set<String> actual = packetIds(GameProtocols.CLIENTBOUND_TEMPLATE);
        assertEquals(139, actual.size(), "1.21.11 has 139 clientbound play packets: " + actual);
        assertClassified("clientbound", actual, List.of(CLIENTBOUND_MODELLED, CLIENTBOUND_PHYSICS_PENDING, CLIENTBOUND_IGNORED));
    }

    // --- enums -----------------------------------------------------------------------------------

    private static List<String> names(Enum<?>[] values) {
        return Arrays.stream(values).map(Enum::name).toList();
    }

    private static List<String> withoutUnknown(Enum<?>[] values) {
        return Arrays.stream(values).map(Enum::name).filter(n -> !n.equals("UNKNOWN")).toList();
    }

    @Test
    void mappedEnumsMatchTheGameExactly() {
        assertEquals(names(ServerboundPlayerCommandPacket.Action.values()), withoutUnknown(ServerboundPacket.PlayerCommand.Action.values()));
        assertEquals(names(ServerboundPlayerActionPacket.Action.values()), withoutUnknown(ServerboundPacket.PlayerAction.Action.values()));
        assertEquals(names(AttributeModifier.Operation.values()), withoutUnknown(ClientboundPacket.UpdateAttributes.Operation.values()));
        assertEquals(names(Relative.values()), withoutUnknown(RelativeFlag.values()));
        assertEquals(names(InteractionHand.values()), names(Hand.values()));
        assertEquals(names(net.minecraft.network.protocol.handshake.ClientIntent.values()), withoutUnknown(ServerboundPacket.Handshake.Intent.values()));
        assertEquals(names(Direction.values()), names(BlockFace.values()));
        assertEquals(names(net.minecraft.world.entity.Pose.values()), withoutUnknown(ClientboundPacket.EntityPose.values()));
        for (Direction direction : Direction.values()) {
            assertEquals(direction.name(), BlockFace.values()[direction.get3DDataValue()].name(), "3D data value of " + direction);
        }
    }

    @Test
    void everyGameConstantMapsToItsNamesake() {
        for (ServerboundPlayerActionPacket.Action action : ServerboundPlayerActionPacket.Action.values()) {
            assertEquals(action.name(), PacketTranslator.PLAYER_ACTION.map(action).name());
        }
        for (ServerboundPlayerCommandPacket.Action action : ServerboundPlayerCommandPacket.Action.values()) {
            assertEquals(action.name(), PacketTranslator.PLAYER_COMMAND.map(action).name());
        }
        for (Relative relative : Relative.values()) {
            assertEquals(relative.name(), PacketTranslator.RELATIVE.map(relative).name());
        }
        for (AttributeModifier.Operation operation : AttributeModifier.Operation.values()) {
            assertEquals(operation.name(), PacketTranslator.OPERATION.map(operation).name());
        }
        for (net.minecraft.world.entity.Pose pose : net.minecraft.world.entity.Pose.values()) {
            assertEquals(pose.name(), PacketTranslator.POSE.map(pose).name());
            assertEquals(pose.ordinal(), PacketTranslator.POSE.map(pose).ordinal(), "the recorded pose is its ordinal");
        }
    }

    @Test
    void gameModeEventIdIsTheOneTheSandwichPolicyUses() {
        ClientboundPacket translated = new PacketTranslator().outbound(new ClientboundGameEventPacket(ClientboundGameEventPacket.CHANGE_GAME_MODE, 1.0F));
        assertEquals(new ClientboundPacket.GameEvent(ClientboundPacket.GameEvent.CHANGE_GAME_MODE, 1.0F), translated);
    }

    @Test
    void categoriesAreConsistentWithTheModel() {
        // Every serverbound model record except the generic ones corresponds to a modelled packet family.
        long records = Stream.of(ServerboundPacket.class.getPermittedSubclasses())
                .map(Class::getSimpleName)
                .filter(n -> !n.equals("Unmodeled") && !n.equals("Untranslatable"))
                .count();
        assertTrue(records <= SERVERBOUND_MODELLED.size(), "more model records (" + records + ") than modelled packets");
        Set<String> all = Stream.of(SERVERBOUND_MODELLED, SERVERBOUND_PLANNED, SERVERBOUND_IGNORED).flatMap(Set::stream).collect(Collectors.toSet());
        assertEquals(66, all.size());
    }
}
