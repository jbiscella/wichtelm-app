package net.jacopobiscella.wichtelm;

import io.cucumber.java.After;
import io.cucumber.java.Before;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import net.jacopobiscella.wichtelm.config.BacktestConfig;
import net.jacopobiscella.wichtelm.config.DataSource;
import net.jacopobiscella.wichtelm.config.SweepDefinition;
import net.jacopobiscella.wichtelm.runtime.BacktestRunner;
import net.jacopobiscella.wichtelm.runtime.LoadedMarketData;
import net.jacopobiscella.wichtelm.strategy.ParsedStrategy;
import net.jacopobiscella.wichtelm.strategy.StrategyParser;
import net.jacopobiscella.wichtelm.sweep.SweepObjective;
import net.jacopobiscella.wichtelm.sweep.SweepResult;
import net.jacopobiscella.wichtelm.sweep.SweepRunner;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Step definitions for {@code sweep-execution.feature} (CLAUDE.md section 18). */
public class SweepExecutionSteps {

    private Path tempDir;
    private ParsedStrategy strategy;
    private List<BigDecimal> fastValues;
    private List<SweepResult> rows;
    private SweepObjective objective;
    private CountingRunner runner;

    /** A BacktestRunner that records how many times market data was loaded. */
    private static final class CountingRunner extends BacktestRunner {
        private int loadCount;

        @Override
        public LoadedMarketData loadMarketData(ParsedStrategy strategy, BacktestConfig config) {
            loadCount++;
            return super.loadMarketData(strategy, config);
        }
    }

    @Before
    public void setUp() throws IOException {
        tempDir = Files.createTempDirectory("wichtelm-sweep-exec");
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

    @Given("a sweepable sma strategy with a {string} period parameter")
    public void sweepableSmaStrategy(String param) {
        strategy = StrategyParser.parse("""
                Feature: Sweepable sma
                  Primary timeframe: 1h
                  Parameter %s default 3

                  Scenario: Enter
                    Given no open position
                    When close is above sma(%s)
                    Then long_entry

                  Scenario: Exit
                    Given a long position is open
                    When close is below sma(%s)
                    Then long_exit
                """.formatted(param, param, param), "sweepable-sma.strat");
    }

    @Given("a CSV dataset for the sweep on the 1h timeframe")
    public void csvDataset() throws IOException {
        StringBuilder csv = new StringBuilder("time,open,high,low,close\n");
        Instant start = Instant.parse("2024-01-01T00:00:00Z");
        for (int i = 0; i < 120; i++) {
            int close = i < 60 ? 100 + i : 100 + (119 - i);
            Instant t = start.plus(Duration.ofHours(i));
            csv.append(t).append(',').append(close).append(',')
                    .append(close + 2).append(',').append(close - 2).append(',')
                    .append(close).append('\n');
        }
        Files.writeString(tempDir.resolve("AAPL_1h.csv"), csv);
    }

    @Given("a sweep over {string} of {int}, {int}, {int}")
    public void aSweepOver(String param, int a, int b, int c) {
        fastValues = List.of(BigDecimal.valueOf(a), BigDecimal.valueOf(b), BigDecimal.valueOf(c));
    }

    @When("the sweep runs ranked by {string}")
    public void theSweepRuns(String objectiveWire) {
        objective = SweepObjective.fromWire(objectiveWire).orElseThrow();
        BacktestConfig config = buildConfig();
        runner = new CountingRunner();
        rows = new SweepRunner(runner).run(strategy, config, objective, 10, 500);
    }

    private BacktestConfig buildConfig() {
        Map<String, SweepDefinition.Axis> axes = new LinkedHashMap<>();
        axes.put("fast", new SweepDefinition.ValueList(fastValues));
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
                Optional.of(new SweepDefinition(axes)),
                Optional.empty(),
                Optional.of(tempDir.resolve("{symbol}_{timeframe}.csv")),
                Optional.empty(),
                List.of());
    }

    @Then("the sweep produces {int} result rows")
    public void theSweepProducesRows(int count) {
        assertNotNull(rows);
        assertEquals(count, rows.size());
    }

    @Then("every result row ran without failure")
    public void everyRowRan() {
        for (SweepResult row : rows) {
            assertTrue(row.ran(), () -> "row failed: " + row.failure().orElse("?")
                    + " for " + row.combination());
        }
    }

    @Then("the rows are ordered by total_return descending")
    public void rowsOrderedByTotalReturnDescending() {
        List<BigDecimal> returns = new ArrayList<>();
        for (SweepResult row : rows) {
            row.metrics().ifPresent(m -> returns.add(m.totalReturn()));
        }
        for (int i = 1; i < returns.size(); i++) {
            assertTrue(returns.get(i - 1).compareTo(returns.get(i)) >= 0,
                    () -> "not descending by total_return: " + returns);
        }
    }

    @Then("any tradeless row sorts below every row that traded")
    public void tradelessBelowTraded() {
        boolean seenTradeless = false;
        for (SweepResult row : rows) {
            if (seenTradeless) {
                assertFalse(row.hasTrades(),
                        () -> "a trading row appears after a tradeless row: " + row.combination());
            }
            if (!row.hasTrades()) {
                seenTradeless = true;
            }
        }
    }

    @Then("the market data was loaded exactly once")
    public void marketDataLoadedOnce() {
        assertEquals(1, runner.loadCount, "market data should be loaded exactly once per sweep");
    }
}
