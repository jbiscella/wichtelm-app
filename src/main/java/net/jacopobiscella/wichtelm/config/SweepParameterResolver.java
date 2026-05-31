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
     * declaration order.
     *
     * @throws SweepConfigException if an axis names a parameter the strategy does
     *                              not declare (C13)
     */
    public static Map<String, List<BigDecimal>> resolveAxes(ParsedStrategy strategy,
                                                            BacktestConfig config) {
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
            resolved.put(name, materialize(name, entry.getValue(), type, config));
        }
        return resolved;
    }

    private static List<BigDecimal> materialize(String name, SweepDefinition.Axis axis,
                                                ParameterType type, BacktestConfig config) {
        return switch (axis) {
            case SweepDefinition.ValueList list -> list.values();
            case SweepDefinition.Range range -> expandRange(name, range, type, config);
        };
    }

    private static List<BigDecimal> expandRange(String name, SweepDefinition.Range range,
                                                ParameterType type, BacktestConfig config) {
        if (type == ParameterType.INTEGER && !isWhole(range.step())) {
            throw new SweepConfigException(config.configPath().toString(),
                    "sweep." + name + ".step", "C14",
                    "sweep step for integer parameter '" + name + "' must be a whole number, was "
                            + range.step());
        }
        List<BigDecimal> values = new ArrayList<>();
        // Honor the inclusive 'to' bound within half a step so a decimal endpoint
        // that lands fractionally past 'to' due to DECIMAL64 rounding is kept.
        BigDecimal epsilon = range.step().divide(BigDecimal.valueOf(2), DECIMAL);
        BigDecimal inclusiveBound = range.to().add(epsilon, DECIMAL);
        for (BigDecimal value = range.from();
             value.compareTo(inclusiveBound) <= 0;
             value = value.add(range.step(), DECIMAL)) {
            values.add(type == ParameterType.INTEGER ? value.stripTrailingZeros() : value);
        }
        return values;
    }

    private static boolean isWhole(BigDecimal value) {
        return value.stripTrailingZeros().scale() <= 0;
    }
}
