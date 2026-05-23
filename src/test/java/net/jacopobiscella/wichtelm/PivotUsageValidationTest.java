package net.jacopobiscella.wichtelm;

import net.jacopobiscella.wichtelm.error.StrategyParseException;
import net.jacopobiscella.wichtelm.strategy.StrategyParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Pivot primitives resolve through a dedicated boolean-step path (the level
 * token stays symbolic and never enters the numeric arithmetic evaluator), so
 * they are only meaningful as a COMPLETE When/And step. Used anywhere a numeric
 * value is expected — inside a comparison, inside arithmetic, or as a Background
 * series — the symbolic level cannot be evaluated and the run would crash at
 * runtime. The parser must reject those usages deterministically (P14), not let
 * them through to a late runtime failure.
 */
class PivotUsageValidationTest {

    private static StrategyParseException parseFails(String strat) {
        return assertThrows(StrategyParseException.class,
                () -> StrategyParser.parse(strat, "pivot-usage.strat"));
    }

    private static String entry(String whenStep) {
        return "Feature: t\n  Primary timeframe: 1h\n\n"
                + "  Scenario: enter\n"
                + "    Given no open position\n"
                + "    When " + whenStep + "\n"
                + "    Then long_entry\n";
    }

    @Test
    void pivotInAComparisonIsRejected() {
        assertEquals("P14", parseFails(entry("price_above_pivot(R1) exceeds 0")).violatedRule());
    }

    @Test
    void pivotEmbeddedInArithmeticIsRejected() {
        assertEquals("P14",
                parseFails(entry("close is above price_above_pivot(R1) + 1")).violatedRule());
    }

    @Test
    void pivotInABackgroundSeriesIsRejected() {
        String strat = "Feature: t\n  Primary timeframe: 1h\n\n"
                + "  Background:\n"
                + "    Given a series gate defined as price_above_pivot(R1)\n\n"
                + "  Scenario: enter\n"
                + "    Given no open position\n"
                + "    When close is above 0\n"
                + "    Then long_entry\n";
        assertEquals("P14", parseFails(strat).violatedRule());
    }

    @Test
    void aBarePivotStepStillParses() {
        // Guard against over-rejection: the supported bare-step form must parse.
        assertDoesNotThrow(() -> StrategyParser.parse(
                entry("price_crosses_above_pivot(R1)"), "ok.strat"));
    }
}
