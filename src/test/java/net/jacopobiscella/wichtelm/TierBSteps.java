package net.jacopobiscella.wichtelm;

import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import net.jacopobiscella.wichtelm.config.BacktestConfig;
import net.jacopobiscella.wichtelm.config.DataSource;
import net.jacopobiscella.wichtelm.runtime.BacktestRunResult;
import net.jacopobiscella.wichtelm.runtime.BacktestRunner;
import net.jacopobiscella.wichtelm.runtime.NachtkrappMatchIndex;
import net.jacopobiscella.wichtelm.strategy.ParsedStrategy;
import net.jacopobiscella.wichtelm.strategy.StrategyParser;
import org.hatrack.frauholle.error.BacktestException;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Step definitions for {@code tier-b-primitives.feature}. */
public class TierBSteps {

    private ParsedStrategy strategy;
    private BacktestRunResult run;

    private static String wrap(String boolStep) {
        return """
                Feature: Tier B parse test
                  Primary timeframe: 1h

                  Scenario: Boolean primitive entry
                    Given no open position
                    When %s
                    Then long_entry
                """.formatted(boolStep);
    }

    @Given("a strategy that fires when {} at the current bar")
    public void aStrategyThatFiresWhen(String boolStep) {
        strategy = StrategyParser.parse(wrap(boolStep), "tier-b.strat");
    }

    @When("the strategy parses")
    public void theStrategyParses() {
        // Parse already happened in the Given step; this verb is just for prose.
        assertNotNull(strategy);
    }

    @Then("the Tier B strategy parses cleanly")
    public void tierBStrategyParsesCleanly() {
        assertNotNull(strategy);
    }

    @Then("a strategy that fires when {} at the current bar parses")
    public void anotherStrategyParses(String boolStep) {
        ParsedStrategy other = StrategyParser.parse(wrap(boolStep), "tier-b-extra.strat");
        assertNotNull(other);
    }

    @Given("a strategy that enters long when ha_doji\\() and exits on the next bar")
    public void haDojiEntryExitStrategy() {
        strategy = StrategyParser.parse("""
                Feature: ha_doji end-to-end
                  Primary timeframe: 1h

                  Scenario: Enter long on ha_doji
                    Given no open position
                    When ha_doji()
                    Then long_entry

                  Scenario: Exit long unconditionally
                    Given a long position is open
                    When close exceeds 0
                    Then long_exit
                """, "ha-doji-e2e.strat");
    }

    @Given("a CSV dataset of {int} primary bars")
    public void aCsvDatasetOfBars(int barCount) throws IOException {
        Path dir = Files.createTempDirectory("tier-b-csv");
        StringBuilder csv = new StringBuilder("time,open,high,low,close\n");
        Instant start = Instant.parse("2024-03-01T00:00:00Z");
        // A simple alternating-direction sequence drives several HA doji bars
        // (the synthetic OHLCs collapse to small bodies when the HA series
        // computed from them lacks a strong directional bias).
        for (int i = 0; i < barCount; i++) {
            double base = 100 + (i % 4 == 0 ? 0.5 : -0.3) * i;
            double open = base;
            double close = base + (i % 2 == 0 ? 0.1 : -0.1);
            double high = Math.max(open, close) + 0.5;
            double low = Math.min(open, close) - 0.5;
            csv.append(start.plus(Duration.ofHours(i))).append(',')
                    .append(open).append(',').append(high).append(',')
                    .append(low).append(',').append(close).append('\n');
        }
        Files.writeString(dir.resolve("TBX_1h.csv"), csv);
        this.tempCsvDir = dir;
    }

    private Path tempCsvDir;

    @When("the backtest runs")
    public void theBacktestRuns() throws BacktestException {
        BacktestConfig config = new BacktestConfig(
                tempCsvDir.resolve("config.toml"),
                tempCsvDir.resolve("strategy.strat"),
                "TBX",
                LocalDate.parse("2024-03-01"), LocalDate.parse("2024-04-01"),
                DataSource.CSV, BigDecimal.valueOf(50), false,
                Map.of(), Optional.empty(),
                Optional.of(tempCsvDir.resolve("{symbol}_{timeframe}.csv")),
                Optional.empty(), List.of());
        run = new BacktestRunner().run(strategy, config, Map.of());
    }

    @Then("the backtest completes without throwing")
    public void backtestCompleted() {
        assertNotNull(run);
    }

    @Then("the prepass-derived match count for ha_doji\\() is queryable")
    public void haDojiPrepassQueryable() {
        // The prepass index is built per backtest; the existence of `run`
        // already proves the prepass executed. Re-running it here outside the
        // backtest pipeline mirrors what BacktestRunner does and lets us
        // assert it returns SOMETHING (an index, not null).
        NachtkrappMatchIndex index = NachtkrappMatchIndex.buildFor(
                strategy, Map.of(), run.primarySeries(), Map.of());
        boolean queryable = index.hasKey(
                new NachtkrappMatchIndex.Key("ha_doji", List.of(),
                        strategy.primaryTimeframe().wire()));
        assertTrue(queryable,
                "the prepass should have indexed the ha_doji() call from the strategy");
    }
}
