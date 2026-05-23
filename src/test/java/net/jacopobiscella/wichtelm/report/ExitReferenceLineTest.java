package net.jacopobiscella.wichtelm.report;

import net.jacopobiscella.wichtelm.strategy.FirstClassCondition;
import net.jacopobiscella.wichtelm.strategy.PositionPrecondition;
import net.jacopobiscella.wichtelm.strategy.StrategyScenario;
import net.jacopobiscella.wichtelm.strategy.StrategyStep;
import org.hatrack.heerwisch.api.spec.Annotation;
import org.hatrack.heerwisch.api.spec.FillColor;
import org.hatrack.heerwisch.api.spec.LevelStyle;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The per-trade primary-pane reference lines. The Exit line (added after the
 * marker-only design proved hard to read off the Y axis) is colored by trade
 * OUTCOME (WIN / LOSS / NEUTRAL), drawn only for closed trades. These AAA tests
 * pin the price and the outcome color directly off {@code referenceLevels},
 * which a raster-PNG assertion on the rendered chart could never reach.
 */
class ExitReferenceLineTest {

    private final HtmlReportGenerator generator = new HtmlReportGenerator();

    private static StrategyScenario longEntry() {
        return new StrategyScenario("Enter long", PositionPrecondition.NO_OPEN_POSITION,
                List.of(new StrategyStep("When", "close is above 1", 1)),
                FirstClassCondition.LONG_ENTRY, Optional.empty(), Optional.empty(), 1);
    }

    private static Annotation.HorizontalLevel level(List<Annotation> levels, String labelPrefix) {
        return levels.stream()
                .filter(a -> a instanceof Annotation.HorizontalLevel)
                .map(a -> (Annotation.HorizontalLevel) a)
                .filter(h -> h.label().startsWith(labelPrefix))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no '" + labelPrefix + "' level among " + levels));
    }

    private List<Annotation> levelsFor(boolean isLong, boolean openTrade,
                                       BigDecimal entry, BigDecimal exit) {
        return generator.referenceLevels(true, openTrade, longEntry(), isLong,
                entry, exit, BigDecimal.ONE, null, null);
    }

    @Test
    void winningLongClosedTradeDrawsAGreenExitLineAtTheExitPrice() {
        // ARRANGE: a long that exited higher than it entered (a winner).
        BigDecimal entry = new BigDecimal("100");
        BigDecimal exit = new BigDecimal("110");

        // ACT
        Annotation.HorizontalLevel exitLine =
                level(levelsFor(true, false, entry, exit), "Exit");

        // ASSERT: the line sits at the exit price, dashed, semantic WIN.
        assertEquals(0, exit.compareTo(exitLine.price()), "exit line at exit price");
        assertEquals(LevelStyle.DASHED, exitLine.style());
        assertEquals(Optional.of(FillColor.WIN), exitLine.fillColor());
    }

    @Test
    void losingLongClosedTradeDrawsARedExitLine() {
        // ARRANGE: a long that exited below entry (a loser).
        Annotation.HorizontalLevel exitLine = level(
                levelsFor(true, false, new BigDecimal("100"), new BigDecimal("90")), "Exit");

        // ASSERT
        assertEquals(Optional.of(FillColor.LOSS), exitLine.fillColor());
    }

    @Test
    void breakevenClosedTradeDrawsANeutralExitLine() {
        // ARRANGE: exit == entry (the rare breakeven edge case).
        Annotation.HorizontalLevel exitLine = level(
                levelsFor(true, false, new BigDecimal("100"), new BigDecimal("100")), "Exit");

        // ASSERT
        assertEquals(Optional.of(FillColor.NEUTRAL), exitLine.fillColor());
    }

    @Test
    void shortOutcomeIsDirectionAware() {
        // ARRANGE: a short closing BELOW entry is a WINNER (capital flows the
        // other way) — the outcome color must not key off the raw price move.
        Annotation.HorizontalLevel exitLine = level(
                levelsFor(false, false, new BigDecimal("100"), new BigDecimal("90")), "Exit");

        // ASSERT
        assertEquals(Optional.of(FillColor.WIN), exitLine.fillColor());
    }

    @Test
    void openPositionGetsNoExitLine() {
        // ARRANGE: still-open trade — exitPrice is null, openTrade is true.
        List<Annotation> levels = generator.referenceLevels(true, true, longEntry(), true,
                new BigDecimal("100"), null, BigDecimal.ONE, null, null);

        // ASSERT: Entry line present, no Exit line.
        assertTrue(levels.stream()
                        .filter(a -> a instanceof Annotation.HorizontalLevel)
                        .map(a -> (Annotation.HorizontalLevel) a)
                        .noneMatch(h -> h.label().startsWith("Exit")),
                "open trade must not draw an Exit line");
        assertSame(FillColor.NEUTRAL, level(levels, "Entry").fillColor().orElseThrow());
    }
}
