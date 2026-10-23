package dev.whynotac.world;

import dev.whynotac.protocol.packet.ClientboundPacket;
import dev.whynotac.protocol.session.TimelineEvent;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The tags the client holds, registry by registry, in the order it processes them (decompiled 1.21.11). In the
 * configuration phase {@code ClientboundUpdateTagsPacket} collects each registry's tags
 * ({@code RegistryDataCollector.appendTags}) and the play registries are made with them; a registry the phase sent
 * none for is not followed. In play the packet replaces each sent registry's tags ({@code handleUpdateTags}:
 * {@code prepareTagReload}, a tag it leaves out is empty) and the others keep theirs. Like the other replicas it follows
 * every prefix of the packets between the last answered ping and the first unanswered one.
 *
 * <p>Only the registries movement and mining read are kept ({@link #FOLLOWED}); identical payloads (every player of a
 * server gets the same) are shared.
 *
 * <p>Confined to the session's analysis thread.
 */
public final class ClientTags {

    public static final String BLOCK = "minecraft:block";
    public static final String FLUID = "minecraft:fluid";
    public static final String ENTITY_TYPE = "minecraft:entity_type";
    static final Set<String> FOLLOWED = Set.of(BLOCK, FLUID, ENTITY_TYPE);
    private static final int MAX_TRACKED_PINGS = 4096;
    /** Versions kept per registry while the client does not answer pings; beyond, its tags are not claimed until it does. */
    static final int MAX_VERSIONS = 64;
    private static final int INTERNED = 16;
    /** Payloads by content, shared across sessions (the most recent few). */
    private static final Map<ClientboundPacket.UpdateTags.RegistryTags, Map<String, int[]>> SHARED = new LinkedHashMap<>(16, 0.75F, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<ClientboundPacket.UpdateTags.RegistryTags, Map<String, int[]>> eldest) {
            return size() > INTERNED;
        }
    };

    /** One registry's tags from a sequence on: each tag's ids sorted; null when not known. */
    private record Version(long seq, Map<String, int[]> tags) {}

    private final ArrayDeque<long[]> pings = new ArrayDeque<>();
    private long certain = Long.MIN_VALUE;
    private final Map<String, List<Version>> registries = new HashMap<>();
    /** The followed registries the configuration phase before the coming login sent. */
    private final Set<String> sentSinceLogin = new HashSet<>();
    /** Counts every change of what {@link #possible} may answer. */
    private long revision;

    /** Applies one timeline event, in sequence order. */
    public void onEvent(TimelineEvent event) {
        switch (event) {
            case TimelineEvent.Outbound out -> onOutbound(out.sequence(), out.packet());
            case TimelineEvent.Acknowledged ack -> onAcknowledged(ack.transactionId());
            case TimelineEvent.Gap gap -> {
                for (String registry : FOLLOWED) {
                    add(registry, new Version(gap.sequence(), null));
                }
            }
            case TimelineEvent.Disconnected ignored -> pings.clear();
            default -> { }
        }
    }

    private void onOutbound(long seq, ClientboundPacket packet) {
        switch (packet) {
            case ClientboundPacket.Ping ping -> {
                pings.add(new long[] {ping.id(), seq});
                if (pings.size() > MAX_TRACKED_PINGS) {
                    pings.pollFirst(); // a later window end: wider, still sound
                }
            }
            case ClientboundPacket.UpdateTags update -> {
                for (ClientboundPacket.UpdateTags.RegistryTags registry : update.registries()) {
                    if (FOLLOWED.contains(registry.registry())) {
                        add(registry.registry(), new Version(seq, shared(registry)));
                        sentSinceLogin.add(registry.registry());
                    }
                }
            }
            case ClientboundPacket.Login ignored -> {
                // the play registries are made with what the configuration phase sent; the rest is not followed
                for (String registry : FOLLOWED) {
                    if (!sentSinceLogin.contains(registry)) {
                        add(registry, new Version(seq, null));
                    }
                }
                sentSinceLogin.clear();
            }
            case ClientboundPacket.Untranslatable ignored -> {
                for (String registry : FOLLOWED) {
                    add(registry, new Version(seq, null)); // it may have been a tag update
                }
            }
            default -> { }
        }
    }

    private void add(String registry, Version version) {
        revision++;
        List<Version> versions = registries.computeIfAbsent(registry, r -> new ArrayList<>());
        versions.add(version);
        if (versions.size() > MAX_VERSIONS) {
            // the two oldest as one, not known, from the first one's sequence (wider, still sound)
            Version first = versions.removeFirst();
            versions.set(0, new Version(first.seq(), null));
        }
    }

    private static Map<String, int[]> shared(ClientboundPacket.UpdateTags.RegistryTags registry) {
        synchronized (SHARED) {
            Map<String, int[]> tags = SHARED.get(registry);
            if (tags == null) {
                tags = new HashMap<>();
                for (ClientboundPacket.UpdateTags.Tag tag : registry.tags()) {
                    int[] ids = tag.ids().clone();
                    Arrays.sort(ids);
                    tags.put(tag.name(), ids);
                }
                tags = Map.copyOf(tags);
                SHARED.put(registry, tags);
            }
            return tags;
        }
    }

    private void onAcknowledged(int id) {
        long seq = Long.MIN_VALUE;
        for (long[] ping : pings) {
            if (ping[0] == id) {
                seq = ping[1];
                break;
            }
        }
        if (seq == Long.MIN_VALUE) {
            return;
        }
        while (!pings.isEmpty() && pings.peekFirst()[1] <= seq) {
            pings.pollFirst();
        }
        certain = Math.max(certain, seq);
        revision++;
        for (List<Version> versions : registries.values()) {
            while (versions.size() > 1 && versions.get(1).seq() < certain) {
                versions.removeFirst();
            }
        }
    }

    /** Changes whenever what the queries may answer changes (a new version, or a ping answered). */
    public long revision() {
        return revision;
    }

    private long windowEnd() {
        long[] first = pings.peekFirst();
        return first == null ? Long.MAX_VALUE : first[1];
    }

    // --- queries ----------------------------------------------------------------------------------------------------

    /**
     * The ids (sorted) the tag may hold now, one array per version of the registry the client may have; null if one
     * of them is not known (or the registry never was). A tag the version does not name is empty.
     */
    public List<int[]> possible(String registry, String tag) {
        List<Version> versions = registries.get(registry);
        if (versions == null || versions.isEmpty()) {
            return null;
        }
        long end = windowEnd();
        List<int[]> out = new ArrayList<>(1);
        for (int i = 0; i < versions.size(); i++) {
            Version v = versions.get(i);
            if (v.seq() >= end) {
                break;
            }
            Version next = i + 1 < versions.size() ? versions.get(i + 1) : null;
            if (next != null && next.seq() < certain) {
                continue; // certainly replaced
            }
            if (v.tags() == null) {
                return null;
            }
            out.add(v.tags().getOrDefault(tag, new int[0]));
        }
        return out.isEmpty() ? null : out;
    }

    /** Whether the tag holds the id in every version the client may have now (TRUE), in none (FALSE), or either (null). */
    public Boolean holds(String registry, String tag, int id) {
        List<int[]> possible = possible(registry, tag);
        if (possible == null) {
            return null;
        }
        boolean any = false;
        boolean all = true;
        for (int[] ids : possible) {
            boolean in = Arrays.binarySearch(ids, id) >= 0;
            any |= in;
            all &= in;
        }
        return all ? Boolean.TRUE : any ? null : Boolean.FALSE;
    }

    /** Whether the tag certainly holds exactly these ids (any order) in every version the client may have now. */
    public boolean certainlyExactly(String registry, String tag, int[] ids) {
        List<int[]> possible = possible(registry, tag);
        if (possible == null) {
            return false;
        }
        int[] expected = ids.clone();
        Arrays.sort(expected);
        for (int[] held : possible) {
            if (!Arrays.equals(held, expected)) {
                return false;
            }
        }
        return true;
    }
}
