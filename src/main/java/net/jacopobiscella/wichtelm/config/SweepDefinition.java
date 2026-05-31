package net.jacopobiscella.wichtelm.config;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Parsed {@code [sweep]} section of a per-backtest TOML config (CLAUDE.md
 * section 18). Each declared parameter maps to one {@link Axis} — either a
 * numeric range ({@code from} / {@code to} / {@code step}) or an explicit list
 * of values. The axis order is the declaration order in the TOML file, which
 * fixes the deterministic order of the expanded grid.
 *
 * <p>The structural rules C12 (no overlap with {@code [parameters]}) and C14
 * (well-formed ranges and non-empty lists) are enforced by {@link ConfigParser}
 * at parse time. C13 (every axis names a declared strategy parameter) needs the
 * parsed strategy and is enforced by {@code SweepParameterResolver}.
 */
public record SweepDefinition(Map<String, Axis> axes) {

    public SweepDefinition {
        axes = Map.copyOf(axes);
    }

    public boolean isEmpty() {
        return axes.isEmpty();
    }

    /** Parameter names with a declared axis, in TOML declaration order. */
    public List<String> parameterNames() {
        return List.copyOf(axes.keySet());
    }

    /**
     * One swept parameter's value set: either an inclusive numeric range with a
     * positive step, or an explicit non-empty list of values. Exactly one of
     * {@code range} / {@code list} is non-null.
     */
    public sealed interface Axis permits Range, ValueList {
    }

    /**
     * An inclusive {@code [from, to]} range stepped by {@code step}. The
     * concrete values are materialized later (type-aware: integer parameters
     * step by whole numbers, decimal parameters in {@code DECIMAL64}), because
     * the parameter's declared type lives in the strategy, not the config.
     */
    public record Range(BigDecimal from, BigDecimal to, BigDecimal step) implements Axis {
        public Range {
            Objects.requireNonNull(from, "from");
            Objects.requireNonNull(to, "to");
            Objects.requireNonNull(step, "step");
        }
    }

    /** An explicit, non-empty list of values in declaration order. */
    public record ValueList(List<BigDecimal> values) implements Axis {
        public ValueList {
            values = List.copyOf(values);
        }
    }
}
