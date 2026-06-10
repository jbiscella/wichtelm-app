package net.jacopobiscella.wichtelm;

import net.jacopobiscella.wichtelm.strategy.TrailingStops;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link TrailingStops#isAtrDistanceMode} is the single source of truth shared by
 * the runtime and the HTML report (§3.4.1). Two past divergences this locks down:
 * the report once used a {@code contains("atr_value")} substring test (so a
 * percentage-mode parameter merely containing those letters was wrongly read as
 * ATR-distance), and a whole-number-decimal period like {@code atr_value(14.0)}
 * (valid per P21) must still register as a call. AAA unit test.
 */
class TrailingStopsModeTest {

    @Test
    void atrValueCallIsDistanceMode() {
        assertTrue(TrailingStops.isAtrDistanceMode("3 * atr_value(14)"));
        assertTrue(TrailingStops.isAtrDistanceMode("atr_value(14.0)"));        // P21-valid decimal period
        assertTrue(TrailingStops.isAtrDistanceMode("2 * atr_value ( 14 )"));   // whitespace tolerated
    }

    @Test
    void percentageExpressionsAreNotDistanceMode() {
        assertFalse(TrailingStops.isAtrDistanceMode("8"));
        assertFalse(TrailingStops.isAtrDistanceMode("trail_pct"));
        assertFalse(TrailingStops.isAtrDistanceMode("atr_value_pct"));         // identifier, not a call
    }
}
