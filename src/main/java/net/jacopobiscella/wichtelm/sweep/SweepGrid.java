package net.jacopobiscella.wichtelm.sweep;

import net.jacopobiscella.wichtelm.error.SweepConfigException;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Expands a {@link SweepSpec}'s axes into the Cartesian product of parameter
 * combinations (CLAUDE.md section 18). The product is enumerated in a
 * deterministic order: the first axis varies slowest, the last varies fastest,
 * matching nested-loop / odometer order over the axes in declaration order.
 *
 * <p>The combinatorial guard runs first: if the product exceeds {@code
 * maxCombos}, no combination is produced and a {@link SweepConfigException}
 * (rule C15) is raised naming each axis size and the product, so the author can
 * narrow the ranges before any backtest runs.
 */
public final class SweepGrid {

    private SweepGrid() {
    }

    /**
     * @param configPath path used for error reporting if the cap is exceeded
     * @return every parameter combination, each an ordered map keyed in axis
     *         declaration order
     * @throws SweepConfigException if the product of axis sizes exceeds the cap (C15)
     */
    public static List<Map<String, BigDecimal>> expand(SweepSpec spec, String configPath) {
        List<String> names = new ArrayList<>(spec.axes().keySet());
        long product = spec.combinationCount();
        if (product > spec.maxCombos()) {
            throw new SweepConfigException(configPath, "sweep", "C15",
                    capMessage(spec, names, product));
        }

        List<Map<String, BigDecimal>> combinations = new ArrayList<>((int) product);
        List<List<BigDecimal>> axisValues = new ArrayList<>();
        for (String name : names) {
            axisValues.add(spec.axes().get(name));
        }
        expandInto(names, axisValues, 0, new LinkedHashMap<>(), combinations);
        return combinations;
    }

    private static void expandInto(List<String> names, List<List<BigDecimal>> axisValues,
                                   int depth, LinkedHashMap<String, BigDecimal> current,
                                   List<Map<String, BigDecimal>> out) {
        if (depth == names.size()) {
            out.add(new LinkedHashMap<>(current));
            return;
        }
        String name = names.get(depth);
        for (BigDecimal value : axisValues.get(depth)) {
            current.put(name, value);
            expandInto(names, axisValues, depth + 1, current, out);
        }
        current.remove(name);
    }

    private static String capMessage(SweepSpec spec, List<String> names, long product) {
        StringBuilder sizes = new StringBuilder();
        for (String name : names) {
            if (!sizes.isEmpty()) {
                sizes.append(" × ");
            }
            sizes.append(name).append('(').append(spec.axes().get(name).size()).append(')');
        }
        return "sweep would run " + product + " combinations (" + sizes + "), exceeding the cap of "
                + spec.maxCombos() + "; narrow the ranges or raise --max-combos";
    }
}
