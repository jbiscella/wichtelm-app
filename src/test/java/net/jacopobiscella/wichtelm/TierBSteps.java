package net.jacopobiscella.wichtelm;

import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import net.jacopobiscella.wichtelm.config.BacktestConfig;
import net.jacopobiscella.wichtelm.config.DataSource;
import net.jacopobiscella.wichtelm.runtime.BacktestRunResult;
import net.jacopobiscella.wichtelm.runtime.BacktestRunner;
import net.jacopobiscella.wichtelm.runtime.NachtkrappMatchIndex;
import net.jacopobiscella.wichtelm.strategy.BuiltinCatalog;
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

    // ── MA trend filter (ma-trend-filter.feature) ────────────────────────────

    private ParsedStrategy gatedStrategy;
    private BacktestRunResult ungatedRun;
    private BacktestRunResult gatedRun;

    private static String entryExitStrategy(String entryStep, String extraEntryStep) {
        return "Feature: prepass\n"
                + "  Primary timeframe: 1h\n\n"
                + "  Scenario: Enter long\n"
                + "    Given no open position\n"
                + "    When " + entryStep + "\n"
                + extraEntryStep
                + "    Then long_entry\n\n"
                + "  Scenario: Exit long unconditionally\n"
                + "    Given a long position is open\n"
                + "    When close exceeds 0\n"
                + "    Then long_exit\n";
    }

    private BacktestConfig csvConfig() {
        return new BacktestConfig(
                tempCsvDir.resolve("config.toml"), tempCsvDir.resolve("strategy.strat"),
                "TBX", LocalDate.parse("2024-03-01"), LocalDate.parse("2024-04-01"),
                DataSource.CSV, BigDecimal.valueOf(50), false,
                Map.of(), Optional.empty(),
                Optional.of(tempCsvDir.resolve("{symbol}_{timeframe}.csv")),
                Optional.empty(), List.of());
    }

    @Given("a backtestable strategy whose entry fires when {}")
    public void backtestableEntryFiresWhen(String boolStep) {
        strategy = StrategyParser.parse(entryExitStrategy(boolStep, ""), "prepass.strat");
    }

    @Given("an ATR-stop long strategy whose stop_loss is {string}")
    public void atrStopLongStrategy(String stopExpr) {
        strategy = StrategyParser.parse(
                "Feature: atr stop\n"
                        + "  Primary timeframe: 1h\n\n"
                        + "  Scenario: Enter long\n"
                        + "    Given no open position\n"
                        // Gate the entry past the ATR(14) warmup so atr_value is
                        // defined at the fill bar (a realistic atr_value strategy
                        // never enters before its ATR has warmed).
                        + "    When bar_index exceeds 20\n"
                        + "    Then long_entry\n"
                        + "    And with stop_loss at " + stopExpr + "\n\n"
                        + "  Scenario: Exit long unconditionally\n"
                        + "    Given a long position is open\n"
                        + "    When close drops below 0\n"
                        + "    Then long_exit\n", "atr-stop.strat");
    }

    @Then("the prepass indexed the {string} key with arg {string}")
    public void prepassIndexedArg(String name, String arg) {
        // A pivot primitive's arg is a symbolic level token (e.g. "R1"), which
        // is not parseable as a number; index it as a symbolic Key. Numeric
        // primitives (periods, thresholds) keep the BigDecimal arg.
        if (BuiltinCatalog.isPivotPrimitive(name)) {
            NachtkrappMatchIndex index = NachtkrappMatchIndex.buildFor(
                    strategy, Map.of(), run.primarySeries(), Map.of());
            assertTrue(index.hasKey(NachtkrappMatchIndex.Key.pivot(
                            name, arg, strategy.primaryTimeframe().wire())),
                    "the prepass should have indexed " + name + "(" + arg + ")");
            return;
        }
        assertPrepassKey(name, List.of(new BigDecimal(arg)));
    }

    @Then("the prepass indexed the {string} key with args {string}")
    public void prepassIndexedArgs(String name, String args) {
        List<BigDecimal> parsed = new java.util.ArrayList<>();
        for (String p : args.split("\\s*,\\s*")) {
            parsed.add(new BigDecimal(p.strip()));
        }
        assertPrepassKey(name, parsed);
    }

    private void assertPrepassKey(String name, List<BigDecimal> args) {
        NachtkrappMatchIndex index = NachtkrappMatchIndex.buildFor(
                strategy, Map.of(), run.primarySeries(), Map.of());
        assertTrue(index.hasKey(new NachtkrappMatchIndex.Key(
                        name, args, strategy.primaryTimeframe().wire())),
                "the prepass should have indexed " + name + args);
    }

    @Then("no entry fired before bar {int}")
    public void noEntryFiredBeforeBar(int bar) {
        // With fewer than `bar` bars the gating MA never warms up, so no long
        // entry can fire: the backtest produced no trades and no open position.
        assertTrue(run.result().trades().isEmpty()
                        && run.result().openPositionAtEnd().isEmpty(),
                "no entry should fire before the gating MA has warmed up");
    }

    @Given("a backtestable strategy that enters long on ha_bullish_reversal\\(2)")
    public void ungatedReversalStrategy() {
        strategy = StrategyParser.parse(
                entryExitStrategy("ha_bullish_reversal(2)", ""), "ungated.strat");
    }

    @Given("the same strategy with an added {string} trend gate")
    public void gatedReversalStrategy(String gate) {
        gatedStrategy = StrategyParser.parse(
                entryExitStrategy("ha_bullish_reversal(2)", "    " + gate + "\n"),
                "gated.strat");
    }

    @When("both backtests run")
    public void bothBacktestsRun() throws BacktestException {
        BacktestConfig config = csvConfig();
        ungatedRun = new BacktestRunner().run(strategy, config, Map.of());
        gatedRun = new BacktestRunner().run(gatedStrategy, config, Map.of());
    }

    @Then("the trend-gated strategy fires no more entries than the ungated one")
    public void trendGatedFiresNoMore() {
        int ungated = entryCount(ungatedRun);
        int gated = entryCount(gatedRun);
        assertTrue(gated <= ungated,
                "trend-gated entries (" + gated + ") should not exceed ungated (" + ungated + ")");
    }

    private static int entryCount(BacktestRunResult r) {
        return r.result().trades().size()
                + (r.result().openPositionAtEnd().isPresent() ? 1 : 0);
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
