package net.jacopobiscella.wichtelm;

import net.jacopobiscella.wichtelm.runtime.BarIndicatorSource;
import net.jacopobiscella.wichtelm.runtime.WichtelmSignalGenerator;
import org.hatrack.commons.OHLCBar;
import org.hatrack.indicators.Indicators;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Correctness of the atr_value(period) accessor (INC2). The Gherkin scenarios
 * prove it parses in stop/take and the backtest runs; this AAA test proves the
 * value: atr_value(n) at a bar equals the indicators-module ATR(n) there. The
 * freeze-at-fill is structural — {@code WichtelmSignalGenerator.evaluateProtective}
 * always positions the source at {@code position.entryTime()} — so a stop of
 * "entry_price - 2 * atr_value(14)" is entry minus twice the ATR at the fill bar.
 */
class AtrDynamicStopEvaluationTest {

    @Test
    void atrValueResolvesToTheIndicatorsAtrAtTheSourceBar() {
        // Arrange: bars with deliberately varying ranges so ATR is non-trivial.
        List<OHLCBar> bars = new ArrayList<>();
        List<BigDecimal> highs = new ArrayList<>();
        List<BigDecimal> lows = new ArrayList<>();
        List<BigDecimal> closes = new ArrayList<>();
        Instant start = Instant.parse("2024-01-01T00:00:00Z");
        for (int i = 0; i < 30; i++) {
            BigDecimal close = BigDecimal.valueOf(100 + i);
            BigDecimal high = close.add(BigDecimal.valueOf((i % 5) + 1));
            BigDecimal low = close.subtract(BigDecimal.valueOf((i % 3) + 1));
            bars.add(new OHLCBar(start.plus(Duration.ofHours(i)), close, high, low, close,
                    Optional.empty()));
            highs.add(high);
            lows.add(low);
            closes.add(close);
        }
        long lastIndex = bars.size() - 1;
        BarIndicatorSource source = new BarIndicatorSource(
                bars, "atr-eval", bars.get((int) lastIndex).time(), lastIndex);

        // Act
        BigDecimal got = source.evaluate("atr_value", List.of(BigDecimal.valueOf(14)));

        // Assert: identical to the indicators-module ATR(14) at the same bar.
        BigDecimal[] atr = Indicators.atr(highs, lows, closes, 14);
        BigDecimal expected = atr[atr.length - 1];
        assertEquals(0, got.compareTo(expected),
                "atr_value(14) must equal ATR(14) at the bar; got " + got + " expected " + expected);
    }

    @Test
    void atrSnapshotExcludesTheFillBarToAvoidLookahead() {
        // The fill happens at the bar's OPEN, so the fill bar's high/low/close
        // are unknown then; the ATR snapshot must use only bars that closed
        // strictly before the fill. Build history that ends AT the fill instant.
        Instant start = Instant.parse("2024-01-01T00:00:00Z");
        List<OHLCBar> history = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            BigDecimal c = BigDecimal.valueOf(100 + i);
            history.add(new OHLCBar(start.plus(Duration.ofHours(i)), c, c.add(BigDecimal.ONE),
                    c.subtract(BigDecimal.ONE), c, Optional.empty()));
        }
        Instant fill = start.plus(Duration.ofHours(5)); // the last bar IS the fill bar

        List<OHLCBar> beforeFill = WichtelmSignalGenerator.barsStrictlyBefore(history, fill);

        assertEquals(5, beforeFill.size(), "the fill bar must be excluded from the ATR snapshot");
        assertTrue(beforeFill.stream().noneMatch(b -> b.time().equals(fill)),
                "no bar at the fill instant may feed atr_value (lookahead-safety)");
    }
}
