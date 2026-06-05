package net.jacopobiscella.wichtelm;

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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The protective-exit fill instant must land strictly inside the breaching bar
 * regardless of bar spacing. An intraday series can contain bars spaced closer
 * than the nominal timeframe (irregular / sub-timeframe spacing is observed in
 * real provider data), so the NEXT bar can open sooner than {@code open +
 * timeframe}; the old nominal-midpoint fill ({@code open + timeframe/2}) then
 * fell AFTER that next bar and frau-holle rejected the {@code ClosePositionAtPrice}
 * signal, aborting the backtest.
 * The generator cannot see the next bar (lookahead-safety), so the fill must be
 * a minimal instant after the bar's open — strictly before any sub-timeframe
 * next bar. AAA unit test; the Gherkin scenarios assume a regular bar grid.
 */
class IntrabarFillTimeTest {

    @Test
    void protectiveExitFillLandsBeforeASubTimeframeNextBar() {
        // Arrange: a long strategy with a fixed stop at entry_price * 0.98.
        ParsedStrategy strategy = StrategyParser.parse("""
                Feature: Sub-nominal bar fill
                  Primary timeframe: 1h

                  Scenario: Enter
                    Given no open position
                    When close exceeds 1
                    Then long_entry
                    And with stop_loss at entry_price * 0.98
                """, "subnominal.strat");
        WichtelmSignalGenerator generator =
                new WichtelmSignalGenerator(strategy, Map.of(), BigDecimal.valueOf(50), false);

        Instant t0 = Instant.parse("2022-06-14T01:00:00Z");
        BigDecimal equity = BigDecimal.valueOf(10000);
        OHLCBar bar0 = new OHLCBar(t0, BigDecimal.valueOf(100), BigDecimal.valueOf(101),
                BigDecimal.valueOf(99), BigDecimal.valueOf(100), Optional.empty());
        assertInstanceOf(Signal.Buy.class,
                generator.generate(new BarContext(bar0, List.of(), Optional.empty(), equity, equity, 0)));

        // A long position opened at 100 — its stop sits at 98.
        Instant fill = t0.plus(Duration.ofHours(1)); // 02:00:00Z
        Position position = new Position(Direction.LONG, BigDecimal.ONE, fill, BigDecimal.valueOf(100));
        // The breaching bar: its low (96) trips the stop at 98.
        OHLCBar breachBar = new OHLCBar(fill, BigDecimal.valueOf(100), BigDecimal.valueOf(100),
                BigDecimal.valueOf(96), BigDecimal.valueOf(97), Optional.empty());

        // Act: the stop fires on this bar.
        Signal signal = generator.generate(new BarContext(breachBar, List.of(bar0),
                Optional.of(position), equity, equity, 1));

        // Assert: the fill is strictly after the bar's open and strictly before a
        // sub-timeframe next bar (the real EODHD filler at +29min). The old
        // nominal midpoint (+30min) would violate the second bound.
        Signal.ClosePositionAtPrice exit = assertInstanceOf(Signal.ClosePositionAtPrice.class, signal);
        Instant subNominalNextBar = fill.plus(Duration.ofMinutes(29)); // 02:29:00Z
        assertTrue(exit.fillTime().isAfter(fill),
                () -> "fill time " + exit.fillTime() + " not after bar open " + fill);
        assertTrue(exit.fillTime().isBefore(subNominalNextBar),
                () -> "fill time " + exit.fillTime() + " not before sub-timeframe next bar " + subNominalNextBar);
    }
}
