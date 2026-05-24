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
}
