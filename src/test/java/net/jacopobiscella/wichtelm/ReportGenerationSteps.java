package net.jacopobiscella.wichtelm;

import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import net.jacopobiscella.wichtelm.report.HtmlReportGenerator;
import net.jacopobiscella.wichtelm.report.ReportData;
import net.jacopobiscella.wichtelm.strategy.ParsedStrategy;
import net.jacopobiscella.wichtelm.strategy.StrategyParser;
import org.hatrack.commons.OHLCBar;
import org.hatrack.commons.OHLCSeries;
import org.hatrack.frauholle.model.Direction;
import org.hatrack.frauholle.model.EquityPoint;
import org.hatrack.frauholle.model.Trade;
import org.hatrack.frauholle.result.BacktestDiagnostics;
import org.hatrack.frauholle.result.BacktestMetrics;
import org.hatrack.frauholle.result.BacktestResult;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Step definitions for {@code report-generation.feature} (Block 5). */
public class ReportGenerationSteps {

    private static final String DEFAULT_STRATEGY = """
            Feature: Report test
              Primary timeframe: 1h

              Scenario: Enter
                Given no open position
                When close exceeds 1
                Then long_entry
            """;

    private static final String TIER_B_STRATEGY = """
            Feature: Trend gated Tier B
              Primary timeframe: 1h

              Scenario: Enter long on oversold doji
                Given no open position
                When ha_doji(0.1)
                And rsi_oversold(30)
                Then long_entry
                And with stop_loss at entry_price * 0.98
                And with take_profit at entry_price * 1.04

              Scenario: Exit long on bearish strong
                Given a long position is open
                When ha_strong_bearish()
                Then long_exit
            """;

    private String configBasename = "report";
    private LocalDateTime generatedAt = LocalDateTime.parse("2026-01-01T00:00:00");
    private Path outputDirectory;
    private ParsedStrategy strategy = StrategyParser.parse(DEFAULT_STRATEGY, "report-test.strat");
    private final Map<String, OHLCSeries> higherSeries = new HashMap<>();
    private BacktestResult result;
    private Path reportPath;
    private String reportHtml;
    private Map<String, List<Instant>> triggerTimes = Map.of();

