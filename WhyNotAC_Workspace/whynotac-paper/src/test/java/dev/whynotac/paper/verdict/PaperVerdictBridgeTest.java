package dev.whynotac.paper.verdict;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.whynotac.verdict.VerdictConfig;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
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
}
