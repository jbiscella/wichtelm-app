package net.jacopobiscella.wichtelm.runtime;

import net.jacopobiscella.wichtelm.error.DslEvaluationException;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Evaluates DSL condition and arithmetic expressions against a bar's values.
 *
 * <p>Scope: market variables, declared parameters, trade-context variables,
 * arithmetic, the English-prose comparison operators, and indicator function
 * calls resolved through an {@link IndicatorSource}.
 */
public final class ExpressionEvaluator {

    /** Resolves a bare identifier to its numeric value for a given bar. */
    public interface Values {
        BigDecimal get(String identifier);
    }

    /** Resolves an indicator/function call to its numeric value at a given bar. */
    public interface IndicatorSource {
        BigDecimal evaluate(String functionName, List<BigDecimal> arguments);

        /**
         * Resolves a pivot Tier B primitive on a dedicated boolean-step path
         * that keeps the symbolic level token (R1, S2…) out of the numeric
         * arithmetic pipeline. The default throws: only the runtime
         * {@code BarIndicatorSource} (which holds the prepass index) can
         * resolve a pivot. The stop/take and pure-arithmetic contexts never
         * reach a pivot step — P16 bars functions inside stop_loss/take_profit.
         */
        default boolean pivotPrimitive(String name, String level) {
            throw new UnsupportedOperationException(
                    "pivot primitive " + name + " cannot be evaluated in this context");
        }
    }

    /** Identifier and function resolution for one bar. */
    public record Scope(Values values, IndicatorSource indicators) {
    }

    private static final MathContext DECIMAL = MathContext.DECIMAL64;

    private static final List<String> COMPARATORS = List.of(
            "crosses below", "crosses above", "drops below", "rises above",
            "is above", "is below", "exceeds");

    private static final Pattern PIVOT_STEP = Pattern.compile(
            "^(price_above_pivot|price_below_pivot"
                    + "|price_crosses_above_pivot|price_crosses_below_pivot)"
                    + "\\s*\\(\\s*([A-Za-z][A-Za-z0-9]*)\\s*\\)$");

    private final String strategyName;
    private final Instant barTime;
    private final long barIndex;

    public ExpressionEvaluator(String strategyName, Instant barTime, long barIndex) {
        this.strategyName = strategyName;
        this.barTime = barTime;
        this.barIndex = barIndex;
    }

    /**
     * Evaluates a single {@code When}/{@code And} condition step. {@code previous}
     * may be {@code null} when no prior bar exists; crossing operators then yield
     * {@code false}.
     */
    public boolean condition(String text, Scope current, Scope previous) {
        String t = text.strip();
        int splitAt = -1;
        String operator = null;
        for (String candidate : COMPARATORS) {
            int idx = t.indexOf(" " + candidate + " ");
            if (idx >= 0 && (splitAt < 0 || idx < splitAt)) {
                splitAt = idx;
                operator = candidate;
            }
        }
        if (operator == null) {
            // Pivot primitive: a dedicated boolean-step path. Its argument is a
            // symbolic level token (R1, S2…) that the numeric arithmetic Cursor
            // cannot carry, so it is resolved directly against the prepass index
            // here rather than through evaluate(name, List<BigDecimal>).
            Matcher pivot = PIVOT_STEP.matcher(t);
            if (pivot.matches()) {
                return current.indicators().pivotPrimitive(pivot.group(1), pivot.group(2));
            }
            // Bare boolean-valued primitive (Tier B): "When ha_doji()" or
            // "And rsi_oversold(30)". Evaluate the whole step as an
            // arithmetic expression; the boolean primitives return 1.0 / 0.0
            // through the IndicatorSource. Any other expression that lacks a
            // comparator yields a numeric value too, which we interpret as
            // truthy iff non-zero — same convention as C / Python.
            return arithmetic(t, current).signum() != 0;
        }
        String lhs = t.substring(0, splitAt).strip();
        String rhs = t.substring(splitAt + operator.length() + 2).strip();

        BigDecimal left = arithmetic(lhs, current);
        BigDecimal right = arithmetic(rhs, current);
        return switch (operator) {
            case "exceeds", "is above" -> left.compareTo(right) > 0;
            case "is below" -> left.compareTo(right) < 0;
            case "crosses above", "rises above" -> {
                if (previous == null) {
                    yield false;
                }
                yield arithmetic(lhs, previous).compareTo(arithmetic(rhs, previous)) <= 0
                        && left.compareTo(right) > 0;
            }
            case "crosses below", "drops below" -> {
                if (previous == null) {
                    yield false;
                }
                yield arithmetic(lhs, previous).compareTo(arithmetic(rhs, previous)) >= 0
                        && left.compareTo(right) < 0;
            }
            default -> throw fail(text, "unsupported comparison operator: " + operator);
        };
    }

