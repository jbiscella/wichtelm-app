package net.jacopobiscella.wichtelm.report;

import net.jacopobiscella.wichtelm.strategy.FirstClassCondition;
import net.jacopobiscella.wichtelm.strategy.PositionPrecondition;
import net.jacopobiscella.wichtelm.strategy.StrategyScenario;
import net.jacopobiscella.wichtelm.strategy.StrategyStep;
import org.hatrack.commons.OHLCBar;
import org.hatrack.commons.OHLCSeries;
import org.hatrack.commons.PivotPointVariant;
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
 * overlays) and pivot-point primitives (→ a {@link Annotation.PivotPointLevels}
 * level set). These AAA tests pin the emitted indicator / annotation objects
 * directly, which a raster-PNG assertion on the rendered chart could never
 * reach.
 *
 * <p>Window-aggregate Background series ({@code highest_high} etc.) are
 * deliberately NOT plotted: heerwisch has no rolling-extremum indicator, and a
 * single-snapshot {@code HorizontalLevel} misrepresents a stepping channel, so
 * they stay unplotted until an additive ha-track indicator lands.
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
}
