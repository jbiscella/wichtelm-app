package net.jacopobiscella.wichtelm.config;

import net.jacopobiscella.wichtelm.error.SweepConfigException;
import net.jacopobiscella.wichtelm.strategy.ParameterType;
import net.jacopobiscella.wichtelm.strategy.ParsedStrategy;
import net.jacopobiscella.wichtelm.strategy.StrategyParameter;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.MathContext;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reconciles a config's {@code [sweep]} axes against the parameters declared in
 * a parsed strategy (CLAUDE.md section 18, rule C13) and materializes each axis
 * into a concrete, type-aware list of values:
 *
 * <ul>
 *   <li>An INTEGER parameter steps by whole numbers and its values are whole.</li>
 *   <li>A DECIMAL parameter steps in {@code MathContext.DECIMAL64}, with the
 *       inclusive {@code to} bound honored within a half-step epsilon so floating
 *       endpoints are not dropped.</li>
 * </ul>
 *
 * The result preserves axis declaration order, which fixes the deterministic
 * order of the expanded grid.
 */
public final class SweepParameterResolver {

    private static final MathContext DECIMAL = MathContext.DECIMAL64;

    private SweepParameterResolver() {
    }

    /**
     * Returns the materialized value sets per swept parameter, keyed in axis
     * declaration order. Equivalent to {@link #resolveAxes(ParsedStrategy,
     * BacktestConfig, int)} with no combinatorial cap — use the 3-arg form on
     * the run path so a single pathological range cannot allocate an unbounded
     * axis before the grid cap is checked.
     *
     * @throws SweepConfigException if an axis names a parameter the strategy does
     *                              not declare (C13)
     */
    public static Map<String, List<BigDecimal>> resolveAxes(ParsedStrategy strategy,
                                                            BacktestConfig config) {
        return resolveAxes(strategy, config, Integer.MAX_VALUE);
    }

    /**
     * Returns the materialized value sets per swept parameter, keyed in axis
     * declaration order, rejecting any axis whose own size already exceeds
     * {@code maxCombos}. Because the grid is the product of the axis sizes, an
     * axis larger than the cap guarantees the grid exceeds it (C15); catching it
     * here means a single wide range (e.g. {@code from=1, to=1000000000, step=1})
     * is rejected before its billion values are ever allocated, rather than after
     * {@code SweepGrid.expand} would have OOM'd materializing the product.
     *
     * @throws SweepConfigException if an axis names a parameter the strategy does
     *                              not declare (C13), or a materialized axis alone
     *                              exceeds {@code maxCombos} (C15)
     */
    public static Map<String, List<BigDecimal>> resolveAxes(ParsedStrategy strategy,
                                                            BacktestConfig config, int maxCombos) {
        if (config.sweep().isEmpty()) {
            return Map.of();
        }
        SweepDefinition sweep = config.sweep().get();
        Map<String, ParameterType> declared = new LinkedHashMap<>();
        for (StrategyParameter parameter : strategy.parameters()) {
            declared.put(parameter.name(), parameter.type());
        }

        Map<String, List<BigDecimal>> resolved = new LinkedHashMap<>();
        for (Map.Entry<String, SweepDefinition.Axis> entry : sweep.axes().entrySet()) {
            String name = entry.getKey();
            ParameterType type = declared.get(name);
            if (type == null) {
                throw new SweepConfigException(config.configPath().toString(),
                        "sweep." + name, "C13",
                        "sweep parameter '" + name + "' is not declared in the strategy");
            }
            resolved.put(name, materialize(name, entry.getValue(), type, config, maxCombos));
        }
        return resolved;
    }

    private static List<BigDecimal> materialize(String name, SweepDefinition.Axis axis,
                                                ParameterType type, BacktestConfig config,
                                                int maxCombos) {
        return switch (axis) {
            case SweepDefinition.ValueList list -> materializeList(name, list, type, config);
            case SweepDefinition.Range range -> expandRange(name, range, type, config, maxCombos);
        };
    }

    private static List<BigDecimal> materializeList(String name, SweepDefinition.ValueList list,
                                                    ParameterType type, BacktestConfig config) {
        List<BigDecimal> values = list.values();
        if (type == ParameterType.INTEGER) {
            // An integer axis carries whole values; a list entry like 1.5 must be
            // rejected here (it skips the range whole-number check) rather than
            // reaching the backtest as a malformed per-combination value.
            for (int i = 0; i < values.size(); i++) {
                requireWhole(name, "value[" + i + "]", values.get(i), config);
            }
        }
        return values;
    }

    private static List<BigDecimal> expandRange(String name, SweepDefinition.Range range,
                                                ParameterType type, BacktestConfig config,
                                                int maxCombos) {
        boolean integer = type == ParameterType.INTEGER;
        if (integer) {
            // An integer axis must materialize whole values, so from / to / step
            // all have to be whole — checking only the step would let a range like
            // {from=1.5, to=3.5, step=1} push fractional values into the backtest.
            requireWhole(name, "from", range.from(), config);
            requireWhole(name, "to", range.to(), config);
            requireWhole(name, "step", range.step(), config);
        }

        // The number of increments from 'from' to 'to' inclusive; the axis holds
        // (steps + 1) values. Counted as floor((to - from) / step) — the integer
        // part of the exact decimal quotient, with no epsilon — so a value
        // genuinely beyond 'to' is never counted (from=0,to=1,step=0.6 -> 0,0.6;
        // step=0.3333333334 -> 0, 0.3333333334, 0.6666666668) while an exact
        // endpoint is kept. divideToIntegralValue is exact for both axis types.
        BigInteger steps = range.to().subtract(range.from())
                .divideToIntegralValue(range.step()).toBigInteger();
        BigInteger size = steps.add(BigInteger.ONE);

        // Reject before materializing: an axis larger than the whole-grid cap
        // guarantees the grid exceeds it (C15). Named with its exact size and the
        // cap so the fast path is as actionable as SweepGrid.expand's C15 message.
        if (size.compareTo(BigInteger.valueOf(maxCombos)) > 0) {
            throw new SweepConfigException(config.configPath().toString(),
                    "sweep." + name, "C15",
                    "sweep axis '" + name + "' expands to " + size + " values, exceeding the cap of "
                            + maxCombos + "; narrow its from/to/step or raise --max-combos");
        }

        List<BigDecimal> values = new ArrayList<>(size.intValueExact());
        BigDecimal from = range.from();
        BigDecimal step = range.step();
        for (BigInteger k = BigInteger.ZERO; k.compareTo(steps) <= 0; k = k.add(BigInteger.ONE)) {
            // value = from + k*step, recomputed each step (no accumulation drift).
            // Integer arithmetic is exact — DECIMAL64 would round the increment
            // away once values pass its 16-digit precision.
            BigDecimal value = integer
                    ? from.add(step.multiply(new BigDecimal(k)))
                    : from.add(step.multiply(new BigDecimal(k), DECIMAL), DECIMAL);
            values.add(integer ? value.stripTrailingZeros() : value);
        }
        return values;
    }

    private static void requireWhole(String name, String field, BigDecimal value,
                                     BacktestConfig config) {
        if (!isWhole(value)) {
            throw new SweepConfigException(config.configPath().toString(),
                    "sweep." + name + "." + field, "C14",
                    "sweep " + field + " for integer parameter '" + name
                            + "' must be a whole number, was " + value);
        }
    }

    private static boolean isWhole(BigDecimal value) {
        return value.stripTrailingZeros().scale() <= 0;
    }
}
