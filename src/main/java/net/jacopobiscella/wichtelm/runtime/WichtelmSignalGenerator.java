package net.jacopobiscella.wichtelm.runtime;

import net.jacopobiscella.wichtelm.runtime.ExpressionEvaluator.Scope;
import net.jacopobiscella.wichtelm.strategy.FirstClassCondition;
import net.jacopobiscella.wichtelm.strategy.ParsedStrategy;
import net.jacopobiscella.wichtelm.strategy.PositionPrecondition;
import net.jacopobiscella.wichtelm.strategy.StrategyScenario;
import net.jacopobiscella.wichtelm.strategy.StrategyStep;
import net.jacopobiscella.wichtelm.strategy.Timeframes;
import org.hatrack.commons.OHLCBar;
import org.hatrack.commons.Timeframe;
import org.hatrack.frauholle.model.BarContext;
import org.hatrack.frauholle.model.Direction;
import org.hatrack.frauholle.model.Position;
import org.hatrack.frauholle.model.Signal;
import org.hatrack.frauholle.port.SignalGenerator;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Translates a parsed strategy into frau-holle {@link Signal}s, one per primary
 * bar (CLAUDE.md sections 6.2 / 6.3 / Block 4).
 *
 * <p>Per-bar resolution order for an open position: intrabar stop_loss /
 * take_profit (which precede the close), then close-evaluated exit Scenarios,
 * then pyramiding entries. With no open position, entry Scenarios are evaluated
 * in source order; the first match wins.
 */
public final class WichtelmSignalGenerator implements SignalGenerator {

    private static final MathContext DECIMAL = MathContext.DECIMAL64;
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    /** Indicator source for stop/take expressions, which rule P16 forbids functions in. */
    private static final ExpressionEvaluator.IndicatorSource NO_INDICATORS = (name, arguments) -> {
        throw new IllegalStateException("indicators are not allowed in stop_loss/take_profit: " + name);
    };

    private final ParsedStrategy strategy;
    private final Map<String, BigDecimal> parameters;
    private final BigDecimal positionSizePct;
    private final boolean pyramiding;
    private final Timeframe timeframe;

    public WichtelmSignalGenerator(ParsedStrategy strategy,
                                   Map<String, BigDecimal> parameters,
                                   BigDecimal positionSizePct,
                                   boolean pyramiding) {
        this.strategy = strategy;
        this.parameters = Map.copyOf(parameters);
        this.positionSizePct = positionSizePct;
        this.pyramiding = pyramiding;
        this.timeframe = strategy.primaryTimeframe();
    }

    @Override
    public Signal generate(BarContext context) {
        OHLCBar bar = context.currentBar();
        OHLCBar previousBar = previousBar(context);
        ExpressionEvaluator evaluator =
                new ExpressionEvaluator(strategy.featureName(), bar.time(), context.barIndex());

        Optional<Position> open = context.currentPosition();
        Scope current = scopeFor(context, bar, open, context.barIndex());
        Scope previous = previousBar == null
                ? null : scopeFor(context, previousBar, open, context.barIndex() - 1);

        if (open.isEmpty()) {
            return entrySignal(context, evaluator, current, previous);
        }

        Position position = open.get();
        Optional<Signal> protectiveExit = protectiveExit(position, bar);
        if (protectiveExit.isPresent()) {
            return protectiveExit.get();
        }

        PositionPrecondition exitPrecondition = position.direction() == Direction.LONG
                ? PositionPrecondition.LONG_POSITION_OPEN
                : PositionPrecondition.SHORT_POSITION_OPEN;
        for (StrategyScenario scenario : strategy.scenarios()) {
            if (scenario.precondition() == exitPrecondition
                    && conjunctionHolds(scenario, evaluator, current, previous)) {
                return new Signal.ClosePosition();
            }
        }

        if (pyramiding) {
            FirstClassCondition sameDirectionEntry = position.direction() == Direction.LONG
                    ? FirstClassCondition.LONG_ENTRY
                    : FirstClassCondition.SHORT_ENTRY;
            for (StrategyScenario scenario : strategy.scenarios()) {
                if (scenario.precondition() == PositionPrecondition.NO_OPEN_POSITION
                        && scenario.terminalCondition() == sameDirectionEntry
                        && conjunctionHolds(scenario, evaluator, current, previous)) {
                    return new Signal.AddToPosition(quantity(context), position.direction());
                }
            }
        }

        return new Signal.Hold();
    }

    private Signal entrySignal(BarContext context, ExpressionEvaluator evaluator,
                               Scope current, Scope previous) {
        for (StrategyScenario scenario : strategy.scenarios()) {
            if (scenario.precondition() != PositionPrecondition.NO_OPEN_POSITION) {
                continue;
            }
            if (conjunctionHolds(scenario, evaluator, current, previous)) {
                BigDecimal quantity = quantity(context);
                return scenario.terminalCondition() == FirstClassCondition.LONG_ENTRY
                        ? new Signal.Buy(quantity)
                        : new Signal.Sell(quantity);
            }
        }
        return new Signal.Hold();
    }

    /** Snapshotted stop_loss price for an open position, if its entry Scenario declared one. */
    public Optional<BigDecimal> stopLossPriceFor(Position position) {
        return entryScenarioFor(position.direction())
                .flatMap(StrategyScenario::stopLossExpression)
                .map(expression -> evaluateProtective(expression, position));
    }

