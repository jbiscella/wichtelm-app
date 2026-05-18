package net.jacopobiscella.wichtelm.runtime;

import net.jacopobiscella.wichtelm.error.DslEvaluationException;
import org.hatrack.commons.OHLCBar;
import org.hatrack.indicators.Indicators;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Evaluates indicator function calls against the bars visible up to and
 * including the current bar, delegating the arithmetic to the ha-track
 * {@code indicators} module.
 *
 * <p>This increment covers the single-period numeric indicators {@code sma},
 * {@code ema}, {@code rsi} and {@code atr}. The remaining catalog entries
 * ({@code macd}, {@code stddev}, window aggregates, and the nachtkrapp
 * pattern primitives) raise a {@link DslEvaluationException} until their
 * own increment lands.
 */
public final class BarIndicatorSource implements ExpressionEvaluator.IndicatorSource {

    private final List<OHLCBar> bars;
    private final String strategyName;
    private final Instant barTime;
    private final long barIndex;

    public BarIndicatorSource(List<OHLCBar> bars, String strategyName,
                              Instant barTime, long barIndex) {
        this.bars = List.copyOf(bars);
        this.strategyName = strategyName;
        this.barTime = barTime;
        this.barIndex = barIndex;
    }

    @Override
    public BigDecimal evaluate(String functionName, List<BigDecimal> arguments) {
        return switch (functionName) {
            case "sma" -> latest(functionName, Indicators.sma(closes(), period(functionName, arguments)));
            case "ema" -> latest(functionName, Indicators.ema(closes(), period(functionName, arguments)));
            case "rsi" -> latest(functionName, Indicators.rsi(closes(), period(functionName, arguments)));
            case "atr" -> latest(functionName,
                    Indicators.atr(highs(), lows(), closes(), period(functionName, arguments)));
            default -> throw fail(functionName,
                    "indicator '" + functionName + "' is not implemented in this increment");
        };
    }

    private int period(String functionName, List<BigDecimal> arguments) {
        if (arguments.size() != 1) {
            throw fail(functionName, functionName + " expects a single period argument");
        }
        try {
            int period = arguments.getFirst().intValueExact();
            if (period < 1) {
                throw fail(functionName, functionName + " period must be >= 1, was " + period);
            }
            return period;
        } catch (ArithmeticException e) {
            throw fail(functionName, functionName + " period must be an integer");
        }
    }

    private BigDecimal latest(String functionName, BigDecimal[] series) {
        if (series.length == 0 || series[series.length - 1] == null) {
            throw fail(functionName,
                    "insufficient bar history to evaluate " + functionName + " at this bar");
        }
        return series[series.length - 1];
    }

    private List<BigDecimal> closes() {
        return bars.stream().map(OHLCBar::close).toList();
    }

    private List<BigDecimal> highs() {
        return bars.stream().map(OHLCBar::high).toList();
    }

    private List<BigDecimal> lows() {
        return bars.stream().map(OHLCBar::low).toList();
    }

    private DslEvaluationException fail(String functionName, String message) {
        return new DslEvaluationException(strategyName, 0, barTime, barIndex, functionName, message);
    }
}
