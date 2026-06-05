package net.jacopobiscella.wichtelm.runtime;

import org.hatrack.commons.OHLCBar;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Market data loaded once for a backtest: the primary-timeframe series plus any
 * higher-timeframe series keyed by timeframe wire. None of this depends on the
 * strategy's parameter values, so it can be loaded a single time and reused
 * across every parameter combination of a {@code sweep} (CLAUDE.md section 18).
 *
 * @param primarySeries       the primary-timeframe bars the backtest runs over
 * @param higherTimeframeBars higher-timeframe bars per timeframe wire, already
 *                            verified to span the primary range
 */
public record LoadedMarketData(List<OHLCBar> primarySeries,
                               Map<String, List<OHLCBar>> higherTimeframeBars) {

    public LoadedMarketData {
        primarySeries = List.copyOf(primarySeries);
        higherTimeframeBars = Map.copyOf(higherTimeframeBars);
    }

    public int primaryBarCount() {
        return primarySeries.size();
    }

    public boolean isEmpty() {
        return primarySeries.isEmpty();
    }

    public static LoadedMarketData of(List<OHLCBar> primarySeries,
                                      Map<String, List<OHLCBar>> higherTimeframeBars) {
        Objects.requireNonNull(primarySeries, "primarySeries");
        Objects.requireNonNull(higherTimeframeBars, "higherTimeframeBars");
        return new LoadedMarketData(primarySeries, higherTimeframeBars);
    }
}
