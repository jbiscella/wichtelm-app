package net.jacopobiscella.wichtelm.runtime;

import net.jacopobiscella.wichtelm.error.DslEvaluationException;
import org.hatrack.commons.OHLCBar;
import org.hatrack.indicators.Indicators;
import org.hatrack.indicators.MacdResult;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.Instant;
import java.util.List;
import java.util.function.Function;

/**
 * Evaluates indicator function calls against the bars visible up to and
 * including the current bar, delegating the arithmetic to the ha-track
 * {@code indicators} module where it provides the calculator.
 *
 * <p>This covers the §3.7 base indicators {@code sma}, {@code ema},
 * {@code rsi}, {@code atr}, {@code stddev}, the MACD components
 * {@code macd_line}, {@code macd_signal}, {@code macd_histogram} and the
 * window aggregates {@code highest_high}, {@code lowest_low},
 * {@code highest_close}, {@code lowest_close} and {@code avg_volume}.
 * {@code stddev} and the window aggregates are computed here because the
 * {@code indicators} module exposes no standalone calculator for them.
 *
 * <p>The Tier B pattern primitives ({@code ha_doji}, {@code ha_strong},
 * {@code ha_strong_bullish}, {@code ha_strong_bearish},
 * {@code ha_bullish_reversal}, {@code ha_bearish_reversal},
 * {@code rsi_overbought}, {@code rsi_oversold}, {@code rsi_crosses_50},
 * {@code macd_bullish_cross}, {@code macd_bearish_cross},
 * {@code macd_zero_cross_up}, {@code macd_zero_cross_down}) resolve via the
 * {@link NachtkrappMatchIndex} prepass — each per-bar evaluation is an O(1)
 * membership lookup that returns {@link BigDecimal#ONE} for true,
 * {@link BigDecimal#ZERO} for false, so the boolean primitives compose with
 * the existing numeric-arithmetic / boolean-step pipeline.
 */
public final class BarIndicatorSource implements ExpressionEvaluator.IndicatorSource {

    private static final MathContext DECIMAL = MathContext.DECIMAL64;

    private final List<OHLCBar> bars;
    private final String strategyName;
    private final Instant barTime;
    private final long barIndex;
    private final NachtkrappMatchIndex matchIndex;
    private final String timeframeWire;

    public BarIndicatorSource(List<OHLCBar> bars, String strategyName,
                              Instant barTime, long barIndex) {
        this(bars, strategyName, barTime, barIndex, NachtkrappMatchIndex.empty(), "1h");
    }

    public BarIndicatorSource(List<OHLCBar> bars, String strategyName,
                              Instant barTime, long barIndex,
                              NachtkrappMatchIndex matchIndex, String timeframeWire) {
        this.bars = List.copyOf(bars);
        this.strategyName = strategyName;
        this.barTime = barTime;
        this.barIndex = barIndex;
        this.matchIndex = matchIndex;
        this.timeframeWire = timeframeWire;
    }

    @Override
    public BigDecimal evaluate(String functionName, List<BigDecimal> arguments) {
        return switch (functionName) {
            case "sma" -> latest(functionName, Indicators.sma(closes(), period(functionName, arguments)));
            case "ema" -> latest(functionName, Indicators.ema(closes(), period(functionName, arguments)));
            case "rsi" -> latest(functionName, Indicators.rsi(closes(), period(functionName, arguments)));
            case "atr" -> latest(functionName,
                    Indicators.atr(highs(), lows(), closes(), period(functionName, arguments)));
            case "stddev" -> stddev(period(functionName, arguments));
            case "macd_line" -> latest(functionName, macd(functionName, arguments).macdLine());
            case "macd_signal" -> latest(functionName, macd(functionName, arguments).signalLine());
            case "macd_histogram" -> latest(functionName, macd(functionName, arguments).histogram());
            case "highest_high" -> windowExtreme(
                    window(functionName, period(functionName, arguments)), OHLCBar::high, true);
            case "lowest_low" -> windowExtreme(
                    window(functionName, period(functionName, arguments)), OHLCBar::low, false);
            case "highest_close" -> windowExtreme(
                    window(functionName, period(functionName, arguments)), OHLCBar::close, true);
            case "lowest_close" -> windowExtreme(
                    window(functionName, period(functionName, arguments)), OHLCBar::close, false);
            case "avg_volume" -> avgVolume(window(functionName, period(functionName, arguments)));
            case "ha_doji", "ha_strong", "ha_strong_bullish", "ha_strong_bearish",
                 "ha_bullish_reversal", "ha_bearish_reversal",
                 "rsi_overbought", "rsi_oversold", "rsi_crosses_50",
                 "macd_bullish_cross", "macd_bearish_cross",
                 "macd_zero_cross_up", "macd_zero_cross_down",
                 "price_above_sma", "price_below_sma", "price_above_ema", "price_below_ema",
                 "price_crosses_above_sma", "price_crosses_below_sma",
                 "price_crosses_above_ema", "price_crosses_below_ema",
                 "sma_above_ema", "sma_crosses_above_ema", "sma_crosses_below_ema"
                    -> tierB(functionName, arguments);
            default -> throw fail(functionName,
                    "indicator '" + functionName + "' is not implemented in this increment");
        };
    }

