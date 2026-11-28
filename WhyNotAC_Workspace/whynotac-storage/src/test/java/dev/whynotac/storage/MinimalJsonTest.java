package dev.whynotac.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;

final class MinimalJsonTest {

    @Test
    void parsesEscapesUnicodeScientificNumbersAndReorderedFields() {
        String jsonA = """
                {
                  "note": "line1\\nline2\\t\\"quoted\\"\\\\slash\\/\\u0041\\u2603",
                  "sci": -1.25e-4,
                  "big": 9007199254740991,
                  "flag": true,
                  "nil": null,
                  "nested": { "arr": [1, 2.5E+2, false, "ok"] }
                }
                """;
        String jsonB = """
                {
                  "nested": { "arr": [1, 2.5E+2, false, "ok"] },
                  "nil": null,
                  "flag": true,
                  "big": 9007199254740991,
                  "sci": -1.25e-4,
                  "note": "line1\\nline2\\t\\"quoted\\"\\\\slash\\/\\u0041\\u2603"
                }
                """;
        Map<String, Object> a = MinimalJson.parseObject(jsonA);
        Map<String, Object> b = MinimalJson.parseObject(jsonB);
        assertEquals(a, b, "Field order must never alter parsed object equality");
        assertEquals("line1\nline2\t\"quoted\"\\slash/A\u2603", MinimalJson.getString(a, "note"));
        assertEquals(-1.25e-4, MinimalJson.getDouble(a, "sci", 0.0), 1e-12);
        assertEquals(9007199254740991L, MinimalJson.getLong(a, "big", 0L));
        assertTrue(Boolean.TRUE.equals(a.get("flag")));
        assertNull(a.get("nil"));
        Map<String, Object> nested = MinimalJson.getObject(a, "nested");
        List<Object> arr = MinimalJson.getArray(nested, "arr");
        assertEquals(4, arr.size());
        assertEquals(250.0, ((Number) arr.get(1)).doubleValue(), 1e-9);
        assertFalse((Boolean) arr.get(2));
    }

    @Test
    void enforcesMaxDepthDoSGuardAndRejectsMalformedInputs() {
        StringBuilder deep = new StringBuilder();
        for (int i = 0; i <= MinimalJson.MAX_DEPTH + 1; i++) {
            deep.append('[');
        }
        for (int i = 0; i <= MinimalJson.MAX_DEPTH + 1; i++) {
            deep.append(']');
        }
        assertThrows(IllegalArgumentException.class, () -> MinimalJson.parse(deep.toString()));
        assertThrows(IllegalArgumentException.class, () -> MinimalJson.parse("{\"a\":1} trailing"));
        assertThrows(IllegalArgumentException.class, () -> MinimalJson.parse("{\"unterminated\":\"abc}"));
        assertThrows(IllegalArgumentException.class, () -> MinimalJson.parse("[1, 2,]"));
        assertThrows(IllegalArgumentException.class, () -> MinimalJson.parse("\"\\uZZZZ\""));
        assertThrows(IllegalArgumentException.class, () -> MinimalJson.parseObject("[1,2,3]"));
    }

    @Test
    void handlesSpecialEscapedStringsInsideStringValuesWithoutRegexCorruption() {
        // Classic regex-parser breaker: string values containing JSON-like key/value tokens & braces
        String adversarial = "{\"summary\":\"fake \\\"status\\\":\\\"OVERTURNED\\\", } { [ ]\",\"status\":\"OPEN\"}";
        Map<String, Object> obj = MinimalJson.parseObject(adversarial);
        assertEquals("OPEN", MinimalJson.getString(obj, "status"));
        assertEquals("fake \"status\":\"OVERTURNED\", } { [ ]", MinimalJson.getString(obj, "summary"));
    }

    @Test
    void deterministicMalformedInputFuzzingNeverThrowsNonIllegalArgument() {
        Random rng = new Random(0x2301_F022L);
        String alphabet = "{}[]\":,truefalsn0123456789.-+eE\\u \t\r\n";
        for (int trial = 0; trial < 2_000; trial++) {
            int len = 1 + rng.nextInt(48);
            StringBuilder sb = new StringBuilder(len);
            for (int i = 0; i < len; i++) {
                sb.append(alphabet.charAt(rng.nextInt(alphabet.length())));
            }
            try {
                MinimalJson.parse(sb.toString());
            } catch (IllegalArgumentException expected) {
                // Only IllegalArgumentException is permitted on malformed input
            }
        }
    }
}
