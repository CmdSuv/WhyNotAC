package dev.whynotac.analysis.movement;

import dev.whynotac.protocol.packet.ClientboundPacket;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * One operation's modifiers as {@code AttributeInstance} holds them: an {@code Object2ObjectOpenHashMap<Identifier,
 * AttributeModifier>} of fastutil 8.5 (the client's 8.5.18) with its defaults. 32 slots; a key's slot is
 * {@code HashCommon.mix(Identifier.hashCode()) & 31}, taken by linear probing; removal shifts the probe chain
 * back ({@code shiftKeys}); {@code values()} iterates from the last slot down. The client sums the modifiers
 * in that order, so the order is the value's last bits. Immutable.
 */
final class ModifierTable {

    static final int SLOTS = 32;
    private static final int MASK = SLOTS - 1;
    /** {@code Hash.DEFAULT_LOAD_FACTOR} 0.75 of 32: the 25th entry rehashes (not modelled). */
    static final int MAX_ENTRIES = 24;
    static final ModifierTable EMPTY = new ModifierTable(new ClientboundPacket.UpdateAttributes.Modifier[SLOTS], 0);

    private final ClientboundPacket.UpdateAttributes.Modifier[] slots;
    private final int size;

    private ModifierTable(ClientboundPacket.UpdateAttributes.Modifier[] slots, int size) {
        this.slots = slots;
        this.size = size;
    }

    /** {@code Identifier.hashCode}: {@code 31 * namespace.hashCode() + path.hashCode()}. */
    static int identifierHash(String id) {
        int colon = id.indexOf(':');
        String namespace = colon < 0 ? "minecraft" : id.substring(0, colon);
        String path = colon < 0 ? id : id.substring(colon + 1);
        return 31 * namespace.hashCode() + path.hashCode();
    }

    /** {@code HashCommon.mix}. */
    static int mix(int x) {
        int h = x * 0x9E3779B9;
        return h ^ (h >>> 16);
    }

    private static int home(String id) {
        return mix(identifierHash(id)) & MASK;
    }

    private int find(String id) {
        int pos = home(id);
        while (slots[pos] != null) {
            if (slots[pos].id().equals(id)) {
                return pos;
            }
            pos = (pos + 1) & MASK;
        }
        return -(pos + 1);
    }

    int size() {
        return size;
    }

    boolean contains(String id) {
        return find(id) >= 0;
    }

    /** {@code put}: an existing key keeps its slot; a new one takes the first free slot from its own. Null past {@link #MAX_ENTRIES}. */
    ModifierTable put(ClientboundPacket.UpdateAttributes.Modifier modifier) {
        int pos = find(modifier.id());
        ClientboundPacket.UpdateAttributes.Modifier[] next = slots.clone();
        if (pos >= 0) {
            next[pos] = modifier;
            return new ModifierTable(next, size);
        }
        if (size >= MAX_ENTRIES) {
            return null;
        }
        next[-pos - 1] = modifier;
        return new ModifierTable(next, size + 1);
    }

    /** {@code remove}: {@code removeEntry} and {@code shiftKeys}. */
    ModifierTable remove(String id) {
        int pos = find(id);
        if (pos < 0) {
            return this;
        }
        ClientboundPacket.UpdateAttributes.Modifier[] next = slots.clone();
        int last;
        for (;;) {
            pos = ((last = pos) + 1) & MASK;
            ClientboundPacket.UpdateAttributes.Modifier current;
            for (;;) {
                if ((current = next[pos]) == null) {
                    next[last] = null;
                    return new ModifierTable(next, size - 1);
                }
                int slot = home(current.id());
                if (last <= pos ? last >= slot || slot > pos : last >= slot && slot > pos) {
                    break;
                }
                pos = (pos + 1) & MASK;
            }
            next[last] = current;
        }
    }

    /** {@code values()}'s order: from the last slot down. */
    List<ClientboundPacket.UpdateAttributes.Modifier> inOrder() {
        List<ClientboundPacket.UpdateAttributes.Modifier> out = new ArrayList<>(size);
        for (int i = SLOTS - 1; i >= 0; i--) {
            if (slots[i] != null) {
                out.add(slots[i]);
            }
        }
        return out;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof ModifierTable t && Arrays.equals(slots, t.slots);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(slots);
    }

    @Override
    public String toString() {
        return inOrder().toString();
    }
}
