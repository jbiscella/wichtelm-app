package net.jacopobiscella.wichtelm.strategy;

import java.math.BigDecimal;
import java.util.Objects;

/** A {@code Parameter <name> default <value>} declaration from the Feature description block. */
public record StrategyParameter(String name, ParameterType type, BigDecimal value, int line) {

    public StrategyParameter {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(value, "value");
    }
}
