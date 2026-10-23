package dev.whynotac.analysis.checks;

import dev.whynotac.analysis.movement.EngineTags;
import dev.whynotac.physics.OnFoot;
import dev.whynotac.protocol.packet.ClientboundPacket;
import dev.whynotac.world.BlockStates;
import java.util.ArrayList;
import java.util.List;

/** The tags a vanilla server sends in the configuration phase, as far as the checks read them. */
final class VanillaTagsPacket {

    private VanillaTagsPacket() {}

    static ClientboundPacket.UpdateTags packet() {
        BlockStates states = BlockStates.vanilla();
        List<Integer> climbable = new ArrayList<>();
        for (String block : OnFoot.CLIMBABLE) {
            climbable.add(states.blockId(states.firstStateOf(block)));
        }
        List<Integer> canGlideThrough = new ArrayList<>();
        for (String block : OnFoot.CAN_GLIDE_THROUGH) {
            canGlideThrough.add(states.blockId(states.firstStateOf(block)));
        }
        List<Integer> fallDamageResetting = new ArrayList<>();
        for (String block : OnFoot.FALL_DAMAGE_RESETTING) {
            fallDamageResetting.add(states.blockId(states.firstStateOf(block)));
        }
        List<Integer> fences = new ArrayList<>();
        List<Integer> walls = new ArrayList<>();
        for (int id = 0; id < states.blockCount(); id++) {
            if (states.blockKey(id).endsWith("_fence")) {
                fences.add(id);
            } else if (states.blockKey(id).endsWith("_wall")) {
                walls.add(id);
            }
        }
        return new ClientboundPacket.UpdateTags(List.of(
                new ClientboundPacket.UpdateTags.RegistryTags("minecraft:block", List.of(tag("minecraft:climbable", climbable),
                        tag("minecraft:can_glide_through", canGlideThrough), tag("minecraft:fall_damage_resetting", fallDamageResetting),
                        tag("minecraft:fences", fences), tag("minecraft:walls", walls))),
                new ClientboundPacket.UpdateTags.RegistryTags("minecraft:entity_type", List.of(tag("minecraft:powder_snow_walkable_mobs", List.of()))),
                new ClientboundPacket.UpdateTags.RegistryTags("minecraft:fluid", List.of(
                        tag("minecraft:water", List.of(EngineTags.WATER, EngineTags.FLOWING_WATER)),
                        tag("minecraft:lava", List.of(EngineTags.LAVA, EngineTags.FLOWING_LAVA))))));
    }

    private static ClientboundPacket.UpdateTags.Tag tag(String name, List<Integer> ids) {
        return new ClientboundPacket.UpdateTags.Tag(name, ids.stream().mapToInt(Integer::intValue).toArray());
    }
}
