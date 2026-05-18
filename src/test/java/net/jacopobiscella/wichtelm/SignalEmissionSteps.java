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
import java.math.MathContext;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Step definitions for {@code signal-emission.feature} (Block 4). */
public class SignalEmissionSteps {

    private static final MathContext DECIMAL = MathContext.DECIMAL64;
    private static final Instant ENTRY_TIME = Instant.parse("2024-01-01T10:00:00Z");
    private static final Instant PREVIOUS_TIME = Instant.parse("2024-01-01T10:00:00Z");
    private static final Instant CURRENT_TIME = Instant.parse("2024-01-01T11:00:00Z");

    private ParsedStrategy strategy;
    private BigDecimal positionSizePct;
    private boolean pyramiding;
    private BigDecimal equity;
    private OHLCBar currentBar;
    private OHLCBar previousBar;
    private Optional<Position> position = Optional.empty();
    private Signal emitted;
    private BigDecimal snapshottedStop;

    private static OHLCBar flatBar(Instant time, String price) {
        BigDecimal p = new BigDecimal(price);
        return new OHLCBar(time, p, p, p, p, Optional.empty());
    }

    private WichtelmSignalGenerator generator() {
        return new WichtelmSignalGenerator(strategy, Map.of(), positionSizePct, pyramiding);
    }

    @Given("an entry-only long strategy")
    public void anEntryOnlyLongStrategy() {
        strategy = StrategyParser.parse("""
                Feature: Signal test
                  Primary timeframe: 1h

                  Scenario: Enter
                    Given no open position
                    When close exceeds 1
                    Then long_entry
                """, "signal-test.strat");
    }

    @Given("a long strategy with stop_loss {string}")
    public void aLongStrategyWithStopLoss(String stopExpression) {
        strategy = StrategyParser.parse("""
                Feature: Signal test
                  Primary timeframe: 1h

                  Scenario: Enter
                    Given no open position
                    When close exceeds 1
                    Then long_entry
                    And with stop_loss at %s
                """.formatted(stopExpression), "signal-test.strat");
    }

    @Given("a long strategy with stop_loss {string} and a long_exit on {string}")
    public void aLongStrategyWithStopLossAndExit(String stopExpression, String exitCondition) {
        strategy = StrategyParser.parse("""
                Feature: Signal test
                  Primary timeframe: 1h

                  Scenario: Enter
                    Given no open position
                    When close exceeds 1
                    Then long_entry
                    And with stop_loss at %s

                  Scenario: Exit
                    Given a long position is open
                    When %s
                    Then long_exit
                """.formatted(stopExpression, exitCondition), "signal-test.strat");
    }

    @Given("position sizing of {int} percent with pyramiding {word}")
    public void positionSizing(int pct, String pyramidingState) {
        positionSizePct = BigDecimal.valueOf(pct);
        pyramiding = "enabled".equals(pyramidingState);
    }

    @Given("no open position with equity {int} and bar close {int}")
    public void noOpenPositionWith(int equityValue, int close) {
        equity = BigDecimal.valueOf(equityValue);
        currentBar = flatBar(CURRENT_TIME, String.valueOf(close));
        position = Optional.empty();
    }

    @Given("a long position already open with equity {int} and bar close {int}")
    public void aLongPositionAlreadyOpen(int equityValue, int close) {
        equity = BigDecimal.valueOf(equityValue);
        currentBar = flatBar(CURRENT_TIME, String.valueOf(close));
        position = Optional.of(new Position(Direction.LONG, BigDecimal.TEN, ENTRY_TIME,
                BigDecimal.valueOf(close)));
    }

    @Given("a long position opened at price {int}")
    public void aLongPositionOpenedAtPrice(int entryPrice) {
        equity = BigDecimal.valueOf(10000);
        position = Optional.of(new Position(Direction.LONG, BigDecimal.TEN, ENTRY_TIME,
                BigDecimal.valueOf(entryPrice)));
    }

    @Given("a previous bar with close {int}")
    public void aPreviousBarWithClose(int close) {
        previousBar = flatBar(PREVIOUS_TIME, String.valueOf(close));
    }

    @Given("the current bar is open {double} high {double} low {double} close {double}")
    public void theCurrentBarIs(double open, double high, double low, double close) {
        currentBar = new OHLCBar(CURRENT_TIME,
                BigDecimal.valueOf(open), BigDecimal.valueOf(high),
                BigDecimal.valueOf(low), BigDecimal.valueOf(close), Optional.empty());
    }

    @When("the SignalGenerator emits a signal for the bar")
    public void theSignalGeneratorEmitsASignal() {
        List<OHLCBar> history = previousBar == null ? List.of() : List.of(previousBar);
        BarContext context = new BarContext(currentBar, history, position, equity, equity, 1);
        emitted = generator().generate(context);
    }

    @When("the stop price is snapshotted")
    public void theStopPriceIsSnapshotted() {
        snapshottedStop = generator().stopLossPriceFor(position.orElseThrow()).orElseThrow();
    }

    @Then("the emitted signal is a Buy")
    public void theEmittedSignalIsABuy() {
        assertInstanceOf(Signal.Buy.class, emitted);
    }

    @Then("the emitted signal is an AddToPosition with direction LONG")
    public void theEmittedSignalIsAnAddToPosition() {
        Signal.AddToPosition add = assertInstanceOf(Signal.AddToPosition.class, emitted);
        assertEquals(Direction.LONG, add.direction());
    }

    @Then("a ClosePositionAtPrice signal is emitted with price {int}")
    public void aClosePositionAtPriceSignalIsEmitted(int price) {
        Signal.ClosePositionAtPrice signal = assertInstanceOf(Signal.ClosePositionAtPrice.class, emitted);
        assertEquals(0, signal.price().compareTo(BigDecimal.valueOf(price)),
                () -> "stop price was " + signal.price());
    }

    @Then("no plain ClosePosition signal is emitted")
    public void noPlainClosePositionSignalIsEmitted() {
        assertFalse(emitted instanceof Signal.ClosePosition, "a plain ClosePosition was emitted");
    }

    @Then("the signal quantity equals {int} percent of equity over price")
    public void theSignalQuantityEquals(int pct) {
        BigDecimal expected = BigDecimal.valueOf(pct)
                .divide(BigDecimal.valueOf(100), DECIMAL)
                .multiply(equity, DECIMAL)
                .divide(currentBar.close(), DECIMAL);
        BigDecimal actual = switch (emitted) {
            case Signal.Buy buy -> buy.quantity();
            case Signal.AddToPosition add -> add.quantity();
            default -> throw new AssertionError("signal carries no quantity: " + emitted);
        };
        assertEquals(0, expected.compareTo(actual), () -> "quantity was " + actual);
    }

    @Then("the snapshotted stop price is {int}")
    public void theSnapshottedStopPriceIs(int price) {
        assertNotNull(snapshottedStop);
        assertEquals(0, snapshottedStop.compareTo(BigDecimal.valueOf(price)),
                () -> "snapshotted stop was " + snapshottedStop);
    }

    @Then("the fill time is strictly inside the current bar interval")
    public void theFillTimeIsStrictlyInside() {
        Signal.ClosePositionAtPrice signal = assertInstanceOf(Signal.ClosePositionAtPrice.class, emitted);
        Instant barClose = Instant.parse("2024-01-01T12:00:00Z");
        assertTrue(signal.fillTime().isAfter(CURRENT_TIME),
                () -> "fill time " + signal.fillTime() + " not after bar open");
        assertTrue(signal.fillTime().isBefore(barClose),
                () -> "fill time " + signal.fillTime() + " not before bar close");
    }
}
