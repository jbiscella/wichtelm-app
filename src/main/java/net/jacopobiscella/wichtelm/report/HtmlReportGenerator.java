package net.jacopobiscella.wichtelm.report;

import net.jacopobiscella.wichtelm.error.ReportGenerationException;
import net.jacopobiscella.wichtelm.strategy.BackgroundSeries;
import net.jacopobiscella.wichtelm.strategy.StrategyScenario;
import net.jacopobiscella.wichtelm.strategy.StrategyStep;
import net.jacopobiscella.wichtelm.strategy.Timeframes;
import org.hatrack.commons.OHLCBar;
import org.hatrack.commons.OHLCSeries;
import org.hatrack.commons.PriceSource;
import org.hatrack.commons.Timeframe;
import org.hatrack.frauholle.model.EquityPoint;
import org.hatrack.frauholle.model.Trade;
import org.hatrack.frauholle.result.BacktestDiagnostics;
import org.hatrack.frauholle.result.BacktestMetrics;
import org.hatrack.heerwisch.api.error.ChartRenderException;
import org.hatrack.heerwisch.api.error.DriverInternalException;
import org.hatrack.heerwisch.api.port.ChartRenderer;
import org.hatrack.heerwisch.api.spec.Annotation;
import org.hatrack.heerwisch.api.spec.ChartImage;
import org.hatrack.heerwisch.api.spec.ChartSpec;
import org.hatrack.heerwisch.api.spec.ChartSpecBuilder;
import org.hatrack.heerwisch.api.spec.Indicator;
import org.hatrack.heerwisch.api.spec.LayoutSpec;
import org.hatrack.heerwisch.api.spec.Pane;
import org.hatrack.heerwisch.jfreechart.JFreeChartRenderer;
import org.hatrack.indicators.Indicators;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Renders a self-contained HTML backtest report (CLAUDE.md section 7).
 *
 * <p>Per-Scenario price charts are rendered via the heerwisch-jfreechart
 * driver. The equity and drawdown curves are rendered as inline SVG line
 * charts: the heerwisch {@code Series} contract accepts only OHLC/HA price
 * data and cannot represent an equity curve.
 */
public final class HtmlReportGenerator {

    private static final MathContext DECIMAL = MathContext.DECIMAL64;
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    private static final Pattern INDICATOR_CALL =
            Pattern.compile("^\\s*([a-z_][a-z0-9_]*)\\s*\\(([^)]*)\\)\\s*$");
    private static final BigDecimal DEFAULT_RSI_OVERBOUGHT = BigDecimal.valueOf(70);
    private static final BigDecimal DEFAULT_RSI_OVERSOLD = BigDecimal.valueOf(30);

    /**
     * Generates the report and returns the path written. The filename follows
     * {@code {configBasename}_{timestamp}.html} with colons replaced by hyphens
     * (CLAUDE.md section 7.1); existing reports are never overwritten.
     */
    public Path generate(ReportData data) {
        try {
            Files.createDirectories(data.outputDirectory());
            String timestamp = data.generatedAt()
                    .format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
                    .replace(':', '-');
            Path target = data.outputDirectory()
                    .resolve(data.configBasename() + "_" + timestamp + ".html");
            Files.writeString(target, buildHtml(data));
            return target;
        } catch (IOException e) {
            throw new ReportGenerationException("failed to write HTML report", e);
        }
    }

    private String buildHtml(ReportData data) {
        ChartRenderer renderer = newRenderer();
        StringBuilder html = new StringBuilder();
        html.append("<!DOCTYPE html><html lang=\"en\"><head><meta charset=\"utf-8\"/>")
                .append("<title>Backtest report — ").append(esc(data.configBasename()))
                .append("</title>").append(reportStylesheet())
                .append("</head><body>");

        html.append("<header><h1>Backtest report</h1><p>Strategy: ")
                .append(esc(data.strategy().featureName())).append("</p></header>");

        appendMetrics(html, data.result().metrics());
        appendScenarioBoxes(html, data, renderer);
        appendTrailingSection(html, data);
        appendDisclaimerFooter(html);

        html.append("</body></html>");
        return html.toString();
    }

    private void appendDisclaimerFooter(StringBuilder html) {
        html.append("<footer style=\"font-size: 0.85em; color: #666; margin-top: 2em; ")
                .append("padding: 1em; border-top: 1px solid #ccc;\">")
                .append("<p><strong>Disclaimer:</strong> This report contains hypothetical ")
                .append("results on historical data. Past performance is not indicative ")
                .append("of future results. Results have inherent limitations and do not ")
                .append("account for all market conditions. This is NOT financial advice. ")
                .append("Use at your own risk. Generated by wichtelm-app, provided \"AS IS\" ")
                .append("under its open-source license with no warranties.</p></footer>");
    }

    private void appendMetrics(StringBuilder html, BacktestMetrics metrics) {
        html.append("<section class=\"aggregate-metrics\"><h2>Aggregate metrics</h2>")
                .append("<table class=\"metrics-table\"><tbody>");
        formattedMetricRow(html, "Total return", formatPercent(metrics.totalReturn()));
        formattedMetricRow(html, "Number of trades", Integer.toString(metrics.numTrades()));
        formattedMetricRow(html, "Win rate", formatPercent(metrics.winRate()));
        formattedMetricRow(html, "Max drawdown", formatPercent(metrics.maxDrawdown()));
        formattedMetricRow(html, "Sharpe ratio", formatRatio(metrics.sharpeRatio()));
        formattedMetricRow(html, "Sortino ratio", formatRatio(metrics.sortinoRatio()));
        formattedMetricRow(html, "Calmar ratio", formatRatio(metrics.calmarRatio()));
        formattedMetricRow(html, "Profit factor", metrics.profitFactor().signum() == 0
                ? "undefined" : formatRatio(metrics.profitFactor()));
        formattedMetricRow(html, "Average win", formatAmount(metrics.avgWin()));
        formattedMetricRow(html, "Average loss", formatAmount(metrics.avgLoss()));
        html.append("</tbody></table></section>");
    }

