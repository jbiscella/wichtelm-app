package net.jacopobiscella.wichtelm;

import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import net.jacopobiscella.wichtelm.runtime.WichtelmSignalGenerator;
import net.jacopobiscella.wichtelm.strategy.ParsedStrategy;
import net.jacopobiscella.wichtelm.strategy.StrategyParser;
import org.hatrack.commons.OHLCBar;
import org.hatrack.frauholle.model.BarContext;
import org.hatrack.frauholle.model.Signal;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Step definitions for {@code indicator-evaluation.feature} (increment 6a). */
public class IndicatorEvaluationSteps {

    private ParsedStrategy strategy;
    private List<OHLCBar> history;
    private Signal emitted;

    @Given("a strategy whose entry condition is {string}")
    public void aStrategyWhoseEntryConditionIs(String condition) {
        strategy = StrategyParser.parse("""
                Feature: Indicator test
                  Primary timeframe: 1h

                  Scenario: Enter
                    Given no open position
                    When %s
                    Then long_entry
                """.formatted(condition), "indicator-test.strat");
    }

    @Given("a rising price history of {int} bars")
    public void aRisingPriceHistory(int barCount) {
        history = new ArrayList<>();
        Instant start = Instant.parse("2024-01-01T00:00:00Z");
        for (int i = 0; i < barCount; i++) {
            BigDecimal close = BigDecimal.valueOf(100 + i);
            history.add(new OHLCBar(start.plus(Duration.ofHours(i)),
                    close, close.add(BigDecimal.TWO), close.subtract(BigDecimal.TWO),
                    close, Optional.empty()));
        }
    }

    @When("the SignalGenerator evaluates the bar")
    public void theSignalGeneratorEvaluatesTheBar() {
        WichtelmSignalGenerator generator = new WichtelmSignalGenerator(
                strategy, Map.of(), BigDecimal.valueOf(50), false);
        OHLCBar currentBar = history.getLast();
        BarContext context = new BarContext(currentBar, history, Optional.empty(),
                BigDecimal.valueOf(10000), BigDecimal.valueOf(10000), history.size() - 1);
        emitted = generator.generate(context);
    }

    @Then("an entry signal is emitted")
    public void anEntrySignalIsEmitted() {
        assertInstanceOf(Signal.Buy.class, emitted);
    }

    @Then("no entry signal is emitted")
    public void noEntrySignalIsEmitted() {
        assertTrue(emitted instanceof Signal.Hold,
                () -> "expected Hold but emitted " + emitted);
    }
}
