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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * Gap-aware protective-exit fills (CLAUDE.md §19). When a bar opens BEYOND a
 * protective level in the breach direction, the price never traded at the level
 * on that bar, so the realistic fill is the bar's open — not the stale level.
 * When the level is within the bar's traded range the fill is unchanged (the
 * level). AAA unit tests mirroring the §19.3 scenarios; the Gherkin signal-emission
 * scenarios assume non-gapping bars.
 */
class GapFillTest {

    private static OHLCBar bar(Instant t, double o, double h, double l, double c) {
        return new OHLCBar(t, BigDecimal.valueOf(o), BigDecimal.valueOf(h),
                BigDecimal.valueOf(l), BigDecimal.valueOf(c), Optional.empty());
    }

    private static WichtelmSignalGenerator longStopGenerator() {
        ParsedStrategy strategy = StrategyParser.parse("""
                Feature: Gap stop
                  Primary timeframe: 1h

                  Scenario: Enter
                    Given no open position
                    When close exceeds 1
                    Then long_entry
                    And with stop_loss at entry_price * 0.98
                """, "gapstop.strat");
        return new WichtelmSignalGenerator(strategy, Map.of(), BigDecimal.valueOf(50), false);
    }

    @Test
    void longStopGappedThroughFillsAtTheBarOpenNotTheStaleLevel() {
        // Arrange: a long opened at 100 with stop_loss at 98.
        WichtelmSignalGenerator generator = longStopGenerator();
        Instant fill = Instant.parse("2022-03-15T02:00:00Z");
        Position position = new Position(Direction.LONG, BigDecimal.ONE, fill, BigDecimal.valueOf(100));
        BigDecimal equity = BigDecimal.valueOf(10000);
        // The next in-position bar OPENS at 95 — already below the 98 stop (a gap-through).
        OHLCBar gapBar = bar(fill, 95, 96, 94, 95);

        // Act
        Signal signal = generator.generate(new BarContext(gapBar, List.of(),
                Optional.of(position), equity, equity, 1));

        // Assert: fills at the open (95), not the stale level (98).
        Signal.ClosePositionAtPrice exit = assertInstanceOf(Signal.ClosePositionAtPrice.class, signal);
        assertEquals(0, BigDecimal.valueOf(95).compareTo(exit.price()),
                () -> "expected fill at the gapped-through open 95, got " + exit.price());
    }

    @Test
    void intrabarTouchWithoutAGapStillFillsAtTheLevel() {
        // Arrange: a long opened at 100 with stop_loss at 98.
        WichtelmSignalGenerator generator = longStopGenerator();
        Instant fill = Instant.parse("2022-03-15T02:00:00Z");
        Position position = new Position(Direction.LONG, BigDecimal.ONE, fill, BigDecimal.valueOf(100));
        BigDecimal equity = BigDecimal.valueOf(10000);
        // Opens at 99 (above the stop), then trades down through 98 intrabar (low 97).
        OHLCBar touchBar = bar(fill, 99, 99.5, 97, 98.5);

        // Act
        Signal signal = generator.generate(new BarContext(touchBar, List.of(),
                Optional.of(position), equity, equity, 1));

        // Assert: fills exactly at the level (98) — unchanged behaviour.
        Signal.ClosePositionAtPrice exit = assertInstanceOf(Signal.ClosePositionAtPrice.class, signal);
        assertEquals(0, BigDecimal.valueOf(98).compareTo(exit.price()),
                () -> "expected fill at the level 98, got " + exit.price());
    }

    @Test
    void longTrailingStopGappedThroughFillsAtTheBarOpen() {
        // Arrange: a long opened at 100 with a 10% trailing stop.
        ParsedStrategy strategy = StrategyParser.parse("""
                Feature: Gap trailing
                  Primary timeframe: 1h

                  Scenario: Enter
                    Given no open position
                    When close exceeds 1
                    Then long_entry
                    And with trailing_stop at 10
                """, "gaptrail.strat");
        WichtelmSignalGenerator generator =
                new WichtelmSignalGenerator(strategy, Map.of(), BigDecimal.valueOf(50), false);
        Instant fill = Instant.parse("2022-03-15T02:00:00Z");
        Position position = new Position(Direction.LONG, BigDecimal.ONE, fill, BigDecimal.valueOf(100));
        BigDecimal equity = BigDecimal.valueOf(10000);

        // A bar that prints a high of 120 sets the high-water mark to 120
        // (trailing level for the NEXT bar = 120 * 0.90 = 108).
        OHLCBar hwmBar = bar(fill, 110, 120, 109, 119);
        generator.generate(new BarContext(hwmBar, List.of(), Optional.of(position), equity, equity, 1));

        // The next bar OPENS at 105 — already below the 108 trailing level (gap-through).
        Instant t2 = fill.plus(Duration.ofHours(1));
        OHLCBar gapBar = bar(t2, 105, 106, 104, 105);

        // Act
        Signal signal = generator.generate(new BarContext(gapBar, List.of(hwmBar),
                Optional.of(position), equity, equity, 2));

        // Assert: fills at the open (105), not the trailing level (108).
        Signal.ClosePositionAtPrice exit = assertInstanceOf(Signal.ClosePositionAtPrice.class, signal);
        assertEquals(0, BigDecimal.valueOf(105).compareTo(exit.price()),
                () -> "expected fill at the gapped-through open 105, got " + exit.price());
    }
}
