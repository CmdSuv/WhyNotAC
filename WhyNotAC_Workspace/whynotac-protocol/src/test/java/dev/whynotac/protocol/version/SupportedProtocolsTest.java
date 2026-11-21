package dev.whynotac.protocol.version;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
}
