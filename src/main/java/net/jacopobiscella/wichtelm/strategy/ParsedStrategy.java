package net.jacopobiscella.wichtelm.strategy;

import org.hatrack.commons.Timeframe;

import java.util.List;
import java.util.Objects;

/** Immutable AST produced by {@link StrategyParser} for a valid {@code .strat} file. */
public record ParsedStrategy(String featureName,
                             Timeframe primaryTimeframe,
                             List<StrategyParameter> parameters,
                             List<BackgroundSeries> backgroundSeries,
                             List<StrategyScenario> scenarios) {

    public ParsedStrategy {
        Objects.requireNonNull(featureName, "featureName");
        Objects.requireNonNull(primaryTimeframe, "primaryTimeframe");
        parameters = List.copyOf(parameters);
        backgroundSeries = List.copyOf(backgroundSeries);
        scenarios = List.copyOf(scenarios);
    }
}
