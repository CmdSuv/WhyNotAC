package dev.whynotac.analysis.policy;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.whynotac.analysis.evidence.Admissibility.IdentityTier;
import dev.whynotac.analysis.evidence.EvidenceClass;
import dev.whynotac.analysis.policy.ActionPolicy.Action;
import dev.whynotac.analysis.policy.ActionPolicy.Lifecycle;
import dev.whynotac.analysis.policy.ActionPolicy.Permission;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ActionPolicyTest {

    /** The matrix rows of docs/POLICY.md ("| **P-A** | ✔ | ..."), keyed by the row label. */
    private static Map<String, Permission[]> documentedMatrix() throws IOException {
        List<String> lines = Files.readAllLines(Path.of(System.getProperty("whynotac.policyDoc")), StandardCharsets.UTF_8);
        Map<String, Permission[]> rows = new HashMap<>();
        boolean inMatrix = false;
        for (String line : lines) {
            if (line.startsWith("## ")) {
                inMatrix = line.equals("## Eylem matrisi");
                continue;
            }
            if (!inMatrix || !line.startsWith("| **")) {
                continue;
            }
            String[] cells = line.split("\\|");
            String label = cells[1].strip().replace("*", "");
            Permission[] row = new Permission[Action.values().length];
            for (int i = 0; i < row.length; i++) {
                row[i] = switch (cells[i + 2].strip().codePointAt(0)) {
                    case '✔' -> Permission.ALLOWED;
                    case '⚙' -> Permission.OPERATOR_OPT_IN;
                    case 0x1F464 -> Permission.STAFF_ONLY; // 👤
                    case '✘' -> Permission.FORBIDDEN;
                    default -> throw new AssertionError("unknown symbol in POLICY.md: " + line);
                };
            }
            rows.put(label, row);
        }
        return rows;
    }

    @Test
    void theCodeIsTheDocumentCellByCell() throws IOException {
        Map<String, Permission[]> documented = documentedMatrix();
        assertEquals(EvidenceClass.values().length + 1, documented.size(), "one row per class, plus SHADOW: " + documented.keySet());
        for (EvidenceClass c : EvidenceClass.values()) {
            assertArrayEquals(documented.get(c.code()), ActionPolicy.row(c, Lifecycle.ARMED), c.code());
        }
        assertArrayEquals(documented.get("SHADOW"), ActionPolicy.row(EvidenceClass.ADVANTAGE_PROOF, Lifecycle.SHADOW), "SHADOW");
    }

    @Test
    void anOperatorCanNeverEnableAForbiddenOrStaffOnlyCell() {
        for (EvidenceClass c : EvidenceClass.values()) {
            for (Action a : Action.values()) {
                for (IdentityTier tier : IdentityTier.values()) {
                    for (Lifecycle lifecycle : Lifecycle.values()) {
                        Permission p = ActionPolicy.permission(c, a, tier, lifecycle);
                        boolean enabled = ActionPolicy.enabled(c, a, tier, lifecycle, true);
                        assertEquals(p == Permission.ALLOWED || p == Permission.OPERATOR_OPT_IN, enabled, c + " " + a + " " + tier + " " + lifecycle);
                        assertEquals(p == Permission.ALLOWED, ActionPolicy.enabled(c, a, tier, lifecycle, false));
                    }
                }
            }
        }
    }

    @Test
    void statisticsAndAiNeverPunish() {
        for (EvidenceClass c : List.of(EvidenceClass.DESIGN_STATISTIC, EvidenceClass.NOMINAL_STATISTIC, EvidenceClass.CONTEXT)) {
            for (Action a : List.of(Action.NEUTRALIZE, Action.KICK, Action.TEMP_BAN, Action.PERMANENT_BAN)) {
                for (IdentityTier tier : IdentityTier.values()) {
                    assertFalse(ActionPolicy.enabled(c, a, tier, Lifecycle.ARMED, true), c + " " + a);
                }
            }
        }
    }

    @Test
    void anUnauthenticatedIdentityIsNeverBannedAutomatically() {
        assertEquals(Permission.OPERATOR_OPT_IN,
                ActionPolicy.permission(EvidenceClass.ADVANTAGE_PROOF, Action.TEMP_BAN, IdentityTier.AUTHENTICATED, Lifecycle.ARMED));
        assertEquals(Permission.STAFF_ONLY,
                ActionPolicy.permission(EvidenceClass.ADVANTAGE_PROOF, Action.TEMP_BAN, IdentityTier.UNAUTHENTICATED, Lifecycle.ARMED));
        for (EvidenceClass c : EvidenceClass.values()) {
            for (Action a : List.of(Action.TEMP_BAN, Action.PERMANENT_BAN)) {
                assertFalse(ActionPolicy.enabled(c, a, IdentityTier.UNAUTHENTICATED, Lifecycle.ARMED, true), c + " " + a);
            }
        }
    }

    @Test
    void aShadowCheckOnlyRecordsAndAlerts() {
        for (EvidenceClass c : EvidenceClass.values()) {
            for (Action a : Action.values()) {
                boolean expected = a == Action.ALERT || a == Action.REVIEW;
                assertEquals(expected, ActionPolicy.enabled(c, a, IdentityTier.AUTHENTICATED, Lifecycle.SHADOW, true), c + " " + a);
            }
        }
        assertTrue(ActionPolicy.enabled(EvidenceClass.ADVANTAGE_PROOF, Action.NEUTRALIZE, IdentityTier.UNAUTHENTICATED, Lifecycle.ARMED, false));
    }
}
