package net.jacopobiscella.wichtelm.strategy;

import net.jacopobiscella.wichtelm.error.StrategyParseException;
import org.hatrack.commons.Timeframe;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.math.MathContext;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Hand-written, line-oriented parser for {@code .strat} strategy files. JDK-only,
 * independent of any Gherkin library. Enforces parse-time rules P1-P21
 * (CLAUDE.md section 4); every violation throws {@link StrategyParseException}
 * carrying the rule identifier and source location.
 */
public final class StrategyParser {

    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    private static final Pattern PARAMETER_LINE =
            Pattern.compile("Parameter\\s+([A-Za-z_][A-Za-z0-9_]*)\\s+default\\s+(\\S+)");
    private static final Pattern SERIES_LINE =
            Pattern.compile("a series\\s+([A-Za-z_][A-Za-z0-9_]*)\\s+defined as\\s+(.+?)(?:\\s+on\\s+(\\S+))?");
    private static final Set<String> STEP_KEYWORDS = Set.of("Given", "When", "Then", "And", "But");
    private static final MathContext DECIMAL = MathContext.DECIMAL64;

    private final String filePath;
    private final String[] lines;

    private StrategyParser(String filePath, String content) {
        this.filePath = filePath;
        this.lines = content.replace("\r\n", "\n").replace("\r", "\n").split("\n", -1);
    }

    /** Parses a {@code .strat} file from disk. */
    public static ParsedStrategy parse(Path file) {
        try {
            return parse(Files.readString(file), file.toString());
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read strategy file: " + file, e);
        }
    }

    /** Parses {@code .strat} content already loaded into memory. */
    public static ParsedStrategy parse(String content, String filePath) {
        return new StrategyParser(filePath, content).parseInternal();
    }

    private ParsedStrategy parseInternal() {
        int featureCount = 0;
        for (String line : lines) {
            if (line.strip().startsWith("Feature:")) {
                featureCount++;
            }
        }

        int i = nextSignificant(0);
        if (i >= lines.length || !lines[i].strip().startsWith("Feature:")) {
            throw fail("P1", 1, 1, "strategy file must begin with a Feature: header");
        }
        if (featureCount > 1) {
            throw fail("P1", i + 1, 1, "strategy file must contain exactly one Feature: block");
        }
        String featureName = lines[i].strip().substring("Feature:".length()).strip();
        i++;

        Timeframe primaryTimeframe = null;
        List<StrategyParameter> parameters = new ArrayList<>();
        Set<String> parameterNames = new HashSet<>();

        while (i < lines.length) {
            String t = lines[i].strip();
            if (isSkippable(t) || isSectionHeader(t)) {
                if (isSectionHeader(t)) {
                    break;
                }
                i++;
                continue;
            }
            if (t.startsWith("Primary timeframe:")) {
                if (primaryTimeframe != null) {
                    throw fail("P2", i + 1, 1, "duplicate Primary timeframe declaration");
                }
                primaryTimeframe = parseTimeframe(t.substring("Primary timeframe:".length()).strip(),
                        i + 1, "P2");
            } else if (firstWord(t).equals("Parameter")) {
                StrategyParameter p = parseParameter(t, i + 1);
                if (!parameterNames.add(p.name())) {
                    throw fail("P4", i + 1, 1, "duplicate parameter declaration: " + p.name());
                }
                parameters.add(p);
            }
            i++;
        }
        if (primaryTimeframe == null) {
            throw fail("P2", 1, 1, "Feature description must declare exactly one Primary timeframe");
        }

        List<BackgroundSeries> backgroundSeries = new ArrayList<>();
        Set<String> seriesNames = new HashSet<>();
        if (i < lines.length && lines[i].strip().startsWith("Background:")) {
            i++;
            while (i < lines.length) {
                String t = lines[i].strip();
                if (isSkippable(t)) {
                    i++;
                    continue;
                }
                if (t.startsWith("Scenario:")) {
                    break;
                }
                backgroundSeries.add(parseSeries(t, i + 1, primaryTimeframe, parameterNames, seriesNames));
                i++;
            }
        }

        List<StrategyScenario> scenarios = new ArrayList<>();
        Set<String> scenarioNames = new HashSet<>();
        while (i < lines.length) {
            String t = lines[i].strip();
            if (isSkippable(t)) {
                i++;
                continue;
            }
            if (!t.startsWith("Scenario:")) {
                throw fail("P10", i + 1, 1, "unexpected line outside of a Scenario: " + t);
            }
            ScenarioBlock block = collectScenario(i);
            if (!scenarioNames.add(block.name())) {
                throw fail("P22", block.headerLine(), 1,
                        "duplicate Scenario name: " + block.name());
            }
            scenarios.add(parseScenario(block, parameterNames, seriesNames));
            i = block.nextIndex();
        }

        return new ParsedStrategy(featureName, primaryTimeframe, parameters, backgroundSeries, scenarios);
    }

