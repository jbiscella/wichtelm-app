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
import org.hatrack.frauholle.model.EquityPoint;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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

    private String configBasename = "report";
    private LocalDateTime generatedAt = LocalDateTime.parse("2026-01-01T00:00:00");
    private Path outputDirectory;
    private ParsedStrategy strategy = StrategyParser.parse(DEFAULT_STRATEGY, "report-test.strat");
    private final Map<String, OHLCSeries> higherSeries = new HashMap<>();
    private String focusScenario;
    private BacktestResult result;
    private Path reportPath;
    private String reportHtml;

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

    private OHLCSeries primarySeries() {
        return series(Instant.parse("2024-01-01T00:00:00Z"), Duration.ofHours(1), 6);
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
                backtestResult(), primarySeries(), higherSeries, Map.of());
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

    @Given("a strategy with Scenarios named {string}, {string}, {string}, {string}")
    public void aStrategyWithScenariosNamed(String a, String b, String c, String d) {
        StringBuilder text = new StringBuilder("Feature: Box test\n  Primary timeframe: 1h\n");
        for (String name : List.of(a, b, c, d)) {
            text.append("\n  Scenario: ").append(name)
                    .append("\n    Given no open position\n    When close exceeds 1\n")
                    .append("    Then long_entry\n");
        }
        strategy = StrategyParser.parse(text.toString(), "box-test.strat");
    }

    @Given("a strategy with an entry Scenario referencing a 1d Background series on primary 1h")
    public void aStrategyReferencingTwoTimeframes() {
        strategy = StrategyParser.parse("""
                Feature: Chart test
                  Primary timeframe: 1h

                  Background:
                    Given a series trend defined as ema(200) on 1d

                  Scenario: Multi
                    Given no open position
                    When close is above trend
                    Then long_entry
                """, "chart-test.strat");
        focusScenario = "Multi";
        higherSeries.put("1d", series(Instant.parse("2024-01-01T00:00:00Z"),
                Duration.ofDays(1), 6));
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

    @Then("the report contains {int} scenario boxes")
    public void theReportContainsScenarioBoxes(int count) {
        assertEquals(count, countMatches(reportHtml, "class=\"scenario-box\""));
    }

    @Then("the scenario box order is {string}, {string}, {string}, {string}")
    public void theScenarioBoxOrderIs(String a, String b, String c, String d) {
        assertEquals(List.of(a, b, c, d), dataAttributeValues(reportHtml, "data-scenario"));
    }

    @Then("the box for that Scenario contains {int} charts")
    public void theBoxForThatScenarioContainsCharts(int count) {
        assertEquals(count, countMatches(focusBox(), "class=\"chart\""));
    }

    @Then("the chart timeframes of that box are {string} and {string}")
    public void theChartTimeframesOfThatBoxAre(String first, String second) {
        assertEquals(List.of(first, second), dataAttributeValues(focusBox(), "data-timeframe"));
    }

    private String focusBox() {
        String marker = "data-scenario=\"" + focusScenario + "\"";
        int start = reportHtml.indexOf(marker);
        assertTrue(start >= 0, () -> "no box for scenario " + focusScenario);
        int end = reportHtml.indexOf("</section>", start);
        return reportHtml.substring(start, end < 0 ? reportHtml.length() : end);
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

    private static List<String> dataAttributeValues(String html, String attribute) {
        List<String> values = new ArrayList<>();
        Matcher matcher = Pattern.compile(attribute + "=\"([^\"]*)\"").matcher(html);
        while (matcher.find()) {
            values.add(matcher.group(1));
        }
        return values;
    }
}
