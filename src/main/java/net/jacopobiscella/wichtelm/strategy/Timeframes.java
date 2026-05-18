package net.jacopobiscella.wichtelm.strategy;

import org.hatrack.commons.Timeframe;

import java.time.Duration;

/**
 * Ordering helper for {@link Timeframe} values. The commons {@code Timeframe}
 * record carries no comparison method, so rule P8 (higher-TF strictly above
 * the primary TF) is decided here via an approximate seconds-per-timeframe
 * magnitude. Month and year use nominal lengths; this is sufficient for the
 * strict-ordering comparison the spec requires.
 */
public final class Timeframes {

    private Timeframes() {
    }

    /** Approximate duration of one timeframe in seconds, for ordering purposes only. */
    public static long approximateSeconds(Timeframe tf) {
        long unitSeconds = switch (tf.unit()) {
            case SECOND -> 1L;
            case MINUTE -> 60L;
            case HOUR -> 3_600L;
            case DAY -> 86_400L;
            case WEEK -> 604_800L;
            case MONTH -> 2_592_000L;
            case YEAR -> 31_536_000L;
        };
        return unitSeconds * tf.amount();
    }

    /** True when {@code candidate} represents a strictly longer timeframe than {@code primary}. */
    public static boolean isStrictlyHigher(Timeframe candidate, Timeframe primary) {
        return approximateSeconds(candidate) > approximateSeconds(primary);
    }

    /**
     * Nominal duration of one timeframe. Month and year use nominal lengths
     * (30 and 365 days); callers that need an exact bar-close time should
     * prefer the open time of the following bar.
     */
    public static Duration nominalDuration(Timeframe tf) {
        return Duration.ofSeconds(approximateSeconds(tf));
    }
}