    // ----- description-block helpers -------------------------------------------------

    private StrategyParameter parseParameter(String text, int line) {
        Matcher m = PARAMETER_LINE.matcher(text);
        if (!m.matches()) {
            throw fail("P3", line, 1,
                    "Parameter line must match 'Parameter <name> default <value>'");
        }
        String name = m.group(1);
        String literal = m.group(2);
        ParameterType type;
        BigDecimal value;
        try {
            value = new BigDecimal(literal, DECIMAL);
        } catch (NumberFormatException e) {
            throw fail("P3", line, 1, "Parameter default value must be a numeric literal: " + literal);
        }
        type = literal.matches("[+-]?\\d+") ? ParameterType.INTEGER : ParameterType.DECIMAL;
        return new StrategyParameter(name, type, value, line);
    }

    private Timeframe parseTimeframe(String wire, int line, String rule) {
        try {
            return Timeframe.fromWire(wire);
        } catch (RuntimeException e) {
            throw fail(rule, line, 1, "unrecognized timeframe: " + wire);
        }
    }

    // ----- background ----------------------------------------------------------------

    private BackgroundSeries parseSeries(String stepLine, int line, Timeframe primary,
                                         Set<String> parameterNames, Set<String> seriesNames) {
        String keyword = firstWord(stepLine);
        if (!keyword.equals("Given") && !keyword.equals("And")) {
            throw fail("P5", line, 1, "Background step must start with Given or And");
        }
        String body = stepLine.substring(keyword.length()).strip();
        Matcher m = SERIES_LINE.matcher(body);
        if (!m.matches()) {
            throw fail("P6", line, 1,
                    "Background series must match 'Given a series <name> defined as <expression>'");
        }
        String name = m.group(1);
        String expression = m.group(2).strip();
        Optional<Timeframe> timeframe = Optional.empty();
        if (m.group(3) != null) {
            Timeframe higher = parseTimeframe(m.group(3), line, "P8");
            if (!Timeframes.isStrictlyHigher(higher, primary)) {
                throw fail("P8", line, 1,
                        "Background series timeframe must be strictly higher than the primary timeframe");
            }
            timeframe = Optional.of(higher);
        }
        if (BuiltinCatalog.isMarketVariable(name) || BuiltinCatalog.isTradeContextVariable(name)) {
            throw fail("P7", line, 1, "series name collides with a built-in variable: " + name);
        }
        if (!seriesNames.add(name)) {
            throw fail("P7", line, 1, "duplicate Background series name: " + name);
        }
        analyzeExpression(expression, line, ExprContext.BACKGROUND, parameterNames, seriesNames);
        return new BackgroundSeries(name, expression, timeframe, line);
    }

    // ----- scenarios -----------------------------------------------------------------

    private record RawStep(String keyword, String text, int line) {
    }

    private record ScenarioBlock(String name, int headerLine, List<RawStep> steps, int nextIndex) {
    }

