package net.jacopobiscella.wichtelm.runtime;

import org.hatrack.commons.OHLCBar;
import org.hatrack.frauholle.result.BacktestResult;

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
 * @param triggerCounts       number of bars each Scenario fired on, by Scenario name
 */
public record BacktestRunResult(BacktestResult result,
                                List<OHLCBar> primarySeries,
                                Map<String, List<OHLCBar>> higherTimeframeBars,
                                Map<String, Integer> triggerCounts) {

    public BacktestRunResult {
        Objects.requireNonNull(result, "result");
        primarySeries = List.copyOf(primarySeries);
        higherTimeframeBars = Map.copyOf(higherTimeframeBars);
        triggerCounts = Map.copyOf(triggerCounts);
    }
}
