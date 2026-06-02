package net.jacopobiscella.wichtelm.runtime;

import net.jacopobiscella.wichtelm.error.DslEvaluationException;
import net.jacopobiscella.wichtelm.strategy.BackgroundSeries;
import net.jacopobiscella.wichtelm.strategy.FirstClassCondition;
import net.jacopobiscella.wichtelm.strategy.ParsedStrategy;
import net.jacopobiscella.wichtelm.strategy.PositionPrecondition;
import net.jacopobiscella.wichtelm.strategy.StrategyScenario;
import net.jacopobiscella.wichtelm.strategy.StrategyStep;
import net.jacopobiscella.wichtelm.strategy.Timeframes;
import org.hatrack.commons.OHLCBar;
import org.hatrack.commons.Timeframe;
import org.hatrack.dsl.BarIndicatorSource;
import org.hatrack.dsl.ExpressionEvaluator;
import org.hatrack.dsl.ExpressionEvaluator.Scope;
import org.hatrack.dsl.NachtkrappMatchIndex;
import org.hatrack.dsl.error.IndicatorWarmupException;
import org.hatrack.frauholle.model.BarContext;
import org.hatrack.frauholle.model.Direction;
import org.hatrack.frauholle.model.Position;
import org.hatrack.frauholle.model.Signal;
import org.hatrack.frauholle.port.SignalGenerator;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Translates a parsed strategy into frau-holle {@link Signal}s, one per primary
 * bar (CLAUDE.md sections 6.2 / 6.3).
 *
 * <p>Per-bar resolution order for an open position: intrabar stop_loss /
 * take_profit (which precede the close), then close-evaluated exit Scenarios,
 * then pyramiding entries. With no open position, entry Scenarios are evaluated
 * in source order; the first match wins.
 *
 * <p>Background series are resolved lazily: a primary-timeframe series is
 * evaluated against the current bar's history, a higher-timeframe series
 * against the most recently closed higher-TF bar at or before the primary bar
 * time ({@link HigherTimeframeSeries}), keeping the runtime lookahead-safe.
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
    private final Map<String, BackgroundSeries> seriesByName = new LinkedHashMap<>();
    private final Map<String, StrategyScenario> scenariosByName = new LinkedHashMap<>();
    private final Map<String, List<OHLCBar>> higherTimeframeBars = new HashMap<>();
    private final Map<String, HigherTimeframeSeries> higherResolvers = new HashMap<>();
    private final Map<String, List<Instant>> triggerTimes = new LinkedHashMap<>();
    /**
     * Binds an open position to the scenario that emitted its entry signal,
     * keyed by {@link Position#entryTime()}. Populated on the bar AFTER the
     * Buy/Sell fires (when the BarContext first surfaces the new position) and
     * evicted when the position closes. Resolves Concern 1 from the external
     * review: without this map, protective-exit lookup picked any same-direction
     * entry scenario carrying a stop/take, so two competing long_entry
     * scenarios would silently apply the wrong stop.
     */
    private final Map<Instant, String> openPositionEntryScenario = new HashMap<>();
    /**
     * Last entry scenario name whose Buy/Sell signal has been emitted but whose
     * resulting Position has not yet been observed in a subsequent BarContext.
     * Cleared on the next {@code generate} call.
     */
    private String pendingEntryScenarioName;
    private NachtkrappMatchIndex matchIndex = NachtkrappMatchIndex.empty();
    /**
     * Primary bars seen so far (chronological), refreshed each {@link #generate}
     * call. Lets {@link #evaluateProtective} resolve atr_value(period) at the
     * entry fill bar even when the public {@link #stopLossPriceFor} /
     * {@link #takeProfitPriceFor} are called by the backtester without a
     * {@code BarContext}.
     */
    private List<OHLCBar> primaryHistory = List.of();

    /**
     * Entries matched but not fired because their protective stop / take could
     * not be evaluated at the prospective fill (atr_value warmup). Accumulated
     * across the backtest and surfaced in the report.
     */
    private final List<SuppressedEntry> suppressedEntries = new ArrayList<>();

    /** {@code atr_value(<arg>)} occurrences, for reporting the unwarmed period. */
    private static final Pattern ATR_VALUE_CALL =
            Pattern.compile("atr_value\\s*\\(\\s*([A-Za-z_][A-Za-z0-9_]*|\\d+)\\s*\\)");

    /** Generator for a strategy without higher-timeframe Background series. */
    public WichtelmSignalGenerator(ParsedStrategy strategy, Map<String, BigDecimal> parameters,
                                   BigDecimal positionSizePct, boolean pyramiding) {
        this(strategy, parameters, positionSizePct, pyramiding, Map.of());
    }

    /**
     * Wires the Tier B pattern prepass. Must be called once, before
     * {@link #generate(BarContext)} is invoked for the first bar.
     */
    public void setNachtkrappMatchIndex(NachtkrappMatchIndex matchIndex) {
        this.matchIndex = java.util.Objects.requireNonNull(matchIndex, "matchIndex");
    }

    /**
     * @param higherTimeframeBars OHLC bars for each higher timeframe referenced by a
     *                            Background series, keyed by timeframe wire (e.g. "1d")
     */
    public WichtelmSignalGenerator(ParsedStrategy strategy, Map<String, BigDecimal> parameters,
                                   BigDecimal positionSizePct, boolean pyramiding,
                                   Map<String, List<OHLCBar>> higherTimeframeBars) {
        this.strategy = strategy;
        this.parameters = Map.copyOf(parameters);
        this.positionSizePct = positionSizePct;
        this.pyramiding = pyramiding;
        this.timeframe = strategy.primaryTimeframe();
        for (BackgroundSeries series : strategy.backgroundSeries()) {
            seriesByName.put(series.name(), series);
        }
        for (StrategyScenario scenario : strategy.scenarios()) {
            scenariosByName.put(scenario.name(), scenario);
        }
        higherTimeframeBars.forEach((wire, bars) -> {
            List<OHLCBar> sorted = new ArrayList<>(bars);
            sorted.sort(Comparator.comparing(OHLCBar::time));
            this.higherTimeframeBars.put(wire, List.copyOf(sorted));
            this.higherResolvers.put(wire,
                    new HigherTimeframeSeries(Timeframe.fromWire(wire), sorted));
        });
    }

    @Override
    public Signal generate(BarContext context) {
        OHLCBar bar = context.currentBar();
        primaryHistory = barsUpToAndIncluding(context, bar);
        OHLCBar previousBar = previousBar(context);
        ExpressionEvaluator evaluator =
                new ExpressionEvaluator(strategy.featureName(), bar.time(), context.barIndex());

        Optional<Position> open = context.currentPosition();
        reconcileEntryOwnership(open);
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
                recordTrigger(scenario, bar.time());
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
                    recordTrigger(scenario, bar.time());
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
                Optional<String> warmupMiss = protectiveWarmupMiss(scenario);
                if (warmupMiss.isPresent()) {
                    // The declared protective stop / take cannot be snapshotted
                    // at the prospective fill (atr_value not warm). Suppress the
                    // entry — opening without its declared protection would
                    // misrepresent the strategy; it fires later once ATR warms.
                    suppressedEntries.add(new SuppressedEntry(
                            context.currentBar().time(), scenario.name(), warmupMiss.get()));
                    continue;
                }
                recordTrigger(scenario, context.currentBar().time());
                pendingEntryScenarioName = scenario.name();
                BigDecimal quantity = quantity(context);
                return scenario.terminalCondition() == FirstClassCondition.LONG_ENTRY
                        ? new Signal.Buy(quantity)
                        : new Signal.Sell(quantity);
            }
        }
        return new Signal.Hold();
    }

    /**
     * Maintains the {@link #openPositionEntryScenario} side-map at the start
     * of every {@link #generate} call:
     * <ul>
     *   <li>If a position is open and a pending entry-scenario name is
     *       recorded but not yet bound to this position's entryTime, bind it.
     *       The Buy/Sell fires at bar T; frau-holle fills at bar T+1's open,
     *       so the binding always happens on the next {@code generate} call.
     *   <li>If no position is open, evict any stale bindings — at most one
     *       position can be open at a time, so an empty {@code currentPosition}
     *       means every entry tracked so far is closed.
     *   <li>Pyramiding keeps the same {@link Position#entryTime()}, so the
     *       binding survives subsequent same-direction entries.
     * </ul>
     */
    private void reconcileEntryOwnership(Optional<Position> open) {
        if (open.isEmpty()) {
            openPositionEntryScenario.clear();
            pendingEntryScenarioName = null;
            return;
        }
        Instant entryTime = open.get().entryTime();
        if (pendingEntryScenarioName != null
                && !openPositionEntryScenario.containsKey(entryTime)) {
            openPositionEntryScenario.put(entryTime, pendingEntryScenarioName);
        }
        pendingEntryScenarioName = null;
    }

    /**
     * Trigger bar times per Scenario name, in chronological order, accumulated
     * across the backtest. The report derives the trigger count, chart markers
     * and sub-report rows from this list (CLAUDE.md section 7.3).
     */
    public Map<String, List<Instant>> triggerTimes() {
        Map<String, List<Instant>> copy = new LinkedHashMap<>();
        triggerTimes.forEach((name, times) -> copy.put(name, List.copyOf(times)));
        return Map.copyOf(copy);
    }

    private void recordTrigger(StrategyScenario scenario, Instant barTime) {
        triggerTimes.computeIfAbsent(scenario.name(), name -> new ArrayList<>()).add(barTime);
    }

    /**
     * Entries matched but suppressed because their protective stop / take could
     * not be evaluated at the prospective fill (atr_value warmup), chronological.
     */
    public List<SuppressedEntry> suppressedEntries() {
        return List.copyOf(suppressedEntries);
    }

    /** Snapshotted stop_loss price for an open position, if its entry Scenario declared one. */
    public Optional<BigDecimal> stopLossPriceFor(Position position) {
        return entryScenarioFor(position)
                .flatMap(StrategyScenario::stopLossExpression)
                .map(expression -> evaluateProtective(expression, position));
    }

    /** Snapshotted take_profit price for an open position, if its entry Scenario declared one. */
    public Optional<BigDecimal> takeProfitPriceFor(Position position) {
        return entryScenarioFor(position)
                .flatMap(StrategyScenario::takeProfitExpression)
                .map(expression -> evaluateProtective(expression, position));
    }

    private Optional<Signal> protectiveExit(Position position, OHLCBar bar) {
        Optional<StrategyScenario> entry = entryScenarioFor(position);
        if (entry.isEmpty()) {
            return Optional.empty();
        }
        boolean isLong = position.direction() == Direction.LONG;

        // CLAUDE.md §6.3 same-bar rule: when both stop_loss and take_profit
        // would fire within the same [low, high] bar range, stop_loss wins.
        // Pessimistic convention — the backtest assumes the worse fill. The
        // check order here encodes that rule: stop first; if hit, return
        // immediately without evaluating take_profit.
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
        // Resolve at the entry fill so atr_value(period) snapshots ATR there and
        // stays frozen for the trade (every call uses position.entryTime()).
        // Lookahead-safety: ATR uses ONLY bars that closed strictly before the
        // fill. OHLCBar.time() is the bar OPEN and the fill happens at that open,
        // so the fill bar's own high/low/close are not yet known — including it
        // would let atr_value depend on future data.
        Instant fill = position.entryTime();
        List<OHLCBar> beforeFill = barsStrictlyBefore(primaryHistory, fill);
        ExpressionEvaluator.IndicatorSource source;
        if (beforeFill.isEmpty()) {
            source = NO_INDICATORS;
        } else {
            long idx = beforeFill.size() - 1;
            source = new BarIndicatorSource(beforeFill, strategy.featureName(),
                    beforeFill.getLast().time(), idx, matchIndex, timeframe.wire());
        }
        ExpressionEvaluator evaluator =
                new ExpressionEvaluator(strategy.featureName(), fill, 0);
        return evaluator.arithmetic(expression, new Scope(positionValues(position), source));
    }

    /**
     * Probes, at the entry-signal bar, whether {@code scenario}'s protective
     * stop / take could be snapshotted at the prospective fill. The fill is the
     * NEXT bar's open, so the bars that will precede it are exactly
     * {@link #primaryHistory} now (bars at or before the signal bar) — the same
     * set {@link #evaluateProtective} will use. If an {@code atr_value(period)}
     * is not yet warm over them, returns a reason string; otherwise empty.
     *
     * <p>The probe evaluates the expression with placeholder trade-context
     * values: atr_value's warmup depends only on bar history, not on
     * {@code entry_price} / {@code position_size}, so the placeholders cannot
     * change whether it throws. Only {@link IndicatorWarmupException} is caught
     * — any other failure is a genuine error and propagates.
     */
    private Optional<String> protectiveWarmupMiss(StrategyScenario scenario) {
        List<String> expressions = new ArrayList<>();
        scenario.stopLossExpression().ifPresent(expressions::add);
        scenario.takeProfitExpression().ifPresent(expressions::add);
        if (expressions.isEmpty() || primaryHistory.isEmpty()) {
            return Optional.empty();
        }
        OHLCBar last = primaryHistory.getLast();
        long idx = primaryHistory.size() - 1L;
        ExpressionEvaluator.IndicatorSource source = new BarIndicatorSource(
                primaryHistory, strategy.featureName(), last.time(), idx,
                matchIndex, timeframe.wire());
        ExpressionEvaluator.Values placeholders = name -> switch (name) {
            case "entry_price", "position_size" -> BigDecimal.ONE;
            default -> resolveParameter(name);
        };
        ExpressionEvaluator evaluator =
                new ExpressionEvaluator(strategy.featureName(), last.time(), idx);
        for (String expression : expressions) {
            try {
                evaluator.arithmetic(expression, new Scope(placeholders, source));
            } catch (IndicatorWarmupException notWarm) {
                int period = largestAtrValuePeriod(expression);
                return Optional.of("ATR not warm: needs " + period + " pre-fill bars, only "
                        + primaryHistory.size() + " available");
            }
        }
        return Optional.empty();
    }

    /** Largest {@code atr_value(period)} period referenced in {@code expression}. */
    private int largestAtrValuePeriod(String expression) {
        Matcher matcher = ATR_VALUE_CALL.matcher(expression);
        int largest = 0;
        while (matcher.find()) {
            String arg = matcher.group(1);
            BigDecimal value;
            try {
                value = new BigDecimal(arg);
            } catch (NumberFormatException notLiteral) {
                value = parameters.get(arg);
            }
            if (value != null) {
                largest = Math.max(largest, value.intValueExact());
            }
        }
        return largest;
    }

    /** Bars that closed strictly before {@code exclusiveLimit} (lookahead-safe). */
    public static List<OHLCBar> barsStrictlyBefore(List<OHLCBar> bars, Instant exclusiveLimit) {
        List<OHLCBar> out = new ArrayList<>();
        for (OHLCBar candidate : bars) {
            if (candidate.time().isBefore(exclusiveLimit)) {
                out.add(candidate);
            }
        }
        return out;
    }

    /**
     * Resolves the scenario that opened {@code position}. Consults the
     * {@link #openPositionEntryScenario} side-map first (populated by
     * {@link #reconcileEntryOwnership} when a Buy/Sell fires and the resulting
     * position appears on the next bar). Falls back to a direction-based
     * lookup for positions that were never observed through {@code generate}
     * (e.g. positions constructed directly in unit tests); the fallback
     * preserves backward compatibility but does NOT disambiguate between two
     * competing same-direction entry scenarios — which is the very situation
     * the side-map exists to fix.
     */
    private Optional<StrategyScenario> entryScenarioFor(Position position) {
        String name = openPositionEntryScenario.get(position.entryTime());
        if (name != null) {
            StrategyScenario tracked = scenariosByName.get(name);
            if (tracked != null) {
                return Optional.of(tracked);
            }
        }
        FirstClassCondition entryCondition = position.direction() == Direction.LONG
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
            try {
                if (!evaluator.condition(step.text(), current, previous)) {
                    return false;
                }
            } catch (IndicatorWarmupException notReady) {
                // An indicator or Background series is still warming up: the
                // Scenario cannot fire at this bar, but the backtest continues.
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
        List<OHLCBar> barsUpTo = barsUpToAndIncluding(context, bar);
        BarIndicatorSource indicators = new BarIndicatorSource(
                barsUpTo, strategy.featureName(), bar.time(), barIndex,
                matchIndex, timeframe.wire());
        return new Scope(barValues(bar, position, barIndex, barsUpTo), indicators);
    }

    /** All known bars with time at or before {@code bar}, in chronological order. */
    private List<OHLCBar> barsUpToAndIncluding(BarContext context, OHLCBar bar) {
        TreeMap<Instant, OHLCBar> byTime = new TreeMap<>();
        for (OHLCBar candidate : context.history()) {
            if (!candidate.time().isAfter(bar.time())) {
                byTime.put(candidate.time(), candidate);
            }
        }
        byTime.put(bar.time(), bar);
        return List.copyOf(byTime.values());
    }

    private ExpressionEvaluator.Values barValues(OHLCBar bar, Optional<Position> position,
                                                 long barIndex, List<OHLCBar> primaryBarsUpTo) {
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
            default -> {
                BackgroundSeries series = seriesByName.get(name);
                yield series != null
                        ? resolveSeries(series, bar.time(), primaryBarsUpTo)
                        : resolveParameter(name);
            }
        };
    }

    /** Evaluates a Background series at the bar visible at {@code primaryBarTime}. */
    private BigDecimal resolveSeries(BackgroundSeries series, Instant primaryBarTime,
                                     List<OHLCBar> primaryBarsUpTo) {
        List<OHLCBar> layerBars;
        OHLCBar targetBar;
        if (series.timeframe().isEmpty()) {
            layerBars = primaryBarsUpTo;
            targetBar = layerBars.getLast();
        } else {
            String wire = series.timeframe().get().wire();
            HigherTimeframeSeries resolver = higherResolvers.get(wire);
            if (resolver == null) {
                throw seriesFailure(series, primaryBarTime,
                        "no " + wire + " data was loaded for Background series " + series.name());
            }
            targetBar = resolver.resolveAt(primaryBarTime)
                    .orElseThrow(() -> new IndicatorWarmupException(
                            "no closed " + wire + " bar exists yet for series " + series.name()));
            layerBars = barsUpTo(wire, targetBar.time());
        }
        long targetIndex = layerBars.size() - 1;
        ExpressionEvaluator evaluator =
                new ExpressionEvaluator(strategy.featureName(), primaryBarTime, targetIndex);
        Scope scope = new Scope(seriesLayerValues(targetBar, series, targetIndex),
                new BarIndicatorSource(layerBars, strategy.featureName(), targetBar.time(),
                        targetIndex, matchIndex, series.timeframe()
                                .map(tf -> tf.wire()).orElse(timeframe.wire())));
        return evaluator.arithmetic(series.expression(), scope);
    }

    private List<OHLCBar> barsUpTo(String wire, Instant inclusiveLimit) {
        List<OHLCBar> bars = new ArrayList<>();
        for (OHLCBar bar : higherTimeframeBars.getOrDefault(wire, List.of())) {
            if (!bar.time().isAfter(inclusiveLimit)) {
                bars.add(bar);
            }
        }
        return bars;
    }

    private ExpressionEvaluator.Values seriesLayerValues(OHLCBar bar, BackgroundSeries series,
                                                         long barIndex) {
        return name -> switch (name) {
            case "open" -> bar.open();
            case "high" -> bar.high();
            case "low" -> bar.low();
            case "close" -> bar.close();
            case "volume" -> bar.volume().orElse(BigDecimal.ZERO);
            case "bar_index" -> BigDecimal.valueOf(barIndex);
            default -> {
                BigDecimal parameter = parameters.get(name);
                if (parameter != null) {
                    yield parameter;
                }
                throw seriesFailure(series, bar.time(),
                        "Background series expression may only reference market variables, "
                                + "parameters and indicators: " + name);
            }
        };
    }

    private DslEvaluationException seriesFailure(BackgroundSeries series, Instant barTime,
                                                 String message) {
        return new DslEvaluationException(strategy.featureName(), series.line(), barTime, 0,
                series.expression(), message);
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
