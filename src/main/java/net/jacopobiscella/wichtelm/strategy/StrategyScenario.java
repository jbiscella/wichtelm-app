package net.jacopobiscella.wichtelm.strategy;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * One Scenario block: a position precondition, the conjunction of When/And
 * condition steps, the terminating first-class condition, and the optional
 * intrabar stop_loss / take_profit / trailing_stop expressions.
 *
 * <p>{@code stopLossExpression} and {@code trailingStopExpression} are mutually
 * exclusive (enforced at parse time by P23); {@code takeProfitExpression} MAY
 * accompany either. The trailing-stop expression is a percentage (no
 * {@code atr_value}) or an ATR price distance (references {@code atr_value}); see
 * CLAUDE.md §3.4.1.
 */
public record StrategyScenario(String name,
                               PositionPrecondition precondition,
                               List<StrategyStep> conditionSteps,
                               FirstClassCondition terminalCondition,
                               Optional<String> stopLossExpression,
                               Optional<String> takeProfitExpression,
                               Optional<String> trailingStopExpression,
                               int line) {

    public StrategyScenario {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(precondition, "precondition");
        Objects.requireNonNull(terminalCondition, "terminalCondition");
        Objects.requireNonNull(stopLossExpression, "stopLossExpression");
        Objects.requireNonNull(takeProfitExpression, "takeProfitExpression");
        Objects.requireNonNull(trailingStopExpression, "trailingStopExpression");
        conditionSteps = List.copyOf(conditionSteps);
    }
}
