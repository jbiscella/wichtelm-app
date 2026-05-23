package net.jacopobiscella.wichtelm;

import net.jacopobiscella.wichtelm.error.StrategyParseException;
import net.jacopobiscella.wichtelm.strategy.StrategyParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Parse-time validation of period-typed arguments (review hardening on the 0.52
 * line). A period is a bar count: the prepass / indicator layer calls
 * {@code intValueExact()} on it, so a malformed period must fail deterministically
 * at parse time (P21 / P16) rather than crashing later. Two gaps this pins:
 *
 * <ul>
 *   <li>{@code atr_value(period)} accepted a trade-context variable
 *       ({@code entry_price} / {@code position_size}) as its "period" — those
 *       are valid stop/take identifiers but are prices, not integer periods.</li>
 *   <li>A {@code Parameter} with a fractional default used as a period
 *       ({@code Parameter p default 2.5} + {@code price_above_ema(p)}) slipped
 *       past the literal-only period check and crashed at prepass build.</li>
 * </ul>
 */
class PeriodArgumentValidationTest {

    private static StrategyParseException parseExpectingFailure(String strat) {
        return assertThrows(StrategyParseException.class,
                () -> StrategyParser.parse(strat, "period-arg.strat"));
    }

    private static String entryStrategy(String descriptionLines, String entryStep, String stopClause) {
        return "Feature: t\n  Primary timeframe: 1h\n" + descriptionLines
                + "\n  Scenario: enter\n"
                + "    Given no open position\n"
                + "    When " + entryStep + "\n"
                + "    Then long_entry\n"
                + stopClause;
    }

    @Test
    void atrValueRejectsAnEntryPricePeriod() {
        // entry_price is a valid stop/take identifier, but it is a price, not a
        // bar-count period — atr_value(entry_price) must not parse.
        StrategyParseException ex = parseExpectingFailure(entryStrategy("",
                "close is above 1", "    And with stop_loss at entry_price - atr_value(entry_price)\n"));
        assertEquals("P16", ex.violatedRule());
    }

    @Test
    void atrValueRejectsAPositionSizePeriod() {
        StrategyParseException ex = parseExpectingFailure(entryStrategy("",
                "close is above 1", "    And with stop_loss at entry_price - atr_value(position_size)\n"));
        assertEquals("P16", ex.violatedRule());
    }

    @Test
    void fractionalParameterUsedAsMaPeriodIsRejected() {
        // Parameter p default 2.5 used as an EMA period would crash at prepass
        // (intValueExact); reject it deterministically at parse time.
        StrategyParseException ex = parseExpectingFailure(entryStrategy(
                "  Parameter p default 2.5\n", "price_above_ema(p)", ""));
        assertEquals("P21", ex.violatedRule());
    }

    @Test
    void fractionalParameterUsedAsAtrValuePeriodIsRejected() {
        StrategyParseException ex = parseExpectingFailure(entryStrategy(
                "  Parameter p default 2.5\n", "close is above 1",
                "    And with stop_loss at entry_price - atr_value(p)\n"));
        assertEquals("P21", ex.violatedRule());
    }

    @Test
    void integerLiteralAndIntegerParameterPeriodsStillParse() {
        // Guard against over-rejection: valid integer-literal and integer-
        // parameter periods must keep parsing.
        assertDoesNotThrow(() -> StrategyParser.parse(entryStrategy(
                "  Parameter ema_p default 50\n", "price_above_ema(ema_p)",
                "    And with stop_loss at entry_price - 2 * atr_value(14)\n"), "ok.strat"));
    }
}
