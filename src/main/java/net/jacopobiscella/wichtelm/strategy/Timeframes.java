package net.jacopobiscella.wichtelm.strategy;

import org.hatrack.commons.Timeframe;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;

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
     * Advances an open time by exactly one timeframe, using calendar-correct
     * arithmetic at UTC. Month and year boundaries land on the true calendar
     * boundary (not a nominal 30/365-day length), so a bar's close time is
     * exact even for the final bar of a {@code 1M} or {@code 1Y} series.
     */
    public static Instant advance(Instant time, Timeframe tf) {
        ZonedDateTime zoned = time.atZone(ZoneOffset.UTC);
        ZonedDateTime advanced = switch (tf.unit()) {
            case SECOND -> zoned.plusSeconds(tf.amount());
            case MINUTE -> zoned.plusMinutes(tf.amount());
            case HOUR -> zoned.plusHours(tf.amount());
            case DAY -> zoned.plusDays(tf.amount());
            case WEEK -> zoned.plusWeeks(tf.amount());
            case MONTH -> zoned.plusMonths(tf.amount());
            case YEAR -> zoned.plusYears(tf.amount());
        };
        return advanced.toInstant();
    }

    /**
     * Moves a time back by exactly one timeframe, using calendar-correct
     * arithmetic at UTC. Used to widen a higher-timeframe fetch so the bar that
     * already contains the requested range start is included.
     */
    public static Instant retreat(Instant time, Timeframe tf) {
        ZonedDateTime zoned = time.atZone(ZoneOffset.UTC);
        ZonedDateTime retreated = switch (tf.unit()) {
            case SECOND -> zoned.minusSeconds(tf.amount());
            case MINUTE -> zoned.minusMinutes(tf.amount());
            case HOUR -> zoned.minusHours(tf.amount());
            case DAY -> zoned.minusDays(tf.amount());
            case WEEK -> zoned.minusWeeks(tf.amount());
            case MONTH -> zoned.minusMonths(tf.amount());
            case YEAR -> zoned.minusYears(tf.amount());
        };
        return retreated.toInstant();
    }
}
