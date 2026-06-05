package net.jacopobiscella.wichtelm;

import net.jacopobiscella.wichtelm.error.StrategyParseException;
import net.jacopobiscella.wichtelm.strategy.StrategyParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * An expression with no operand — {@code ()}, empty, or a lone operator — is
 * balanced and token-clean, so it slipped past {@code analyzeExpression} and only
 * failed opaquely at evaluation time. It must be rejected deterministically at
 * parse time (P15). AAA unit test.
 */
class EmptyExpressionTest {

    @Test
    void emptyParensTrailingStopIsRejectedAtParseTime() {
        StrategyParseException ex = assertThrows(StrategyParseException.class, () ->
                StrategyParser.parse("""
                        Feature: Empty trailing expression
                          Primary timeframe: 1h

                          Scenario: Enter
                            Given no open position
                            When close exceeds 1
                            Then long_entry
                            And with trailing_stop at ()
                        """, "empty-expr.strat"));
        assertEquals("P15", ex.violatedRule(), () -> "expected P15, got " + ex.violatedRule());
    }

    @Test
    void emptyParensStopLossIsRejectedAtParseTime() {
        StrategyParseException ex = assertThrows(StrategyParseException.class, () ->
                StrategyParser.parse("""
                        Feature: Empty stop expression
                          Primary timeframe: 1h

                          Scenario: Enter
                            Given no open position
                            When close exceeds 1
                            Then long_entry
                            And with stop_loss at ()
                        """, "empty-stop.strat"));
        assertEquals("P15", ex.violatedRule(), () -> "expected P15, got " + ex.violatedRule());
    }
}
