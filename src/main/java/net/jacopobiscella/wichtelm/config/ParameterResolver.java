package net.jacopobiscella.wichtelm.config;

import net.jacopobiscella.wichtelm.error.ConfigParseException;
import net.jacopobiscella.wichtelm.strategy.ParsedStrategy;
import net.jacopobiscella.wichtelm.strategy.StrategyParameter;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Reconciles a {@link BacktestConfig}'s {@code [parameters]} overrides against
 * the parameters declared in a parsed strategy (CLAUDE.md rule C7). Overrides
 * take precedence over strategy defaults; missing parameters keep their default.
 */
public final class ParameterResolver {

    private static final MathContext DECIMAL = MathContext.DECIMAL64;

    private ParameterResolver() {
    }

    /** Returns the effective parameter values, keyed by declared parameter name. */
    public static Map<String, BigDecimal> resolve(ParsedStrategy strategy, BacktestConfig config) {
        Set<String> declared = strategy.parameters().stream()
                .map(StrategyParameter::name)
                .collect(Collectors.toSet());

        for (String key : config.parameterOverrides().keySet()) {
            if (!declared.contains(key)) {
                throw new ConfigParseException(config.configPath().toString(),
                        "parameters." + key, "C7",
                        "parameter override does not match any strategy parameter: " + key);
            }
        }

        Map<String, BigDecimal> resolved = new LinkedHashMap<>();
        for (StrategyParameter parameter : strategy.parameters()) {
            Object override = config.parameterOverrides().get(parameter.name());
            resolved.put(parameter.name(),
                    override != null ? toBigDecimal(override, parameter.name(), config) : parameter.value());
        }
        return resolved;
    }

    private static BigDecimal toBigDecimal(Object value, String parameterName, BacktestConfig config) {
        return switch (value) {
            case Long l -> new BigDecimal(l, DECIMAL);
            case Double d -> new BigDecimal(d, DECIMAL);
            default -> throw new ConfigParseException(config.configPath().toString(),
                    "parameters." + parameterName, "C7",
                    "parameter override must be a numeric value: " + parameterName);
        };
    }
}
