package net.jacopobiscella.wichtelm.runtime;

import net.jacopobiscella.wichtelm.strategy.Timeframes;
import org.hatrack.commons.OHLCBar;
import org.hatrack.commons.Timeframe;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * A higher-timeframe bar series with lookahead-safe resolution. At a primary
 * bar time T, {@link #resolveAt(Instant)} returns the most recently CLOSED
 * higher-TF bar whose {@code closeTime} is at or before T (CLAUDE.md section
 * 3.5 / Block 3). This type structurally cannot return a bar that closes after
 * T, so it enforces the lookahead-safety invariant by construction.
 *
 * <p>A bar's close time is the open time of the following bar; the final bar
 * uses the nominal timeframe duration.
 */
public final class HigherTimeframeSeries {

    private final Timeframe timeframe;
    private final List<OHLCBar> bars;
    private final List<Instant> closeTimes;

    public HigherTimeframeSeries(Timeframe timeframe, List<OHLCBar> bars) {
        this.timeframe = Objects.requireNonNull(timeframe, "timeframe");
        Objects.requireNonNull(bars, "bars");

        List<OHLCBar> sorted = new ArrayList<>(bars);
        sorted.sort(Comparator.comparing(OHLCBar::time));
        for (int i = 1; i < sorted.size(); i++) {
            if (!sorted.get(i).time().isAfter(sorted.get(i - 1).time())) {
                throw new IllegalArgumentException(
                        "higher-timeframe bars must have strictly increasing times");
            }
        }
        this.bars = List.copyOf(sorted);

        List<Instant> computed = new ArrayList<>(sorted.size());
        for (int i = 0; i < sorted.size(); i++) {
            computed.add(i + 1 < sorted.size()
                    ? sorted.get(i + 1).time()
                    : sorted.get(i).time().plus(Timeframes.nominalDuration(timeframe)));
        }
        this.closeTimes = List.copyOf(computed);
    }

    public Timeframe timeframe() {
        return timeframe;
    }

    public List<OHLCBar> bars() {
        return bars;
    }

    /**
     * Resolves the higher-TF bar visible at primary bar time {@code T}: the bar
     * with the latest {@code closeTime} that is at or before {@code T}. Returns
     * empty when no higher-TF bar has closed yet.
     */
    public Optional<OHLCBar> resolveAt(Instant primaryBarTime) {
        Objects.requireNonNull(primaryBarTime, "primaryBarTime");
        OHLCBar resolved = null;
        for (int i = 0; i < bars.size(); i++) {
            if (closeTimes.get(i).isAfter(primaryBarTime)) {
                break;
            }
            resolved = bars.get(i);
        }
        return Optional.ofNullable(resolved);
    }

    /** The close time of the given bar, i.e. the instant after which it is visible. */
    public Instant closeTimeOf(OHLCBar bar) {
        int index = bars.indexOf(bar);
        if (index < 0) {
            throw new IllegalArgumentException("bar does not belong to this series");
        }
        return closeTimes.get(index);
    }
}
