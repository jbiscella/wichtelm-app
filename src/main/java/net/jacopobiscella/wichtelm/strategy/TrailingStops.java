package net.jacopobiscella.wichtelm.strategy;

import java.util.regex.Pattern;

/**
 * Shared semantics for {@code trailing_stop} expressions (§3.4.1), so the
 * runtime evaluator and the HTML report agree on how a trailing clause behaves
 * instead of each re-deriving it (a past divergence: the report used a naive
 * {@code contains("atr_value")} substring test while the runtime matched the
 * call form, so a percentage-mode stop whose parameter merely contained the
 * letters {@code atr_value} — e.g. {@code atr_value_pct} — was misclassified).
 */
public final class TrailingStops {

    private TrailingStops() {
    }

    /** {@code atr_value(} by name, any argument form — for distance-mode detection. */
    private static final Pattern ATR_VALUE_CALL = Pattern.compile("atr_value\\s*\\(");

    /**
     * A {@code trailing_stop} is ATR-distance mode iff its expression calls
     * {@code atr_value(...)}; otherwise it is percentage mode (§3.4.1). Matches
     * the call by name + {@code (} regardless of the argument form, so a valid
     * whole-number-decimal period like {@code atr_value(14.0)} (accepted by P21)
     * is still detected, while a mere identifier containing the substring
     * {@code atr_value} (e.g. a parameter {@code atr_value_pct}) is NOT.
     */
    public static boolean isAtrDistanceMode(String expression) {
        return ATR_VALUE_CALL.matcher(expression).find();
    }
}
