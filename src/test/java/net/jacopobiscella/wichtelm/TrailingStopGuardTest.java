package net.jacopobiscella.wichtelm;

import net.jacopobiscella.wichtelm.error.DslEvaluationException;
import net.jacopobiscella.wichtelm.runtime.WichtelmSignalGenerator;
import net.jacopobiscella.wichtelm.strategy.ParsedStrategy;
import net.jacopobiscella.wichtelm.strategy.StrategyParser;
import org.hatrack.commons.OHLCBar;
import org.hatrack.frauholle.model.BarContext;
import org.hatrack.frauholle.model.Direction;
import org.hatrack.frauholle.model.Position;
import org.hatrack.frauholle.model.Signal;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * A compound trailing_stop expression that evaluates non-positive (e.g.
 * {@code 10 - 30}) slips past the parser's bare-literal/parameter P21 check, so
 * the runtime must reject it rather than compute an impossible stop level
 * (a long stop above the high-water mark, filled outside the bar range). AAA
 * unit test — the Gherkin scenarios cover only positive, valid trailing values.
 */
class TrailingStopGuardTest {

    @Test
    void compoundNonPositiveTrailingValueThrowsAtRuntime() {
        // Arrange: a strategy whose trailing distance evaluates to -20.
        ParsedStrategy strategy = StrategyParser.parse("""
                Feature: Negative trailing guard
                  Primary timeframe: 1h

                  Scenario: Enter
                    Given no open position
                    When close exceeds 1
                    Then long_entry
                    And with trailing_stop at 10 - 30
                """, "neg-trailing.strat");
        WichtelmSignalGenerator generator =
                new WichtelmSignalGenerator(strategy, Map.of(), BigDecimal.valueOf(50), false);
        Instant t0 = Instant.parse("2024-01-01T00:00:00Z");
        BigDecimal equity = BigDecimal.valueOf(10000);
        OHLCBar bar0 = new OHLCBar(t0, BigDecimal.valueOf(100), BigDecimal.valueOf(101),
                BigDecimal.valueOf(99), BigDecimal.valueOf(100), Optional.empty());
        assertInstanceOf(Signal.Buy.class,
                generator.generate(new BarContext(bar0, List.of(), Optional.empty(), equity, equity, 0)));

        Instant fill = t0.plus(Duration.ofHours(1));
        Position position = new Position(Direction.LONG, BigDecimal.ONE, fill, BigDecimal.valueOf(100));
        OHLCBar fillBar = new OHLCBar(fill, BigDecimal.valueOf(100), BigDecimal.valueOf(100),
                BigDecimal.valueOf(100), BigDecimal.valueOf(100), Optional.empty());

        // Act + Assert: evaluating the trailing stop on the in-position bar fails loud.
        assertThrows(DslEvaluationException.class, () ->
                generator.generate(new BarContext(fillBar, List.of(bar0),
                        Optional.of(position), equity, equity, 1)));
    }
}