    private void formattedMetricRow(StringBuilder html, String label, String value) {
        html.append("<tr><td class=\"metric-name\">").append(esc(label))
                .append("</td><td class=\"metric-value\">").append(esc(value))
                .append("</td></tr>");
    }

    private void metricRow(StringBuilder html, String name, String value) {
        html.append("<dt>").append(name).append("</dt><dd>").append(esc(value)).append("</dd>");
    }

    private static String formatPercent(BigDecimal value) {
        return value.movePointRight(2).setScale(2, RoundingMode.HALF_UP).toPlainString() + "%";
    }

    private static String formatRatio(BigDecimal value) {
        return value.setScale(4, RoundingMode.HALF_UP).toPlainString();
    }

    private static String formatAmount(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    private static String reportStylesheet() {
        return "<style>"
                + "body{font-family:-apple-system,Segoe UI,Helvetica,Arial,sans-serif;"
                + "max-width:1000px;margin:1.5em auto;padding:0 1em;color:#222;line-height:1.4;}"
                + "h1{margin:0 0 .2em 0;}h2{margin-top:1.8em;border-bottom:1px solid #ddd;padding-bottom:.2em;}"
                + ".metrics-table{border-collapse:collapse;margin:.6em 0 1.4em 0;min-width:340px;}"
                + ".metrics-table td{padding:6px 18px;border-bottom:1px solid #eee;}"
                + ".metrics-table tr:last-child td{border-bottom:none;}"
                + ".metrics-table .metric-name{color:#555;}"
                + ".metrics-table .metric-value{text-align:right;font-variant-numeric:tabular-nums;font-weight:600;}"
                + ".scenario-box{margin:1.4em 0;padding:.4em 0;}"
                + ".scenario-box>h3{margin-bottom:.1em;}"
                + ".scenario-box .trigger-count{margin:0 0 .6em 0;color:#666;font-size:.9em;}"
                + ".trigger-block{border:1px solid #ddd;border-radius:4px;padding:.6em .8em;"
                + "margin:.5em 0 1em 0;background:#fafafa;}"
                + ".trigger-block h4{margin:0 0 .4em 0;font-size:.95em;color:#333;font-weight:600;}"
                + ".trigger-block figure{margin:.3em 0;}"
                + ".sub-report{border-collapse:collapse;margin-top:.5em;font-size:.85em;}"
                + ".sub-report th,.sub-report td{border:1px solid #ddd;padding:4px 10px;text-align:left;}"
                + ".sub-report th{background:#f0f0f0;color:#444;font-weight:600;}"
                + ".sub-report td.sub-condition-held{color:#27ae60;font-weight:600;text-align:center;}"
                + "</style>";
    }

    private void appendScenarioBoxes(StringBuilder html, ReportData data, ChartRenderer renderer) {
        html.append("<section class=\"scenario-boxes\"><h2>Per-Scenario breakdown</h2>");
        List<StrategyScenario> scenarios = new ArrayList<>(data.strategy().scenarios());
        scenarios.sort(Comparator.comparing(StrategyScenario::name));
        for (StrategyScenario scenario : scenarios) {
            appendBox(html, data, renderer, scenario);
        }
        html.append("</section>");
    }

    private void appendBox(StringBuilder html, ReportData data, ChartRenderer renderer,
                           StrategyScenario scenario) {
        List<Instant> triggers = data.triggersByScenario()
                .getOrDefault(scenario.name(), List.of());
        html.append("<section class=\"scenario-box\" data-scenario=\"")
                .append(esc(scenario.name())).append("\"><h3>").append(esc(scenario.name()))
                .append("</h3><p class=\"trigger-count\">Trigger count: ")
                .append(triggers.size()).append("</p>");

        List<String> timeframes = timeframesFor(data, scenario);
        List<StrategyStep> steps = scenario.conditionSteps();
        for (int n = 0; n < triggers.size(); n++) {
            Instant trigger = triggers.get(n);
            appendTriggerBlock(html, data, renderer, scenario, trigger, n + 1, timeframes, steps);
        }

        html.append("</section>");
    }

    /**
     * Renders one block for a single trigger: chart(s) zoomed around the
     * trigger, any sub-pane indicators clipped to the same window, and a
     * one-row sub-table listing the values that made the conjunction true
     * at that bar. CLAUDE.md section 7.3 describes the layout.
     */
    private void appendTriggerBlock(StringBuilder html, ReportData data, ChartRenderer renderer,
                                     StrategyScenario scenario, Instant trigger, int ordinal,
                                     List<String> timeframes, List<StrategyStep> steps) {
        html.append("<div class=\"trigger-block\" data-trigger=\"")
                .append(esc(trigger.toString())).append("\">")
                .append("<h4>Trigger #").append(ordinal).append(" — ")
                .append(esc(trigger.toString())).append("</h4>");

        String primaryTfWire = data.strategy().primaryTimeframe().wire();
        for (String timeframe : timeframes) {
            boolean isPrimary = timeframe.equals(primaryTfWire);
            OHLCSeries fullSeries = isPrimary
                    ? data.primarySeries()
                    : data.higherTimeframeSeries().get(timeframe);
            if (fullSeries == null) {
                throw new ReportGenerationException(
                        "no series supplied for timeframe " + timeframe);
            }
            int periodOnTf = maxIndicatorPeriodForTimeframe(scenario, timeframe, data);
            int before = Math.max(30, (int) Math.ceil(periodOnTf * 1.5));
            int after = 10;
            Timeframe tf = isPrimary
                    ? data.strategy().primaryTimeframe()
                    : Timeframe.fromWire(timeframe);
            OHLCSeries window = sliceAround(fullSeries, trigger, tf, isPrimary,
                    before, after);
            if (window.bars().isEmpty()) {
                continue;
            }
            html.append(renderLocalChart(renderer, window, trigger, timeframe, data));
            Instant windowStart = window.bars().getFirst().time();
            Instant windowEnd = window.bars().getLast().time();
            html.append(renderSubpanesForTimeframe(
                    timeframe, fullSeries, windowStart, windowEnd, trigger, data));
        }

        html.append("<table class=\"sub-report\"><thead><tr><th>Trigger time</th>");
        for (StrategyStep step : steps) {
            html.append("<th>").append(esc(step.text())).append("</th>");
        }
        html.append("</tr></thead><tbody>");
        html.append("<tr><td>").append(esc(trigger.toString())).append("</td>");
        for (int i = 0; i < steps.size(); i++) {
            html.append("<td class=\"sub-condition-held\">✓</td>");
        }
        html.append("</tr></tbody></table></div>");
    }

    /**
     * Largest indicator period among the Background series **referenced by
     * the given Scenario** on the given timeframe. The scope-by-scenario rule
     * matters when other Scenarios in the same strategy reference long-period
     * indicators on the same timeframe: those should not inflate this
     * Scenario's window.
     */
    private int maxIndicatorPeriodForTimeframe(StrategyScenario scenario, String timeframe,
                                               ReportData data) {
        String primaryTf = data.strategy().primaryTimeframe().wire();
        Map<String, BackgroundSeries> seriesByName = new HashMap<>();
        for (BackgroundSeries bg : data.strategy().backgroundSeries()) {
            seriesByName.put(bg.name(), bg);
        }
        Set<String> referenced = new LinkedHashSet<>();
        for (StrategyStep step : scenario.conditionSteps()) {
            Matcher matcher = IDENTIFIER.matcher(step.text());
            while (matcher.find()) {
                BackgroundSeries bg = seriesByName.get(matcher.group());
                if (bg == null) {
                    continue;
                }
                String seriesTf = bg.timeframe().map(Timeframe::wire).orElse(primaryTf);
                if (seriesTf.equals(timeframe)) {
                    referenced.add(bg.name());
                }
            }
        }
        int max = 0;
        for (String name : referenced) {
            max = Math.max(max,
                    indicatorPeriodOf(seriesByName.get(name).expression(), data.parameters()));
        }
        return max;
    }

    /**
     * Extracts the lookback period of a Background series expression so the
     * local window can be sized to honour it. Recognises the full v1 catalog,
     * not just the heerwisch-renderable subset that {@link #toIndicator}
     * returns — window aggregates ({@code highest_close} etc.) and MACD
     * component functions ({@code macd_line} etc.) are also accounted for.
     */
    private static int indicatorPeriodOf(String expression,
                                         Map<String, BigDecimal> parameters) {
        Matcher matcher = INDICATOR_CALL.matcher(expression);
        if (!matcher.matches()) {
            return 0;
        }
        String function = matcher.group(1);
        String rawArgs = matcher.group(2).trim();
        String[] args = rawArgs.isEmpty() ? new String[0] : rawArgs.split("\\s*,\\s*");
        try {
            return switch (function) {
                case "sma", "ema", "rsi", "atr", "stddev",
                     "highest_high", "lowest_low", "highest_close", "lowest_close",
                     "avg_volume" -> resolveIntArg(args, 0, parameters);
                case "macd_line", "macd_signal", "macd_histogram" -> {
                    int slow = resolveIntArg(args, 1, parameters);
                    int signal = resolveIntArg(args, 2, parameters);
                    yield slow + signal;
                }
                default -> 0;
            };
        } catch (IllegalArgumentException | ArrayIndexOutOfBoundsException ignored) {
            return 0;
        }
    }

    /**
     * Sub-series of {@code full} centred on the bar visible to the runtime at
     * {@code triggerTime}.
     *
     * <p>For the primary timeframe the trigger time IS the open time of the
     * bar that fired the signal, so that bar (the latest with
     * {@code time <= triggerTime}) is the anchor.
     *
     * <p>For higher timeframes the runtime resolves to the most recently
     * CLOSED bar with {@code closeTime <= triggerTime} (CLAUDE.md section
     * 3.5). The chart anchors on the same bar so the local window mirrors
     * exactly what the strategy could see — the currently-open higher-TF bar
     * containing the trigger is treated as future data and skipped.
     */
    private OHLCSeries sliceAround(OHLCSeries full, Instant triggerTime, Timeframe tf,
                                    boolean isPrimary, int beforeBars, int afterBars) {
        List<OHLCBar> bars = full.bars();
        int idx = -1;
        if (isPrimary) {
            for (int i = 0; i < bars.size(); i++) {
                if (!bars.get(i).time().isAfter(triggerTime)) {
                    idx = i;
                } else {
                    break;
                }
            }
        } else {
            for (int i = 0; i < bars.size(); i++) {
                Instant closeTime = i + 1 < bars.size()
                        ? bars.get(i + 1).time()
                        : Timeframes.advance(bars.get(i).time(), tf);
                if (!closeTime.isAfter(triggerTime)) {
                    idx = i;
                } else {
                    break;
                }
            }
        }
        if (idx < 0) {
            idx = 0;
        }
        int from = Math.max(0, idx - beforeBars);
        int to = Math.min(bars.size(), idx + afterBars + 1);
        if (from >= to) {
            return new OHLCSeries(List.of());
        }
        return new OHLCSeries(bars.subList(from, to));
    }

    /** Primary timeframe plus every higher timeframe a Scenario's steps reference. */
    private List<String> timeframesFor(ReportData data, StrategyScenario scenario) {
        Map<String, String> seriesTimeframe = new HashMap<>();
        data.strategy().backgroundSeries().forEach(series ->
                series.timeframe().ifPresent(tf -> seriesTimeframe.put(series.name(), tf.wire())));

        Set<String> timeframes = new LinkedHashSet<>();
        timeframes.add(data.strategy().primaryTimeframe().wire());
        for (StrategyStep step : scenario.conditionSteps()) {
            Matcher matcher = IDENTIFIER.matcher(step.text());
            while (matcher.find()) {
                String timeframe = seriesTimeframe.get(matcher.group());
                if (timeframe != null) {
                    timeframes.add(timeframe);
                }
            }
        }
        return List.copyOf(timeframes);
    }

    /**
     * Renders one local chart for a single trigger, showing only the bars
     * inside the zoom window plus a single BarHighlight annotation on the
     * trigger bar itself.
     */
    private String renderLocalChart(ChartRenderer renderer, OHLCSeries window, Instant trigger,
                                     String timeframeLabel, ReportData data) {
        try {
            LayoutSpec layout = LayoutSpec.builder().withSize(900, 300).build();
            var builder = ChartSpec.builder().withSeries(window).withLayout(layout);
            addIndicatorsForTimeframe(builder, timeframeLabel, window, data);
            TreeMap<Instant, BigDecimal> closeByTime = new TreeMap<>();
            for (OHLCBar bar : window.bars()) {
                closeByTime.put(bar.time(), bar.close());
            }
            int placed = 0;
            // On a higher-TF chart the primary-TF trigger time lands inside
            // a wider bar; mark the bar that was open at the trigger.
            Map.Entry<Instant, BigDecimal> bar = closeByTime.floorEntry(trigger);
            if (bar != null) {
                builder.addAnnotation(
                        new Annotation.BarHighlight(bar.getKey(), bar.getValue(), "trigger"));
                placed = 1;
            }
            ChartImage image = renderer.render(builder.build());
            String base64 = Base64.getEncoder().encodeToString(image.bytes());
            return "<figure class=\"chart\" data-timeframe=\"" + esc(timeframeLabel)
                    + "\" data-markers=\"" + placed + "\">"
                    + "<img alt=\"" + esc(timeframeLabel) + " price chart\" src=\"data:"
                    + esc(image.contentType()) + ";base64," + base64 + "\"/>"
                    + "<figcaption>" + esc(timeframeLabel) + "</figcaption></figure>";
        } catch (ChartRenderException e) {
            throw new ReportGenerationException(
                    "chart rendering failed for timeframe " + timeframeLabel, e);
        }
    }

    /**
     * Wires every Background series whose timeframe matches the chart's
     * timeframe into the ChartSpec as a heerwisch {@link Indicator}. Each
     * indicator's default pane decides whether it overlays the price pane
     * (SMA, EMA, BollingerBands) or renders as a subplot (RSI, ATR, MACD,
     * ADX, Stochastic). Identifier args in the series expression resolve
     * against the effective parameter map. Unsupported function names
     * (window aggregates, MACD components, HA primitives, etc.) are
     * skipped silently — the catalog covers more functions than heerwisch
     * exposes as chart indicators.
     */
    private void addIndicatorsForTimeframe(ChartSpecBuilder builder, String timeframeLabel,
                                            OHLCSeries underlying, ReportData data) {
        int bars = underlying.bars().size();
        for (BackgroundSeries series : seriesForTimeframe(timeframeLabel, data)) {
            Indicator indicator = toIndicator(series.expression(), data.parameters());
            // Only main-pane overlay indicators (SMA, EMA, BollingerBands)
            // go through heerwisch. Sub-pane indicators are rendered as
            // dedicated SVG sub-panes below the main chart image where we
            // can pin the Y range and draw the overbought / oversold
            // threshold lines that heerwisch does not emit.
            if (indicator == null
                    || indicator.defaultPane() != Pane.MAIN
                    || bars < indicator.minBars()) {
                continue;
            }
            builder.addIndicator(indicator);
        }
    }

    /** Background series whose timeframe matches the chart being rendered. */
    private List<BackgroundSeries> seriesForTimeframe(String timeframeLabel, ReportData data) {
        String primaryTf = data.strategy().primaryTimeframe().wire();
        List<BackgroundSeries> hits = new ArrayList<>();
        for (BackgroundSeries series : data.strategy().backgroundSeries()) {
            String seriesTf = series.timeframe().map(Timeframe::wire).orElse(primaryTf);
            if (seriesTf.equals(timeframeLabel)) {
                hits.add(series);
            }
        }
        return hits;
    }

    /**
     * Renders each sub-pane indicator (currently RSI) as an SVG block below
     * the main chart image, with a pinned 0–100 Y axis, threshold lines at
     * the strategy's overbought / oversold parameters, pale danger-zone
     * shading, and an X axis clipped to the local trigger window.
     *
     * <p>The RSI itself is computed from the full series so the line is
     * already warmed up when it enters the window; only the bars in
     * {@code [windowStart, windowEnd]} are plotted. The trigger bar gets a
     * vertical reference line so the trigger lines up visually with the
     * main chart's BarHighlight above.
     */
    private String renderSubpanesForTimeframe(String timeframeLabel, OHLCSeries fullSeries,
                                              Instant windowStart, Instant windowEnd,
                                              Instant trigger, ReportData data) {
        StringBuilder out = new StringBuilder();
        for (BackgroundSeries series : seriesForTimeframe(timeframeLabel, data)) {
            Indicator indicator = toIndicator(series.expression(), data.parameters());
            if (indicator instanceof Indicator.RSI rsi
                    && fullSeries.bars().size() > rsi.period()) {
                out.append("<figure class=\"subpane\" data-indicator=\"rsi\">")
                        .append(renderRsiSubpane(fullSeries, rsi.period(),
                                rsi.overbought(), rsi.oversold(),
                                windowStart, windowEnd, trigger))
                        .append("</figure>");
            }
        }
        return out.toString();
    }

    private String renderRsiSubpane(OHLCSeries fullSeries, int period,
                                    BigDecimal overbought, BigDecimal oversold,
                                    Instant windowStart, Instant windowEnd, Instant trigger) {
        List<OHLCBar> bars = fullSeries.bars();
        List<BigDecimal> closes = new ArrayList<>(bars.size());
        Instant[] times = new Instant[bars.size()];
        for (int i = 0; i < bars.size(); i++) {
            closes.add(bars.get(i).close());
            times[i] = bars.get(i).time();
        }
        BigDecimal[] rsi = Indicators.rsi(closes, period);

        double vbW = 900.0;
        double vbH = 140.0;
        double padLeft = 50.0;
        double padRight = 30.0;
        double padTop = 22.0;
        double padBottom = 36.0;
        double plotW = vbW - padLeft - padRight;
        double plotH = vbH - padTop - padBottom;

        double obY = overbought.doubleValue();
        double osY = oversold.doubleValue();
        double obYpx = padTop + plotH - (obY / 100.0) * plotH;
        double osYpx = padTop + plotH - (osY / 100.0) * plotH;

        long t0 = windowStart.toEpochMilli();
        long t1 = windowEnd.toEpochMilli();
        long tSpan = Math.max(t1 - t0, 1L);

        StringBuilder svg = new StringBuilder();
        svg.append("<svg class=\"rsi-subpane\" viewBox=\"0 0 ")
                .append((int) vbW).append(' ').append((int) vbH)
                .append("\" width=\"100%\" preserveAspectRatio=\"xMidYMid meet\"")
                .append(" xmlns=\"http://www.w3.org/2000/svg\">");

        // Title.
        svg.append("<text x=\"").append(round(padLeft))
                .append("\" y=\"14\" font-family=\"sans-serif\" font-size=\"11\" fill=\"#333\">RSI (")
                .append(period).append(")</text>");

        // Pale danger zones (red above overbought, green below oversold).
        svg.append("<rect x=\"").append(round(padLeft)).append("\" y=\"").append(round(padTop))
                .append("\" width=\"").append(round(plotW))
                .append("\" height=\"").append(round(obYpx - padTop))
                .append("\" fill=\"rgba(231,76,60,0.08)\"/>");
        svg.append("<rect x=\"").append(round(padLeft)).append("\" y=\"").append(round(osYpx))
                .append("\" width=\"").append(round(plotW))
                .append("\" height=\"").append(round(padTop + plotH - osYpx))
                .append("\" fill=\"rgba(39,174,96,0.08)\"/>");

        // Y-axis grid + tick labels at 0 / 30 / 50 / 70 / 100.
        int[] ticks = {0, 30, 50, 70, 100};
        svg.append("<g font-size=\"10\" font-family=\"sans-serif\" fill=\"#555\">");
        for (int yVal : ticks) {
            double y = padTop + plotH - (yVal / 100.0) * plotH;
            svg.append("<line x1=\"").append(round(padLeft))
                    .append("\" x2=\"").append(round(padLeft + plotW))
                    .append("\" y1=\"").append(round(y))
                    .append("\" y2=\"").append(round(y))
                    .append("\" stroke=\"#e6e6e6\"/>");
            svg.append("<text x=\"").append(round(padLeft - 4))
                    .append("\" y=\"").append(round(y + 3))
                    .append("\" text-anchor=\"end\">").append(yVal).append("</text>");
        }
        svg.append("</g>");

        // Dashed threshold lines at overbought / oversold.
        svg.append("<line x1=\"").append(round(padLeft))
                .append("\" x2=\"").append(round(padLeft + plotW))
                .append("\" y1=\"").append(round(obYpx))
                .append("\" y2=\"").append(round(obYpx))
                .append("\" stroke=\"#999\" stroke-dasharray=\"3 3\" stroke-width=\"1\"/>");
        svg.append("<line x1=\"").append(round(padLeft))
                .append("\" x2=\"").append(round(padLeft + plotW))
                .append("\" y1=\"").append(round(osYpx))
                .append("\" y2=\"").append(round(osYpx))
                .append("\" stroke=\"#999\" stroke-dasharray=\"3 3\" stroke-width=\"1\"/>");

        // X-axis: five evenly-spaced ticks across the window, with the
        // label granularity chosen from the span (hours / days / months).
        DateTimeFormatter xFmt = adaptiveTickFormatter(tSpan);
        svg.append("<g font-size=\"10\" font-family=\"sans-serif\" fill=\"#555\">");
        int tickCount = 5;
        for (int k = 0; k <= tickCount; k++) {
            long tickT = t0 + (long) ((double) tSpan * k / tickCount);
            double px = padLeft + (double) (tickT - t0) / tSpan * plotW;
            svg.append("<line x1=\"").append(round(px))
                    .append("\" x2=\"").append(round(px))
                    .append("\" y1=\"").append(round(padTop + plotH))
                    .append("\" y2=\"").append(round(padTop + plotH + 4))
                    .append("\" stroke=\"#888\"/>");
            double labelY = padTop + plotH + 14;
            String label = Instant.ofEpochMilli(tickT)
                    .atOffset(ZoneOffset.UTC).format(xFmt);
            svg.append("<text x=\"").append(round(px))
                    .append("\" y=\"").append(round(labelY))
                    .append("\" text-anchor=\"end\" transform=\"rotate(-45, ")
                    .append(round(px)).append(',').append(round(labelY))
                    .append(")\">").append(esc(label)).append("</text>");
        }
        svg.append("</g>");

        // Plot border.
        svg.append("<rect x=\"").append(round(padLeft))
                .append("\" y=\"").append(round(padTop))
                .append("\" width=\"").append(round(plotW))
                .append("\" height=\"").append(round(plotH))
                .append("\" fill=\"none\" stroke=\"#888\"/>");

        // Vertical line at the trigger time so the RSI bar visually aligns
        // with the BarHighlight on the main chart above.
        long triggerT = trigger.toEpochMilli();
        if (triggerT >= t0 && triggerT <= t1) {
            double triggerPx = padLeft + (double) (triggerT - t0) / tSpan * plotW;
            svg.append("<line x1=\"").append(round(triggerPx))
                    .append("\" x2=\"").append(round(triggerPx))
                    .append("\" y1=\"").append(round(padTop))
                    .append("\" y2=\"").append(round(padTop + plotH))
                    .append("\" stroke=\"#f1c40f\" stroke-width=\"1.5\"/>");
        }

        // RSI line — plot only the points inside the window.
        StringBuilder path = new StringBuilder();
        boolean started = false;
        for (int i = 0; i < rsi.length; i++) {
            if (rsi[i] == null) {
                continue;
            }
            long ti = times[i].toEpochMilli();
            if (ti < t0 || ti > t1) {
                continue;
            }
            double px = padLeft + (double) (ti - t0) / tSpan * plotW;
            double py = padTop + plotH - (rsi[i].doubleValue() / 100.0) * plotH;
            path.append(started ? "L" : "M").append(round(px)).append(' ')
                    .append(round(py)).append(' ');
            started = true;
        }
        svg.append("<path fill=\"none\" stroke=\"#7d3c98\" stroke-width=\"1.4\" d=\"")
                .append(path.toString().strip()).append("\"/>");

        svg.append("</svg>");
        return svg.toString();
    }

    /** Choose a date/time tick label format based on the visible time span. */
    private static DateTimeFormatter adaptiveTickFormatter(long spanMillis) {
        long days = spanMillis / (1000L * 60 * 60 * 24);
        if (days <= 3) {
            return DateTimeFormatter.ofPattern("MMM d HH:mm", Locale.ENGLISH);
        }
        if (days <= 60) {
            return DateTimeFormatter.ofPattern("MMM d", Locale.ENGLISH);
        }
        return DateTimeFormatter.ofPattern("MMM yyyy", Locale.ENGLISH);
    }

    /**
     * Maps a Background series expression like {@code "ema(trend_period)"}
     * or {@code "rsi(rsi_period)"} to a heerwisch {@link Indicator}, with
     * identifier args resolved against the effective parameter map. RSI
     * thresholds come from {@code overbought} / {@code oversold} parameters
     * if the strategy declared them, otherwise fall back to 70 / 30.
     */
    private static Indicator toIndicator(String expression, Map<String, BigDecimal> parameters) {
        Matcher matcher = INDICATOR_CALL.matcher(expression);
        if (!matcher.matches()) {
            return null;
        }
        String function = matcher.group(1);
        String rawArgs = matcher.group(2).trim();
        String[] args = rawArgs.isEmpty() ? new String[0] : rawArgs.split("\\s*,\\s*");
        try {
            return switch (function) {
                case "sma" -> new Indicator.SMA(resolveIntArg(args, 0, parameters),
                        PriceSource.CLOSE);
                case "ema" -> new Indicator.EMA(resolveIntArg(args, 0, parameters),
                        PriceSource.CLOSE);
                case "rsi" -> new Indicator.RSI(
                        resolveIntArg(args, 0, parameters),
                        parameters.getOrDefault("overbought", DEFAULT_RSI_OVERBOUGHT),
                        parameters.getOrDefault("oversold", DEFAULT_RSI_OVERSOLD),
                        PriceSource.CLOSE);
                case "atr" -> new Indicator.ATR(resolveIntArg(args, 0, parameters));
                default -> null;
            };
        } catch (IllegalArgumentException unresolvable) {
            return null;
        }
    }

    private static int resolveIntArg(String[] args, int index, Map<String, BigDecimal> parameters) {
        if (index >= args.length) {
            throw new IllegalArgumentException("missing arg");
        }
        String token = args[index].trim();
        BigDecimal fromParam = parameters.get(token);
        if (fromParam != null) {
            try {
                return fromParam.intValueExact();
            } catch (ArithmeticException notInteger) {
                // A non-integer override (e.g. 0.95) for an arg used in an
                // integer slot — skip this indicator rather than crash the
                // report. toIndicator() already catches IllegalArgumentException.
                throw new IllegalArgumentException(
                        "non-integer parameter value for arg '" + token + "': " + fromParam);
            }
        }
        return Integer.parseInt(token);
    }

    private void appendTrailingSection(StringBuilder html, ReportData data) {
        List<EquityPoint> curve = data.result().equityCurve();
        html.append("<section class=\"equity-curve\"><h2>Equity curve (% of initial capital)</h2>")
                .append(equityCurveSvg(curve))
                .append("</section>");
        html.append("<section class=\"drawdown-curve\"><h2>Drawdown (peak-to-current, %)</h2>")
                .append(drawdownCurveSvg(curve))
                .append("</section>");

        html.append("<section class=\"trade-list\"><h2>Trades</h2>")
                .append("<table><thead><tr><th>entryTime</th><th>exitTime</th><th>direction</th>")
                .append("<th>entryPrice</th><th>exitPrice</th><th>pnl_pct</th></tr></thead><tbody>");
        for (Trade trade : data.result().trades()) {
            html.append("<tr><td>").append(esc(trade.entryTime().toString()))
                    .append("</td><td>").append(esc(trade.exitTime().toString()))
                    .append("</td><td>").append(trade.direction())
                    .append("</td><td>").append(trade.entryPrice().toPlainString())
                    .append("</td><td>").append(trade.exitPrice().toPlainString())
                    .append("</td><td>").append(trade.pnlPercent().toPlainString())
                    .append("</td></tr>");
        }
        html.append("</tbody></table></section>");

        BacktestDiagnostics diagnostics = data.result().diagnostics();
        html.append("<section class=\"diagnostics\"><h2>Diagnostics</h2><dl>");
        metricRow(html, "ignoredBuySignals", Integer.toString(diagnostics.ignoredBuySignals()));
        metricRow(html, "ignoredSellSignals", Integer.toString(diagnostics.ignoredSellSignals()));
        metricRow(html, "noOpClosePositionSignals",
                Integer.toString(diagnostics.noOpClosePositionSignals()));
        metricRow(html, "unfilledSignalsAtEndOfSeries",
                Integer.toString(diagnostics.unfilledSignalsAtEndOfSeries()));
        metricRow(html, "forcedClosesAtExplicitPrice",
                Integer.toString(diagnostics.forcedClosesAtExplicitPrice()));
        metricRow(html, "addToPositionCount", Integer.toString(diagnostics.addToPositionCount()));
        metricRow(html, "addToPositionOnNoPositionCount",
                Integer.toString(diagnostics.addToPositionOnNoPositionCount()));
        html.append("</dl></section>");
    }

    private String equityCurveSvg(List<EquityPoint> curve) {
        if (curve.isEmpty()) {
            return "<p class=\"equity empty\">no data</p>";
        }
        BigDecimal initial = curve.getFirst().equity();
        Instant[] times = new Instant[curve.size()];
        double[] vals = new double[curve.size()];
        for (int i = 0; i < curve.size(); i++) {
            times[i] = curve.get(i).time();
            vals[i] = curve.get(i).equity()
                    .divide(initial, DECIMAL).doubleValue() * 100.0;
        }
        return renderTimeSeries(times, vals, "equity", true);
    }

    private String drawdownCurveSvg(List<EquityPoint> curve) {
        if (curve.isEmpty()) {
            return "<p class=\"drawdown empty\">no data</p>";
        }
        Instant[] times = new Instant[curve.size()];
        double[] vals = new double[curve.size()];
        double peak = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < curve.size(); i++) {
            times[i] = curve.get(i).time();
            double eq = curve.get(i).equity().doubleValue();
            peak = Math.max(peak, eq);
            vals[i] = peak == 0 ? 0 : (eq / peak - 1.0) * 100.0;
        }
        return renderTimeSeries(times, vals, "drawdown", false);
    }

