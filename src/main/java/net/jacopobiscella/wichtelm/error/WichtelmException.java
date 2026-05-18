package net.jacopobiscella.wichtelm.error;

/**
 * Abstract root of every exception raised by wichtelm-app. Exceptions thrown by
 * ha-track libraries are caught at the runtime boundary and rethrown or wrapped
 * in one of the permitted subtypes.
 */
public abstract sealed class WichtelmException extends RuntimeException
        permits StrategyParseException, ConfigParseException, DslEvaluationException,
                DataSourceUnavailableException, ReportGenerationException {

    protected WichtelmException(String message) {
        super(message);
    }

    protected WichtelmException(String message, Throwable cause) {
        super(message, cause);
    }
}
