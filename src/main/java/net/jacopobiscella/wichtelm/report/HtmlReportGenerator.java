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
import org.hatrack.frauholle.model.Position;
import org.hatrack.frauholle.model.Trade;
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
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Renders a self-contained HTML backtest report against the finalized design
 * template (CLAUDE.md section 7).
 *
 * <p>The frames, header, footer, metrics grid and trade-detail layout are
 * styled per the design system. The per-trade price / higher-TF chart images
 * inside those frames are produced by the heerwisch-jfreechart driver — the
 * chart engine is not touched by this generator; it just embeds the driver's
 * output as a {@code <img>} inside the styled frame. The equity-curve and
 * drawdown panels remain hand-rendered SVG (they predate the JFreeChart
 * integration and match the template aesthetic closely).
 *
 * <p>RSI sub-panes are still emitted as a separate SVG block below the price
 * chart image: heerwisch's native RSI rendering cannot pin the Y-axis to
 * 0–100 or draw the overbought / oversold threshold lines, which is the
 * minimum the strategy author needs to read the signal.
 */
public final class HtmlReportGenerator {

    private static final MathContext DECIMAL = MathContext.DECIMAL64;
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    private static final Pattern INDICATOR_CALL =
            Pattern.compile("^\\s*([a-z_][a-z0-9_]*)\\s*\\(([^)]*)\\)\\s*$");
    private static final BigDecimal DEFAULT_RSI_OVERBOUGHT = BigDecimal.valueOf(70);
    private static final BigDecimal DEFAULT_RSI_OVERSOLD = BigDecimal.valueOf(30);
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private static final String TEMPLATE_CSS = loadResource("/report/template.css");
    private static final String LOGO_SVG = loadResource("/report/logo.svg");

    private static final String VERSION = "1.0.0";

