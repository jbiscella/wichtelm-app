package net.jacopobiscella.wichtelm.sweep;

import net.jacopobiscella.wichtelm.config.BacktestConfig;
import net.jacopobiscella.wichtelm.config.ParameterResolver;
import net.jacopobiscella.wichtelm.config.SweepParameterResolver;
import net.jacopobiscella.wichtelm.error.WichtelmException;
import net.jacopobiscella.wichtelm.runtime.BacktestRunResult;
import net.jacopobiscella.wichtelm.runtime.BacktestRunner;
import net.jacopobiscella.wichtelm.runtime.LoadedMarketData;
import net.jacopobiscella.wichtelm.strategy.ParsedStrategy;
import org.hatrack.frauholle.error.BacktestException;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Runs a parameter sweep (CLAUDE.md section 18): expands the grid, loads market
 * data once, runs the backtest for every combination, and ranks the results
 * best-first by the chosen objective.
 *
 * <p>Single-threaded per CLAUDE.md section 1. A combination whose backtest
 * throws is recorded as a failed row rather than aborting the sweep, so one bad
 * corner of the grid does not discard the rest.
 *
 * <p>Ranking: rows that produced at least one trade always sort above tradeless
 * rows; within each group, higher objective values sort first. A row whose
 * objective is undefined (e.g. profit factor with no trades) sorts last.
 */
public final class SweepRunner {

    private final BacktestRunner backtestRunner;

    public SweepRunner(BacktestRunner backtestRunner) {
        this.backtestRunner = backtestRunner;
    }

    /**
     * Expands and runs the sweep declared in {@code config}, returning the
     * result rows ranked best-first by {@code objective}.
     *
     * @throws net.jacopobiscella.wichtelm.error.SweepConfigException if an axis
     *         is undeclared (C13) or the grid exceeds {@code maxCombos} (C15)
     */
    public List<SweepResult> run(ParsedStrategy strategy, BacktestConfig config,
                                 SweepObjective objective, int top, int maxCombos) {
        Map<String, List<BigDecimal>> axes =
                SweepParameterResolver.resolveAxes(strategy, config, maxCombos);
        SweepSpec spec = new SweepSpec(axes, objective, top, maxCombos);
        List<Map<String, BigDecimal>> grid = SweepGrid.expand(spec, config.configPath().toString());

        // Base parameters: strategy defaults overlaid with the fixed [parameters]
        // overrides. Each combination overlays its swept values on top of this.
        Map<String, BigDecimal> base = ParameterResolver.resolve(strategy, config);

        // Data depends only on (symbol, timeframes, range, source), none of which
        // a sweep varies — so it is loaded exactly once and reused per combination.
        LoadedMarketData data = backtestRunner.loadMarketData(strategy, config);

        List<SweepResult> rows = new ArrayList<>(grid.size());
        for (Map<String, BigDecimal> combination : grid) {
            Map<String, BigDecimal> parameters = new LinkedHashMap<>(base);
            parameters.putAll(combination);
            rows.add(runOne(strategy, config, combination, parameters, data));
        }
        rows.sort(ranking(objective));
        return rows;
    }

    private SweepResult runOne(ParsedStrategy strategy, BacktestConfig config,
                               Map<String, BigDecimal> combination,
                               Map<String, BigDecimal> parameters, LoadedMarketData data) {
        try {
            BacktestRunResult result = backtestRunner.runWith(strategy, config, parameters, data);
            return SweepResult.success(combination, result.result().metrics());
        } catch (WichtelmException | BacktestException e) {
            return SweepResult.failed(combination, e.getClass().getSimpleName() + ": " + e.getMessage());
        } catch (RuntimeException e) {
            // A bad swept value can trip an unchecked failure before frau-holle
            // runs — e.g. a period that fails intValueExact()/a rule-constructor
            // IllegalArgumentException inside NachtkrappMatchIndex.buildFor. Per
            // section 18.3 that combination is recorded as a failed row, not fatal
            // to the whole sweep.
            return SweepResult.failed(combination, e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    /**
     * Best-first ordering: traded rows before tradeless rows; within a group, a
     * higher objective value first; an undefined or absent objective last.
     */
    static Comparator<SweepResult> ranking(SweepObjective objective) {
        Comparator<SweepResult> byTraded =
                Comparator.comparing(SweepResult::hasTrades).reversed();
        Comparator<SweepResult> byObjective = (a, b) -> {
            BigDecimal va = a.objectiveValue(objective).orElse(null);
            BigDecimal vb = b.objectiveValue(objective).orElse(null);
            if (va == null && vb == null) {
                return 0;
            }
            if (va == null) {
                return 1; // a sorts after b
            }
            if (vb == null) {
                return -1; // a sorts before b
            }
            return vb.compareTo(va); // descending
        };
        return byTraded.thenComparing(byObjective);
    }
}
