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
import java.time.Duration;
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
    /** Generator instance held across bars so the entry-ownership side-map survives. */
    private WichtelmSignalGenerator persistentGenerator;

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

    @Then("a ClosePositionAtPrice signal is emitted with price {double}")
    public void aClosePositionAtPriceSignalIsEmitted(double price) {
        Signal.ClosePositionAtPrice signal = assertInstanceOf(Signal.ClosePositionAtPrice.class, emitted);
        assertEquals(0, signal.price().compareTo(BigDecimal.valueOf(price)),
                () -> "stop/take price was " + signal.price() + ", expected " + price);
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

    // ─── Same-bar stop_loss vs take_profit (Concern 3) ──────────────────────

    @Given("a long strategy with stop_loss {string} and take_profit {string}")
    public void aLongStrategyWithStopAndTake(String stopExpression, String takeExpression) {
        strategy = StrategyParser.parse("""
                Feature: Signal test
                  Primary timeframe: 1h

                  Scenario: Enter
                    Given no open position
                    When close exceeds 1
                    Then long_entry
                    And with stop_loss at %s
                    And with take_profit at %s
                """.formatted(stopExpression, takeExpression), "signal-test.strat");
    }

    @Given("a short strategy with stop_loss {string} and take_profit {string}")
    public void aShortStrategyWithStopAndTake(String stopExpression, String takeExpression) {
        strategy = StrategyParser.parse("""
                Feature: Signal test
                  Primary timeframe: 1h

                  Scenario: Enter
                    Given no open position
                    When close exceeds 1
                    Then short_entry
                    And with stop_loss at %s
                    And with take_profit at %s
                """.formatted(stopExpression, takeExpression), "signal-test.strat");
    }

    @Given("a short position opened at price {int}")
    public void aShortPositionOpenedAtPrice(int entryPrice) {
        equity = BigDecimal.valueOf(10000);
        position = Optional.of(new Position(Direction.SHORT, BigDecimal.TEN, ENTRY_TIME,
                BigDecimal.valueOf(entryPrice)));
    }

    // ─── Two-entry scenario ownership (Concern 1) ───────────────────────────

    /**
     * A strategy with two entry scenarios that have mutually exclusive
     * triggers. Scenario A fires only when close exceeds 200; Scenario B
     * fires only when close is below 50. This lets a test drive either
     * branch deterministically through {@code generate}, so the
     * entry-ownership side-map is exercised end-to-end.
     */
    @Given("a two-entry long strategy with A stop {string} and B stop {string}")
    public void aTwoEntryLongStrategy(String aStop, String bStop) {
        strategy = StrategyParser.parse("""
                Feature: Two-entry ownership test
                  Primary timeframe: 1h

                  Scenario: Enter A
                    Given no open position
                    When close exceeds 200
                    Then long_entry
                    And with stop_loss at %s

                  Scenario: Enter B
                    Given no open position
                    When close is below 50
                    Then long_entry
                    And with stop_loss at %s

                  Scenario: Exit fallback
                    Given a long position is open
                    When close drops below 1
                    Then long_exit
                """.formatted(aStop, bStop), "two-entry.strat");
    }

    @When("scenario {word} opens a long position at price {int}")
    public void scenarioOpensALongPosition(String scenarioLetter, int entryPrice) {
        // Construct the generator HERE (not in the strategy step) so the
        // `position sizing` Given that runs between them has set the sizing.
        persistentGenerator =
                new WichtelmSignalGenerator(strategy, Map.of(), positionSizePct, pyramiding);
        // Pick a close price that triggers ONLY the requested scenario:
        //   A: "close exceeds 200" — use close=250
        //   B: "close is below 50" — use close=40
        BigDecimal triggerClose = scenarioLetter.equals("A")
                ? BigDecimal.valueOf(250)
                : BigDecimal.valueOf(40);
        Instant entrySignalBar = Instant.parse("2024-01-01T00:00:00Z");
        OHLCBar bar0 = flatBar(entrySignalBar, triggerClose.toPlainString());
        equity = BigDecimal.valueOf(10000);
        // Bar 0: no open position → entry condition fires → Buy emitted, side-map binds pending.
        BarContext ctx0 = new BarContext(bar0, List.of(), Optional.empty(),
                equity, equity, 0);
        Signal entrySignal = persistentGenerator.generate(ctx0);
        assertInstanceOf(Signal.Buy.class, entrySignal,
                () -> "expected scenario " + scenarioLetter + " to fire a Buy, got " + entrySignal);
        // Bar 1: position opens at this bar's open per frau-holle's next-bar-fill rule.
        // The first generate() call where currentPosition().isPresent() triggers the
        // side-map binding (entryTime → scenario name).
        Instant entryTime = entrySignalBar.plus(Duration.ofHours(1));
        position = Optional.of(new Position(Direction.LONG, BigDecimal.ONE, entryTime,
                BigDecimal.valueOf(entryPrice)));
        OHLCBar fillBar = flatBar(entryTime, String.valueOf(entryPrice));
        BarContext ctx1 = new BarContext(fillBar, List.of(bar0), position, equity, equity, 1);
        persistentGenerator.generate(ctx1);
    }

    @When("a subsequent bar reaches low {double}")
    public void aSubsequentBarReachesLow(double low) {
        Instant subsequent = position.orElseThrow().entryTime().plus(Duration.ofHours(10));
        BigDecimal lowBd = BigDecimal.valueOf(low);
        // Construct a bar whose low touches the requested level; the high and
        // close stay above the entry price so no take_profit / close-evaluated
        // exit gets in the way.
        currentBar = new OHLCBar(subsequent,
                BigDecimal.valueOf(100), BigDecimal.valueOf(100), lowBd, BigDecimal.valueOf(100),
                Optional.empty());
        BarContext ctx = new BarContext(currentBar,
                List.of(flatBar(position.get().entryTime(), "100")),
                position, equity, equity, 12);
        emitted = persistentGenerator.generate(ctx);
    }

    @Then("no ClosePositionAtPrice signal is emitted")
    public void noClosePositionAtPriceSignalIsEmitted() {
        assertFalse(emitted instanceof Signal.ClosePositionAtPrice,
                () -> "a ClosePositionAtPrice was emitted: " + emitted);
    }
}
