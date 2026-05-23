package net.jacopobiscella.wichtelm.runtime;

import org.hatrack.commons.OHLCBar;
import org.hatrack.frauholle.result.BacktestResult;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Output of {@link BacktestRunner}: the frau-holle backtest result together
 * with the OHLC series that fed it, so a report can be rendered afterwards.
 *
 * @param result              the frau-holle backtest result
 * @param primarySeries       the primary-timeframe bars the backtest ran over
 * @param higherTimeframeBars higher-timeframe bars per timeframe wire
 * @param triggerTimes        trigger bar times per Scenario name, chronological
 * @param suppressedEntries   entries matched but not fired because their
 *                            protective stop / take was not evaluable at fill
 *                            (atr_value warmup), chronological
 */
public record BacktestRunResult(BacktestResult result,
                                List<OHLCBar> primarySeries,
                                Map<String, List<OHLCBar>> higherTimeframeBars,
                                Map<String, List<Instant>> triggerTimes,
                                List<SuppressedEntry> suppressedEntries) {

    public BacktestRunResult {
        Objects.requireNonNull(result, "result");
        primarySeries = List.copyOf(primarySeries);
        higherTimeframeBars = Map.copyOf(higherTimeframeBars);
        Map<String, List<Instant>> triggers = new LinkedHashMap<>();
        triggerTimes.forEach((name, times) -> triggers.put(name, List.copyOf(times)));
        triggerTimes = Map.copyOf(triggers);
        suppressedEntries = List.copyOf(suppressedEntries);
    }
}
