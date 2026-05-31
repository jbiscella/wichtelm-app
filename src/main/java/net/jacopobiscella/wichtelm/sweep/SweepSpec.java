package net.jacopobiscella.wichtelm.sweep;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * The resolved plan for a parameter sweep (CLAUDE.md section 18): the per-axis
 * value sets (materialized, in declaration order), the ranking objective, how
 * many top combinations to surface, and the cap on total combinations.
 *
 * @param axes        materialized value sets per swept parameter, in declaration order
 * @param objective   the metric to rank combinations by (higher is better)
 * @param top         how many leading combinations to surface (>= 1)
 * @param maxCombos   the maximum number of combinations permitted (>= 1)
 */
public record SweepSpec(Map<String, List<BigDecimal>> axes,
                        SweepObjective objective,
                        int top,
                        int maxCombos) {

    public SweepSpec {
        // Preserve axis order: callers pass a LinkedHashMap.
        axes = java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(axes));
        if (objective == null) {
            throw new IllegalArgumentException("objective");
        }
        if (top < 1) {
            throw new IllegalArgumentException("top must be >= 1, was " + top);
        }
        if (maxCombos < 1) {
            throw new IllegalArgumentException("maxCombos must be >= 1, was " + maxCombos);
        }
    }

    /** The number of combinations the grid will expand to (the product of axis sizes). */
    public long combinationCount() {
        long product = 1;
        for (List<BigDecimal> values : axes.values()) {
            product *= values.size();
        }
        return product;
    }
}
