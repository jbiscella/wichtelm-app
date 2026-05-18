package net.jacopobiscella.wichtelm.error;

import java.util.Optional;

/**
 * Thrown when a {@code .strat} file violates one of the parse-time validation
 * rules P1-P21. Carries the location of the violation and the rule identifier.
 */
public final class StrategyParseException extends WichtelmException {

    private final String filePath;
    private final int lineNumber;
    private final int columnNumber;
    private final String violatedRule;
    private final String suggestion;

    public StrategyParseException(String filePath, int lineNumber, int columnNumber,
                                  String violatedRule, String message, String suggestion) {
        super(message);
        this.filePath = filePath;
        this.lineNumber = lineNumber;
        this.columnNumber = columnNumber;
        this.violatedRule = violatedRule;
        this.suggestion = suggestion;
    }

    public StrategyParseException(String filePath, int lineNumber, int columnNumber,
                                  String violatedRule, String message) {
        this(filePath, lineNumber, columnNumber, violatedRule, message, null);
    }

    public String filePath() {
        return filePath;
    }

    public int lineNumber() {
        return lineNumber;
    }

    public int columnNumber() {
        return columnNumber;
    }

    public String violatedRule() {
        return violatedRule;
    }

    public Optional<String> suggestion() {
        return Optional.ofNullable(suggestion);
    }
}