    /** Snapshotted take_profit price for an open position, if its entry Scenario declared one. */
    public Optional<BigDecimal> takeProfitPriceFor(Position position) {
        return entryScenarioFor(position.direction())
                .flatMap(StrategyScenario::takeProfitExpression)
                .map(expression -> evaluateProtective(expression, position));
    }

    private Optional<Signal> protectiveExit(Position position, OHLCBar bar) {
        Optional<StrategyScenario> entry = entryScenarioFor(position.direction());
        if (entry.isEmpty()) {
            return Optional.empty();
        }
        boolean isLong = position.direction() == Direction.LONG;

        Optional<BigDecimal> stop = entry.get().stopLossExpression()
                .map(expression -> evaluateProtective(expression, position));
        if (stop.isPresent()) {
            BigDecimal price = stop.get();
            boolean hit = isLong ? bar.low().compareTo(price) <= 0 : bar.high().compareTo(price) >= 0;
            if (hit) {
                return Optional.of(new Signal.ClosePositionAtPrice(price, intrabarFillTime(bar)));
            }
        }

        Optional<BigDecimal> take = entry.get().takeProfitExpression()
                .map(expression -> evaluateProtective(expression, position));
        if (take.isPresent()) {
            BigDecimal price = take.get();
            boolean hit = isLong ? bar.high().compareTo(price) >= 0 : bar.low().compareTo(price) <= 0;
            if (hit) {
                return Optional.of(new Signal.ClosePositionAtPrice(price, intrabarFillTime(bar)));
            }
        }
        return Optional.empty();
    }

    private BigDecimal evaluateProtective(String expression, Position position) {
        ExpressionEvaluator evaluator =
                new ExpressionEvaluator(strategy.featureName(), position.entryTime(), 0);
        return evaluator.arithmetic(expression, new Scope(positionValues(position), NO_INDICATORS));
    }

    private Optional<StrategyScenario> entryScenarioFor(Direction direction) {
        FirstClassCondition entryCondition = direction == Direction.LONG
                ? FirstClassCondition.LONG_ENTRY
                : FirstClassCondition.SHORT_ENTRY;
        return strategy.scenarios().stream()
                .filter(s -> s.terminalCondition() == entryCondition)
                .filter(s -> s.stopLossExpression().isPresent() || s.takeProfitExpression().isPresent())
                .findFirst();
    }

    private boolean conjunctionHolds(StrategyScenario scenario, ExpressionEvaluator evaluator,
                                     Scope current, Scope previous) {
        for (StrategyStep step : scenario.conditionSteps()) {
            if (!evaluator.condition(step.text(), current, previous)) {
                return false;
            }
        }
        return true;
    }

    private BigDecimal quantity(BarContext context) {
        return positionSizePct
                .divide(HUNDRED, DECIMAL)
                .multiply(context.currentEquity(), DECIMAL)
                .divide(context.currentBar().close(), DECIMAL);
    }

    /** A fill instant strictly inside the current bar's interval. */
    private Instant intrabarFillTime(OHLCBar bar) {
        Instant open = bar.time();
        Instant close = Timeframes.advance(open, timeframe);
        return open.plus(Duration.between(open, close).dividedBy(2));
    }

    private OHLCBar previousBar(BarContext context) {
        OHLCBar latest = null;
        for (OHLCBar candidate : context.history()) {
            if (candidate.time().isBefore(context.currentBar().time())
                    && (latest == null || candidate.time().isAfter(latest.time()))) {
                latest = candidate;
            }
        }
        return latest;
    }

    private Scope scopeFor(BarContext context, OHLCBar bar, Optional<Position> position, long barIndex) {
        BarIndicatorSource indicators = new BarIndicatorSource(barsUpToAndIncluding(context, bar),
                strategy.featureName(), bar.time(), barIndex);
        return new Scope(barValues(bar, position, barIndex), indicators);
    }

    /** All known bars with time at or before {@code bar}, in chronological order. */
    private java.util.List<OHLCBar> barsUpToAndIncluding(BarContext context, OHLCBar bar) {
        TreeMap<Instant, OHLCBar> byTime = new TreeMap<>();
        for (OHLCBar candidate : context.history()) {
            if (!candidate.time().isAfter(bar.time())) {
                byTime.put(candidate.time(), candidate);
            }
        }
        byTime.put(bar.time(), bar);
        return java.util.List.copyOf(byTime.values());
    }

    private ExpressionEvaluator.Values barValues(OHLCBar bar, Optional<Position> position,
                                                 long barIndex) {
        return name -> switch (name) {
            case "open" -> bar.open();
            case "high" -> bar.high();
            case "low" -> bar.low();
            case "close" -> bar.close();
            case "volume" -> bar.volume().orElse(BigDecimal.ZERO);
            case "bar_index" -> BigDecimal.valueOf(barIndex);
            case "entry_price" -> position.map(Position::entryPrice)
                    .orElseThrow(() -> unresolved(name));
            case "position_size" -> position.map(Position::quantity)
                    .orElseThrow(() -> unresolved(name));
            default -> resolveParameter(name);
        };
    }

    private ExpressionEvaluator.Values positionValues(Position position) {
        return name -> switch (name) {
            case "entry_price" -> position.entryPrice();
            case "position_size" -> position.quantity();
            default -> resolveParameter(name);
        };
    }

    private BigDecimal resolveParameter(String name) {
        BigDecimal value = parameters.get(name);
        if (value == null) {
            throw unresolved(name);
        }
        return value;
    }

    private IllegalStateException unresolved(String name) {
        return new IllegalStateException("identifier cannot be resolved at evaluation time: " + name);
    }
}
