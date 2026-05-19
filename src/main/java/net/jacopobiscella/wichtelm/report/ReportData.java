package net.jacopobiscella.wichtelm.report;

import net.jacopobiscella.wichtelm.strategy.ParsedStrategy;
import org.hatrack.commons.OHLCSeries;
import org.hatrack.frauholle.result.BacktestResult;

import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Everything {@link HtmlReportGenerator} needs to render one backtest report.
 *
 * @param configBasename       config file name without extension (drives the report filename)
 * @param generatedAt          report generation time (drives the filename timestamp)
 * @param outputDirectory      directory the report is written to
 * @param strategy             the parsed strategy whose Scenarios become report boxes
 * @param result               the frau-holle backtest result
 * @param primarySeries        OHLC bars for the primary timeframe
 * @param higherTimeframeSeries OHLC bars per higher timeframe, keyed by timeframe wire (e.g. "1d")
 * @param triggersByScenario   trigger bar times per Scenario name (chart markers)
 * @param triggerCounts        number of bars each Scenario fired on, by Scenario name
 */
public record ReportData(String configBasename,
                         LocalDateTime generatedAt,
                         Path outputDirectory,
                         ParsedStrategy strategy,
                         BacktestResult result,
                         OHLCSeries primarySeries,
                         Map<String, OHLCSeries> higherTimeframeSeries,
                         Map<String, List<Instant>> triggersByScenario,
                         Map<String, Integer> triggerCounts) {

    public ReportData {
        Objects.requireNonNull(configBasename, "configBasename");
        Objects.requireNonNull(generatedAt, "generatedAt");
        Objects.requireNonNull(outputDirectory, "outputDirectory");
        Objects.requireNonNull(strategy, "strategy");
        Objects.requireNonNull(result, "result");
        Objects.requireNonNull(primarySeries, "primarySeries");
        higherTimeframeSeries = Map.copyOf(higherTimeframeSeries);
        triggersByScenario = Map.copyOf(triggersByScenario);
        triggerCounts = Map.copyOf(triggerCounts);
    }
}
