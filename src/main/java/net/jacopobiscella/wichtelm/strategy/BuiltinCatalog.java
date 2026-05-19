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

    public static final Set<String> TRADE_CONTEXT_VARIABLES =
            Set.of("entry_price", "entry_time", "position_size");

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
            Map.entry("highest", 2),
            Map.entry("lowest", 2),
            Map.entry("avg_volume", 1),
            Map.entry("ha_bullish_reversal", 1),
            Map.entry("ha_bearish_reversal", 1),
            Map.entry("ha_strong", VARIADIC),
            Map.entry("ha_doji", VARIADIC),
            Map.entry("price_above_ma", VARIADIC),
            Map.entry("price_crosses_ma", VARIADIC),
            Map.entry("rsi_crosses_50", 0),
            Map.entry("rsi_overbought", 1),
            Map.entry("rsi_oversold", 1),
            Map.entry("macd_bullish_cross", 0),
            Map.entry("macd_bearish_cross", 0),
            Map.entry("macd_zero_cross_up", 0),
            Map.entry("macd_zero_cross_down", 0));

    private BuiltinCatalog() {
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