    private static final String DISCLAIMER_FULL =
            "This report is a backtest of a hypothetical trading strategy on historical "
            + "data. It is NOT financial advice, investment advice, or a solicitation to "
            + "buy or sell any security. Past performance is not indicative of, and does "
            + "not guarantee, future results. Backtests are subject to survivorship bias, "
            + "look-ahead bias, and modelling assumptions that may not hold in live "
            + "trading; slippage, commissions, taxes and liquidity constraints are "
            + "simplified or excluded. Use at your own risk. The author and wichtelm-app "
            + "accept no liability for losses incurred from acting on this material.";

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
        html.append("<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\"/>")
                .append("<title>Backtest report — ").append(esc(data.strategy().featureName()))
                .append(" — ").append(esc(data.symbol())).append("</title>")
                .append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"/>")
                .append("<style>").append(TEMPLATE_CSS).append("</style>")
                .append("</head><body><div class=\"page\">");

        appendHeader(html, data);
        appendAggregateMetrics(html, data.result().metrics());
        appendEquityAndDrawdown(html, data);
        appendTradeList(html, data, renderer);
        appendFooter(html, data);

        html.append("</div></body></html>");
        return html.toString();
    }

    // ─── Header ──────────────────────────────────────────────────────────────

    private void appendHeader(StringBuilder html, ReportData data) {
        List<OHLCBar> bars = data.primarySeries().bars();
        String windowFrom = bars.isEmpty() ? "—"
                : bars.getFirst().time().atOffset(ZoneOffset.UTC).toLocalDate().toString();
        String windowTo = bars.isEmpty() ? "—"
                : bars.getLast().time().atOffset(ZoneOffset.UTC).toLocalDate().toString();
        String tfWire = data.strategy().primaryTimeframe().wire();
        String multiTf = data.higherTimeframeSeries().isEmpty()
                ? ""
                : " (multi-TF, " + describeHigherTfs(data) + " background)";
        String generated = data.generatedAt()
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd · HH:mm 'UTC'", Locale.ENGLISH));

        html.append("<header class=\"doc-header\">")
                .append("<div class=\"masthead\">").append(LOGO_SVG)
                .append("<div class=\"wordmark\">wichtelm-app")
                .append("<span class=\"v\">backtest report · v").append(VERSION).append("</span>")
                .append("</div></div>")
                .append("<div class=\"top\"><div>")
                .append("<h1>Backtest report</h1>")
                .append("<div class=\"sub\">Strategy <b>")
                .append(esc(data.strategy().featureName())).append("</b> · Symbol <b>")
                .append(esc(data.symbol())).append("</b> · Window <b>")
                .append(esc(windowFrom)).append(" → ").append(esc(windowTo))
                .append("</b> · Bars <b>").append(esc(tfWire)).append(esc(multiTf))
                .append("</b></div></div>")
                .append("<div class=\"meta\"><div>wichtelm-app ").append(VERSION).append("</div>")
                .append("<div class=\"gen\">Generated ").append(esc(generated)).append("</div>")
                .append("</div></div>")
                .append("<div class=\"disclaim\">NOT financial advice · Past performance is not "
                        + "indicative of future results · Use at your own risk</div>")
                .append("</header>");
    }

    private static String describeHigherTfs(ReportData data) {
        List<String> wires = new ArrayList<>(data.higherTimeframeSeries().keySet());
        return String.join(" + ", wires);
    }

    // ─── Aggregate metrics ───────────────────────────────────────────────────

    private void appendAggregateMetrics(StringBuilder html, BacktestMetrics m) {
        html.append("<div class=\"section-title\"><h2>Aggregate metrics</h2>"
                + "<div class=\"rule\"></div></div>");
        html.append("<div class=\"metrics\">");
        metricCard(html, "Total return", formatSignedPercent(m.totalReturn()),
                signOfReturn(m.totalReturn()), "");
        metricCard(html, "Trades", Integer.toString(m.numTrades()), "", "");
        metricCard(html, "Win rate", formatPercent(m.winRate()), "",
                m.numTrades() == 0 ? "no trades"
                        : winsAndLosses(m));
        metricCard(html, "Max drawdown", formatSignedPercent(m.maxDrawdown().negate()),
                m.maxDrawdown().signum() > 0 ? "neg" : "", "");
        metricCard(html, "Sharpe", formatRatio(m.sharpeRatio()), "", "annualised");
        metricCard(html, "Sortino", formatRatio(m.sortinoRatio()), "", "downside-only σ");
        metricCard(html, "Calmar", formatRatio(m.calmarRatio()), "", "return / max DD");
        metricCard(html, "Profit factor",
                m.numTrades() == 0 ? "—" : formatRatio(m.profitFactor()), "",
                m.numTrades() == 0 ? "no closed trades" : "gross win / loss");
        metricCard(html, "Avg win", formatSignedAmount(m.avgWin()), "pos", "per winning trade");
        metricCard(html, "Avg loss", formatSignedAmount(m.avgLoss()), "neg", "per losing trade");
        html.append("</div>");
    }

    private static String winsAndLosses(BacktestMetrics m) {
        int wins = (int) Math.round(m.winRate().doubleValue() * m.numTrades());
        int losses = m.numTrades() - wins;
        return wins + " wins · " + losses + " losses";
    }

    private static String signOfReturn(BigDecimal value) {
        if (value.signum() > 0) {
            return "pos";
        }
        if (value.signum() < 0) {
            return "neg";
        }
        return "";
    }

    private void metricCard(StringBuilder html, String label, String value, String valueClass,
                            String delta) {
        html.append("<div class=\"metric\"><div class=\"label\">").append(esc(label))
                .append("</div><div class=\"value");
        if (!valueClass.isEmpty()) {
            html.append(' ').append(valueClass);
        }
        html.append("\">").append(esc(value)).append("</div>");
        if (!delta.isEmpty()) {
            html.append("<div class=\"delta\">").append(esc(delta)).append("</div>");
        }
        html.append("</div>");
    }

    // ─── Equity & drawdown ───────────────────────────────────────────────────

    private void appendEquityAndDrawdown(StringBuilder html, ReportData data) {
        List<EquityPoint> curve = data.result().equityCurve();
        String windowLabel = "";
        if (!curve.isEmpty()) {
            windowLabel = curve.getFirst().time().atOffset(ZoneOffset.UTC)
                    .toLocalDate() + " → "
                    + curve.getLast().time().atOffset(ZoneOffset.UTC).toLocalDate();
        }
        html.append("<div class=\"section-title\"><h2>Equity curve &amp; drawdown</h2>"
                + "<div class=\"rule\"></div><span class=\"count\">")
                .append(esc(windowLabel)).append("</span></div>");
        html.append("<div class=\"equity\">");
        html.append("<div class=\"panel\"><div class=\"ph-head\">"
                + "<span class=\"t\">Equity curve</span>"
                + "<span class=\"tf\">indexed · base 100.0</span></div>"
                + "<div class=\"ph-body\">").append(equityCurveSvg(curve))
                .append("</div></div>");
        html.append("<div class=\"panel\"><div class=\"ph-head\">"
                + "<span class=\"t\">Drawdown</span>"
                + "<span class=\"tf\">% from peak</span></div>"
                + "<div class=\"ph-body\">").append(drawdownCurveSvg(curve))
                .append("</div></div>");
        html.append("</div>");
    }

    // ─── Trade list (chronological) ──────────────────────────────────────────

    private record TriggerHit(String scenarioName, Instant triggerTime) {
    }

    private void appendTradeList(StringBuilder html, ReportData data, ChartRenderer renderer) {
        List<Trade> trades = new ArrayList<>(data.result().trades());
        trades.sort(Comparator.comparing(Trade::entryTime));
        int totalEntries = trades.size()
                + (data.result().openPositionAtEnd().isPresent() ? 1 : 0);
        int width = Math.max(2, Integer.toString(totalEntries).length());

        html.append("<div class=\"section-title\"><h2>Trade-by-trade breakdown</h2>"
                + "<div class=\"rule\"></div><span class=\"count\">")
                .append(totalEntries).append(" trades · chronological</span></div>");
        html.append("<div class=\"triggers\">");

        Map<Instant, TriggerHit> triggerByFillTime = buildTriggerByFillTime(data);
        Map<String, StrategyScenario> scenarioByName = new HashMap<>();
        for (StrategyScenario s : data.strategy().scenarios()) {
            scenarioByName.put(s.name(), s);
        }

        int ordinal = 0;
        for (Trade trade : trades) {
            ordinal++;
            TriggerHit entry = triggerByFillTime.get(trade.entryTime());
            TriggerHit exit = triggerByFillTime.get(trade.exitTime());
            appendClosedTrade(html, data, renderer, ordinal, width, trade, entry, exit,
                    scenarioByName);
        }
        data.result().openPositionAtEnd().ifPresent(open -> {
            int n = trades.size() + 1;
            TriggerHit entry = triggerByFillTime.get(open.entryTime());
            appendOpenTrade(html, data, renderer, n, width, open, entry, scenarioByName);
        });

        html.append("</div>");
    }

    /**
     * Reverse-index of trade fill times → trigger hit. Signals fire at bar T
     * and the runtime fills at the next available primary bar's open; walk the
     * primary series to find the actual fill bar so datasets with overnight /
     * weekend gaps attribute trades correctly.
     */
    private Map<Instant, TriggerHit> buildTriggerByFillTime(ReportData data) {
        List<OHLCBar> bars = data.primarySeries().bars();
        Map<Instant, TriggerHit> result = new HashMap<>();
        data.triggersByScenario().forEach((name, times) -> {
            for (Instant t : times) {
                Instant fillTime = nextBarOpenAfter(bars, t);
                if (fillTime != null) {
                    result.putIfAbsent(fillTime, new TriggerHit(name, t));
                }
            }
        });
        return result;
    }

    private static Instant nextBarOpenAfter(List<OHLCBar> bars, Instant t) {
        for (OHLCBar bar : bars) {
            if (bar.time().isAfter(t)) {
                return bar.time();
            }
        }
        return null;
    }

    // ─── Closed trade <details> ──────────────────────────────────────────────

    private void appendClosedTrade(StringBuilder html, ReportData data, ChartRenderer renderer,
                                    int ordinal, int width, Trade trade,
                                    TriggerHit entryHit, TriggerHit exitHit,
                                    Map<String, StrategyScenario> scenarioByName) {
        String idx = "#" + String.format(Locale.ROOT, "%0" + width + "d", ordinal);
        String dirClass = trade.direction().toString().equalsIgnoreCase("LONG") ? "long" : "short";
        Duration held = Duration.between(trade.entryTime(), trade.exitTime());
        long holdHours = Math.max(1L, held.toHours());
        long holdDays = Math.max(1L, (holdHours + 23L) / 24L);
        int holdBars = countBarsBetween(data.primarySeries().bars(),
                trade.entryTime(), trade.exitTime());

        BigDecimal pnlPct = trade.pnlPercent();
        boolean isWin = pnlPct.signum() >= 0;
        BigDecimal priceMove = trade.exitPrice().subtract(trade.entryPrice(), DECIMAL)
                .divide(trade.entryPrice(), DECIMAL).multiply(HUNDRED, DECIMAL);
        BigDecimal[] mfeMae = computeMfeMae(data.primarySeries().bars(),
                trade.entryTime(), trade.exitTime(), trade.entryPrice(),
                trade.direction().toString());

        html.append("<details class=\"trigger\"><summary><div class=\"sum-row\">")
                .append("<span class=\"idx\">").append(esc(idx)).append("</span>")
                .append("<span class=\"ttype ").append(dirClass).append("\"><span class=\"dot\"></span>")
                .append(esc(trade.direction().toString().toLowerCase(Locale.ENGLISH))).append("</span>")
                .append("<span class=\"ttime\">")
                .append("<span class=\"range\">").append(esc(formatIsoMinute(trade.entryTime())))
                .append(" → ").append(esc(formatIsoMinute(trade.exitTime()))).append("</span>")
                .append("<span class=\"duration\">").append(holdDays).append(" sessions · ")
                .append(holdHours).append("h in position</span></span>")
                .append("<span class=\"px\"><b>").append(formatPrice(trade.entryPrice()))
                .append("</b> → <b>").append(formatPrice(trade.exitPrice())).append("</b></span>")
                .append("<span class=\"pnl ").append(isWin ? "pos" : "neg").append("\">")
                .append(formatSignedPercent(pnlPct))
                .append("<span class=\"pct\">price ").append(formatSignedPercentRaw(priceMove))
                .append("</span></span>")
                .append("<span class=\"chev\"><svg width=\"12\" height=\"12\" viewBox=\"0 0 12 12\""
                        + " fill=\"none\"><path d=\"M2.5 4.5L6 8L9.5 4.5\" stroke=\"currentColor\""
                        + " stroke-width=\"1.5\" stroke-linecap=\"round\" stroke-linejoin=\"round\"/>"
                        + "</svg></span>")
                .append("</div>");

        appendConditionsRow(html, entryHit, exitHit, scenarioByName, "closed");
        html.append("</summary>");

        // ─── Expanded body ─────────────────────────────────────────────────
        html.append("<div class=\"body\"><div class=\"stats\">")
                .append(statCell("Entry", formatPrice(trade.entryPrice()), ""))
                .append(statCell("Exit", formatPrice(trade.exitPrice()), ""))
                .append(statCell("Hold", holdBars + " × " + data.strategy().primaryTimeframe().wire()
                        + " bars", ""))
                .append(statCell("P/L", formatSignedPercent(pnlPct), isWin ? "pos" : "neg"))
                .append(statCell("MFE", formatSignedPercentRaw(mfeMae[0]), "pos"))
                .append(statCell("MAE", formatSignedPercentRaw(mfeMae[1]), "neg"))
                .append("</div>");

        appendScenarioRow(html, entryHit, exitHit, "closed");
        appendChartFrames(html, data, renderer, trade.entryTime(), trade.exitTime(),
                entryHit, exitHit, scenarioByName, false, dirClass);

        html.append("</div></details>");
    }

    // ─── Open trade <details> ───────────────────────────────────────────────

    private void appendOpenTrade(StringBuilder html, ReportData data, ChartRenderer renderer,
                                  int ordinal, int width, Position open,
                                  TriggerHit entryHit,
                                  Map<String, StrategyScenario> scenarioByName) {
        String idx = "#" + String.format(Locale.ROOT, "%0" + width + "d", ordinal);
        String dirClass = open.direction().toString().equalsIgnoreCase("LONG") ? "long" : "short";
        List<OHLCBar> bars = data.primarySeries().bars();
        Instant windowEnd = bars.isEmpty() ? open.entryTime() : bars.getLast().time();
        Duration held = Duration.between(open.entryTime(), windowEnd);
        long holdHours = Math.max(1L, held.toHours());
        long holdDays = Math.max(1L, (holdHours + 23L) / 24L);
        // countBarsBetween is exclusive on the upper bound (so closed-trade
        // hold counts exclude the exit-fill bar). The open position is still
        // held at the last bar, so bump the bound by 1ns to keep it counted.
        int holdBars = countBarsBetween(bars, open.entryTime(), windowEnd.plusNanos(1));

        BigDecimal lastClose = bars.isEmpty() ? open.entryPrice() : bars.getLast().close();
        BigDecimal markPct = lastClose.subtract(open.entryPrice(), DECIMAL)
                .divide(open.entryPrice(), DECIMAL).multiply(HUNDRED, DECIMAL);
        if (open.direction().toString().equalsIgnoreCase("SHORT")) {
            markPct = markPct.negate();
        }
        BigDecimal[] mfeMae = computeMfeMae(bars, open.entryTime(), windowEnd,
                open.entryPrice(), open.direction().toString());

        html.append("<details class=\"trigger\"><summary><div class=\"sum-row\">")
                .append("<span class=\"idx\">").append(esc(idx)).append("</span>")
                .append("<span class=\"ttype ").append(dirClass).append("\"><span class=\"dot\"></span>")
                .append(esc(open.direction().toString().toLowerCase(Locale.ENGLISH))).append("</span>")
                .append("<span class=\"ttime\">")
                .append("<span class=\"range\">").append(esc(formatIsoMinute(open.entryTime())))
                .append(" → <span style=\"color:var(--ink-faint)\">window end</span></span>")
                .append("<span class=\"duration\">").append(holdDays).append(" sessions · ")
                .append(holdHours).append("h open at window end</span></span>")
                .append("<span class=\"px\"><b>").append(formatPrice(open.entryPrice()))
                .append("</b> → <b>").append(formatPrice(lastClose)).append("</b></span>")
                .append("<span class=\"pnl ")
                .append(markPct.signum() >= 0 ? "pos" : "neg").append("\">")
                .append(formatSignedPercentRaw(markPct))
                .append("<span class=\"open-tag\">still open</span></span>")
                .append("<span class=\"chev\"><svg width=\"12\" height=\"12\" viewBox=\"0 0 12 12\""
                        + " fill=\"none\"><path d=\"M2.5 4.5L6 8L9.5 4.5\" stroke=\"currentColor\""
                        + " stroke-width=\"1.5\" stroke-linecap=\"round\" stroke-linejoin=\"round\"/>"
                        + "</svg></span>")
                .append("</div>");

        appendConditionsRow(html, entryHit, null, scenarioByName, "open");
        html.append("</summary>");

        html.append("<div class=\"body\"><div class=\"stats\">")
                .append(statCell("Entry", formatPrice(open.entryPrice()), ""))
                .append(statCell("Mark", formatPrice(lastClose), ""))
                .append(statCell("Hold", holdBars + " × " + data.strategy().primaryTimeframe().wire()
                        + " bars (open)", ""))
                .append(statCell("Mark P/L", formatSignedPercentRaw(markPct),
                        markPct.signum() >= 0 ? "pos" : "neg"))
                .append(statCell("MFE", formatSignedPercentRaw(mfeMae[0]), "pos"))
                .append(statCell("MAE", formatSignedPercentRaw(mfeMae[1]), "neg"))
                .append("</div>");

        appendScenarioRow(html, entryHit, null, "open");
        appendChartFrames(html, data, renderer, open.entryTime(), windowEnd,
                entryHit, null, scenarioByName, true, dirClass);

        html.append("</div></details>");
    }

    private String statCell(String label, String value, String valueClass) {
        return "<div class=\"s\"><div class=\"l\">" + esc(label) + "</div><div class=\"v"
                + (valueClass.isEmpty() ? "" : " " + valueClass) + "\">"
                + esc(value) + "</div></div>";
    }

    // ─── Conditions row & scenario header ────────────────────────────────────

    private void appendConditionsRow(StringBuilder html, TriggerHit entryHit, TriggerHit exitHit,
                                      Map<String, StrategyScenario> scenarioByName, String state) {
        html.append("<div class=\"cond\">");
        if (entryHit != null) {
            StrategyScenario s = scenarioByName.get(entryHit.scenarioName());
            if (s != null) {
                html.append("<span class=\"lbl\">entry</span>")
                        .append(formatConditionTerms(s.conditionSteps()));
            } else {
                html.append("<span class=\"lbl\">entry</span><span class=\"term\">—</span>");
            }
        } else {
            html.append("<span class=\"lbl\">entry</span><span class=\"term\">—</span>");
        }
        if (state.equals("open")) {
            html.append(" <span class=\"ar\">→</span> <span class=\"lbl\">exit</span>")
                    .append("<span class=\"term\">still open at window end</span>");
        } else if (exitHit != null) {
            StrategyScenario s = scenarioByName.get(exitHit.scenarioName());
            if (s != null) {
                html.append(" <span class=\"ar\">→</span> <span class=\"lbl\">exit</span>")
                        .append(formatConditionTerms(s.conditionSteps()));
            } else {
                html.append(" <span class=\"ar\">→</span> <span class=\"lbl\">exit</span>")
                        .append("<span class=\"term\">—</span>");
            }
        } else {
            html.append(" <span class=\"ar\">→</span> <span class=\"lbl\">exit</span>")
                    .append("<span class=\"term\">stop_loss / take_profit</span>");
        }
        html.append("</div>");
    }

    private String formatConditionTerms(List<StrategyStep> steps) {
        StringBuilder out = new StringBuilder();
        boolean first = true;
        for (StrategyStep step : steps) {
            if (!first) {
                out.append(" · ");
            }
            out.append("<span class=\"term\">").append(esc(step.text())).append("</span>")
                    .append(" <span class=\"ok\">✓</span>");
            first = false;
        }
        return out.toString();
    }

    private void appendScenarioRow(StringBuilder html, TriggerHit entryHit, TriggerHit exitHit,
                                    String state) {
        html.append("<div class=\"scenario\">")
                .append("<div class=\"seg\"><span class=\"lbl\">entry</span><span class=\"nm\">")
                .append(esc(entryHit != null ? entryHit.scenarioName() : "—"))
                .append("</span></div>");
        String exitName;
        String exitNameClass = "nm";
        if (state.equals("open")) {
            exitName = "still open";
            exitNameClass = "nm code";
        } else if (exitHit != null) {
            exitName = exitHit.scenarioName();
        } else {
            exitName = "stop_loss / take_profit";
            exitNameClass = "nm code";
        }
        html.append("<div class=\"seg\"><span class=\"lbl\">exit</span><span class=\"")
                .append(exitNameClass).append("\">").append(esc(exitName))
                .append("</span></div></div>");
    }

    // ─── Chart frames ────────────────────────────────────────────────────────

    private void appendChartFrames(StringBuilder html, ReportData data, ChartRenderer renderer,
                                    Instant tradeEntry, Instant tradeExit,
                                    TriggerHit entryHit, TriggerHit exitHit,
                                    Map<String, StrategyScenario> scenarioByName,
                                    boolean openTrade, String dirClass) {
        html.append("<div class=\"charts\">");

        Set<String> timeframes = collectScenarioTimeframes(entryHit, exitHit, scenarioByName, data);
        String primaryTfWire = data.strategy().primaryTimeframe().wire();
        for (String tf : timeframes) {
            boolean isPrimary = tf.equals(primaryTfWire);
            OHLCSeries fullSeries = isPrimary ? data.primarySeries()
                    : data.higherTimeframeSeries().get(tf);
            if (fullSeries == null) {
                throw new ReportGenerationException("no series supplied for timeframe " + tf);
            }
            int period = maxIndicatorPeriodForTimeframe(entryHit, exitHit, scenarioByName, tf, data);
            int before = Math.max(30, (int) Math.ceil(period * 1.5));
            int after = 10;
            Timeframe timeframe = isPrimary ? data.strategy().primaryTimeframe()
                    : Timeframe.fromWire(tf);
            OHLCSeries window = sliceTradeWindow(fullSeries, timeframe, isPrimary,
                    tradeEntry, tradeExit, before, after);
            if (window.bars().isEmpty()) {
                continue;
            }
            Instant entryMarker = snapToBar(window.bars(), tradeEntry);
            Instant exitMarker = openTrade ? null : snapToBar(window.bars(), tradeExit);
            html.append(renderChartFrame(renderer, window, tf, isPrimary, entryMarker,
                    exitMarker, openTrade, dirClass, data, scenarioByName, entryHit, exitHit,
                    tradeEntry, tradeExit));
        }

        html.append("</div>");
    }

    private Set<String> collectScenarioTimeframes(TriggerHit entryHit, TriggerHit exitHit,
                                                  Map<String, StrategyScenario> scenarioByName,
                                                  ReportData data) {
        Set<String> timeframes = new LinkedHashSet<>();
        timeframes.add(data.strategy().primaryTimeframe().wire());
        for (TriggerHit hit : List.of(
                entryHit != null ? entryHit : new TriggerHit("", Instant.MIN),
                exitHit != null ? exitHit : new TriggerHit("", Instant.MIN))) {
            if (hit.scenarioName().isEmpty()) {
                continue;
            }
            StrategyScenario s = scenarioByName.get(hit.scenarioName());
            if (s == null) {
                continue;
            }
            timeframes.addAll(timeframesFor(data, s));
        }
        return timeframes;
    }

    private int maxIndicatorPeriodForTimeframe(TriggerHit entryHit, TriggerHit exitHit,
                                                Map<String, StrategyScenario> scenarioByName,
                                                String timeframe, ReportData data) {
        int max = 0;
        for (TriggerHit hit : List.of(
                entryHit != null ? entryHit : new TriggerHit("", Instant.MIN),
                exitHit != null ? exitHit : new TriggerHit("", Instant.MIN))) {
            if (hit.scenarioName().isEmpty()) {
                continue;
            }
            StrategyScenario s = scenarioByName.get(hit.scenarioName());
            if (s != null) {
                max = Math.max(max, maxIndicatorPeriodForScenario(s, timeframe, data));
            }
        }
        return max;
    }

    private int maxIndicatorPeriodForScenario(StrategyScenario scenario, String timeframe,
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
     * Sub-series of {@code full} covering the full trade window plus the
     * indicator-lookback margin before and a fixed 10 bars after. For higher
     * timeframes, the window endpoints are snapped to the nearest CLOSED bar
     * so the local chart only shows data the runtime could see.
     */
    private OHLCSeries sliceTradeWindow(OHLCSeries full, Timeframe tf, boolean isPrimary,
                                         Instant tradeEntry, Instant tradeExit,
                                         int beforeBars, int afterBars) {
        List<OHLCBar> bars = full.bars();
        if (bars.isEmpty()) {
            return new OHLCSeries(List.of());
        }
        int entryIdx = locateBar(bars, tf, isPrimary, tradeEntry);
        int exitIdx = locateBar(bars, tf, isPrimary, tradeExit);
        if (entryIdx < 0) {
            entryIdx = 0;
        }
        if (exitIdx < 0) {
            exitIdx = bars.size() - 1;
        }
        int from = Math.max(0, entryIdx - beforeBars);
        int to = Math.min(bars.size(), exitIdx + afterBars + 1);
        if (from >= to) {
            return new OHLCSeries(List.of());
        }
        return new OHLCSeries(bars.subList(from, to));
    }

    private int locateBar(List<OHLCBar> bars, Timeframe tf, boolean isPrimary, Instant t) {
        if (isPrimary) {
            int idx = -1;
            for (int i = 0; i < bars.size(); i++) {
                if (!bars.get(i).time().isAfter(t)) {
                    idx = i;
                } else {
                    break;
                }
            }
            return idx;
        }
        int idx = -1;
        for (int i = 0; i < bars.size(); i++) {
            Instant closeTime = i + 1 < bars.size()
                    ? bars.get(i + 1).time()
                    : net.jacopobiscella.wichtelm.strategy.Timeframes.advance(bars.get(i).time(), tf);
            if (!closeTime.isAfter(t)) {
                idx = i;
            } else {
                break;
            }
        }
        return idx;
    }

    private static Instant snapToBar(List<OHLCBar> bars, Instant t) {
        Instant snapped = bars.getFirst().time();
        for (OHLCBar b : bars) {
            if (!b.time().isAfter(t)) {
                snapped = b.time();
            } else {
                break;
            }
        }
        return snapped;
    }

    private String renderChartFrame(ChartRenderer renderer, OHLCSeries window, String timeframe,
                                     boolean isPrimary, Instant entryMarker, Instant exitMarker,
                                     boolean openTrade, String dirClass, ReportData data,
                                     Map<String, StrategyScenario> scenarioByName,
                                     TriggerHit entryHit, TriggerHit exitHit,
                                     Instant tradeEntry, Instant tradeExit) {
        String title = isPrimary ? "Price · primary" : "Background · higher-TF";
        String indicatorLabel = describeChartIndicators(timeframe, window.bars().size(), data);
        String tag = isPrimary ? "trade window + ctx" : "multi-TF context";
        String img = renderHeerwischImage(renderer, window, timeframe, entryMarker, exitMarker, data);
        Duration heldInWindow = Duration.between(tradeEntry, tradeExit);
        long heldHours = Math.max(1L, heldInWindow.toHours());

        StringBuilder out = new StringBuilder();
        out.append("<div class=\"chart\"><div class=\"ch-head\">")
                .append("<span class=\"t\">").append(esc(title));
        if (!indicatorLabel.isEmpty()) {
            out.append(" <span class=\"ind\">").append(esc(indicatorLabel)).append("</span>");
        }
        out.append("</span><span class=\"tf\">").append(esc(timeframe))
                .append("<span class=\"tag\">").append(esc(tag)).append("</span></span></div>")
                .append("<div class=\"ph-body\">").append(img);
        // RSI sub-pane: render on whichever timeframe the strategy declared
        // it on. Use the FULL series for that timeframe so RSI values are
        // computed with proper warmup, then clip to the chart window.
        OHLCSeries fullForTf = isPrimary
                ? data.primarySeries()
                : data.higherTimeframeSeries().get(timeframe);
        if (fullForTf != null) {
            String rsi = renderRsiSubpaneIfDeclared(timeframe, fullForTf,
                    window.bars().getFirst().time(), window.bars().getLast().time(),
                    entryMarker, exitMarker, data);
            if (!rsi.isEmpty()) {
                out.append(rsi);
            }
        }
        out.append("</div>");

        // Footer annotations
        out.append("<div class=\"ch-foot\">");
        if (isPrimary) {
            String entryTri = dirClass.equals("long") ? "up" : "dn";
            String exitTri = dirClass.equals("long") ? "dn" : "up";
            out.append("<span class=\"mk\"><span class=\"tri ").append(entryTri).append("\"></span>")
                    .append("entry ").append(esc(formatIsoMinute(tradeEntry))).append("</span>");
            if (openTrade) {
                out.append("<span>in position · ").append(heldHours).append("h (open)</span>")
                        .append("<span>mark ").append(esc(formatIsoMinute(tradeExit)))
                        .append(" · window end</span>");
            } else {
                out.append("<span>in position · ").append(heldHours).append("h</span>")
                        .append("<span class=\"mk\">exit ").append(esc(formatIsoMinute(tradeExit)))
                        .append("<span class=\"tri ").append(exitTri).append("\"></span></span>");
            }
        } else {
            String trendDir = dirClass.equals("long") ? "uptrend" : "downtrend";
            out.append("<span>").append(esc(window.bars().getFirst().time().atOffset(ZoneOffset.UTC)
                    .toLocalDate().toString())).append(" → ")
                    .append(esc(window.bars().getLast().time().atOffset(ZoneOffset.UTC)
                            .toLocalDate().toString())).append("</span>");
            out.append("<span>").append(trendDir).append(" filter satisfied at entry");
            if (openTrade) {
                out.append("; trade still open");
            }
            out.append("</span>");
        }
        out.append("</div></div>");
        return out.toString();
    }

    private String renderHeerwischImage(ChartRenderer renderer, OHLCSeries series,
                                         String timeframeLabel, Instant entryMarker,
                                         Instant exitMarker, ReportData data) {
        try {
            LayoutSpec layout = LayoutSpec.builder().withSize(900, 320).build();
            ChartSpecBuilder builder = ChartSpec.builder().withSeries(series).withLayout(layout);
            addIndicatorsForTimeframe(builder, timeframeLabel, series, data);
            TreeMap<Instant, BigDecimal> closeByTime = new TreeMap<>();
            for (OHLCBar bar : series.bars()) {
                closeByTime.put(bar.time(), bar.close());
            }
            Map.Entry<Instant, BigDecimal> entryBar = closeByTime.floorEntry(entryMarker);
            if (entryBar != null) {
                builder.addAnnotation(new Annotation.BarHighlight(
                        entryBar.getKey(), entryBar.getValue(), "entry"));
            }
            if (exitMarker != null) {
                Map.Entry<Instant, BigDecimal> exitBar = closeByTime.floorEntry(exitMarker);
                if (exitBar != null) {
                    builder.addAnnotation(new Annotation.BarHighlight(
                            exitBar.getKey(), exitBar.getValue(), "exit"));
                }
            }
            ChartImage image = renderer.render(builder.build());
            String base64 = Base64.getEncoder().encodeToString(image.bytes());
            return "<img alt=\"" + esc(timeframeLabel) + " price chart\" src=\"data:"
                    + esc(image.contentType()) + ";base64," + base64 + "\"/>";
        } catch (ChartRenderException e) {
            throw new ReportGenerationException(
                    "chart rendering failed for timeframe " + timeframeLabel, e);
        }
    }

    private String describeChartIndicators(String timeframe, int barCount, ReportData data) {
        List<String> parts = new ArrayList<>();
        parts.add("HA candles");
        Set<String> seen = new HashSet<>();
        String primaryTf = data.strategy().primaryTimeframe().wire();
        for (BackgroundSeries bg : data.strategy().backgroundSeries()) {
            String tf = bg.timeframe().map(Timeframe::wire).orElse(primaryTf);
            if (!tf.equals(timeframe)) {
                continue;
            }
            Indicator ind = toIndicator(bg.expression(), data.parameters());
            if (ind == null || barCount < ind.minBars()) {
                continue;
            }
            if (ind instanceof Indicator.RSI rsi
                    && data.primarySeries().bars().size() > rsi.period()
                    && timeframe.equals(primaryTf)) {
                if (seen.add("RSI:" + rsi.period())) {
                    parts.add("RSI(" + rsi.period() + ") sub-pane");
                }
                continue;
            }
            String desc = describeIndicator(ind);
            if (seen.add(desc)) {
                parts.add(desc);
            }
        }
        return String.join(" · ", parts);
    }

    private String describeIndicator(Indicator ind) {
        return switch (ind) {
            case Indicator.SMA s -> "SMA(" + s.period() + ")";
            case Indicator.EMA e -> "EMA(" + e.period() + ")";
            case Indicator.RSI r -> "RSI(" + r.period() + ")";
            case Indicator.ATR a -> "ATR(" + a.period() + ")";
            case Indicator.BollingerBands b -> "BB(" + b.period() + ")";
            case Indicator.MACD m -> "MACD(" + m.fastPeriod() + "/" + m.slowPeriod() + "/"
                    + m.signalPeriod() + ")";
            case Indicator.ADX a -> "ADX(" + a.period() + ")";
            case Indicator.Stochastic s -> "STOCH(" + s.kPeriod() + ")";
            case Indicator.VolumePane v -> "VOL";
        };
    }

    // ─── Indicator dispatch (unchanged from main) ────────────────────────────

    private void addIndicatorsForTimeframe(ChartSpecBuilder builder, String timeframeLabel,
                                            OHLCSeries underlying, ReportData data) {
        Pane[] subPanes = { Pane.SUBPLOT_1, Pane.SUBPLOT_2, Pane.SUBPLOT_3, Pane.SUBPLOT_4,
                Pane.SUBPLOT_5, Pane.SUBPLOT_6, Pane.SUBPLOT_7, Pane.SUBPLOT_8 };
        int subPaneIdx = 0;
        int bars = underlying.bars().size();
        Set<String> addedKey = new HashSet<>();
        for (BackgroundSeries series : seriesForTimeframe(timeframeLabel, data)) {
            Indicator indicator = toIndicator(series.expression(), data.parameters());
            if (indicator == null || bars < indicator.minBars()) {
                continue;
            }
            // RSI is rendered as a custom SVG sub-pane below the heerwisch
            // image (so we can pin the Y range and draw threshold lines that
            // heerwisch does not emit). Skip it from the heerwisch ChartSpec.
            if (indicator instanceof Indicator.RSI) {
                continue;
            }
            // De-dup identical indicators that come from multiple Background
            // series (e.g. macd_line + macd_signal + macd_histogram all map to
            // the same Indicator.MACD record).
            String key = indicator.toString();
            if (!addedKey.add(key)) {
                continue;
            }
            if (indicator.defaultPane() == Pane.MAIN) {
                builder.addIndicator(indicator);
            } else if (subPaneIdx < subPanes.length) {
                builder.addIndicator(indicator, subPanes[subPaneIdx]);
                subPaneIdx++;
            }
        }
    }

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

    private String renderRsiSubpaneIfDeclared(String timeframeLabel, OHLCSeries fullSeries,
                                               Instant windowStart, Instant windowEnd,
                                               Instant entryMarker, Instant exitMarker,
                                               ReportData data) {
        for (BackgroundSeries bg : seriesForTimeframe(timeframeLabel, data)) {
            Indicator ind = toIndicator(bg.expression(), data.parameters());
            if (ind instanceof Indicator.RSI rsi
                    && fullSeries.bars().size() > rsi.period()) {
                return renderRsiSubpane(fullSeries, rsi.period(), rsi.overbought(),
                        rsi.oversold(), windowStart, windowEnd, entryMarker, exitMarker);
            }
        }
        return "";
    }


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
                case "macd_line", "macd_signal", "macd_histogram" -> new Indicator.MACD(
                        resolveIntArg(args, 0, parameters),
                        resolveIntArg(args, 1, parameters),
                        resolveIntArg(args, 2, parameters),
                        PriceSource.CLOSE);
                case "stddev" -> new Indicator.BollingerBands(
                        resolveIntArg(args, 0, parameters),
                        BigDecimal.valueOf(2),
                        PriceSource.CLOSE);
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
                throw new IllegalArgumentException(
                        "non-integer parameter value for arg '" + token + "': " + fromParam);
            }
        }
        return Integer.parseInt(token);
    }

    private static int indicatorPeriodOf(String expression, Map<String, BigDecimal> parameters) {
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

    // ─── RSI sub-pane SVG (kept from Task C) ─────────────────────────────────

    private String renderRsiSubpane(OHLCSeries fullSeries, int period,
                                     BigDecimal overbought, BigDecimal oversold,
                                     Instant windowStart, Instant windowEnd,
                                     Instant entryMarker, Instant exitMarker) {
        List<OHLCBar> bars = fullSeries.bars();
        List<BigDecimal> closes = new ArrayList<>(bars.size());
        Instant[] times = new Instant[bars.size()];
        for (int i = 0; i < bars.size(); i++) {
            closes.add(bars.get(i).close());
            times[i] = bars.get(i).time();
        }
        BigDecimal[] rsi = Indicators.rsi(closes, period);

        double vbW = 900;
        double vbH = 140;
        double padLeft = 50, padRight = 30, padTop = 22, padBottom = 36;
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
        svg.append("<svg viewBox=\"0 0 ").append((int) vbW).append(' ').append((int) vbH)
                .append("\" preserveAspectRatio=\"xMidYMid meet\" xmlns=\"http://www.w3.org/2000/svg\""
                        + " style=\"display:block;width:100%;height:auto;margin-top:8px\">");
        svg.append("<text x=\"").append(round(padLeft))
                .append("\" y=\"14\" font-family=\"JetBrains Mono,monospace\" font-size=\"10\""
                        + " fill=\"#58564e\">RSI(").append(period).append(")</text>");
        // Danger zones
        svg.append("<rect x=\"").append(round(padLeft)).append("\" y=\"").append(round(padTop))
                .append("\" width=\"").append(round(plotW))
                .append("\" height=\"").append(round(obYpx - padTop))
                .append("\" fill=\"oklch(0.58 0.16 28 / 0.08)\"/>");
        svg.append("<rect x=\"").append(round(padLeft)).append("\" y=\"").append(round(osYpx))
                .append("\" width=\"").append(round(plotW))
                .append("\" height=\"").append(round(padTop + plotH - osYpx))
                .append("\" fill=\"oklch(0.58 0.13 155 / 0.08)\"/>");
        int[] ticks = {0, 30, 50, 70, 100};
        svg.append("<g font-size=\"9\" font-family=\"JetBrains Mono,monospace\" fill=\"#8a8880\">");
        for (int yVal : ticks) {
            double y = padTop + plotH - (yVal / 100.0) * plotH;
            svg.append("<line x1=\"").append(round(padLeft))
                    .append("\" x2=\"").append(round(padLeft + plotW))
                    .append("\" y1=\"").append(round(y))
                    .append("\" y2=\"").append(round(y))
                    .append("\" stroke=\"#e6e4dc\"/>");
            svg.append("<text x=\"").append(round(padLeft - 4))
                    .append("\" y=\"").append(round(y + 3))
                    .append("\" text-anchor=\"end\">").append(yVal).append("</text>");
        }
        svg.append("</g>");
        // Thresholds
        svg.append("<line x1=\"").append(round(padLeft))
                .append("\" x2=\"").append(round(padLeft + plotW))
                .append("\" y1=\"").append(round(obYpx))
                .append("\" y2=\"").append(round(obYpx))
                .append("\" stroke=\"#d6d3c7\" stroke-dasharray=\"3 3\" stroke-width=\"1\"/>");
        svg.append("<line x1=\"").append(round(padLeft))
                .append("\" x2=\"").append(round(padLeft + plotW))
                .append("\" y1=\"").append(round(osYpx))
                .append("\" y2=\"").append(round(osYpx))
                .append("\" stroke=\"#d6d3c7\" stroke-dasharray=\"3 3\" stroke-width=\"1\"/>");
        // X axis adaptive
        DateTimeFormatter xFmt = adaptiveTickFormatter(tSpan);
        svg.append("<g font-size=\"9\" font-family=\"JetBrains Mono,monospace\" fill=\"#8a8880\">");
        int tickCount = 5;
        for (int k = 0; k <= tickCount; k++) {
            long tickT = t0 + (long) ((double) tSpan * k / tickCount);
            double px = padLeft + (double) (tickT - t0) / tSpan * plotW;
            svg.append("<line x1=\"").append(round(px))
                    .append("\" x2=\"").append(round(px))
                    .append("\" y1=\"").append(round(padTop + plotH))
                    .append("\" y2=\"").append(round(padTop + plotH + 4))
                    .append("\" stroke=\"#8a8880\"/>");
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
        // Plot border
        svg.append("<rect x=\"").append(round(padLeft))
                .append("\" y=\"").append(round(padTop))
                .append("\" width=\"").append(round(plotW))
                .append("\" height=\"").append(round(plotH))
                .append("\" fill=\"none\" stroke=\"#d6d3c7\"/>");
        // Entry / exit reference lines
        appendRsiMarker(svg, entryMarker, t0, t1, tSpan, padLeft, plotW, padTop, plotH,
                "oklch(0.58 0.13 155)");
        if (exitMarker != null) {
            appendRsiMarker(svg, exitMarker, t0, t1, tSpan, padLeft, plotW, padTop, plotH,
                    "oklch(0.58 0.16 28)");
        }
        // RSI line
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
        svg.append("<path fill=\"none\" stroke=\"oklch(0.55 0.13 280)\" stroke-width=\"1.3\" d=\"")
                .append(path.toString().strip()).append("\"/>");
        svg.append("</svg>");
        return svg.toString();
    }

    private static void appendRsiMarker(StringBuilder svg, Instant marker, long t0, long t1,
                                         long tSpan, double padLeft, double plotW,
                                         double padTop, double plotH, String color) {
        if (marker == null) {
            return;
        }
        long mt = marker.toEpochMilli();
        if (mt < t0 || mt > t1) {
            return;
        }
        double px = padLeft + (double) (mt - t0) / tSpan * plotW;
        svg.append("<line x1=\"").append(round(px))
                .append("\" x2=\"").append(round(px))
                .append("\" y1=\"").append(round(padTop))
                .append("\" y2=\"").append(round(padTop + plotH))
                .append("\" stroke=\"").append(color)
                .append("\" stroke-width=\"1.2\" stroke-dasharray=\"2 3\"/>");
    }

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

    // ─── Equity & drawdown SVG (kept from Task B, palette-aligned) ───────────

    private String equityCurveSvg(List<EquityPoint> curve) {
        if (curve.isEmpty()) {
            return "<p class=\"equity empty\">no data</p>";
        }
        BigDecimal initial = curve.getFirst().equity();
        Instant[] times = new Instant[curve.size()];
        double[] vals = new double[curve.size()];
        for (int i = 0; i < curve.size(); i++) {
            times[i] = curve.get(i).time();
            vals[i] = curve.get(i).equity().divide(initial, DECIMAL).doubleValue() * 100.0;
        }
        return renderTimeSeries(times, vals, true);
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
        return renderTimeSeries(times, vals, false);
    }

    private String renderTimeSeries(Instant[] times, double[] vals, boolean isEquity) {
        double vbW = 880;
        double vbH = isEquity ? 220 : 160;
        double padLeft = 50, padRight = 24, padTop = 12, padBottom = 38;
        double plotW = vbW - padLeft - padRight;
        double plotH = vbH - padTop - padBottom;
        double minV = Double.POSITIVE_INFINITY;
        double maxV = Double.NEGATIVE_INFINITY;
        for (double v : vals) {
            minV = Math.min(minV, v);
            maxV = Math.max(maxV, v);
        }
        double yMin, yMax;
        if (isEquity) {
            yMin = Math.min(100.0, Math.floor(minV / 5.0) * 5.0);
            yMax = Math.max(100.0, Math.ceil(maxV / 5.0) * 5.0);
        } else {
            yMin = Math.floor(minV / 5.0) * 5.0;
            yMax = Math.max(0.0, Math.ceil(maxV / 5.0) * 5.0);
        }
        if (yMin == yMax) {
            yMin -= 5;
            yMax += 5;
        }
        double ySpan = yMax - yMin;
        long t0 = times[0].toEpochMilli();
        long t1 = times[times.length - 1].toEpochMilli();
        long tSpan = Math.max(t1 - t0, 1L);

        StringBuilder svg = new StringBuilder();
        svg.append("<svg viewBox=\"0 0 ").append((int) vbW).append(' ').append((int) vbH)
                .append("\" preserveAspectRatio=\"xMidYMid meet\" xmlns=\"http://www.w3.org/2000/svg\">");
        // Horizontal grid lines every 5%
        svg.append("<g stroke=\"#e6e4dc\" stroke-width=\"0.7\">");
        for (double y = yMin; y <= yMax + 0.001; y += 5.0) {
            double py = padTop + plotH - (y - yMin) / ySpan * plotH;
            svg.append("<line x1=\"").append(round(padLeft))
                    .append("\" x2=\"").append(round(padLeft + plotW))
                    .append("\" y1=\"").append(round(py))
                    .append("\" y2=\"").append(round(py)).append("\"/>");
        }
        svg.append("</g>");
        // Y tick labels
        svg.append("<g font-size=\"9\" font-family=\"JetBrains Mono,monospace\" fill=\"#8a8880\">");
        for (double y = yMin; y <= yMax + 0.001; y += 5.0) {
            double py = padTop + plotH - (y - yMin) / ySpan * plotH;
            svg.append("<text x=\"").append(round(padLeft - 6))
                    .append("\" y=\"").append(round(py + 3))
                    .append("\" text-anchor=\"end\">").append(formatPercentTick(y))
                    .append("</text>");
        }
        svg.append("</g>");
        // Monthly X ticks
        YearMonth startYm = YearMonth.from(times[0].atOffset(ZoneOffset.UTC).toLocalDate());
        YearMonth endYm = YearMonth.from(times[times.length - 1].atOffset(ZoneOffset.UTC).toLocalDate());
        DateTimeFormatter monthFmt = DateTimeFormatter.ofPattern("MMM", Locale.ENGLISH);
        svg.append("<g font-size=\"9\" font-family=\"JetBrains Mono,monospace\" fill=\"#8a8880\">");
        YearMonth ym = startYm;
        while (!ym.isAfter(endYm)) {
            Instant tickInstant = ym.atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant();
            long tickT = tickInstant.toEpochMilli();
            if (tickT >= t0 && tickT <= t1) {
                double px = padLeft + (double) (tickT - t0) / tSpan * plotW;
                svg.append("<line x1=\"").append(round(px)).append("\" x2=\"")
                        .append(round(px)).append("\" y1=\"").append(round(padTop + plotH))
                        .append("\" y2=\"").append(round(padTop + plotH + 3))
                        .append("\" stroke=\"#8a8880\"/>");
                svg.append("<text x=\"").append(round(px))
                        .append("\" y=\"").append(round(padTop + plotH + 14))
                        .append("\" text-anchor=\"middle\">").append(ym.format(monthFmt))
                        .append("</text>");
            }
            ym = ym.plusMonths(1);
        }
        svg.append("</g>");
        // Reference / fill
        if (isEquity) {
            double py100 = padTop + plotH - (100.0 - yMin) / ySpan * plotH;
            svg.append("<line x1=\"").append(round(padLeft))
                    .append("\" x2=\"").append(round(padLeft + plotW))
                    .append("\" y1=\"").append(round(py100))
                    .append("\" y2=\"").append(round(py100))
                    .append("\" stroke=\"#8a8880\" stroke-dasharray=\"3 4\" stroke-width=\"0.8\"/>");
            svg.append("<text x=\"").append(round(padLeft + 4))
                    .append("\" y=\"").append(round(py100 - 4))
                    .append("\" font-family=\"JetBrains Mono,monospace\" font-size=\"9\""
                            + " fill=\"#8a8880\">base 100</text>");
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
            svg.append("<path fill=\"oklch(0.58 0.16 28 / 0.12)\" stroke=\"none\" d=\"")
                    .append(area).append("\"/>");
        }
        // Main line
        StringBuilder path = new StringBuilder();
        for (int i = 0; i < vals.length; i++) {
            double px = padLeft + (double) (times[i].toEpochMilli() - t0) / tSpan * plotW;
            double py = padTop + plotH - (vals[i] - yMin) / ySpan * plotH;
            path.append(i == 0 ? "M" : "L").append(round(px)).append(' ')
                    .append(round(py)).append(' ');
        }
        String stroke = isEquity ? "oklch(0.55 0.13 240)" : "oklch(0.58 0.16 28)";
        svg.append("<path fill=\"none\" stroke=\"").append(stroke)
                .append("\" stroke-width=\"1.6\" d=\"").append(path.toString().strip())
                .append("\"/>");
        svg.append("</svg>");
        return svg.toString();
    }

    // ─── Footer ──────────────────────────────────────────────────────────────

    private void appendFooter(StringBuilder html, ReportData data) {
        String tf = data.strategy().primaryTimeframe().wire();
        String higher = data.higherTimeframeSeries().isEmpty() ? ""
                : " (multi-TF, " + describeHigherTfs(data) + " background)";
        String date = data.generatedAt().toLocalDate().toString();
        html.append("<footer class=\"doc-footer\"><div class=\"row\">")
                .append("<div>Strategy: <b style=\"color:var(--ink-soft);font-weight:500\">")
                .append(esc(data.strategy().featureName())).append("</b> · Symbol: ")
                .append(esc(data.symbol())).append(" · Bars: ").append(esc(tf))
                .append(esc(higher)).append("</div>")
                .append("<div>wichtelm-app ").append(VERSION).append(" · ").append(esc(date))
                .append("</div></div>")
                .append("<div class=\"legal\">")
                .append("<b style=\"color:var(--ink-soft);font-weight:600\">Disclaimer.</b> ")
                .append(esc(DISCLAIMER_FULL)).append("</div></footer>");
    }

    // ─── MFE / MAE ───────────────────────────────────────────────────────────

    private BigDecimal[] computeMfeMae(List<OHLCBar> bars, Instant entryTime, Instant exitTime,
                                        BigDecimal entryPrice, String direction) {
        BigDecimal mfe = BigDecimal.ZERO;
        BigDecimal mae = BigDecimal.ZERO;
        boolean isLong = direction.equalsIgnoreCase("LONG");
        for (OHLCBar bar : bars) {
            // The position is held from the entry bar's open to the exit bar's
            // open (close-evaluated exits fill at the next bar's open). Include
            // bars where entryTime <= bar.time() < exitTime so the exit bar's
            // high/low — which the trade no longer experiences — does not
            // overstate the excursion stats.
            if (bar.time().isBefore(entryTime) || !bar.time().isBefore(exitTime)) {
                continue;
            }
            BigDecimal favorable;
            BigDecimal adverse;
            if (isLong) {
                favorable = bar.high().subtract(entryPrice, DECIMAL)
                        .divide(entryPrice, DECIMAL).multiply(HUNDRED, DECIMAL);
                adverse = bar.low().subtract(entryPrice, DECIMAL)
                        .divide(entryPrice, DECIMAL).multiply(HUNDRED, DECIMAL);
            } else {
                favorable = entryPrice.subtract(bar.low(), DECIMAL)
                        .divide(entryPrice, DECIMAL).multiply(HUNDRED, DECIMAL);
                adverse = entryPrice.subtract(bar.high(), DECIMAL)
                        .divide(entryPrice, DECIMAL).multiply(HUNDRED, DECIMAL);
            }
            if (favorable.compareTo(mfe) > 0) {
                mfe = favorable;
            }
            if (adverse.compareTo(mae) < 0) {
                mae = adverse;
            }
        }
        return new BigDecimal[] { mfe, mae };
    }

    private int countBarsBetween(List<OHLCBar> bars, Instant a, Instant b) {
        // Count bars where a <= bar.time() < b. The exit-fill bar at b is
        // excluded because the trade is closed at its open (close-evaluated
        // exits fill at the next bar's open), matching the same convention
        // used by computeMfeMae.
        int n = 0;
        for (OHLCBar bar : bars) {
            if (!bar.time().isBefore(a) && bar.time().isBefore(b)) {
                n++;
            }
        }
        return n;
    }

    // ─── Formatters ──────────────────────────────────────────────────────────

    private static String formatPercent(BigDecimal value) {
        return value.movePointRight(2).setScale(2, RoundingMode.HALF_UP).toPlainString() + "%";
    }

    private static String formatSignedPercent(BigDecimal value) {
        BigDecimal pct = value.movePointRight(2).setScale(2, RoundingMode.HALF_UP);
        return (pct.signum() > 0 ? "+" : "") + pct.toPlainString() + "%";
    }

    private static String formatSignedPercentRaw(BigDecimal value) {
        BigDecimal pct = value.setScale(2, RoundingMode.HALF_UP);
        return (pct.signum() > 0 ? "+" : "") + pct.toPlainString() + "%";
    }

    private static String formatSignedAmount(BigDecimal value) {
        BigDecimal scaled = value.setScale(2, RoundingMode.HALF_UP);
        return (scaled.signum() > 0 ? "+" : "") + scaled.toPlainString();
    }

    private static String formatRatio(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    private static String formatPrice(BigDecimal value) {
        BigDecimal rounded = value.setScale(2, RoundingMode.HALF_UP);
        String[] parts = rounded.toPlainString().split("\\.");
        StringBuilder out = new StringBuilder();
        String integer = parts[0];
        boolean neg = integer.startsWith("-");
        if (neg) {
            out.append('-');
            integer = integer.substring(1);
        }
        int len = integer.length();
        for (int i = 0; i < len; i++) {
            if (i > 0 && (len - i) % 3 == 0) {
                out.append(' ');
            }
            out.append(integer.charAt(i));
        }
        if (parts.length > 1) {
            out.append('.').append(parts[1]);
        }
        return out.toString();
    }

    private static String formatPercentTick(double v) {
        if (Math.abs(v) < 0.001) {
            return "0%";
        }
        return String.format(Locale.ROOT, "%.0f%%", v);
    }

    private static String formatIsoMinute(Instant t) {
        return t.atOffset(ZoneOffset.UTC).format(
                DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm'Z'", Locale.ENGLISH));
    }

    private static double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    // ─── Misc ────────────────────────────────────────────────────────────────

    private ChartRenderer newRenderer() {
        try {
            return new JFreeChartRenderer();
        } catch (DriverInternalException e) {
            throw new ReportGenerationException("could not initialize the chart renderer", e);
        }
    }

    private static String loadResource(String path) {
        try (InputStream in = HtmlReportGenerator.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new ReportGenerationException("missing report resource: " + path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("could not load report resource " + path, e);
        }
    }

    private static String esc(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;");
    }
}