    private ScenarioBlock collectScenario(int headerIndex) {
        String name = lines[headerIndex].strip().substring("Scenario:".length()).strip();
        List<RawStep> steps = new ArrayList<>();
        int i = headerIndex + 1;
        while (i < lines.length) {
            String t = lines[i].strip();
            if (isSkippable(t)) {
                i++;
                continue;
            }
            if (t.startsWith("Scenario:")) {
                break;
            }
            String keyword = firstWord(t);
            if (!STEP_KEYWORDS.contains(keyword)) {
                throw fail("P10", i + 1, 1, "unrecognized step (expected Given/When/Then/And/But): " + t);
            }
            steps.add(new RawStep(keyword, t.substring(keyword.length()).strip(), i + 1));
            i++;
        }
        return new ScenarioBlock(name, headerIndex + 1, steps, i);
    }

    private StrategyScenario parseScenario(ScenarioBlock block, Set<String> parameterNames,
                                           Set<String> seriesNames) {
        List<RawStep> steps = block.steps();
        if (steps.isEmpty()) {
            throw fail("P9", block.headerLine(), 1, "Scenario must contain at least one step");
        }

        int thenIndex = -1;
        for (int s = 0; s < steps.size(); s++) {
            if (steps.get(s).keyword().equals("Then")) {
                if (thenIndex >= 0) {
                    throw fail("P10", steps.get(s).line(), 1, "Scenario must contain exactly one Then step");
                }
                thenIndex = s;
            }
        }
        if (thenIndex < 0) {
            throw fail("P10", block.headerLine(), 1,
                    "Scenario must terminate with 'Then <long_entry|long_exit|short_entry|short_exit>'");
        }
        RawStep thenStep = steps.get(thenIndex);
        FirstClassCondition condition = FirstClassCondition.fromWire(thenStep.text().strip())
                .orElseThrow(() -> fail("P10", thenStep.line(), 1,
                        "Then must be one of long_entry/long_exit/short_entry/short_exit, was: "
                                + thenStep.text()));

        RawStep first = steps.getFirst();
        PositionPrecondition precondition = PositionPrecondition.fromText(first.text())
                .filter(p -> first.keyword().equals("Given"))
                .orElseThrow(() -> fail("P18", first.line(), 1,
                        "Scenario must begin with a position precondition (Given no open position / "
                                + "a long position is open / a short position is open)"));

        ExprContext conditionContext = condition.isEntry()
                ? ExprContext.CONDITION_ENTRY : ExprContext.CONDITION_EXIT;
        for (int s = 1; s < thenIndex; s++) {
            RawStep step = steps.get(s);
            analyzeExpression(step.text(), step.line(), conditionContext, parameterNames, seriesNames);
        }

        Optional<String> stopLoss = Optional.empty();
        Optional<String> takeProfit = Optional.empty();
        for (int s = thenIndex + 1; s < steps.size(); s++) {
            RawStep step = steps.get(s);
            String text = step.text();
            String expression;
            boolean isStop;
            if (text.startsWith("with stop_loss at ")) {
                expression = text.substring("with stop_loss at ".length()).strip();
                isStop = true;
            } else if (text.startsWith("with take_profit at ")) {
                expression = text.substring("with take_profit at ".length()).strip();
                isStop = false;
            } else {
                throw fail("P10", step.line(), 1,
                        "only 'And with stop_loss at' / 'And with take_profit at' may follow Then");
            }
            if (!condition.isEntry()) {
                throw fail("P12", step.line(), 1,
                        "stop_loss/take_profit clauses are not allowed on exit Scenarios");
            }
            analyzeExpression(expression, step.line(), ExprContext.STOP_TAKE, parameterNames, seriesNames);
            if (isStop) {
                stopLoss = Optional.of(expression);
            } else {
                takeProfit = Optional.of(expression);
            }
        }

        switch (precondition) {
            case NO_OPEN_POSITION -> {
                if (!condition.isEntry()) {
                    throw fail("P18", first.line(), 1,
                            "'Given no open position' must terminate with long_entry or short_entry");
                }
            }
            case LONG_POSITION_OPEN -> {
                if (condition != FirstClassCondition.LONG_EXIT) {
                    throw fail("P19", first.line(), 1,
                            "'Given a long position is open' must terminate with long_exit");
                }
            }
            case SHORT_POSITION_OPEN -> {
                if (condition != FirstClassCondition.SHORT_EXIT) {
                    throw fail("P20", first.line(), 1,
                            "'Given a short position is open' must terminate with short_exit");
                }
            }
        }

        List<StrategyStep> conditionSteps = new ArrayList<>();
        for (int s = 1; s < thenIndex; s++) {
            RawStep step = steps.get(s);
            conditionSteps.add(new StrategyStep(step.keyword(), step.text(), step.line()));
        }
        return new StrategyScenario(block.name(), precondition, conditionSteps, condition,
                stopLoss, takeProfit, block.headerLine());
    }

