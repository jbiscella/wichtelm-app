package net.jacopobiscella.wichtelm.strategy;

import java.util.Map;
import java.util.Set;

/**
 * The closed v1 vocabulary of the strategy DSL (CLAUDE.md section 3.7): market
 * variables, trade-context variables, comparison-operator words, and the
 * built-in function/indicator catalog with declared arities.
 */
public final class BuiltinCatalog {

    /** Arity sentinel for functions whose argument count is left unspecified by the spec. */
    public static final int VARIADIC = -1;

    public static final Set<String> MARKET_VARIABLES =
            Set.of("open", "high", "low", "close", "volume", "bar_time", "bar_index");

    // entry_time intentionally omitted — see CLAUDE.md §15 (reserved pending
    // a time-typed expression sub-language). Numeric epoch exposure is poor
    // UX and the runtime has never resolved it; keeping it parser-accepted
    // would crash any strategy that referenced it at evaluation time.
    public static final Set<String> TRADE_CONTEXT_VARIABLES =
            Set.of("entry_price", "position_size");

    /** English-prose comparison-operator words; never treated as identifiers. */
    public static final Set<String> OPERATOR_WORDS =
            Set.of("crosses", "below", "above", "is", "drops", "rises", "exceeds");

    /** Built-in function/indicator name to required arity ({@link #VARIADIC} when unspecified). */
    public static final Map<String, Integer> FUNCTIONS = Map.ofEntries(
            Map.entry("sma", 1),
            Map.entry("ema", 1),
            Map.entry("rsi", 1),
            Map.entry("atr", 1),
            Map.entry("stddev", 1),
            Map.entry("macd_line", 3),
            Map.entry("macd_signal", 3),
            Map.entry("macd_histogram", 3),
            Map.entry("highest_high", 1),
            Map.entry("lowest_low", 1),
            Map.entry("highest_close", 1),
            Map.entry("lowest_close", 1),
            Map.entry("avg_volume", 1),
            Map.entry("ha_bullish_reversal", 1),
            Map.entry("ha_bearish_reversal", 1),
            Map.entry("ha_strong", VARIADIC),
            Map.entry("ha_strong_bullish", 0),
            Map.entry("ha_strong_bearish", 0),
            Map.entry("ha_doji", VARIADIC),
            Map.entry("rsi_crosses_50", 0),
            Map.entry("rsi_overbought", 1),
            Map.entry("rsi_oversold", 1),
            Map.entry("macd_bullish_cross", 0),
            Map.entry("macd_bearish_cross", 0),
            Map.entry("macd_zero_cross_up", 0),
            Map.entry("macd_zero_cross_down", 0),
            // MA trend filter Tier B primitives (ha-track 0.52). Eight price-vs-MA
            // (one period arg) + three MA-vs-MA (two period args).
            Map.entry("price_above_sma", 1),
            Map.entry("price_below_sma", 1),
            Map.entry("price_above_ema", 1),
            Map.entry("price_below_ema", 1),
            Map.entry("price_crosses_above_sma", 1),
            Map.entry("price_crosses_below_sma", 1),
            Map.entry("price_crosses_above_ema", 1),
            Map.entry("price_crosses_below_ema", 1),
            Map.entry("sma_above_ema", 2),
            Map.entry("sma_crosses_above_ema", 2),
            Map.entry("sma_crosses_below_ema", 2),
            // Pivot point Tier B primitives (ha-track 0.52). The single argument
            // is a symbolic STANDARD pivot LEVEL token (P, R1.., S1..), not a
            // numeric period — validated against PIVOT_LEVELS, not as a number.
            Map.entry("price_above_pivot", 1),
            Map.entry("price_below_pivot", 1),
            Map.entry("price_crosses_above_pivot", 1),
            Map.entry("price_crosses_below_pivot", 1));

    /** The four pivot Tier B primitives, whose lone argument is a symbolic level token. */
    public static final Set<String> PIVOT_PRIMITIVES = Set.of(
            "price_above_pivot", "price_below_pivot",
            "price_crosses_above_pivot", "price_crosses_below_pivot");

    // STANDARD daily pivot level set (v1). CAMARILLA's R4/S4 and the WOODIE /
    // CAMARILLA variants are out of scope — see CLAUDE.md §3.7.
    public static final Set<String> PIVOT_LEVELS =
            Set.of("P", "R1", "R2", "R3", "S1", "S2", "S3");

    private BuiltinCatalog() {
    }

    public static boolean isPivotPrimitive(String name) {
        return PIVOT_PRIMITIVES.contains(name);
    }

    public static boolean isMarketVariable(String name) {
        return MARKET_VARIABLES.contains(name);
    }

    public static boolean isTradeContextVariable(String name) {
        return TRADE_CONTEXT_VARIABLES.contains(name);
    }

    public static boolean isOperatorWord(String word) {
        return OPERATOR_WORDS.contains(word);
    }

    public static boolean isFunction(String name) {
        return FUNCTIONS.containsKey(name);
    }
}
