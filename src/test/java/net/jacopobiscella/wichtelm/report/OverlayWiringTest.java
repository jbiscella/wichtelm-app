package net.jacopobiscella.wichtelm.report;

import net.jacopobiscella.wichtelm.strategy.BackgroundSeries;
import net.jacopobiscella.wichtelm.strategy.FirstClassCondition;
import net.jacopobiscella.wichtelm.strategy.PositionPrecondition;
import net.jacopobiscella.wichtelm.strategy.StrategyScenario;
import net.jacopobiscella.wichtelm.strategy.StrategyStep;
import org.hatrack.commons.OHLCBar;
import org.hatrack.commons.OHLCSeries;
import org.hatrack.commons.PivotPointVariant;
import org.hatrack.commons.Timeframe;
import org.hatrack.heerwisch.api.spec.Annotation;
import org.hatrack.heerwisch.api.spec.Indicator;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Auto-plot wiring for the main-pane overlay primitives that previously
 * produced no chart contents: MA-trend-filter primitives (→ SMA / EMA
 * overlays), pivot-point primitives (→ a {@link Annotation.PivotPointLevels}
 * level set), and window-aggregate Background series (→ snapshot
 * {@link Annotation.HorizontalLevel} channel bounds). These AAA tests pin the
 * emitted indicator / annotation objects directly, which a raster-PNG assertion
 * on the rendered chart could never reach.
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

    // ─── Window-aggregate Background series → channel HorizontalLevels ───────

    private static OHLCSeries weeklySeries() {
        Instant start = Instant.parse("2024-01-01T00:00:00Z");
        // highs:  10 12 11 15 14   lows: 5 4 6 3 7
        int[] hi = {10, 12, 11, 15, 14};
        int[] lo = {5, 4, 6, 3, 7};
        java.util.List<OHLCBar> bars = new java.util.ArrayList<>();
        for (int i = 0; i < hi.length; i++) {
            bars.add(new OHLCBar(start.plus(Duration.ofDays(7L * i)),
                    BigDecimal.valueOf(8), BigDecimal.valueOf(hi[i]),
                    BigDecimal.valueOf(lo[i]), BigDecimal.valueOf(9), Optional.empty()));
        }
        return new OHLCSeries(bars);
    }

    @Test
    void windowAggregateSeriesDrawSnapshotChannelBounds() {
        OHLCSeries weekly = weeklySeries();
        Instant entryMarker = weekly.bars().getLast().time();
        List<BackgroundSeries> bg = List.of(
                new BackgroundSeries("weekly_high", "highest_high(3)",
                        Optional.of(Timeframe.fromWire("1w")), 1),
                new BackgroundSeries("weekly_low", "lowest_low(3)",
                        Optional.of(Timeframe.fromWire("1w")), 2));

        List<Annotation> anns = generator.channelLevels(
                "1w", weekly, entryMarker, bg, "1d", Map.of());

        Annotation.HorizontalLevel high = level(anns, "weekly_high");
        Annotation.HorizontalLevel low = level(anns, "weekly_low");
        // last 3 weekly bars: highs {11,15,14} → 15 ; lows {6,3,7} → 3
        assertEquals(0, new BigDecimal("15").compareTo(high.price()), "highest_high(3)");
        assertEquals(0, new BigDecimal("3").compareTo(low.price()), "lowest_low(3)");
    }

    @Test
    void channelBoundsAreSnapshotAsOfTheEntryNotEndOfWindow() {
        OHLCSeries weekly = weeklySeries();
        // Entry at the 3rd bar (index 2): last 3 bars up to it are indices 0,1,2
        // highs {10,12,11} → 12.
        Instant entryMarker = weekly.bars().get(2).time();
        List<BackgroundSeries> bg = List.of(
                new BackgroundSeries("weekly_high", "highest_high(3)",
                        Optional.of(Timeframe.fromWire("1w")), 1));

        Annotation.HorizontalLevel high = level(
                generator.channelLevels("1w", weekly, entryMarker, bg, "1d", Map.of()),
                "weekly_high");
        assertEquals(0, new BigDecimal("12").compareTo(high.price()),
                "snapshot uses only bars at/before entry");
    }

    @Test
    void channelLevelsIgnoreSeriesOnOtherTimeframes() {
        OHLCSeries weekly = weeklySeries();
        List<BackgroundSeries> bg = List.of(
                new BackgroundSeries("daily_high", "highest_high(3)",
                        Optional.empty(), 1)); // primary-TF series (1d), not 1w
        List<Annotation> anns = generator.channelLevels(
                "1w", weekly, weekly.bars().getLast().time(), bg, "1d", Map.of());
        assertTrue(anns.isEmpty(), "a 1d series must not draw on the 1w pane");
    }

    private static Annotation.HorizontalLevel level(List<Annotation> anns, String labelPrefix) {
        return anns.stream()
                .filter(a -> a instanceof Annotation.HorizontalLevel)
                .map(a -> (Annotation.HorizontalLevel) a)
                .filter(h -> h.label().startsWith(labelPrefix))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no '" + labelPrefix + "' level among " + anns));
    }
}
