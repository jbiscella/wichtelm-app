package net.jacopobiscella.wichtelm.error;

import java.time.Instant;

/**
 * Thrown when a runtime error occurs during DSL expression evaluation, such as
 * division by zero or NaN propagation.
 */
public final class DslEvaluationException extends WichtelmException {

    private final String filePath;
    private final int lineNumber;
    private final Instant barTime;
    private final long barIndex;
    private final String expressionText;

    public DslEvaluationException(String filePath, int lineNumber, Instant barTime,
                                  long barIndex, String expressionText, String message,
                                  Throwable cause) {
        super(message, cause);
        this.filePath = filePath;
        this.lineNumber = lineNumber;
        this.barTime = barTime;
        this.barIndex = barIndex;
        this.expressionText = expressionText;
    }

    public DslEvaluationException(String filePath, int lineNumber, Instant barTime,
                                  long barIndex, String expressionText, String message) {
        this(filePath, lineNumber, barTime, barIndex, expressionText, message, null);
    }

    public String filePath() {
        return filePath;
    }

    public int lineNumber() {
        return lineNumber;
    }

    public Instant barTime() {
        return barTime;
    }

    public long barIndex() {
        return barIndex;
    }

    public String expressionText() {
        return expressionText;
    }
}
