package net.jacopobiscella.wichtelm.config;

import net.jacopobiscella.wichtelm.error.SweepConfigException;
import net.jacopobiscella.wichtelm.strategy.ParameterType;
import net.jacopobiscella.wichtelm.strategy.ParsedStrategy;
import net.jacopobiscella.wichtelm.strategy.StrategyParameter;

import java.math.BigDecimal;
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
            case SweepDefinition.ValueList list -> list.values();
            case SweepDefinition.Range range -> expandRange(name, range, type, config, maxCombos);
        };
    }

    private static List<BigDecimal> expandRange(String name, SweepDefinition.Range range,
                                                ParameterType type, BacktestConfig config,
                                                int maxCombos) {
        if (type == ParameterType.INTEGER) {
            // An integer axis must materialize whole values, so from / to / step
            // all have to be whole — checking only the step would let a range like
            // {from=1.5, to=3.5, step=1} push fractional values into the backtest.
            requireWhole(name, "from", range.from(), config);
            requireWhole(name, "to", range.to(), config);
            requireWhole(name, "step", range.step(), config);
        }
        List<BigDecimal> values = new ArrayList<>();
        // Honor the inclusive 'to' bound within a tiny fraction of the step so a
        // decimal endpoint that lands fractionally past 'to' due to DECIMAL64
        // rounding is kept — but never emit a value beyond the declared 'to'. The
        // tolerance is scaled to the step (not the endpoint magnitude) so it can
        // never rival a whole step even for very large endpoints.
        BigDecimal inclusiveBound = range.to().add(endpointTolerance(range.step()), DECIMAL);
        for (BigDecimal value = range.from();
             value.compareTo(inclusiveBound) <= 0;
             value = value.add(range.step(), DECIMAL)) {
            values.add(type == ParameterType.INTEGER ? value.stripTrailingZeros() : value);
            if (values.size() > maxCombos) {
                throw new SweepConfigException(config.configPath().toString(),
                        "sweep." + name, "C15",
                        "sweep axis '" + name + "' materializes more than the cap of " + maxCombos
                                + " combinations; narrow the range or raise --max-combos");
            }
        }
        return values;
    }

    /**
     * A near-equality tolerance for the inclusive endpoint: a tiny fraction of the
     * step, large enough to absorb a DECIMAL64 rounding wobble at {@code to} yet
     * always far below a whole step, so a value genuinely beyond the declared
     * {@code to} is never emitted — even for large endpoints, where a
     * magnitude-relative epsilon could grow to rival the step.
     */
    private static BigDecimal endpointTolerance(BigDecimal step) {
        return step.multiply(new BigDecimal("1E-9"), DECIMAL);
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
