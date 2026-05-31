package net.jacopobiscella.wichtelm.sweep;

import org.hatrack.frauholle.result.BacktestMetrics;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * One row of a sweep: the parameter combination that was run together with the
 * metrics it produced, or — if that combination failed to run — the failure
 * reason (CLAUDE.md section 18). A failed combination is recorded rather than
 * aborting the whole sweep, so a single bad corner of the grid does not lose the
 * rest of the results.
 *
 * @param combination the swept parameter values for this run, in axis order
 * @param metrics     the backtest metrics, empty if the run failed
 * @param failure     the failure reason, empty if the run succeeded
 */
public record SweepResult(Map<String, BigDecimal> combination,
                          Optional<BacktestMetrics> metrics,
                          Optional<String> failure) {

    public SweepResult {
        combination = Map.copyOf(combination);
    }

    public static SweepResult success(Map<String, BigDecimal> combination, BacktestMetrics metrics) {
        return new SweepResult(ordered(combination), Optional.of(metrics), Optional.empty());
    }

    public static SweepResult failed(Map<String, BigDecimal> combination, String reason) {
        return new SweepResult(ordered(combination), Optional.empty(), Optional.of(reason));
    }

    public boolean ran() {
        return metrics.isPresent();
    }

    /** True when this run produced at least one closed trade. */
    public boolean hasTrades() {
        return metrics.map(m -> m.numTrades() > 0).orElse(false);
    }

    /**
     * The objective value used for ranking, or empty when this combination
     * cannot be ranked (it failed, or the objective is undefined — e.g. profit
     * factor with no trades).
     */
    public Optional<BigDecimal> objectiveValue(SweepObjective objective) {
        return metrics.map(objective::extract);
    }

    private static Map<String, BigDecimal> ordered(Map<String, BigDecimal> combination) {
        return new LinkedHashMap<>(combination);
    }
}
