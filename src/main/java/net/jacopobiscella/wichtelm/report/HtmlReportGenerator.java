package net.jacopobiscella.wichtelm.report;

import net.jacopobiscella.wichtelm.error.ReportGenerationException;
import net.jacopobiscella.wichtelm.strategy.BackgroundSeries;
import net.jacopobiscella.wichtelm.strategy.StrategyScenario;
import net.jacopobiscella.wichtelm.strategy.StrategyStep;
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
import org.hatrack.heerwisch.jfreechart.JFreeChartRenderer;

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

        for (String timeframe : timeframesFor(data, scenario)) {
            OHLCSeries series = timeframe.equals(data.strategy().primaryTimeframe().wire())
                    ? data.primarySeries()
                    : data.higherTimeframeSeries().get(timeframe);
            if (series == null) {
                throw new ReportGenerationException(
                        "no series supplied for timeframe " + timeframe);
            }
            html.append(renderChart(renderer, series, triggers, timeframe, data));
        }

        List<StrategyStep> steps = scenario.conditionSteps();
        html.append("<table class=\"sub-report\"><thead><tr><th>Trigger time</th>");
        for (StrategyStep step : steps) {
            html.append("<th>").append(esc(step.text())).append("</th>");
        }
        html.append("</tr></thead><tbody>");
        for (Instant trigger : triggers) {
            html.append("<tr><td>").append(esc(trigger.toString())).append("</td>");
            // Every When/And step held at a trigger bar — that is what a trigger is.
            for (int i = 0; i < steps.size(); i++) {
                html.append("<td class=\"sub-condition-held\">✓</td>");
            }
            html.append("</tr>");
        }
        html.append("</tbody></table></section>");
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

    private String renderChart(ChartRenderer renderer, OHLCSeries series,
                               List<Instant> markers, String timeframeLabel, ReportData data) {
        try {
            var builder = ChartSpec.builder().withSeries(series).withLayout(LayoutSpec.defaults());
            addIndicatorsForTimeframe(builder, timeframeLabel, series, data);
            TreeMap<Instant, BigDecimal> closeByTime = new TreeMap<>();
            for (OHLCBar bar : series.bars()) {
                closeByTime.put(bar.time(), bar.close());
            }
            int placed = 0;
            for (Instant marker : markers) {
                // A trigger time is a primary-TF instant. On a higher-TF chart
                // it falls inside a wider bar, so the marker is placed on the
                // bar that was open at the trigger (greatest bar time <= marker).
                Map.Entry<Instant, BigDecimal> bar = closeByTime.floorEntry(marker);
                if (bar != null) {
                    builder.addAnnotation(
                            new Annotation.BarHighlight(bar.getKey(), bar.getValue(), "trigger"));
                    placed++;
                }
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
        String primaryTf = data.strategy().primaryTimeframe().wire();
        int bars = underlying.bars().size();
        for (BackgroundSeries series : data.strategy().backgroundSeries()) {
            String seriesTf = series.timeframe().map(Timeframe::wire).orElse(primaryTf);
            if (!seriesTf.equals(timeframeLabel)) {
                continue;
            }
            Indicator indicator = toIndicator(series.expression(), data.parameters());
            // Skip indicators the chart can't honour — heerwisch rejects a
            // ChartSpec whose series has fewer bars than the indicator needs.
            if (indicator != null && bars >= indicator.minBars()) {
                builder.addIndicator(indicator);
            }
        }
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
            return fromParam.intValueExact();
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
