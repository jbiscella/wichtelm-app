package net.jacopobiscella.wichtelm.runtime;

import net.jacopobiscella.wichtelm.error.DslEvaluationException;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.Instant;
import java.util.List;

/**
 * Evaluates DSL condition and arithmetic expressions against a bar's values.
 *
 * <p>Scope: market variables, declared parameters, trade-context variables,
 * arithmetic, and the English-prose comparison operators. Indicator and
 * window-aggregate function evaluation is intentionally out of this increment;
 * a function call in an expression raises a {@link DslEvaluationException}.
 */
public final class ExpressionEvaluator {

    /** Resolves a bare identifier to its numeric value for a given bar. */
    public interface Values {
        BigDecimal get(String identifier);
    }

    private static final MathContext DECIMAL = MathContext.DECIMAL64;

    /** Comparison operators, longest-first so multi-word phrases match before shorter ones. */
    private static final List<String> COMPARATORS = List.of(
            "crosses below", "crosses above", "drops below", "rises above",
            "is above", "is below", "exceeds");

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
    public boolean condition(String text, Values current, Values previous) {
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
            throw fail(text, "condition has no comparison operator");
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
    public BigDecimal arithmetic(String expression, Values values) {
        Cursor cursor = new Cursor(expression, values);
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
        private final Values values;
        private int pos;

        Cursor(String s, Values values) {
            this.s = s;
            this.values = values;
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
                throw fail(s, "function/indicator evaluation is not implemented in this increment: "
                        + name);
            }
            return values.get(name);
        }
    }
}