    /**
     * Renders a self-contained SVG time series chart with monthly X-axis
     * ticks, a 5%-step Y-axis grid, an axis label, and padding on all sides.
     * Equity charts get a dashed 100% reference line; drawdown charts get a
     * light-red filled area under the curve and a dark-red line.
     */
    private String renderTimeSeries(Instant[] times, double[] vals,
                                    String cssClass, boolean isEquity) {
        double vbW = 880.0;
        double vbH = 300.0;
        double padLeft = 60.0;
        double padRight = 40.0;
        double padTop = 40.0;
        double padBottom = 60.0;
        double plotW = vbW - padLeft - padRight;
        double plotH = vbH - padTop - padBottom;

        double minV = Double.POSITIVE_INFINITY;
        double maxV = Double.NEGATIVE_INFINITY;
        for (double v : vals) {
            minV = Math.min(minV, v);
            maxV = Math.max(maxV, v);
        }
        double yMin;
        double yMax;
        if (isEquity) {
            yMin = Math.min(100.0, Math.floor(minV / 5.0) * 5.0);
            yMax = Math.max(100.0, Math.ceil(maxV / 5.0) * 5.0);
        } else {
            yMin = Math.floor(minV / 5.0) * 5.0;
            yMax = Math.max(0.0, Math.ceil(maxV / 5.0) * 5.0);
        }
        if (yMin == yMax) {
            yMin -= 5.0;
            yMax += 5.0;
        }
        double ySpan = yMax - yMin;

        long t0 = times[0].toEpochMilli();
        long t1 = times[times.length - 1].toEpochMilli();
        long tSpan = Math.max(t1 - t0, 1L);

        StringBuilder svg = new StringBuilder();
        svg.append("<svg class=\"").append(cssClass)
                .append("\" viewBox=\"0 0 ").append((int) vbW).append(' ').append((int) vbH)
                .append("\" width=\"100%\" preserveAspectRatio=\"xMidYMid meet\"")
                .append(" xmlns=\"http://www.w3.org/2000/svg\">");

        // Horizontal grid lines every 5%.
        svg.append("<g stroke=\"#e6e6e6\" stroke-width=\"1\">");
        for (double y = yMin; y <= yMax + 0.001; y += 5.0) {
            double py = padTop + plotH - (y - yMin) / ySpan * plotH;
            svg.append("<line x1=\"").append(round(padLeft))
                    .append("\" x2=\"").append(round(padLeft + plotW))
                    .append("\" y1=\"").append(round(py))
                    .append("\" y2=\"").append(round(py)).append("\"/>");
        }
        svg.append("</g>");

        // Y-axis tick labels.
        svg.append("<g font-size=\"11\" font-family=\"sans-serif\" fill=\"#555\">");
        for (double y = yMin; y <= yMax + 0.001; y += 5.0) {
            double py = padTop + plotH - (y - yMin) / ySpan * plotH;
            svg.append("<text x=\"").append(round(padLeft - 6))
                    .append("\" y=\"").append(round(py + 4))
                    .append("\" text-anchor=\"end\">").append(formatPercentTick(y))
                    .append("</text>");
        }
        svg.append("</g>");

        // Y-axis label, rotated.
        double labelCy = padTop + plotH / 2.0;
        svg.append("<text x=\"18\" y=\"").append(round(labelCy))
                .append("\" transform=\"rotate(-90, 18, ").append(round(labelCy))
                .append(")\" text-anchor=\"middle\" font-size=\"12\" font-family=\"sans-serif\" fill=\"#333\">")
                .append(isEquity ? "Equity (% of initial)" : "Drawdown (%)")
                .append("</text>");

        // Reference line / filled area BEFORE the main path so the line sits on top.
        if (isEquity) {
            double py100 = padTop + plotH - (100.0 - yMin) / ySpan * plotH;
            svg.append("<line x1=\"").append(round(padLeft))
                    .append("\" x2=\"").append(round(padLeft + plotW))
                    .append("\" y1=\"").append(round(py100))
                    .append("\" y2=\"").append(round(py100))
                    .append("\" stroke=\"#888\" stroke-dasharray=\"4 4\" stroke-width=\"1\"/>");
        } else {
            double py0 = padTop + plotH - (0.0 - yMin) / ySpan * plotH;
            StringBuilder area = new StringBuilder();
            double firstPx = padLeft + (double) (times[0].toEpochMilli() - t0) / tSpan * plotW;
            area.append("M ").append(round(firstPx)).append(',').append(round(py0)).append(' ');
            for (int i = 0; i < vals.length; i++) {
                double px = padLeft + (double) (times[i].toEpochMilli() - t0) / tSpan * plotW;
                double py = padTop + plotH - (vals[i] - yMin) / ySpan * plotH;
                area.append("L ").append(round(px)).append(',').append(round(py)).append(' ');
            }
            double lastPx = padLeft + (double) (times[times.length - 1].toEpochMilli() - t0)
                    / tSpan * plotW;
            area.append("L ").append(round(lastPx)).append(',').append(round(py0)).append(" Z");
            svg.append("<path fill=\"rgba(192,57,43,0.18)\" stroke=\"none\" d=\"")
                    .append(area).append("\"/>");
        }

        // Monthly X-axis ticks, labels rotated 45 degrees.
        YearMonth startYm = YearMonth.from(times[0].atOffset(ZoneOffset.UTC).toLocalDate());
        YearMonth endYm = YearMonth.from(times[times.length - 1].atOffset(ZoneOffset.UTC).toLocalDate());
        DateTimeFormatter monthFmt = DateTimeFormatter.ofPattern("MMM yyyy", Locale.ENGLISH);
        svg.append("<g font-size=\"11\" font-family=\"sans-serif\" fill=\"#555\">");
        YearMonth ym = startYm;
        while (!ym.isAfter(endYm)) {
            Instant tick = ym.atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant();
            long tickT = tick.toEpochMilli();
            if (tickT >= t0 && tickT <= t1) {
                double px = padLeft + (double) (tickT - t0) / tSpan * plotW;
                svg.append("<line x1=\"").append(round(px))
                        .append("\" x2=\"").append(round(px))
                        .append("\" y1=\"").append(round(padTop + plotH))
                        .append("\" y2=\"").append(round(padTop + plotH + 5))
                        .append("\" stroke=\"#888\"/>");
                double labelY = padTop + plotH + 18;
                svg.append("<text x=\"").append(round(px))
                        .append("\" y=\"").append(round(labelY))
                        .append("\" text-anchor=\"end\" transform=\"rotate(-45, ")
                        .append(round(px)).append(',').append(round(labelY))
                        .append(")\">").append(ym.format(monthFmt)).append("</text>");
            }
            ym = ym.plusMonths(1);
        }
        svg.append("</g>");

        // Plot border.
        svg.append("<rect x=\"").append(round(padLeft))
                .append("\" y=\"").append(round(padTop))
                .append("\" width=\"").append(round(plotW))
                .append("\" height=\"").append(round(plotH))
                .append("\" fill=\"none\" stroke=\"#888\" stroke-width=\"1\"/>");

        // Main line on top of fill/grid/reference.
        StringBuilder path = new StringBuilder();
        for (int i = 0; i < vals.length; i++) {
            double px = padLeft + (double) (times[i].toEpochMilli() - t0) / tSpan * plotW;
            double py = padTop + plotH - (vals[i] - yMin) / ySpan * plotH;
            path.append(i == 0 ? "M" : "L").append(round(px)).append(' ')
                    .append(round(py)).append(' ');
        }
        String stroke = isEquity ? "#1f77b4" : "#922b21";
        svg.append("<path fill=\"none\" stroke=\"").append(stroke)
                .append("\" stroke-width=\"2\" d=\"").append(path.toString().strip())
                .append("\"/>");

        svg.append("</svg>");
        return svg.toString();
    }

    private static String formatPercentTick(double v) {
        if (Math.abs(v) < 0.001) {
            return "0%";
        }
        return String.format(Locale.ROOT, "%.0f%%", v);
    }

    private static double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private ChartRenderer newRenderer() {
        try {
            return new JFreeChartRenderer();
        } catch (DriverInternalException e) {
            throw new ReportGenerationException("could not initialize the chart renderer", e);
        }
    }

    private static String esc(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;");
    }
}