    // ----- expression analysis -------------------------------------------------------

    private enum ExprContext {
        CONDITION_ENTRY,
        CONDITION_EXIT,
        BACKGROUND,
        STOP_TAKE
    }

    /**
     * Validates an expression for balanced parentheses (P15), known functions and
     * arity (P14), identifier resolution (P13), trade-context placement (P16/P17),
     * and static numeric ranges (P21).
     */
    private void analyzeExpression(String expr, int line, ExprContext context,
                                   Set<String> parameterNames, Set<String> seriesNames) {
        int depth = 0;
        int i = 0;
        int n = expr.length();
        while (i < n) {
            char c = expr.charAt(i);
            if (c == '(') {
                depth++;
                i++;
            } else if (c == ')') {
                depth--;
                if (depth < 0) {
                    throw fail("P15", line, i + 1, "unbalanced parentheses in expression");
                }
                i++;
            } else if (Character.isLetter(c) || c == '_') {
                int j = i;
                while (j < n && (Character.isLetterOrDigit(expr.charAt(j)) || expr.charAt(j) == '_')) {
                    j++;
                }
                String word = expr.substring(i, j);
                int k = j;
                while (k < n && expr.charAt(k) == ' ') {
                    k++;
                }
                if (k < n && expr.charAt(k) == '(') {
                    validateFunction(word, expr, k, line, context, parameterNames);
                } else if (!BuiltinCatalog.isOperatorWord(word)) {
                    validateIdentifier(word, line, context, parameterNames, seriesNames);
                }
                i = j;
            } else {
                i++;
            }
        }
        if (depth != 0) {
            throw fail("P15", line, 1, "unbalanced parentheses in expression");
        }
    }

    private void validateFunction(String name, String expr, int openParen, int line,
                                  ExprContext context, Set<String> parameterNames) {
        if (name.equals("atr_value")) {
            // INC2: atr_value(period) is a stop/take-only frozen-ATR accessor —
            // the one function P16 admits inside stop_loss / take_profit. It is
            // evaluated once at the entry fill bar and frozen for the trade.
            if (context != ExprContext.STOP_TAKE) {
                throw fail("P16", line, 1,
                        "atr_value(...) is only valid in stop_loss/take_profit expressions; "
                                + "use atr(...) in conditions");
            }
            List<String> atrArgs = extractArguments(expr, openParen);
            if (atrArgs.size() != 1) {
                throw fail("P14", line, 1, "atr_value expects 1 argument but got " + atrArgs.size());
            }
            requirePositivePeriod(atrArgs.getFirst(), name, line);
            return;
        }
        if (context == ExprContext.STOP_TAKE) {
            throw fail("P16", line, 1,
                    "stop_loss/take_profit expressions must not reference functions or indicators: "
                            + name);
        }
        if (!BuiltinCatalog.isFunction(name)) {
            throw fail("P14", line, 1, "unknown function: " + name);
        }
        List<String> arguments = extractArguments(expr, openParen);
        int required = BuiltinCatalog.FUNCTIONS.get(name);
        if (required != BuiltinCatalog.VARIADIC && arguments.size() != required) {
            throw fail("P14", line, 1, "function " + name + " expects " + required
                    + " argument(s) but got " + arguments.size());
        }
        checkNumericRanges(name, arguments, line);
        checkTierBArgumentsAreResolvable(name, arguments, parameterNames, line);
    }

