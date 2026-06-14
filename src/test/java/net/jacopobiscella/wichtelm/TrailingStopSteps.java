package net.jacopobiscella.wichtelm;

import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import net.jacopobiscella.wichtelm.runtime.WichtelmSignalGenerator;
import net.jacopobiscella.wichtelm.strategy.ParsedStrategy;
import net.jacopobiscella.wichtelm.strategy.StrategyParser;
import org.hatrack.commons.OHLCBar;
import org.hatrack.frauholle.model.BarContext;
import org.hatrack.frauholle.model.Direction;
import org.hatrack.frauholle.model.Position;
import org.hatrack.frauholle.model.Signal;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * Step definitions for {@code trailing-stops.feature} (Block 4). Drives the
 * generator across several bars with a single persistent instance so the
 * high-water mark accumulates exactly as it does in a real backtest.
 */
public class TrailingStopSteps {

    private static final Instant T0 = Instant.parse("2024-01-01T00:00:00Z");
    private static final BigDecimal EQUITY = BigDecimal.valueOf(10000);

    private WichtelmSignalGenerator generator;
    private Position position;
    private Instant entryTime;
    private List<OHLCBar> history = new ArrayList<>();
    private List<OHLCBar> warmupHistory = new ArrayList<>();
    private int barCounter;
    private Signal emitted;

    private static OHLCBar bar(Instant t, double open, double high, double low, double close) {
        return new OHLCBar(t, BigDecimal.valueOf(open), BigDecimal.valueOf(high),
                BigDecimal.valueOf(low), BigDecimal.valueOf(close), Optional.empty());
    }

    @Given("a constant-range warmup history of {int} bars with range {double}")
    public void aConstantRangeWarmupHistory(int count, double range) {
        warmupHistory = new ArrayList<>();
        double half = range / 2.0;
        for (int i = 0; i < count; i++) {
            Instant t = T0.minus(Duration.ofHours((long) (count - i)));
            warmupHistory.add(bar(t, 100.0, 100.0 + half, 100.0 - half, 100.0));
        }
    }

    @Given("a long position opened at {int} with trailing_stop {string}")
    public void aLongPositionOpened(int entryPrice, String trailingExpr) {
        open(Direction.LONG, entryPrice, trailingExpr);
    }

    @Given("a short position opened at {int} with trailing_stop {string}")
    public void aShortPositionOpened(int entryPrice, String trailingExpr) {
        open(Direction.SHORT, entryPrice, trailingExpr);
    }

    private void open(Direction direction, int entryPrice, String trailingExpr) {
        String terminal = direction == Direction.LONG ? "long_entry" : "short_entry";
        ParsedStrategy strategy = StrategyParser.parse("""
                Feature: Trailing test
                  Primary timeframe: 1h

                  Scenario: Enter
                    Given no open position
                    When close exceeds 1
                    Then %s
                    And with trailing_stop at %s
                """.formatted(terminal, trailingExpr), "trailing-test.strat");
        generator = new WichtelmSignalGenerator(strategy, Map.of(), BigDecimal.valueOf(50), false);

        // Bar 0 (entry signal): a range-2 bar so a 14-period ATR over the warmup
        // history is well defined; close = entry price triggers "close exceeds 1".
        OHLCBar bar0 = bar(T0, entryPrice, entryPrice + 1, entryPrice - 1, entryPrice);
        BarContext ctx0 = new BarContext(bar0, warmupHistory, Optional.empty(),
                EQUITY, EQUITY, warmupHistory.size());
        Signal entrySignal = generator.generate(ctx0);
        if (direction == Direction.LONG) {
            assertInstanceOf(Signal.Buy.class, entrySignal, () -> "expected Buy, got " + entrySignal);
        } else {
            assertInstanceOf(Signal.Sell.class, entrySignal, () -> "expected Sell, got " + entrySignal);
        }

        // Bar 1 (fill at next bar open per frau-holle): the position appears, the
        // ownership side-map binds, and the high-water mark seeds at this bar.
        entryTime = T0.plus(Duration.ofHours(1));
        position = new Position(direction, BigDecimal.ONE, entryTime, BigDecimal.valueOf(entryPrice));
        history = new ArrayList<>(warmupHistory);
        history.add(bar0);
        OHLCBar fillBar = bar(entryTime, entryPrice, entryPrice, entryPrice, entryPrice);
        BarContext ctx1 = new BarContext(fillBar, history, Optional.of(position),
                EQUITY, EQUITY, history.size());
        generator.generate(ctx1);
        history.add(fillBar);
        barCounter = 1;
    }

    @When("an in-position bar prints high {double} low {double}")
    public void anInPositionBarPrints(double high, double low) {
        barCounter++;
        Instant t = entryTime.plus(Duration.ofHours(barCounter));
        // open/close sit inside [low, high]; there are no exit scenarios, so only
        // the trailing stop can close the position.
        OHLCBar b = bar(t, low, high, low, high);
        BarContext ctx = new BarContext(b, history, Optional.of(position),
                EQUITY, EQUITY, history.size());
        emitted = generator.generate(ctx);
        history.add(b);
    }

    // Open-aware variant for §19 gap-through scenarios, where the bar must OPEN
    // beyond the trailing level (a gap) rather than merely touch it intrabar.
    @When("an in-position bar prints open {double} high {double} low {double} close {double}")
    public void anInPositionBarPrintsOHLC(double open, double high, double low, double close) {
        barCounter++;
        Instant t = entryTime.plus(Duration.ofHours(barCounter));
        OHLCBar b = bar(t, open, high, low, close);
        BarContext ctx = new BarContext(b, history, Optional.of(position),
                EQUITY, EQUITY, history.size());
        emitted = generator.generate(ctx);
        history.add(b);
    }

    @Then("a ClosePositionAtPrice signal is emitted at price {double}")
    public void aClosePositionAtPriceSignalIsEmittedAtPrice(double price) {
        Signal.ClosePositionAtPrice signal =
                assertInstanceOf(Signal.ClosePositionAtPrice.class, emitted,
                        () -> "expected ClosePositionAtPrice, got " + emitted);
        assertEquals(0, signal.price().compareTo(BigDecimal.valueOf(price)),
                () -> "trailing level was " + signal.price() + ", expected " + price);
    }

    @Then("no exit signal is emitted")
    public void noExitSignalIsEmitted() {
        assertFalse(emitted instanceof Signal.ClosePositionAtPrice,
                () -> "unexpected ClosePositionAtPrice: " + emitted);
        assertFalse(emitted instanceof Signal.ClosePosition,
                () -> "unexpected ClosePosition: " + emitted);
    }
}
