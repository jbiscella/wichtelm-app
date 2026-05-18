package net.jacopobiscella.wichtelm.strategy;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * One Scenario block: a position precondition, the conjunction of When/And
 * condition steps, the terminating first-class condition, and the optional
 * intrabar stop_loss / take_profit expressions.
 */
public record StrategyScenario(String name,
                               PositionPrecondition precondition,
                               List<StrategyStep> conditionSteps,
                               FirstClassCondition terminalCondition,
                               Optional<String> stopLossExpression,
                               Optional<String> takeProfitExpression,
                               int line) {

    public StrategyScenario {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(precondition, "precondition");
        Objects.requireNonNull(terminalCondition, "terminalCondition");
        Objects.requireNonNull(stopLossExpression, "stopLossExpression");
        Objects.requireNonNull(takeProfitExpression, "takeProfitExpression");
        conditionSteps = List.copyOf(conditionSteps);
    }
}
