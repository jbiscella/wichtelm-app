package net.jacopobiscella.wichtelm;

import net.jacopobiscella.wichtelm.runtime.NachtkrappMatchIndex;
import net.jacopobiscella.wichtelm.runtime.NachtkrappMatchIndex.Key;
import net.jacopobiscella.wichtelm.strategy.ParsedStrategy;
import net.jacopobiscella.wichtelm.strategy.StrategyParser;
import org.hatrack.commons.OHLCBar;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.IntUnaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Per-bar correctness of the MA trend filter primitives (INC1). The Gherkin
 * scenarios prove the wiring (parse, prepass-key, no-crash); these AAA tests
 * prove the SEMANTICS against engineered series where the price/MA relationship
 * is known by construction — catching mutations the Gherkin would miss
 * (above↔below swap, cross emitted per-bar instead of once, MA-vs-MA wiring).
 */
class MaTrendFilterEvaluationTest {

    private static final Instant START = Instant.parse("2024-01-01T00:00:00Z");
    private static final String TF = "1h";

    private static List<OHLCBar> series(int n, IntUnaryOperator closeFn) {
        List<OHLCBar> bars = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            BigDecimal c = BigDecimal.valueOf(closeFn.applyAsInt(i));
            // Wicks are irrelevant: the rules use PriceSource.CLOSE.
            bars.add(new OHLCBar(START.plus(Duration.ofHours(i)),
                    c, c.add(BigDecimal.ONE), c.subtract(BigDecimal.ONE), c, Optional.empty()));
        }
        return bars;
    }

    /** Build an index over {@code bars} for a strategy that references each {@code ;}-joined step. */
    private static NachtkrappMatchIndex indexFor(String semicolonSteps, List<OHLCBar> bars) {
        StringBuilder s = new StringBuilder("Feature: t\n  Primary timeframe: 1h\n");
        String[] steps = semicolonSteps.split(";");
        for (int i = 0; i < steps.length; i++) {
            s.append("\n  Scenario: enter ").append(i).append('\n')
                    .append("    Given no open position\n")
                    .append("    When ").append(steps[i].strip()).append('\n')
                    .append("    Then long_entry\n");
        }
        ParsedStrategy strategy = StrategyParser.parse(s.toString(), "ma-eval.strat");
        return NachtkrappMatchIndex.buildFor(strategy, Map.of(), bars, Map.of());
    }

    private static Key key(String name, List<OHLCBar> bars, long... args) {
        List<BigDecimal> a = new ArrayList<>();
        for (long v : args) {
            a.add(BigDecimal.valueOf(v));
        }
        return new Key(name, a, TF);
    }

    private static int matchCount(NachtkrappMatchIndex idx, Key k, List<OHLCBar> bars) {
        int c = 0;
        for (OHLCBar b : bars) {
            if (idx.matches(k, b.time())) {
                c++;
            }
        }
        return c;
    }

    @Test
    void priceAboveAndBelowAreDistinguishedOnAnUptrend() {
        // Strictly increasing close: every warm bar has close > SMA(5) (the
        // average of the lower earlier window), so above=true, below=false.
        List<OHLCBar> bars = series(40, i -> 100 + i);
        NachtkrappMatchIndex idx = indexFor("price_above_sma(5);price_below_sma(5)", bars);
        Instant late = bars.get(39).time();

        assertTrue(idx.matches(key("price_above_sma", bars, 5), late),
                "close must be ABOVE SMA(5) on a strict uptrend");
        assertFalse(idx.matches(key("price_below_sma", bars, 5), late),
                "close must NOT be below SMA(5) on a strict uptrend (catches above/below swap)");
    }

    @Test
    void priceCrossIsAnEventNotAPerBarState() {
        List<OHLCBar> bars = series(40, i -> 100 + i);
        NachtkrappMatchIndex idx = indexFor(
                "price_above_sma(5);price_crosses_above_sma(5)", bars);
        int state = matchCount(idx, key("price_above_sma", bars, 5), bars);
        int cross = matchCount(idx, key("price_crosses_above_sma", bars, 5), bars);

        assertTrue(state > 5, "the state primitive should hold on most uptrend bars, was " + state);
        assertTrue(cross <= 1, "a pure uptrend has at most one up-cross, was " + cross);
        assertTrue(cross < state,
                "cross is a transition: it must fire on fewer bars than the state (catches "
                        + "a cross rule that emits every bar)");
    }

    @Test
    void priceCrossAboveFiresOnceOnASingleReversal() {
        // Down then up (a single V): close falls below the lagging SMA, then
        // rises back through it exactly once.
        List<OHLCBar> bars = series(40, i -> i < 20 ? 200 - i * 4 : 120 + (i - 20) * 4);
        NachtkrappMatchIndex idx = indexFor("price_crosses_above_sma(5)", bars);
        int cross = matchCount(idx, key("price_crosses_above_sma", bars, 5), bars);

        assertEquals(1, cross,
                "a single down-then-up reversal must produce exactly one up-cross, was " + cross);
    }

    @Test
    void smaAboveEmaTrueWhenFastSitsAboveSlowOnUptrend() {
        // Steady uptrend: the fast SMA(2) tracks price closely and sits above
        // the laggier EMA(20).
        List<OHLCBar> bars = series(40, i -> 100 + i * 3);
        NachtkrappMatchIndex idx = indexFor("sma_above_ema(2, 20)", bars);

        assertTrue(idx.matches(key("sma_above_ema", bars, 2, 20), bars.get(39).time()),
                "fast SMA(2) must sit above the laggier EMA(20) on a steady uptrend "
                        + "(catches MA-vs-MA wiring / above-filter errors)");
    }
}
