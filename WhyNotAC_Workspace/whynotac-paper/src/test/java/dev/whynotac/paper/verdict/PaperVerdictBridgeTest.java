package dev.whynotac.paper.verdict;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.whynotac.verdict.VerdictConfig;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.bukkit.Location;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

class PaperVerdictBridgeTest {

    @Test
    void loadsDefaultsWhenSectionMissingAndFiltersUnknownArmedChecks() {
        assertEquals(VerdictConfig.shadowDefaults(), PaperVerdictBridge.loadConfig(null, Logger.getLogger("test")));

        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("verdict.armed-checks", java.util.List.of("attack-reach", "nonexistent-check", "movement"));
        yaml.set("verdict.mitigations", true);
        yaml.set("verdict.auto-kick", false);
        yaml.set("verdict.auto-ban", true);
        yaml.set("verdict.temp-ban-hours", 48);
        yaml.set("verdict.max-enforced-players-per-minute", 9);
        yaml.set("verdict.max-false-ban-budget", 2);

        VerdictConfig cfg = PaperVerdictBridge.loadConfig(yaml.getConfigurationSection("verdict"), Logger.getLogger("test"));
        assertEquals(Set.of("attack-reach", "movement"), cfg.armedChecks());
        assertTrue(cfg.mitigationsEnabled());
        assertFalse(cfg.autoKickEnabled());
        assertTrue(cfg.autoBanEnabled());
        assertEquals(TimeUnit.HOURS.toMillis(48), cfg.tempBanDurationMillis());
        assertEquals(9, cfg.maxUniquePenalizedPlayersPerMinute());
        assertEquals(2, cfg.maxFalseBanBudgetPerWindow());
    }

    @Test
    void mitigationTtlBridgesReportCooldownAndSafeTrailSelectsEarliestStep() {
        assertTrue(PaperVerdictBridge.MITIGATION_TTL_NANOS > TimeUnit.SECONDS.toNanos(1),
                "mitigation TTL must exceed 1s check report cooldown to eliminate unmitigated gaps");

        Deque<Location> trail = new ArrayDeque<>();
        PaperVerdictBridge.recordSafeStep(trail, new Location(null, 1.0, 64.0, 1.0));
        PaperVerdictBridge.recordSafeStep(trail, new Location(null, 2.0, 64.0, 1.0));
        PaperVerdictBridge.recordSafeStep(trail, new Location(null, 3.0, 64.0, 1.0));
        PaperVerdictBridge.recordSafeStep(trail, new Location(null, 4.0, 64.0, 1.0));
        Location earliest = PaperVerdictBridge.earliestSafeLocation(trail);
        assertNotNull(earliest);
        assertEquals(2.0, earliest.getX(), 1e-9);
    }

    @Test
    void allShippedDeploymentProfilesLoadWithoutWarningsAndKeepAutoBanDisabled() {
        List<String> warnings = new ArrayList<>();
        Logger capturing = Logger.getLogger("deploy-config-test");
        capturing.setUseParentHandlers(false);
        Handler handler = new Handler() {
            @Override
            public void publish(LogRecord r) {
                warnings.add(r.getMessage());
            }

            @Override
            public void flush() {}

            @Override
            public void close() {}
        };
        capturing.addHandler(handler);
        try {
            for (String profile : List.of("paper-shadow", "paper-mitigate-pilot", "paper-velocity-backend")) {
                Path cfgPath = Path.of("..", "deploy", "configs", profile, "config.yml").normalize();
                if (!Files.exists(cfgPath)) {
                    cfgPath = Path.of("deploy", "configs", profile, "config.yml");
                }
                assertTrue(Files.exists(cfgPath), "Missing deployment profile: " + cfgPath);
                YamlConfiguration yaml = YamlConfiguration.loadConfiguration(cfgPath.toFile());
                VerdictConfig cfg = PaperVerdictBridge.loadConfig(yaml.getConfigurationSection("verdict"), capturing);
                assertFalse(cfg.autoBanEnabled(), profile + " must ship with auto-ban disabled");
                assertFalse(cfg.autoKickEnabled(), profile + " must ship with auto-kick disabled");
                if (profile.equals("paper-mitigate-pilot")) {
                    assertTrue(cfg.mitigationsEnabled());
                    assertTrue(cfg.hidesInformationEnabled());
                    assertEquals(11, cfg.armedChecks().size());
                    assertTrue(cfg.armedChecks().contains("velocity-response"));
                    assertTrue(cfg.armedChecks().contains("inventory-protocol"));
                } else {
                    assertTrue(cfg.armedChecks().isEmpty(), profile + " must default to SHADOW");
                    assertFalse(cfg.mitigationsEnabled());
                }
            }
            assertEquals(List.of(), warnings, "All shipped profiles must reference valid check ids");
        } finally {
            capturing.removeHandler(handler);
        }
    }
}
