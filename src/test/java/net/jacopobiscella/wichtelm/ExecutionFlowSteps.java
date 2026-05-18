package net.jacopobiscella.wichtelm;

import io.cucumber.java.After;
import io.cucumber.java.Before;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import net.jacopobiscella.wichtelm.config.BacktestConfig;
import net.jacopobiscella.wichtelm.config.DataSource;
import net.jacopobiscella.wichtelm.error.DataSourceUnavailableException;
import net.jacopobiscella.wichtelm.error.WichtelmException;
import net.jacopobiscella.wichtelm.runtime.BacktestRunResult;
import net.jacopobiscella.wichtelm.runtime.BacktestRunner;
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
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Step definitions for {@code execution-flow.feature} (increment 6b). */
public class ExecutionFlowSteps {

    private Path tempDir;
    private ParsedStrategy strategy;
    private BacktestRunResult runResult;
    private WichtelmException thrown;

    @Before
    public void setUp() throws IOException {
        tempDir = Files.createTempDirectory("wichtelm-block6b");
    }

    @After
    public void tearDown() throws IOException {
        try (Stream<Path> paths = Files.walk(tempDir)) {
            paths.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // best-effort cleanup
                }
            });
        }
    }

    @Given("a single-timeframe strategy with sma-based entry and exit")
    public void aSingleTimeframeStrategy() {
        strategy = StrategyParser.parse("""
                Feature: Execution test
                  Primary timeframe: 1h

                  Scenario: Enter
                    Given no open position
                    When close is above sma(3)
                    Then long_entry

                  Scenario: Exit
                    Given a long position is open
                    When close is below sma(3)
                    Then long_exit
                """, "execution-test.strat");
    }

    @Given("a multi-timeframe strategy with a 1d Background series")
    public void aMultiTimeframeStrategy() {
        strategy = StrategyParser.parse("""
                Feature: Multi-timeframe execution test
                  Primary timeframe: 1h

                  Background:
                    Given a series trend defined as sma(3) on 1d

                  Scenario: Enter
                    Given no open position
                    When close is above trend
                    Then long_entry

                  Scenario: Exit
                    Given a long position is open
                    When close is below trend
                    Then long_exit
                """, "multi-tf-test.strat");
    }

    @Given("a CSV dataset for the symbol on the 1h timeframe")
    public void aCsvDatasetOn1h() throws IOException {
        writeHourlyDataset();
    }

    @Given("a CSV dataset for the symbol on the 1h and 1d timeframes")
    public void aCsvDatasetOn1hAnd1d() throws IOException {
        writeHourlyDataset();
        writeDailyDataset();
    }

    private void writeHourlyDataset() throws IOException {
        // 120 hourly bars over 5 days: a rising-then-falling ramp.
        StringBuilder csv = new StringBuilder("time,open,high,low,close\n");
        Instant start = Instant.parse("2024-01-01T00:00:00Z");
        for (int i = 0; i < 120; i++) {
            int close = i < 60 ? 100 + i : 100 + (119 - i);
            appendRow(csv, start.plus(Duration.ofHours(i)), close);
        }
        Files.writeString(tempDir.resolve("AAPL_1h.csv"), csv);
    }

    private void writeDailyDataset() throws IOException {
        writeDailyDatasetOnDays(0, 1, 2, 3, 4, 5, 6, 7, 8, 9);
    }

    private void writeDailyDatasetOnDays(int... dayOffsets) throws IOException {
        StringBuilder csv = new StringBuilder("time,open,high,low,close\n");
        Instant start = Instant.parse("2024-01-01T00:00:00Z");
        for (int day : dayOffsets) {
            appendRow(csv, start.plus(Duration.ofDays(day)), 100 + day);
        }
        Files.writeString(tempDir.resolve("AAPL_1d.csv"), csv);
    }

    @Given("a 1d dataset with interior gaps that still spans the primary range")
    public void a1dDatasetWithInteriorGaps() throws IOException {
        // Hourly data spans days 0-4; the 1d series keeps days 0, 1 and 4
        // (an interior weekend-like gap) yet still spans the primary range.
        writeDailyDatasetOnDays(0, 1, 4);
    }

    @Given("a 1d dataset that ends before the primary range end")
    public void a1dDatasetThatEndsEarly() throws IOException {
        writeDailyDatasetOnDays(0, 1, 2);
    }

    private static void appendRow(StringBuilder csv, Instant time, int close) {
        csv.append(time).append(',').append(close).append(',')
                .append(close + 2).append(',').append(close - 2).append(',')
                .append(close).append('\n');
    }

    private BacktestConfig buildConfig() {
        return new BacktestConfig(
                tempDir.resolve("config.toml"),
                tempDir.resolve("strategy.strat"),
                "AAPL",
                LocalDate.parse("2024-01-01"),
                LocalDate.parse("2024-01-10"),
                DataSource.CSV,
                BigDecimal.valueOf(50),
                false,
                Map.of(),
                Optional.empty(),
                Optional.of(tempDir.resolve("{symbol}_{timeframe}.csv")),
                Optional.empty(),
                List.of());
    }

    @When("the backtest runner executes")
    public void theBacktestRunnerExecutes() throws BacktestException {
        runResult = new BacktestRunner().run(strategy, buildConfig(), Map.of());
    }

    @When("the backtest runner is executed expecting failure")
    public void theBacktestRunnerIsExecutedExpectingFailure() throws BacktestException {
        try {
            runResult = new BacktestRunner().run(strategy, buildConfig(), Map.of());
        } catch (WichtelmException e) {
            thrown = e;
        }
    }

    @Then("a higher-timeframe coverage error is raised")
    public void aHigherTimeframeCoverageErrorIsRaised() {
        assertInstanceOf(DataSourceUnavailableException.class, thrown);
        assertTrue(thrown.getMessage().contains("higher-timeframe"),
                () -> "unexpected error message: " + thrown.getMessage());
    }

    @Then("a BacktestResult is produced")
    public void aBacktestResultIsProduced() {
        assertNotNull(runResult);
        assertNotNull(runResult.result());
    }

    @Then("the result has one equity point per primary bar")
    public void theResultHasOneEquityPointPerPrimaryBar() {
        assertEquals(runResult.primarySeries().size(),
                runResult.result().equityCurve().size());
    }
}
