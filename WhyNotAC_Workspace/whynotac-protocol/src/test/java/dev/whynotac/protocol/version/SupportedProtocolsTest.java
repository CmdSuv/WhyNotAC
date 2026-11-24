package dev.whynotac.protocol.version;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class SupportedProtocolsTest {

    @Test
    void verifiesProtocol774AndRejectsUnverifiedVersions() {
        assertTrue(SupportedProtocols.isFullyVerified("774"));
        assertFalse(SupportedProtocols.isFullyVerified("773"));
        assertFalse(SupportedProtocols.isFullyVerified("775"));
        assertFalse(SupportedProtocols.isFullyVerified(null));

        SupportedProtocols.ProtocolSpec spec = SupportedProtocols.find(774).orElseThrow();
        assertEquals("1.21.11", spec.minecraftVersion());
        assertTrue(spec.physicsVerified());
        assertTrue(spec.combatVerified());
        assertTrue(spec.clockAndOrderVerified());
        assertTrue(spec.fullyVerified());
        assertEquals(SupportedProtocols.VersionedTables.MC_1_21_11, SupportedProtocols.tablesFor(774).orElseThrow());
        assertTrue(SupportedProtocols.tablesFor(999).isEmpty());
        assertTrue(SupportedProtocols.supportsTranslatorSafe("viaversion", false));
        assertFalse(SupportedProtocols.supportsTranslatorSafe("viaversion", true));
    }

    @Test
    void registersAdditionalProtocolSpecsWithoutMutatingBuiltIn774() {
        int futureProto = 9999;
        try {
            SupportedProtocols.VersionedTables tables = new SupportedProtocols.VersionedTables(
                    "26.3",
                    "/dev/whynotac/world/v26_3/block-states.txt.gz",
                    "/dev/whynotac/world/v26_3/entity-sizes.tsv",
                    "/dev/whynotac/world/v26_3/item-prototypes.tsv");
            SupportedProtocols.ProtocolSpec staged = new SupportedProtocols.ProtocolSpec(
                    futureProto, "26.3", false, true, true, tables);
            SupportedProtocols.registerAdditional(staged);
            assertTrue(SupportedProtocols.find(futureProto).isPresent());
            assertFalse(SupportedProtocols.isFullyVerified(Integer.toString(futureProto)),
                    "Staged spec with physicsVerified=false must not claim full verification");
            assertEquals(tables, SupportedProtocols.tablesFor(futureProto).orElseThrow());

            // Built-in 774 baseline cannot be overwritten
            assertThrows(IllegalArgumentException.class, () -> SupportedProtocols.registerAdditional(
                    new SupportedProtocols.ProtocolSpec(774, "tampered", false, false, false, tables)));
            assertTrue(SupportedProtocols.isFullyVerified("774"));
        } finally {
            SupportedProtocols.unregisterForTest(futureProto);
        }
    }
}