    /**
     * Tier B boolean primitives evaluate via a one-shot nachtkrapp pre-pass at
     * backtest setup, which means each argument must resolve to a concrete
     * numeric value BEFORE any bar is iterated. The only resolvable arguments
     * are numeric literals and declared {@code Parameter} names; anything
     * else (a market variable like {@code close}, a Background series name,
     * an unknown identifier) cannot be turned into a {@code DetectionRule}
     * constructor argument and is rejected at parse time with a
     * {@link net.jacopobiscella.wichtelm.error.StrategyParseException} rather
     * than escaping as an uncaught runtime exception.
     */
    private void checkTierBArgumentsAreResolvable(String name, List<String> arguments,
                                                  Set<String> parameterNames, int line) {
        if (!TIER_B_PRIMITIVES.contains(name)) {
            return;
        }
        for (String arg : arguments) {
            if (numericLiteral(arg) != null) {
                continue;
            }
            if (parameterNames.contains(arg)) {
                continue;
            }
            throw fail("P14", line, 1, "Tier B primitive " + name + " argument '" + arg
                    + "' must be a numeric literal or a declared Parameter name");
        }
    }

    private static final Set<String> TIER_B_PRIMITIVES = Set.of(
            "ha_doji", "ha_strong", "ha_strong_bullish", "ha_strong_bearish",
            "ha_bullish_reversal", "ha_bearish_reversal",
            "rsi_overbought", "rsi_oversold", "rsi_crosses_50",
            "macd_bullish_cross", "macd_bearish_cross",
            "macd_zero_cross_up", "macd_zero_cross_down",
            "price_above_sma", "price_below_sma", "price_above_ema", "price_below_ema",
            "price_crosses_above_sma", "price_crosses_below_sma",
            "price_crosses_above_ema", "price_crosses_below_ema",
            "sma_above_ema", "sma_crosses_above_ema", "sma_crosses_below_ema");

    private void validateIdentifier(String word, int line, ExprContext context,
                                    Set<String> parameterNames, Set<String> seriesNames) {
        boolean marketVar = BuiltinCatalog.isMarketVariable(word);
        boolean parameter = parameterNames.contains(word);
        boolean series = seriesNames.contains(word);
        boolean tradeContext = BuiltinCatalog.isTradeContextVariable(word);

        switch (context) {
            case STOP_TAKE -> {
                if (parameter || tradeContext) {
                    return;
                }
                if (series || marketVar) {
                    throw fail("P16", line, 1, "stop_loss/take_profit expressions may only reference "
                            + "constants, parameters and trade-context variables: " + word);
                }
                throw fail("P13", line, 1, "undeclared identifier: " + word);
            }
            case CONDITION_ENTRY -> {
                if (tradeContext) {
                    throw fail("P17", line, 1,
                            "trade-context variable not allowed in an entry Scenario: " + word);
                }
                if (!(marketVar || parameter || series)) {
                    throw fail("P13", line, 1, "undeclared identifier: " + word);
                }
            }
            case CONDITION_EXIT -> {
                if (!(marketVar || parameter || series || tradeContext)) {
                    throw fail("P13", line, 1, "undeclared identifier: " + word);
                }
            }
            case BACKGROUND -> {
                if (!(marketVar || parameter)) {
                    throw fail("P13", line, 1, "undeclared identifier in Background series: " + word);
                }
            }
        }
    }