    private BigDecimal tierB(String name, List<BigDecimal> arguments) {
        NachtkrappMatchIndex.Key key = new NachtkrappMatchIndex.Key(name, arguments, timeframeWire);
        if (!matchIndex.hasKey(key)) {
            // Should not happen — the prepass walks the AST and pre-registers
            // every Tier B call. A miss means the call was synthesised at
            // evaluation time (e.g. through a parameter override unseen at
            // setup), which is not supported.
            throw fail(name,
                    "Tier B primitive '" + name + "' was not pre-indexed; the strategy "
                            + "must declare it statically in a Background series or a "
                            + "Scenario step so the prepass can scan it");
        }
        return matchIndex.matches(key, barTime) ? BigDecimal.ONE : BigDecimal.ZERO;
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

    /** Computes the MACD result for the {@code (fast, slow, signal)} period arguments. */
    private MacdResult macd(String functionName, List<BigDecimal> arguments) {
        if (arguments.size() != 3) {
            throw fail(functionName,
                    functionName + " expects fast, slow and signal period arguments");
        }
        int fast = macdPeriod(functionName, arguments.get(0), "fast");
        int slow = macdPeriod(functionName, arguments.get(1), "slow");
        int signal = macdPeriod(functionName, arguments.get(2), "signal");
        if (fast >= slow) {
            throw fail(functionName, functionName + " fast period (" + fast
                    + ") must be below the slow period (" + slow + ")");
        }
        return Indicators.macd(closes(), fast, slow, signal);
    }

    private int macdPeriod(String functionName, BigDecimal value, String role) {
        try {
            int period = value.intValueExact();
            if (period < 1) {
                throw fail(functionName,
                        functionName + " " + role + " period must be >= 1, was " + period);
            }
            return period;
        } catch (ArithmeticException e) {
            throw fail(functionName, functionName + " " + role + " period must be an integer");
        }
    }

    private BigDecimal latest(String functionName, BigDecimal[] series) {
        if (series.length == 0 || series[series.length - 1] == null) {
            throw new IndicatorWarmupException(
                    "insufficient bar history to evaluate " + functionName + " at this bar");
        }
        return series[series.length - 1];
    }

    /** Population standard deviation of the most recent {@code period} close prices. */
    private BigDecimal stddev(int period) {
        List<BigDecimal> closes = closes();
        if (closes.size() < period) {
            throw new IndicatorWarmupException(
                    "insufficient bar history to evaluate stddev at this bar");
        }
        List<BigDecimal> window = closes.subList(closes.size() - period, closes.size());
        BigDecimal divisor = BigDecimal.valueOf(period);
        BigDecimal sum = BigDecimal.ZERO;
        for (BigDecimal value : window) {
            sum = sum.add(value, DECIMAL);
        }
        BigDecimal mean = sum.divide(divisor, DECIMAL);
        BigDecimal varianceSum = BigDecimal.ZERO;
        for (BigDecimal value : window) {
            BigDecimal deviation = value.subtract(mean, DECIMAL);
            varianceSum = varianceSum.add(deviation.multiply(deviation, DECIMAL), DECIMAL);
        }
        return varianceSum.divide(divisor, DECIMAL).sqrt(DECIMAL);
    }

    /** The last {@code period} bars ending at (and including) the current bar. */
    private List<OHLCBar> window(String functionName, int period) {
        if (bars.size() < period) {
            throw new IndicatorWarmupException(
                    "insufficient bar history to evaluate " + functionName + " at this bar");
        }
        return bars.subList(bars.size() - period, bars.size());
    }

    /** Maximum (when {@code max}) or minimum of {@code field} over {@code window}. */
    private static BigDecimal windowExtreme(List<OHLCBar> window,
                                            Function<OHLCBar, BigDecimal> field, boolean max) {
        BigDecimal result = field.apply(window.getFirst());
        for (OHLCBar bar : window) {
            BigDecimal value = field.apply(bar);
            if (max ? value.compareTo(result) > 0 : value.compareTo(result) < 0) {
                result = value;
            }
        }
        return result;
    }

    /** Mean bar volume over {@code window}; volume must be present on every bar. */
    private BigDecimal avgVolume(List<OHLCBar> window) {
        BigDecimal sum = BigDecimal.ZERO;
        for (OHLCBar bar : window) {
            sum = sum.add(bar.volume().orElseThrow(() -> fail("avg_volume",
                    "avg_volume requires volume data, absent for a bar in the window")), DECIMAL);
        }
        return sum.divide(BigDecimal.valueOf(window.size()), DECIMAL);
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
