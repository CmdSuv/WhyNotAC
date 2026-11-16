package dev.whynotac.storage;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class SubjectProfileStoreTest {

    @Test
    void persistsReloadsPreservesWithCaseCountersAndErasesBaselineFeatures(@TempDir Path dir) throws IOException {
        SubjectProfileStore store = new SubjectProfileStore(dir);
        UUID player = UUID.randomUUID();

        Map<String, SubjectProfileStore.FeatureSummary> baseline = Map.of(
                "click_interval_ticks", new SubjectProfileStore.FeatureSummary(120L, 4.85, 0.36),
                "aim_angular_step_deg", new SubjectProfileStore.FeatureSummary(450L, 3.125, 1.44));
        store.saveBaselineFeatures(player, baseline, 1000L);
        store.recordCase(player, "click-pattern", 2000L);

        Optional<SubjectProfileStore.SubjectProfile> loaded = store.find(player);
        assertTrue(loaded.isPresent());
        SubjectProfileStore.SubjectProfile p = loaded.get();
        assertEquals(1L, p.totalCases());
        assertEquals(1L, p.casesByCheck().get("click-pattern"));
        assertEquals(2, p.baselineFeatures().size());
        assertEquals(120L, p.baselineFeatures().get("click_interval_ticks").count());
        assertEquals(4.85, p.baselineFeatures().get("click_interval_ticks").mean(), 1e-6);
        assertEquals(0.36, p.baselineFeatures().get("click_interval_ticks").sampleVariance(), 1e-6);

        assertTrue(store.forget(player));
        assertFalse(store.find(player).isPresent());
    }
}
