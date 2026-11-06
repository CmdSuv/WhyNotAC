package dev.whynotac.analysis.evidence;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;
import org.junit.jupiter.api.Test;

class MarginsTest {

    @Test
    void countsEachCheckAndKeepsItsSmallestSlack() {
        Margins margins = new Margins();
        margins.add("b", 3.0);
        margins.add("a", 0.5);
        margins.add("b", 1.25);
        margins.add("b", Double.NaN); // no claim: not an observation
        margins.add("b", 2.0);
        assertEquals(Map.of("a", new Margins.Margin(1, 0.5), "b", new Margins.Margin(3, 1.25)), margins.snapshot());
    }
}
