package net.jacopobiscella.wichtelm.error;

/**
 * Thrown when HTML report rendering fails, for example a filesystem write error
 * or a chart driver error. Wraps the underlying exception as cause.
 */
public final class ReportGenerationException extends WichtelmException {

    public ReportGenerationException(String message, Throwable cause) {
        super(message, cause);
    }

    public ReportGenerationException(String message) {
        super(message);
    }
}
