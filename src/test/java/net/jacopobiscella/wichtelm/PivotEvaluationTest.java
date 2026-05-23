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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Per-bar correctness of the pivot Tier B primitives (INC3). The Gherkin
 * scenarios prove the wiring (parse, level-rejection, prepass-key, no-crash);
 * these AAA tests prove the SEMANTICS against a series whose STANDARD daily
 * pivot is known by construction — catching mutations the Gherkin would miss
 * (R1↔S1 level confusion, the level filter being dropped, above↔below swap).
 *
 * <p>Construction: a prior UTC day aggregating to High=110, Low=90, Close=100
 * gives P=(110+90+100)/3=100, R1=2P-Low=110, S1=2P-High=90. The following day's
 * bars are placed in three close bands — above R1, between S1 and R1, below S1 —
 * so each primitive's truth value at a mid-band bar is known exactly.
 */
class PivotEvaluationTest {

    private static final Instant DAY1 = Instant.parse("2024-01-01T00:00:00Z");
    private static final String TF = "1h";

    /** A 48-bar 1h series: day 1 aggregates to H=110/L=90/C=100, day 2 in three bands. */
    private static List<OHLCBar> twoDaySeries() {
        List<OHLCBar> bars = new ArrayList<>();
        // Day 1 (prior): all closes 100; one bar reaches the daily high 110,
        // one the daily low 90, so the aggregate is H=110, L=90, C=100.
        for (int i = 0; i < 24; i++) {
            BigDecimal close = BigDecimal.valueOf(100);
            BigDecimal high = i == 0 ? BigDecimal.valueOf(110) : BigDecimal.valueOf(101);
            BigDecimal low = i == 1 ? BigDecimal.valueOf(90) : BigDecimal.valueOf(99);
            bars.add(new OHLCBar(DAY1.plus(Duration.ofHours(i)), close, high, low, close,
                    Optional.empty()));
        }
        // Day 2: three close bands of 8 bars each. above R1 (120) | between
        // S1 and R1 (100) | below S1 (80). Pivots use PriceSource.CLOSE.
        for (int i = 0; i < 24; i++) {
            int close = i < 8 ? 120 : i < 16 ? 100 : 80;
            BigDecimal c = BigDecimal.valueOf(close);
            bars.add(new OHLCBar(DAY1.plus(Duration.ofHours(24 + i)),
                    c, c.add(BigDecimal.ONE), c.subtract(BigDecimal.ONE), c, Optional.empty()));
        }
        return bars;
    }

    private static NachtkrappMatchIndex indexFor(String semicolonSteps, List<OHLCBar> bars) {
        StringBuilder s = new StringBuilder("Feature: t\n  Primary timeframe: 1h\n");
        String[] steps = semicolonSteps.split(";");
        for (int i = 0; i < steps.length; i++) {
            s.append("\n  Scenario: enter ").append(i).append('\n')
                    .append("    Given no open position\n")
                    .append("    When ").append(steps[i].strip()).append('\n')
                    .append("    Then long_entry\n");
        }
        ParsedStrategy strategy = StrategyParser.parse(s.toString(), "pivot-eval.strat");
        return NachtkrappMatchIndex.buildFor(strategy, Map.of(), bars, Map.of());
    }

    private static Key pivot(String name, String level) {
        return Key.pivot(name, level, TF);
    }

    @Test
    void aboveAndBelowAreDistinguishedAndLevelAware() {
        // ARRANGE: index every above/below × R1/S1 combination.
        List<OHLCBar> bars = twoDaySeries();
        NachtkrappMatchIndex idx = indexFor(
                "price_above_pivot(R1);price_above_pivot(S1)"
                        + ";price_below_pivot(R1);price_below_pivot(S1)", bars);
        Instant aboveR1 = bars.get(28).time();   // day-2 mid 120-band: close 120
        Instant betweenS1R1 = bars.get(35).time(); // mid 100-band: 90 < 100 < 110
        Instant belowS1 = bars.get(44).time();   // mid 80-band: close 80

        // ASSERT: close 120 is above both levels, below neither.
        assertTrue(idx.matches(pivot("price_above_pivot", "R1"), aboveR1), "120 > R1(110)");
        assertTrue(idx.matches(pivot("price_above_pivot", "S1"), aboveR1), "120 > S1(90)");
        assertFalse(idx.matches(pivot("price_below_pivot", "R1"), aboveR1), "120 not < R1");

        // ASSERT (the level-aware case): close 100 sits ABOVE S1 but BELOW R1.
        // A mutation that ignored the level token would make these identical.
        assertTrue(idx.matches(pivot("price_above_pivot", "S1"), betweenS1R1), "100 > S1(90)");
        assertFalse(idx.matches(pivot("price_above_pivot", "R1"), betweenS1R1),
                "100 is NOT above R1(110) — catches R1/S1 confusion or a dropped level filter");
        assertTrue(idx.matches(pivot("price_below_pivot", "R1"), betweenS1R1), "100 < R1(110)");
        assertFalse(idx.matches(pivot("price_below_pivot", "S1"), betweenS1R1), "100 not < S1(90)");

        // ASSERT: close 80 is below both levels, above neither (above/below swap guard).
        assertTrue(idx.matches(pivot("price_below_pivot", "S1"), belowS1), "80 < S1(90)");
        assertFalse(idx.matches(pivot("price_above_pivot", "S1"), belowS1), "80 not > S1(90)");
    }

    @Test
    void crossIsAnEventNotAPerBarState() {
        // ARRANGE: price enters the above-R1 band once (bars 24-31) and stays;
        // the cross fires on the single transition bar, the state on many.
        List<OHLCBar> bars = twoDaySeries();
        NachtkrappMatchIndex idx = indexFor(
                "price_above_pivot(R1);price_crosses_above_pivot(R1)", bars);
        int state = 0;
        int cross = 0;
        for (OHLCBar b : bars) {
            if (idx.matches(pivot("price_above_pivot", "R1"), b.time())) {
                state++;
            }
            if (idx.matches(pivot("price_crosses_above_pivot", "R1"), b.time())) {
                cross++;
            }
        }

        // ASSERT: the state holds on the whole 120-band; the cross is a single event.
        assertTrue(state > 1, "the above-R1 state should hold on the whole 120-band, was " + state);
        assertTrue(cross <= 1, "price crosses above R1 at most once here, was " + cross);
        assertTrue(cross < state,
                "cross is a transition: it must fire on fewer bars than the state (catches a "
                        + "cross rule that emits every in-state bar)");
    }
}