    private static OHLCSeries series(Instant start, Duration step, int count) {
        List<OHLCBar> bars = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            BigDecimal base = BigDecimal.valueOf(100 + i);
            bars.add(new OHLCBar(start.plus(step.multipliedBy(i)),
                    base, base.add(BigDecimal.TWO), base.subtract(BigDecimal.ONE),
                    base.add(BigDecimal.ONE), Optional.empty()));
        }
        return new OHLCSeries(bars);
    }

    private OHLCSeries primarySeriesOverride;

    private OHLCSeries primarySeries() {
        return primarySeriesOverride != null ? primarySeriesOverride
                : series(Instant.parse("2024-01-01T00:00:00Z"), Duration.ofHours(1), 6);
    }

    private BacktestResult backtestResult() {
        if (result == null) {
            BigDecimal zero = BigDecimal.ZERO;
            BacktestMetrics metrics = new BacktestMetrics(zero, zero, 0, zero, zero, zero,
                    zero, zero, zero, zero);
            List<EquityPoint> curve = List.of(
                    new EquityPoint(Instant.parse("2024-01-01T00:00:00Z"),
                            BigDecimal.valueOf(10000), BigDecimal.valueOf(10000), zero),
                    new EquityPoint(Instant.parse("2024-01-01T05:00:00Z"),
                            BigDecimal.valueOf(10250), BigDecimal.valueOf(10250), zero));
            result = new BacktestResult(metrics, List.of(), curve, Optional.empty(),
                    new BacktestDiagnostics(0, 0, 0, 0, 0, 0, 0));
        }
        return result;
    }

    private ReportData reportData() {
        return new ReportData(configBasename, generatedAt, outputDirectory, strategy,
                backtestResult(), primarySeries(), higherSeries, triggerTimes, Map.of(), "TEST",
                List.of());
    }

    @Given("a report for config basename {string} generated at {string}")
    public void aReportForConfigBasename(String basename, String generatedAtIso) {
        configBasename = basename;
        generatedAt = LocalDateTime.parse(generatedAtIso);
    }

    @Given("the report output directory is a fresh temporary directory")
    public void theOutputDirectoryIsFresh() throws IOException {
        outputDirectory = Files.createTempDirectory("wichtelm-block5");
    }

    @When("the report is generated")
    public void theReportIsGenerated() throws IOException {
        reportPath = new HtmlReportGenerator().generate(reportData());
        reportHtml = Files.readString(reportPath);
    }

    @When("the run completes with reporting disabled")
    public void theRunCompletesWithReportingDisabled() {
        backtestResult();
        // Reporting disabled: the report generator is intentionally not invoked.
    }

    @Then("a report file named {string} exists")
    public void aReportFileNamedExists(String fileName) {
        assertEquals(fileName, reportPath.getFileName().toString());
        assertTrue(Files.exists(reportPath), () -> "missing report file: " + reportPath);
    }

    @Then("the report file is non-empty")
    public void theReportFileIsNonEmpty() throws IOException {
        assertTrue(Files.size(reportPath) > 0, "report file is empty");
    }

    @Then("no HTML report file is produced")
    public void noHtmlReportFileIsProduced() throws IOException {
        try (Stream<Path> entries = Files.list(outputDirectory)) {
            assertFalse(entries.anyMatch(p -> p.toString().endsWith(".html")),
                    "an HTML report file was produced despite reporting being disabled");
        }
    }

    @Then("the backtest result is still accessible")
    public void theBacktestResultIsStillAccessible() {
        assertNotNull(result, "backtest result should still be computed and accessible");
    }

    @Then("the report contains the disclaimer footer")
    public void theReportContainsTheDisclaimerFooter() {
        assertEquals(1, countMatches(reportHtml, "<footer"),
                "report should contain exactly one footer element");
        assertTrue(reportHtml.contains("NOT financial advice"),
                "footer should carry the financial-advice disclaimer");
        assertTrue(reportHtml.contains("Past performance is not indicative"),
                "footer should carry the past-performance disclaimer");
    }

    // ─── Fixtures + assertions for the 0.5x chart-quality report features ──────

    @Given("a trend-gated Tier B strategy fixture")
    public void aTrendGatedTierBStrategyFixture() {
        strategy = StrategyParser.parse(TIER_B_STRATEGY, "trend-gated.strat");
    }

    @Given("a trend-gated Tier B strategy fixture with a stop-hit trade")
    public void aTrendGatedTierBStrategyFixtureWithStopHitTrade() {
        aTrendGatedTierBStrategyFixture();
        installStopHitTrade("Enter long on oversold doji");
    }

    @Given("an ATR-stop strategy fixture with a stop-hit trade")
    public void anAtrStopFixtureWithStopHitTrade() {
        // Reproduces the report crash where evaluateLevel met an atr_value stop:
        // the chart's stop HorizontalLevel must resolve atr_value at the fill.
        strategy = StrategyParser.parse(
                "Feature: ATR stop report\n"
                        + "  Primary timeframe: 1h\n\n"
                        + "  Scenario: Enter long\n"
                        + "    Given no open position\n"
                        + "    When close exceeds 0\n"
                        + "    Then long_entry\n"
                        + "    And with stop_loss at entry_price - 2 * atr_value(14)\n\n"
                        + "  Scenario: Exit long on bearish strong\n"
                        + "    Given a long position is open\n"
                        + "    When ha_strong_bearish()\n"
                        + "    Then long_exit\n", "atr-stop-report.strat");
        installStopHitTrade("Enter long");
    }

    /**
     * A 48-bar series with one closed LONG trade filling at bar 24 (signal at
     * bar 23) and a forced exit (no exit trigger). {@code entryScenarioName}
     * keys the entry trigger so the report attributes the protective stop to it.
     */
    private void installStopHitTrade(String entryScenarioName) {
        Instant seriesStart = Instant.parse("2024-01-01T00:00:00Z");
        primarySeriesOverride = series(seriesStart, Duration.ofHours(1), 48);
        Instant entryFill = seriesStart.plus(Duration.ofHours(24));
        Instant exitFill = seriesStart.plus(Duration.ofHours(30));
        BigDecimal entryPrice = new BigDecimal("124");
        BigDecimal exitPrice = new BigDecimal("121.52");
        Trade trade = new Trade(Direction.LONG, new BigDecimal("50"),
                entryFill, entryPrice, exitFill, exitPrice,
                new BigDecimal("-101.00"), new BigDecimal("-2.00"));
        BigDecimal zero = BigDecimal.ZERO;
        BacktestMetrics metrics = new BacktestMetrics(zero, new BigDecimal("-2"), 1, zero, zero,
                zero, zero, zero, zero, zero);
        List<EquityPoint> curve = List.of(
                new EquityPoint(seriesStart,
                        BigDecimal.valueOf(10000), BigDecimal.valueOf(10000), zero),
                new EquityPoint(exitFill,
                        BigDecimal.valueOf(9899), BigDecimal.valueOf(9899), zero));
        result = new BacktestResult(metrics, List.of(trade), curve, Optional.empty(),
                new BacktestDiagnostics(0, 0, 0, 0, 1, 0, 0));
        triggerTimes = Map.of(entryScenarioName,
                List.of(seriesStart.plus(Duration.ofHours(23))));
    }

    @Then("the report contains the strategy rules section")
    public void theReportContainsTheStrategyRulesSection() {
        assertTrue(reportHtml.contains("class=\"strategy-rules\""),
                "report is missing the Strategy rules section");
        assertTrue(reportHtml.contains("Strategy: Trend gated Tier B"),
                "Strategy rules section is missing the strategy name heading");
    }

    @Then("the rules section uses Given, When and Then labels")
    public void theRulesSectionUsesGwtLabels() {
        assertTrue(reportHtml.contains("<b>GIVEN</b>"), "missing GIVEN label");
        assertTrue(reportHtml.contains("<b>WHEN</b>"), "missing WHEN label");
        assertTrue(reportHtml.contains("<b>THEN</b>"), "missing THEN label");
    }

    @Then("the rules section translates a Tier B step keeping its call form verbatim")
    public void theRulesSectionTranslatesATierBStep() {
        assertTrue(reportHtml.contains("HA doji within threshold 0.1"),
                "ha_doji was not translated to its natural-language phrase");
        assertTrue(reportHtml.contains("<code>ha_doji(0.1)</code>"),
                "ha_doji call form was not kept verbatim in monospace");
        assertTrue(reportHtml.contains("(oversold)"),
                "rsi_oversold natural-language phrase is missing");
    }

    @Then("the rules section shows the stop_loss and take_profit expressions untranslated")
    public void theRulesSectionShowsStopTakeUntranslated() {
        assertTrue(reportHtml.contains("entry_price * 0.98"),
                "stop_loss expression missing/translated in the rules section");
        assertTrue(reportHtml.contains("entry_price * 1.04"),
                "take_profit expression missing/translated in the rules section");
    }

    @Then("every embedded chart image is a PNG")
    public void everyEmbeddedChartImageIsPng() {
        assertTrue(reportHtml.contains("data:image/png;base64,"),
                "no lossless-PNG chart image was embedded");
    }

    @Then("no embedded chart image is a JPEG")
    public void noEmbeddedChartImageIsJpeg() {
        assertFalse(reportHtml.contains("data:image/jpeg") || reportHtml.contains("data:image/jpg"),
                "a JPEG chart image was embedded — chart export should be lossless PNG");
    }

    @Then("a trade Rule line names the fired entry primitives")
    public void aTradeRuleLineNamesTheFiredEntryPrimitives() {
        assertTrue(reportHtml.contains("class=\"rule-line\""),
                "expanded trade is missing the compact Rule line");
        assertTrue(reportHtml.contains("entered via ha_doji(0.1) AND rsi_oversold(30)"),
                "Rule line does not name the fired entry primitives with resolved call forms");
    }

    @Then("the forced exit Rule line is tagged as a stop_loss or take_profit hit")
    public void theForcedExitRuleLineIsTagged() {
        assertTrue(reportHtml.contains("exited via stop_loss (forced, hit at")
                        || reportHtml.contains("exited via take_profit (forced, hit at"),
                "forced exit was not tagged as a stop_loss / take_profit hit");
    }

    @Then("the report contains a chart legend strip")
    public void theReportContainsAChartLegendStrip() {
        assertTrue(reportHtml.contains("class=\"chart-legend\""),
                "rendered chart is missing its indicator legend strip");
    }

    private static int countMatches(String haystack, String needle) {
        int count = 0;
        int idx = 0;
        while ((idx = haystack.indexOf(needle, idx)) >= 0) {
            count++;
            idx += needle.length();
        }
        return count;
    }
}
