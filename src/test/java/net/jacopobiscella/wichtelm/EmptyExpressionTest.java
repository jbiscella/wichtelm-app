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

    @Test
    void trailingOperatorIsRejectedAtParseTime() {
        for (String bad : new String[] {"1 +", "3 * atr_value(14) +", "entry_price * "}) {
            StrategyParseException ex = assertThrows(StrategyParseException.class, () ->
                    StrategyParser.parse("""
                            Feature: Malformed arithmetic
                              Primary timeframe: 1h

                              Scenario: Enter
                                Given no open position
                                When close exceeds 1
                                Then long_entry
                                And with stop_loss at %s
                            """.formatted(bad), "malformed.strat"),
                    () -> "expected parse failure for: " + bad);
            assertEquals("P15", ex.violatedRule(), () -> "expected P15 for \"" + bad + "\", got " + ex.violatedRule());
        }
    }

    @Test
    void unexpectedCharacterIsRejectedAtParseTime() {
        // A char outside the supported arithmetic token set (e.g. a comparison
        // operator) must fail deterministically as P15 at parse time, not slip
        // through validate() and fail later in the evaluator.
        record Case(String clause, String expr) {
        }
        for (Case c : new Case[] {
                new Case("trailing_stop", "5 >"),
                new Case("stop_loss", "entry_price >"),
                new Case("stop_loss", "entry_price , 2")}) {
            StrategyParseException ex = assertThrows(StrategyParseException.class, () ->
                    StrategyParser.parse("""
                            Feature: Unexpected character
                              Primary timeframe: 1h

                              Scenario: Enter
                                Given no open position
                                When close exceeds 1
                                Then long_entry
                                And with %s at %s
                            """.formatted(c.clause(), c.expr()), "badchar.strat"),
                    () -> "expected parse failure for: " + c.clause() + " / " + c.expr());
            assertEquals("P15", ex.violatedRule(),
                    () -> "expected P15 for \"" + c.expr() + "\", got " + ex.violatedRule());
        }
    }

    @Test
    void wellFormedArithmeticIsAccepted() {
        // Guards against over-rejection by the structure validator.
        for (String ok : new String[] {
                "entry_price * (1 - stop_loss_pct / 100)",
                "entry_price - 2 * atr_value(14)",
                "3 * atr_value(14)"}) {
            StrategyParser.parse("""
                    Feature: Valid arithmetic
                      Primary timeframe: 1h
                      Parameter stop_loss_pct default 2

                      Scenario: Enter
                        Given no open position
                        When close exceeds 1
                        Then long_entry
                        And with stop_loss at %s
                    """.formatted(ok), "valid.strat");
        }
    }

    @Test
    void duplicateTrailingStopIsRejectedAtParseTime() {
        StrategyParseException ex = assertThrows(StrategyParseException.class, () ->
                StrategyParser.parse("""
                        Feature: Duplicate trailing
                          Primary timeframe: 1h

                          Scenario: Enter
                            Given no open position
                            When close exceeds 1
                            Then long_entry
                            And with trailing_stop at 5
                            And with trailing_stop at 8
                        """, "dup-trailing.strat"));
        assertEquals("P11", ex.violatedRule(), () -> "expected P11, got " + ex.violatedRule());
    }
}