    /** Evaluates an arithmetic expression to a single value. */
    public BigDecimal arithmetic(String expression, Scope scope) {
        Cursor cursor = new Cursor(expression, scope);
        BigDecimal result = cursor.expression();
        cursor.skipWhitespace();
        if (!cursor.atEnd()) {
            throw fail(expression, "unexpected trailing input in expression");
        }
        return result;
    }

    private DslEvaluationException fail(String expression, String message) {
        return new DslEvaluationException(strategyName, 0, barTime, barIndex, expression, message);
    }

    /** Recursive-descent cursor over a single arithmetic expression. */
    private final class Cursor {
        private final String s;
        private final Scope scope;
        private int pos;

        Cursor(String s, Scope scope) {
            this.s = s;
            this.scope = scope;
        }

        boolean atEnd() {
            return pos >= s.length();
        }

        void skipWhitespace() {
            while (pos < s.length() && s.charAt(pos) == ' ') {
                pos++;
            }
        }

        BigDecimal expression() {
            BigDecimal value = term();
            while (true) {
                skipWhitespace();
                if (atEnd()) {
                    return value;
                }
                char c = s.charAt(pos);
                if (c == '+') {
                    pos++;
                    value = value.add(term(), DECIMAL);
                } else if (c == '-') {
                    pos++;
                    value = value.subtract(term(), DECIMAL);
                } else {
                    return value;
                }
            }
        }

        BigDecimal term() {
            BigDecimal value = factor();
            while (true) {
                skipWhitespace();
                if (atEnd()) {
                    return value;
                }
                char c = s.charAt(pos);
                if (c == '*') {
                    pos++;
                    value = value.multiply(factor(), DECIMAL);
                } else if (c == '/') {
                    pos++;
                    BigDecimal divisor = factor();
                    if (divisor.signum() == 0) {
                        throw fail(s, "division by zero");
                    }
                    value = value.divide(divisor, DECIMAL);
                } else {
                    return value;
                }
            }
        }

        BigDecimal factor() {
            skipWhitespace();
            if (atEnd()) {
                throw fail(s, "unexpected end of expression");
            }
            char c = s.charAt(pos);
            if (c == '(') {
                pos++;
                BigDecimal value = expression();
                skipWhitespace();
                if (atEnd() || s.charAt(pos) != ')') {
                    throw fail(s, "missing closing parenthesis");
                }
                pos++;
                return value;
            }
            if (c == '-') {
                pos++;
                return factor().negate();
            }
            if (Character.isDigit(c) || c == '.') {
                return number();
            }
            if (Character.isLetter(c) || c == '_') {
                return identifier();
            }
            throw fail(s, "unexpected character '" + c + "' in expression");
        }

        BigDecimal number() {
            int start = pos;
            while (pos < s.length() && (Character.isDigit(s.charAt(pos)) || s.charAt(pos) == '.')) {
                pos++;
            }
            return new BigDecimal(s.substring(start, pos), DECIMAL);
        }

        BigDecimal identifier() {
            int start = pos;
            while (pos < s.length()
                    && (Character.isLetterOrDigit(s.charAt(pos)) || s.charAt(pos) == '_')) {
                pos++;
            }
            String name = s.substring(start, pos);
            skipWhitespace();
            if (pos < s.length() && s.charAt(pos) == '(') {
                return functionCall(name);
            }
            return scope.values().get(name);
        }

        BigDecimal functionCall(String name) {
            pos++;
            List<BigDecimal> arguments = new ArrayList<>();
            skipWhitespace();
            if (pos < s.length() && s.charAt(pos) == ')') {
                pos++;
            } else {
                arguments.add(expression());
                skipWhitespace();
                while (pos < s.length() && s.charAt(pos) == ',') {
                    pos++;
                    arguments.add(expression());
                    skipWhitespace();
                }
                if (atEnd() || s.charAt(pos) != ')') {
                    throw fail(s, "missing closing parenthesis in call to " + name);
                }
                pos++;
            }
            return scope.indicators().evaluate(name, arguments);
        }
    }
}
