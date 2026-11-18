package dev.whynotac.analysis.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

final class BenchmarkRunnerTest {

    @Test
    void benchmarkSuitePassesAllSyntheticScenariosWithZeroHonestFalsePositives() throws Exception {
        BenchmarkRunner.BenchmarkReport report = BenchmarkRunner.run(Path.of("../regression"));
        assertEquals(0, report.honestFalsePositives(), "Honest recordings must produce 0 false positives");
        assertEquals(report.scenariosTotal(), report.scenariosPassed(),
                "All synthetic benchmark scenarios must pass: " + report.scenarioFindings());
        assertTrue(report.allPassed());
    }
}