    private List<String> extractArguments(String expr, int openParen) {
        List<String> args = new ArrayList<>();
        int depth = 0;
        int start = openParen + 1;
        for (int i = openParen; i < expr.length(); i++) {
            char c = expr.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
                if (depth == 0) {
                    String tail = expr.substring(start, i).strip();
                    if (!tail.isEmpty() || !args.isEmpty()) {
                        args.add(tail);
                    }
                    return args;
                }
            } else if (c == ',' && depth == 1) {
                args.add(expr.substring(start, i).strip());
                start = i + 1;
            }
        }
        return args;
    }

    private void checkNumericRanges(String name, List<String> arguments, int line) {
        switch (name) {
            case "sma", "ema", "rsi", "atr", "stddev", "avg_volume" -> {
                if (arguments.size() == 1) {
                    requirePositivePeriod(arguments.getFirst(), name, line);
                }
            }
            case "highest", "lowest" -> {
                if (arguments.size() == 2) {
                    requirePositivePeriod(arguments.get(1), name, line);
                }
            }
            case "rsi_overbought", "rsi_oversold" -> {
                if (arguments.size() == 1) {
                    requireRsiThreshold(arguments.getFirst(), name, line);
                }
            }
            case "price_above_sma", "price_below_sma", "price_above_ema", "price_below_ema",
                 "price_crosses_above_sma", "price_crosses_below_sma",
                 "price_crosses_above_ema", "price_crosses_below_ema" -> {
                if (arguments.size() == 1) {
                    requirePositivePeriod(arguments.getFirst(), name, line);
                }
            }
            case "sma_above_ema", "sma_crosses_above_ema", "sma_crosses_below_ema" -> {
                if (arguments.size() == 2) {
                    requirePositivePeriod(arguments.get(0), name, line);
                    requirePositivePeriod(arguments.get(1), name, line);
                }
            }
            default -> {
            }
        }
    }

    private void requirePositivePeriod(String arg, String name, int line) {
        BigDecimal literal = numericLiteral(arg);
        if (literal == null) {
            return;
        }
        if (literal.signum() <= 0) {
            throw fail("P21", line, 1, "period argument of " + name + " must be > 0, was " + arg);
        }
        // A period is a bar count: reject fractional literals at parse time so a
        // value like 2.5 fails deterministically here instead of throwing
        // ArithmeticException from intValueExact() during prepass construction.
        if (literal.stripTrailingZeros().scale() > 0) {
            throw fail("P21", line, 1,
                    "period argument of " + name + " must be a whole number, was " + arg);
        }
    }

    private void requireRsiThreshold(String arg, String name, int line) {
        BigDecimal literal = numericLiteral(arg);
        if (literal != null
                && (literal.signum() <= 0 || literal.compareTo(BigDecimal.valueOf(100)) >= 0)) {
            throw fail("P21", line, 1, "threshold argument of " + name + " must be in (0, 100), was " + arg);
        }
    }

    private static BigDecimal numericLiteral(String text) {
        try {
            return IDENTIFIER.matcher(text).find() ? null : new BigDecimal(text.strip(), DECIMAL);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // ----- low-level helpers ---------------------------------------------------------

    private int nextSignificant(int from) {
        int i = from;
        while (i < lines.length && isSkippable(lines[i].strip())) {
            i++;
        }
        return i;
    }

    private static boolean isSkippable(String trimmed) {
        return trimmed.isEmpty() || trimmed.startsWith("#");
    }

    private static boolean isSectionHeader(String trimmed) {
        return trimmed.startsWith("Background:") || trimmed.startsWith("Scenario:");
    }

    private static String firstWord(String text) {
        int space = text.indexOf(' ');
        return space < 0 ? text : text.substring(0, space);
    }

    private StrategyParseException fail(String rule, int line, int column, String message) {
        return new StrategyParseException(filePath, line, column, rule, message);
    }
}
