package net.jacopobiscella.wichtelm.report;

import net.jacopobiscella.wichtelm.strategy.FirstClassCondition;
import net.jacopobiscella.wichtelm.strategy.ParsedStrategy;
import net.jacopobiscella.wichtelm.strategy.PositionPrecondition;
import net.jacopobiscella.wichtelm.strategy.StrategyParser;
import net.jacopobiscella.wichtelm.strategy.StrategyScenario;
import net.jacopobiscella.wichtelm.strategy.StrategyStep;
import org.hatrack.commons.OHLCBar;
import org.hatrack.commons.OHLCSeries;
import org.hatrack.commons.PivotPointVariant;
import org.hatrack.commons.PriceSource;
import org.hatrack.heerwisch.api.spec.Annotation;
import org.hatrack.heerwisch.api.spec.ChartImage;
import org.hatrack.heerwisch.api.spec.Indicator;
import org.hatrack.heerwisch.api.spec.IndicatorPlacement;
import org.hatrack.heerwisch.api.spec.LegendEntry;
import org.hatrack.heerwisch.api.spec.Pane;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Auto-plot wiring for the main-pane overlay primitives that previously
 * produced no chart contents: MA-trend-filter primitives (→ SMA / EMA
 * overlays) and pivot-point primitives (→ a {@link Annotation.PivotPointLevels}
 * level set). These AAA tests pin the emitted indicator / annotation objects
 * directly, which a raster-PNG assertion on the rendered chart could never
 * reach.
 *
 * <p>Window-aggregate Background series ({@code highest_high}, {@code lowest_low},
 * {@code highest_close}, {@code lowest_close}) ARE plotted, as field-matched
 * {@code RollingMax} / {@code RollingMin} channel overlays (ha-track 0.54), pinned
 * by {@link #windowAggregatesMapToFieldMatchedRollingExtremumOverlays}.
 */
class OverlayWiringTest {

    private final HtmlReportGenerator generator = new HtmlReportGenerator();

    private static StrategyScenario entry(String stepText) {
        return new StrategyScenario("Enter", PositionPrecondition.NO_OPEN_POSITION,
                List.of(new StrategyStep("When", stepText, 1)),
                FirstClassCondition.LONG_ENTRY, Optional.empty(), Optional.empty(), 1);
    }

    // ─── MA-trend-filter primitives → SMA / EMA overlays ─────────────────────

    @Test
    void priceCrossesAboveEmaPlotsTheEma() {
        List<Indicator> inds = generator.tierBIndicators(
                Map.of(), entry("price_crosses_above_ema(200)"), null);

        assertTrue(inds.contains(new Indicator.EMA(200, org.hatrack.commons.PriceSource.CLOSE)),
                "EMA(200) overlay among " + inds);
    }

    @Test
    void priceAboveSmaResolvesPeriodFromAParameter() {
        List<Indicator> inds = generator.tierBIndicators(
                Map.of("trend_period", new BigDecimal("50")),
                entry("price_above_sma(trend_period)"), null);

        assertTrue(inds.contains(new Indicator.SMA(50, org.hatrack.commons.PriceSource.CLOSE)),
                "SMA(50) overlay among " + inds);
    }

    @Test
    void smaAboveEmaPlotsBothMovingAverages() {
        List<Indicator> inds = generator.tierBIndicators(
                Map.of(), entry("sma_crosses_above_ema(50, 200)"), null);

        assertTrue(inds.contains(new Indicator.SMA(50, org.hatrack.commons.PriceSource.CLOSE)),
                "SMA(50) overlay among " + inds);
        assertTrue(inds.contains(new Indicator.EMA(200, org.hatrack.commons.PriceSource.CLOSE)),
                "EMA(200) overlay among " + inds);
    }

    // ─── Pivot-point primitives → PivotPointLevels ───────────────────────────

    private static OHLCSeries dailySeries(int n) {
        Instant start = Instant.parse("2024-03-01T00:00:00Z");
        java.util.List<OHLCBar> bars = new java.util.ArrayList<>();
        for (int i = 0; i < n; i++) {
            BigDecimal c = new BigDecimal(100 + i);
            bars.add(new OHLCBar(start.plus(Duration.ofDays(i)),
                    c, c.add(BigDecimal.ONE), c.subtract(BigDecimal.ONE), c, Optional.empty()));
        }
        return new OHLCSeries(bars);
    }

    @Test
    void pivotPrimitiveEmitsStandardLevelsFromThePriorDailyBar() {
        OHLCSeries series = dailySeries(5);
        Instant entryMarker = series.bars().get(3).time();

        List<Annotation> anns = generator.pivotAnnotations(true, series, entryMarker,
                entry("price_crosses_above_pivot(R1)"), null);

        List<Annotation.PivotPointLevels> pivots = anns.stream()
                .filter(a -> a instanceof Annotation.PivotPointLevels)
                .map(a -> (Annotation.PivotPointLevels) a).toList();
        assertEquals(1, pivots.size(), "exactly one pivot level set among " + anns);
        assertSame(PivotPointVariant.STANDARD, pivots.getFirst().variant());
        // The pivots active at the entry day are computed from the PRIOR day's bar.
        assertEquals(series.bars().get(2), pivots.getFirst().previousPeriodBar());
    }

    @Test
    void noPivotPrimitiveEmitsNoPivotLevels() {
        OHLCSeries series = dailySeries(5);
        List<Annotation> anns = generator.pivotAnnotations(true, series, series.bars().get(3).time(),
                entry("close is above 100"), null);
        assertTrue(anns.isEmpty(), "no pivot primitive → no pivot annotation");
    }

    @Test
    void pivotLevelsAreSuppressedOnHigherTimeframePanes() {
        OHLCSeries series = dailySeries(5);
        List<Annotation> anns = generator.pivotAnnotations(false, series, series.bars().get(3).time(),
                entry("price_above_pivot(P)"), null);
        assertTrue(anns.isEmpty(), "pivots are a primary-pane overlay only");
    }

    @Test
    void intradayPrimaryAggregatesThePriorDayNotThePriorBar() {
        // 3 hourly bars on 2024-03-01, then 2 on 2024-03-02; entry on day 2.
        // The pivot must come from day 1 AGGREGATED, not the previous hour bar.
        Instant d1 = Instant.parse("2024-03-01T00:00:00Z");
        Instant d2 = Instant.parse("2024-03-02T00:00:00Z");
        java.util.List<OHLCBar> bars = new java.util.ArrayList<>(List.of(
                bar(d1.plus(Duration.ofHours(0)), 10, 12, 9, 11),
                bar(d1.plus(Duration.ofHours(1)), 11, 15, 10, 14),
                bar(d1.plus(Duration.ofHours(2)), 14, 13, 8, 12),
                bar(d2.plus(Duration.ofHours(0)), 12, 16, 11, 13),
                bar(d2.plus(Duration.ofHours(1)), 13, 17, 12, 15)));
        OHLCSeries series = new OHLCSeries(bars);
        Instant entryMarker = d2.plus(Duration.ofHours(1));

        List<Annotation> anns = generator.pivotAnnotations(true, series, entryMarker,
                entry("price_crosses_above_pivot(R1)"), null);

        OHLCBar prior = anns.stream()
                .filter(a -> a instanceof Annotation.PivotPointLevels)
                .map(a -> ((Annotation.PivotPointLevels) a).previousPeriodBar())
                .findFirst().orElseThrow();
        // day 1 aggregate: open 10 (first), high 15 (max), low 8 (min), close 12 (last)
        assertEquals(0, new BigDecimal("10").compareTo(prior.open()), "day open");
        assertEquals(0, new BigDecimal("15").compareTo(prior.high()), "day high");
        assertEquals(0, new BigDecimal("8").compareTo(prior.low()), "day low");
        assertEquals(0, new BigDecimal("12").compareTo(prior.close()), "day close");
        assertEquals(d1, prior.time(), "day-bar time is the day's first bar");
    }

    private static OHLCBar bar(Instant t, int o, int h, int l, int c) {
        return new OHLCBar(t, BigDecimal.valueOf(o), BigDecimal.valueOf(h),
                BigDecimal.valueOf(l), BigDecimal.valueOf(c), Optional.empty());
    }

    // ─── Window-aggregate series → RollingMax/RollingMin overlays (0.54) ─────

    @Test
    void windowAggregatesMapToFieldMatchedRollingExtremumOverlays() {
        assertEquals(new Indicator.RollingMax(8, org.hatrack.commons.PriceSource.HIGH),
                HtmlReportGenerator.toIndicator("highest_high(8)", Map.of()),
                "highest_high → RollingMax(HIGH)");
        assertEquals(new Indicator.RollingMin(8, org.hatrack.commons.PriceSource.LOW),
                HtmlReportGenerator.toIndicator("lowest_low(8)", Map.of()),
                "lowest_low → RollingMin(LOW)");
        assertEquals(new Indicator.RollingMax(10, org.hatrack.commons.PriceSource.CLOSE),
                HtmlReportGenerator.toIndicator("highest_close(10)", Map.of()),
                "highest_close → RollingMax(CLOSE)");
        assertEquals(new Indicator.RollingMin(10, org.hatrack.commons.PriceSource.CLOSE),
                HtmlReportGenerator.toIndicator("lowest_close(10)", Map.of()),
                "lowest_close → RollingMin(CLOSE)");
    }

    @Test
    void windowAggregatePeriodResolvesFromAParameter() {
        assertEquals(new Indicator.RollingMax(8, org.hatrack.commons.PriceSource.HIGH),
                HtmlReportGenerator.toIndicator("highest_high(chan)",
                        Map.of("chan", new BigDecimal("8"))),
                "period read from the strategy, not hardcoded");
    }

    // ─── stddev series → StdDev σ sub-pane (0.55), not a Bollinger band ───────

    @Test
    void stddevMapsToAStdDevSubPaneNotBollingerBands() {
        Indicator ind = HtmlReportGenerator.toIndicator("stddev(20)", Map.of());

        assertEquals(new Indicator.StdDev(20, org.hatrack.commons.PriceSource.CLOSE), ind,
                "stddev → StdDev(20, CLOSE) σ sub-pane");
        assertTrue(!(ind instanceof Indicator.BollingerBands),
                "stddev must NOT render as a Bollinger band: " + ind);
    }

    @Test
    void stddevPeriodResolvesFromAParameter() {
        assertEquals(new Indicator.StdDev(20, org.hatrack.commons.PriceSource.CLOSE),
                HtmlReportGenerator.toIndicator("stddev(vol_period)",
                        Map.of("vol_period", new BigDecimal("20"))),
                "period read from the strategy, not hardcoded");
    }

    // ─── rsi_overbought(N)/rsi_oversold(N) → RSI sub-pane zone matches trigger ──

    private static Indicator.RSI rsiOf(List<Indicator> inds) {
        return inds.stream().filter(i -> i instanceof Indicator.RSI)
                .map(i -> (Indicator.RSI) i).findFirst().orElseThrow();
    }

    @Test
    void rsiThresholdsThreadFromLiteralsIntoTheDangerZone() {
        Indicator.RSI r = rsiOf(generator.tierBIndicators(
                Map.of(), entry("rsi_overbought(65)"), entry("rsi_oversold(35)")));

        assertEquals(0, new BigDecimal("65").compareTo(r.overbought()), "overbought zone = trigger");
        assertEquals(0, new BigDecimal("35").compareTo(r.oversold()), "oversold zone = trigger");
    }

    @Test
    void rsiThresholdsResolveFromParameters() {
        Indicator.RSI r = rsiOf(generator.tierBIndicators(
                Map.of("overbought", new BigDecimal("65"), "oversold", new BigDecimal("35")),
                entry("rsi_overbought(overbought)"), entry("rsi_oversold(oversold)")));

        assertEquals(0, new BigDecimal("65").compareTo(r.overbought()));
        assertEquals(0, new BigDecimal("35").compareTo(r.oversold()));
    }

    @Test
    void rsiCrosses50KeepsTheDefaultZones() {
        Indicator.RSI r = rsiOf(generator.tierBIndicators(Map.of(), entry("rsi_crosses_50()")));

        assertEquals(0, new BigDecimal("70").compareTo(r.overbought()), "default overbought 70");
        assertEquals(0, new BigDecimal("30").compareTo(r.oversold()), "default oversold 30");
    }

    // ─── frame-header descriptor names the chart's actual contents (§7.5) ─────

    private static StrategyScenario entrySteps(String... stepTexts) {
        java.util.List<StrategyStep> steps = new java.util.ArrayList<>();
        for (int i = 0; i < stepTexts.length; i++) {
            steps.add(new StrategyStep(i == 0 ? "When" : "And", stepTexts[i], i + 1));
        }
        return new StrategyScenario("Enter", PositionPrecondition.NO_OPEN_POSITION, steps,
                FirstClassCondition.LONG_ENTRY, Optional.empty(), Optional.empty(), 1);
    }

    private static ParsedStrategy primaryOnlyStrategy(String primaryTf) {
        return StrategyParser.parse(
                "Feature: Header descriptor\n"
                        + "  Primary timeframe: " + primaryTf + "\n\n"
                        + "  Scenario: Enter\n"
                        + "    Given no open position\n"
                        + "    When close is above 1\n"
                        + "    Then long_entry\n",
                "header.strat");
    }

    @Test
    void primaryHeaderNamesTierBOverlaysSubPanesAndPivots() {
        OHLCSeries series = dailySeries(200);
        StrategyScenario entryScenario = entrySteps(
                "price_above_sma(10)", "price_above_ema(30)", "rsi_oversold(30)",
                "price_above_pivot(R1)");

        String header = generator.describeChartIndicators("1d", series.bars().size(),
                primaryOnlyStrategy("1d"), Map.of(), series, true,
                entryScenario, null, series.bars().get(150).time());

        assertTrue(header.startsWith("HA candles"), header);
        assertTrue(header.contains("SMA(10)"), "SMA overlay named: " + header);
        assertTrue(header.contains("EMA(30)"), "EMA overlay named: " + header);
        assertTrue(header.contains("RSI(14)"), "RSI sub-pane named: " + header);
        assertTrue(header.contains("pivots"), "pivot levels named: " + header);
    }

    @Test
    void higherTimeframePaneNamesOnlyItsBackgroundSeriesNotPrimaryTierBOverlays() {
        OHLCSeries series = dailySeries(60);
        StrategyScenario entryScenario = entrySteps("price_above_sma(10)", "price_above_pivot(R1)");

        String header = generator.describeChartIndicators("1d", series.bars().size(),
                primaryOnlyStrategy("1h"), Map.of(), series, false,
                entryScenario, null, series.bars().get(40).time());

        assertEquals("HA candles", header,
                "higher-TF context pane shows no primary-pane tier-B overlays/pivots: " + header);
    }

    // ─── BEN-2: legend tags overlays referenced-by-this-trade vs context ──────

    private static ParsedStrategy strategyWithVolSeries() {
        return StrategyParser.parse(
                "Feature: Referenced vs context\n"
                        + "  Primary timeframe: 1d\n\n"
                        + "  Background:\n"
                        + "    Given a series vol defined as stddev(20)\n\n"
                        + "  Scenario: Enter\n"
                        + "    Given no open position\n"
                        + "    When close is above 1\n"
                        + "    Then long_entry\n",
                "ref.strat");
    }

    @Test
    void referencedSetCoversTierBOverlaysAndTradeNamedBackgroundSeries() {
        Set<Indicator> referenced = generator.referencedIndicators(
                strategyWithVolSeries(), Map.of(), "1d",
                entrySteps("price_above_sma(10)", "vol is above 1"), null, true);

        assertTrue(referenced.contains(new Indicator.SMA(10, PriceSource.CLOSE)),
                "tier-B SMA overlay is referenced: " + referenced);
        assertTrue(referenced.contains(new Indicator.StdDev(20, PriceSource.CLOSE)),
                "Background series named in this trade's steps is referenced: " + referenced);
    }

    @Test
    void backgroundSeriesNotNamedByThisTradeIsNotReferenced() {
        Set<Indicator> referenced = generator.referencedIndicators(
                strategyWithVolSeries(), Map.of(), "1d",
                entrySteps("price_above_sma(10)"), null, true);

        assertTrue(referenced.contains(new Indicator.SMA(10, PriceSource.CLOSE)), "" + referenced);
        assertTrue(!referenced.contains(new Indicator.StdDev(20, PriceSource.CLOSE)),
                "a Background series this trade does not consult is context, not referenced: " + referenced);
    }

    @Test
    void referencedSetIsScopedPerTimeframeForIdenticalCrossTfIndicators() {
        // Two Background series compile to the SAME Indicator (sma(10)) on
        // different timeframes; the trade references only the primary one.
        ParsedStrategy strategy = StrategyParser.parse(
                "Feature: Multi-TF legend\n"
                        + "  Primary timeframe: 1h\n\n"
                        + "  Background:\n"
                        + "    Given a series fast defined as sma(10)\n"
                        + "    And a series slow defined as sma(10) on 1d\n\n"
                        + "  Scenario: Enter\n"
                        + "    Given no open position\n"
                        + "    When close is above 1\n"
                        + "    Then long_entry\n",
                "multitf.strat");
        StrategyScenario entry = entrySteps("close is above fast"); // names fast (1h), not slow (1d)
        Indicator sma10 = new Indicator.SMA(10, PriceSource.CLOSE);

        Set<Indicator> primary = generator.referencedIndicators(strategy, Map.of(), "1h", entry, null, true);
        Set<Indicator> higher = generator.referencedIndicators(strategy, Map.of(), "1d", entry, null, false);

        assertTrue(primary.contains(sma10), "the referenced primary SMA(10) is referenced: " + primary);
        assertTrue(!higher.contains(sma10),
                "the identical 1d SMA(10) the trade does not reference stays context: " + higher);
    }

    @Test
    void legendStripTagsReferencedAndContextEntries() {
        Indicator sma = new Indicator.SMA(10, PriceSource.CLOSE);
        Indicator sigma = new Indicator.StdDev(20, PriceSource.CLOSE);
        ChartImage image = new ChartImage(new byte[]{1}, "image/png", 900, 460, List.of(
                new LegendEntry(new IndicatorPlacement(sma, Pane.MAIN), "SMA(10)", 0x112233, Pane.MAIN),
                new LegendEntry(new IndicatorPlacement(sigma, Pane.SUBPLOT_1), "σ(20)", 0x445566,
                        Pane.SUBPLOT_1)));

        String html = generator.legendStrip(image, Set.of(sma)); // SMA referenced, σ context

        int smaAt = html.indexOf("SMA(10)");
        int sigmaAt = html.indexOf("σ(20)");
        assertTrue(smaAt >= 0 && sigmaAt >= 0, html);
        assertTrue(html.lastIndexOf("leg-referenced", smaAt) > html.lastIndexOf("leg-context", smaAt),
                "SMA(10) is tagged referenced: " + html);
        assertTrue(html.lastIndexOf("leg-context", sigmaAt) > html.lastIndexOf("leg-referenced", sigmaAt),
                "σ(20) is tagged context: " + html);
    }
}
