package net.jacopobiscella.wichtelm.runtime;

/**
 * Internal signal raised when an indicator or Background series cannot be
 * evaluated at the current bar because not enough history has accumulated yet
 * (the warm-up window), or because no higher-timeframe bar has closed yet.
 *
 * <p>This is not an error: it is the expected state for the early bars of every
 * backtest. The signal generator catches it and treats the affected condition
 * as not met, so the backtest proceeds rather than aborting. Genuine evaluation
 * errors use {@code DslEvaluationException} instead.
 */
final class IndicatorWarmupException extends RuntimeException {

    IndicatorWarmupException(String message) {
        super(message);
    }
}
