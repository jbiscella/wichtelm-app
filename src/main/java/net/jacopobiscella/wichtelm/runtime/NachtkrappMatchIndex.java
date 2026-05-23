package net.jacopobiscella.wichtelm.runtime;

import net.jacopobiscella.wichtelm.strategy.BackgroundSeries;
import net.jacopobiscella.wichtelm.strategy.BuiltinCatalog;
import net.jacopobiscella.wichtelm.strategy.ParsedStrategy;
import net.jacopobiscella.wichtelm.strategy.StrategyScenario;
import net.jacopobiscella.wichtelm.strategy.StrategyStep;
import org.hatrack.commons.HABar;
import org.hatrack.commons.HASeries;
import org.hatrack.commons.HeikinAshiCalculator;
import org.hatrack.commons.OHLCBar;
import org.hatrack.commons.OHLCSeries;
import org.hatrack.commons.PivotLevel;
import org.hatrack.commons.PivotPointVariant;
import org.hatrack.commons.PriceSource;
import org.hatrack.commons.Timeframe;
import org.hatrack.nachtkrapp.detector.DetectionResult;
import org.hatrack.nachtkrapp.detector.RuleBasedPatternDetector;
import org.hatrack.nachtkrapp.error.DetectionException;
import org.hatrack.nachtkrapp.match.PatternMatch;
import org.hatrack.nachtkrapp.rule.DetectionRule;
import org.hatrack.nachtkrapp.rule.DetectionRule.HAColorChangeRule;
import org.hatrack.nachtkrapp.rule.DetectionRule.HADojiRule;
import org.hatrack.nachtkrapp.rule.DetectionRule.HAStrongCandleRule;
import org.hatrack.nachtkrapp.rule.DetectionRule.MACDSignalCrossRule;
import org.hatrack.nachtkrapp.rule.DetectionRule.MACDZeroCrossRule;
import org.hatrack.nachtkrapp.rule.DetectionRule.MACrossMARule;
import org.hatrack.nachtkrapp.rule.DetectionRule.MAVsMARule;
import org.hatrack.nachtkrapp.rule.DetectionRule.PivotPointRule;
import org.hatrack.nachtkrapp.rule.DetectionRule.PriceMACrossRule;
import org.hatrack.nachtkrapp.rule.DetectionRule.PriceVsMARule;
import org.hatrack.nachtkrapp.rule.DetectionRule.RSILevel50CrossRule;
import org.hatrack.nachtkrapp.rule.DetectionRule.RSIThresholdRule;
import org.hatrack.nachtkrapp.rule.MAType;
import org.hatrack.nachtkrapp.spec.DetectionSpec;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pre-computed lookup table of nachtkrapp pattern-match times per Tier B
 * primitive call referenced anywhere in the strategy. Built ONCE at backtest
 * setup against the closed primary series; the per-bar evaluator then does an
 * O(1) {@link Set#contains(Object)} on a known {@link Key}.
 *
 * <p>Two detection passes run inside {@link #buildFor}:
 * <ul>
 *   <li>HA rules ({@code HADojiRule}, {@code HAColorChangeRule},
 *       {@code HAStrongCandleRule}) require an {@code HASeries}; the primary
 *       OHLC series is converted via
 *       {@link HeikinAshiCalculator#computeChain(Optional, List)} once.</li>
 *   <li>Price / RSI / MACD rules require an {@code OHLCSeries} when their
 *       {@code PriceSource} is {@code CLOSE} (we don't use the HA_* variants
 *       today); the primary OHLC series is passed as-is.</li>
 * </ul>
 *
 * <p>Lookahead-safety: the engine produces a {@code List<PatternMatch>} sorted
 * by match time; each match's {@code time()} is the bar at which the pattern
 * was emitted (the rule itself only looks at bars up to and including that
 * time). Pre-computing the whole match set against the closed series is
 * therefore equivalent to a per-bar evaluation that only sees the bars visible
 * at that bar.
 */
public final class NachtkrappMatchIndex {

    /**
     * Per-bar lookup key: function name + resolved numeric argument list +
     * timeframe wire string. The timeframe disambiguates calls of the same
     * primitive declared on different timeframes (e.g. {@code ha_doji() on 1d}
     * vs the same call against the primary series); without it, a multi-TF
     * strategy would conflate match instants between timeframes whenever
     * timestamps coincide (1d bar at midnight UTC vs 1h bar at midnight UTC).
     */
    public record Key(String name, List<BigDecimal> args, String timeframeWire,
                      List<String> symbolicArgs) {
        public Key {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(timeframeWire, "timeframeWire");
            args = List.copyOf(args);
            symbolicArgs = List.copyOf(symbolicArgs);
        }

        /** Numeric-only key (all primitives except pivots): no symbolic args. */
        public Key(String name, List<BigDecimal> args, String timeframeWire) {
            this(name, args, timeframeWire, List.of());
        }

        /**
         * Pivot key — the level (e.g. {@code "R1"}) stays symbolic end-to-end
         * rather than being encoded as a sentinel number, so the prepass, the
         * index lookup and the evaluator all speak the same level token.
         */
        public static Key pivot(String name, String level, String timeframeWire) {
            return new Key(name, List.of(), timeframeWire, List.of(level));
        }
    }

    private static final BigDecimal DEFAULT_DOJI_MAX_BODY = new BigDecimal("0.1");
    private static final BigDecimal STRONG_WICK_TOLERANCE = new BigDecimal("0.05");
    private static final BigDecimal STRONG_MIN_BODY = new BigDecimal("0.6");
    private static final BigDecimal RSI_SENTINEL_OVERBOUGHT = new BigDecimal("70");
    private static final BigDecimal RSI_SENTINEL_OVERSOLD = new BigDecimal("30");
    private static final int DEFAULT_RSI_PERIOD = 14;
    private static final int DEFAULT_MACD_FAST = 12;
    private static final int DEFAULT_MACD_SLOW = 26;
    private static final int DEFAULT_MACD_SIGNAL = 9;

    /** The 13 Tier B primitive names recognised by the AST walker. */
    private static final Set<String> TIER_B_NAMES = Set.of(
            "ha_doji", "ha_strong", "ha_strong_bullish", "ha_strong_bearish",
            "ha_bullish_reversal", "ha_bearish_reversal",
            "rsi_overbought", "rsi_oversold", "rsi_crosses_50",
            "macd_bullish_cross", "macd_bearish_cross",
            "macd_zero_cross_up", "macd_zero_cross_down",
            "price_above_sma", "price_below_sma", "price_above_ema", "price_below_ema",
            "price_crosses_above_sma", "price_crosses_below_sma",
            "price_crosses_above_ema", "price_crosses_below_ema",
            "sma_above_ema", "sma_crosses_above_ema", "sma_crosses_below_ema",
            "price_above_pivot", "price_below_pivot",
            "price_crosses_above_pivot", "price_crosses_below_pivot");

    private static final Pattern CALL_PATTERN = Pattern.compile(
            "\\b(ha_doji|ha_strong_bullish|ha_strong_bearish|ha_strong|"
                    + "ha_bullish_reversal|ha_bearish_reversal|"
                    + "rsi_overbought|rsi_oversold|rsi_crosses_50|"
                    + "macd_bullish_cross|macd_bearish_cross|"
                    + "macd_zero_cross_up|macd_zero_cross_down|"
                    + "price_crosses_above_sma|price_crosses_below_sma|"
                    + "price_crosses_above_ema|price_crosses_below_ema|"
                    + "price_above_sma|price_below_sma|price_above_ema|price_below_ema|"
                    + "sma_crosses_above_ema|sma_crosses_below_ema|sma_above_ema|"
                    + "price_crosses_above_pivot|price_crosses_below_pivot|"
                    + "price_above_pivot|price_below_pivot)"
                    + "\\s*\\(([^)]*)\\)");

    /** Names of Tier B primitives — exposed for the evaluator / catalog. */
    public static Set<String> tierBNames() {
        return TIER_B_NAMES;
    }

    private final Map<Key, Set<Instant>> matchesByKey;

    private NachtkrappMatchIndex(Map<Key, Set<Instant>> matchesByKey) {
        this.matchesByKey = Map.copyOf(matchesByKey);
    }

    /** Empty index — used when a strategy declares no Tier B primitives. */
    public static NachtkrappMatchIndex empty() {
        return new NachtkrappMatchIndex(Map.of());
    }

    /** True iff {@code key} matches at {@code barTime}. */
    public boolean matches(Key key, Instant barTime) {
        Set<Instant> times = matchesByKey.get(key);
        return times != null && times.contains(barTime);
    }

    /** Whether the strategy references this Tier B primitive at all (regardless of bar). */
    public boolean hasKey(Key key) {
        return matchesByKey.containsKey(key);
    }

    // ─── Construction ────────────────────────────────────────────────────────

    /**
     * Walks {@code strategy}'s Background series + Scenario steps to collect
     * every Tier B primitive call, runs nachtkrapp detection passes against
     * the appropriate series (OHLC vs HA) at the appropriate timeframe, and
     * indexes the resulting match instants per call key.
     *
     * <p>Each Tier B call carries a timeframe inferred from where it was
     * declared: a Background series with {@code on 1d} runs against the 1d
     * bar stream; calls directly in Scenario steps and Background series
     * without {@code on} run against the primary series. The detection pass
     * is grouped by timeframe so multi-TF strategies index against the
     * correct bar timestamps rather than projecting higher-TF rules onto
     * primary bars.
     */
    public static NachtkrappMatchIndex buildFor(ParsedStrategy strategy,
                                                Map<String, BigDecimal> parameters,
                                                List<OHLCBar> primarySeries,
                                                Map<String, List<OHLCBar>> higherTimeframeBars) {
        String primaryTfWire = strategy.primaryTimeframe().wire();
        List<KeySpec> specs = collectKeySpecs(strategy, parameters, primaryTfWire);
        if (specs.isEmpty()) {
            return empty();
        }

        // Group rules by (timeframeWire, haOrNot) so we can run one
        // DetectionEngine pass per group against the right bar stream.
        record Group(String tfWire, boolean ha) {
        }
        Map<Group, Set<DetectionRule>> rulesByGroup = new HashMap<>();
        for (KeySpec spec : specs) {
            Group g = new Group(spec.key().timeframeWire(), spec.haRule());
            rulesByGroup.computeIfAbsent(g, k -> new LinkedHashSet<>()).add(spec.rule());
        }

        // Per-group matches — keyed by tfWire so we can look up per-Key later.
        Map<String, List<PatternMatch>> matchesByTf = new HashMap<>();
        try {
            for (var entry : rulesByGroup.entrySet()) {
                Group group = entry.getKey();
                Set<DetectionRule> rules = entry.getValue();
                List<OHLCBar> sourceBars = group.tfWire().equals(primaryTfWire)
                        ? primarySeries
                        : higherTimeframeBars.get(group.tfWire());
                if (sourceBars == null) {
                    throw new IllegalStateException("no bars supplied for timeframe "
                            + group.tfWire() + " referenced by a Tier B primitive");
                }
                // A rule whose warmup exceeds the available series cannot emit
                // any match, and nachtkrapp rejects such a spec ([V6] insufficient
                // data). Drop those rules so the primitive simply evaluates false
                // everywhere (warmup → false, no exception); their keys fall
                // through to an empty match set in the per-KeySpec loop below.
                Set<DetectionRule> validRules = new LinkedHashSet<>();
                for (DetectionRule rule : rules) {
                    if (rule.minBars() <= sourceBars.size()) {
                        validRules.add(rule);
                    }
                }
                if (validRules.isEmpty()) {
                    continue;
                }
                DetectionSpec spec;
                if (group.ha()) {
                    List<HABar> haBars = HeikinAshiCalculator.computeChain(
                            Optional.empty(), sourceBars);
                    spec = DetectionSpec.builder()
                            .withSeries(new HASeries(haBars))
                            .addAllRules(validRules)
                            .build();
                } else {
                    spec = DetectionSpec.builder()
                            .withSeries(new OHLCSeries(sourceBars))
                            .addAllRules(validRules)
                            .build();
                }
                DetectionResult result = new RuleBasedPatternDetector().detect(spec);
                matchesByTf.computeIfAbsent(group.tfWire(), k -> new ArrayList<>())
                        .addAll(result.matches());
            }
        } catch (DetectionException e) {
            throw new IllegalStateException(
                    "nachtkrapp detection failed during Tier B prepass: " + e.getMessage(), e);
        }

        Map<Key, Set<Instant>> result = new HashMap<>();
        for (KeySpec spec : specs) {
            Set<Instant> times = new HashSet<>();
            List<PatternMatch> candidates = matchesByTf.getOrDefault(
                    spec.key().timeframeWire(), List.of());
            for (PatternMatch match : candidates) {
                if (spec.filter().test(match)) {
                    times.add(match.time());
                }
            }
            result.put(spec.key(), times);
        }
        return new NachtkrappMatchIndex(result);
    }

    private record KeySpec(Key key, DetectionRule rule, Predicate<PatternMatch> filter,
                           boolean haRule) {
    }

    private static List<KeySpec> collectKeySpecs(ParsedStrategy strategy,
                                                  Map<String, BigDecimal> parameters,
                                                  String primaryTfWire) {
        Set<Key> seen = new HashSet<>();
        List<KeySpec> specs = new ArrayList<>();
        for (BackgroundSeries series : strategy.backgroundSeries()) {
            // A Background series declared `on <htf>` evaluates against the
            // higher-TF bars; a series declared without `on` evaluates against
            // the primary. Tier B calls inside the expression follow the same
            // timeframe.
            String tfWire = series.timeframe()
                    .map(tf -> tf.wire())
                    .orElse(primaryTfWire);
            scan(series.expression(), parameters, tfWire, seen, specs);
        }
        for (StrategyScenario scenario : strategy.scenarios()) {
            for (StrategyStep step : scenario.conditionSteps()) {
                // Tier B calls directly in a Scenario step always evaluate on
                // the primary timeframe (Scenario evaluation iterates the
                // primary bar series); references to higher-TF Background
                // series happen by NAME and are resolved through the layered
                // scope in WichtelmSignalGenerator.
                scan(step.text(), parameters, primaryTfWire, seen, specs);
            }
        }
        return specs;
    }

    private static void scan(String text, Map<String, BigDecimal> parameters, String tfWire,
                              Set<Key> seen, List<KeySpec> specs) {
        Matcher matcher = CALL_PATTERN.matcher(text);
        while (matcher.find()) {
            String name = matcher.group(1);
            // Pivot primitives carry a symbolic level token (R1, S2…) — keep it
            // symbolic rather than forcing it through the numeric parseArgs.
            Key key = BuiltinCatalog.isPivotPrimitive(name)
                    ? Key.pivot(name, matcher.group(2).strip(), tfWire)
                    : new Key(name, parseArgs(name, matcher.group(2), parameters), tfWire);
            if (seen.add(key)) {
                specs.add(buildKeySpec(key));
            }
        }
    }

    private static List<BigDecimal> parseArgs(String functionName, String raw,
                                               Map<String, BigDecimal> parameters) {
        String stripped = raw.strip();
        if (stripped.isEmpty()) {
            return List.of();
        }
        List<BigDecimal> out = new ArrayList<>();
        for (String part : stripped.split("\\s*,\\s*")) {
            String token = part.strip();
            if (token.isEmpty()) {
                continue;
            }
            BigDecimal fromParam = parameters.get(token);
            if (fromParam != null) {
                out.add(fromParam);
                continue;
            }
            try {
                out.add(new BigDecimal(token));
            } catch (NumberFormatException e) {
                // Tier B primitive args must be numeric literals or declared
                // strategy parameter names. Anything else (e.g. a market
                // variable like `close`, an unknown identifier, or a Background
                // series name) cannot be resolved to a constant the underlying
                // nachtkrapp Rule needs at construction time.
                throw new IllegalArgumentException(
                        "Tier B primitive '" + functionName + "' argument '" + token
                                + "' is not a numeric literal or a declared parameter; "
                                + "arguments must be constants that can resolve at "
                                + "prepass time");
            }
        }
        return out;
    }

    private static KeySpec buildKeySpec(Key key) {
        String name = key.name();
        List<BigDecimal> args = key.args();
        return switch (name) {
            case "ha_doji" -> {
                BigDecimal maxBody = args.isEmpty() ? DEFAULT_DOJI_MAX_BODY : args.getFirst();
                DetectionRule rule = new HADojiRule(maxBody);
                // The rule itself filters by maxBodyRatio at detection time, so
                // matches in the result already satisfy bodyRatio <= maxBody.
                // The bodyRatio bound is re-checked here so a second
                // ha_doji(stricter) call doesn't see the looser rule's matches.
                Predicate<PatternMatch> filter = m -> m instanceof PatternMatch.HADoji doji
                        && doji.bodyRatio().compareTo(maxBody) <= 0;
                yield new KeySpec(key, rule, filter, true);
            }
            case "ha_strong" -> {
                DetectionRule rule = new HAStrongCandleRule(STRONG_WICK_TOLERANCE, STRONG_MIN_BODY);
                Predicate<PatternMatch> filter = m -> m instanceof PatternMatch.HABullishStrong
                        || m instanceof PatternMatch.HABearishStrong;
                yield new KeySpec(key, rule, filter, true);
            }
            case "ha_strong_bullish" -> {
                DetectionRule rule = new HAStrongCandleRule(STRONG_WICK_TOLERANCE, STRONG_MIN_BODY);
                Predicate<PatternMatch> filter = m -> m instanceof PatternMatch.HABullishStrong;
                yield new KeySpec(key, rule, filter, true);
            }
            case "ha_strong_bearish" -> {
                DetectionRule rule = new HAStrongCandleRule(STRONG_WICK_TOLERANCE, STRONG_MIN_BODY);
                Predicate<PatternMatch> filter = m -> m instanceof PatternMatch.HABearishStrong;
                yield new KeySpec(key, rule, filter, true);
            }
            case "ha_bullish_reversal" -> {
                int streak = args.getFirst().intValueExact();
                DetectionRule rule = new HAColorChangeRule(streak);
                Predicate<PatternMatch> filter = m -> m instanceof PatternMatch.HABullishReversal r
                        && r.streakLength() >= streak;
                yield new KeySpec(key, rule, filter, true);
            }
            case "ha_bearish_reversal" -> {
                int streak = args.getFirst().intValueExact();
                DetectionRule rule = new HAColorChangeRule(streak);
                Predicate<PatternMatch> filter = m -> m instanceof PatternMatch.HABearishReversal r
                        && r.streakLength() >= streak;
                yield new KeySpec(key, rule, filter, true);
            }
            case "rsi_overbought" -> {
                BigDecimal threshold = args.getFirst();
                DetectionRule rule = new RSIThresholdRule(
                        DEFAULT_RSI_PERIOD, threshold, RSI_SENTINEL_OVERSOLD, PriceSource.CLOSE);
                Predicate<PatternMatch> filter = m -> m instanceof PatternMatch.RSIOverbought r
                        && r.threshold().compareTo(threshold) == 0;
                yield new KeySpec(key, rule, filter, false);
            }
            case "rsi_oversold" -> {
                BigDecimal threshold = args.getFirst();
                DetectionRule rule = new RSIThresholdRule(
                        DEFAULT_RSI_PERIOD, RSI_SENTINEL_OVERBOUGHT, threshold, PriceSource.CLOSE);
                Predicate<PatternMatch> filter = m -> m instanceof PatternMatch.RSIOversold r
                        && r.threshold().compareTo(threshold) == 0;
                yield new KeySpec(key, rule, filter, false);
            }
            case "rsi_crosses_50" -> {
                DetectionRule rule = new RSILevel50CrossRule(DEFAULT_RSI_PERIOD, PriceSource.CLOSE);
                Predicate<PatternMatch> filter = m -> m instanceof PatternMatch.RSICrossedAbove50
                        || m instanceof PatternMatch.RSICrossedBelow50;
                yield new KeySpec(key, rule, filter, false);
            }
            case "macd_bullish_cross" -> {
                DetectionRule rule = new MACDSignalCrossRule(
                        DEFAULT_MACD_FAST, DEFAULT_MACD_SLOW, DEFAULT_MACD_SIGNAL, PriceSource.CLOSE);
                Predicate<PatternMatch> filter = m -> m instanceof PatternMatch.MACDBullishCross;
                yield new KeySpec(key, rule, filter, false);
            }
            case "macd_bearish_cross" -> {
                DetectionRule rule = new MACDSignalCrossRule(
                        DEFAULT_MACD_FAST, DEFAULT_MACD_SLOW, DEFAULT_MACD_SIGNAL, PriceSource.CLOSE);
                Predicate<PatternMatch> filter = m -> m instanceof PatternMatch.MACDBearishCross;
                yield new KeySpec(key, rule, filter, false);
            }
            case "macd_zero_cross_up" -> {
                DetectionRule rule = new MACDZeroCrossRule(
                        DEFAULT_MACD_FAST, DEFAULT_MACD_SLOW, DEFAULT_MACD_SIGNAL, PriceSource.CLOSE);
                Predicate<PatternMatch> filter = m -> m instanceof PatternMatch.MACDCrossedAboveZero;
                yield new KeySpec(key, rule, filter, false);
            }
            case "macd_zero_cross_down" -> {
                DetectionRule rule = new MACDZeroCrossRule(
                        DEFAULT_MACD_FAST, DEFAULT_MACD_SLOW, DEFAULT_MACD_SIGNAL, PriceSource.CLOSE);
                Predicate<PatternMatch> filter = m -> m instanceof PatternMatch.MACDCrossedBelowZero;
                yield new KeySpec(key, rule, filter, false);
            }
            case "price_above_sma" -> priceVsMa(key, MAType.SMA, true);
            case "price_below_sma" -> priceVsMa(key, MAType.SMA, false);
            case "price_above_ema" -> priceVsMa(key, MAType.EMA, true);
            case "price_below_ema" -> priceVsMa(key, MAType.EMA, false);
            case "price_crosses_above_sma" -> priceMaCross(key, MAType.SMA, true);
            case "price_crosses_below_sma" -> priceMaCross(key, MAType.SMA, false);
            case "price_crosses_above_ema" -> priceMaCross(key, MAType.EMA, true);
            case "price_crosses_below_ema" -> priceMaCross(key, MAType.EMA, false);
            case "sma_above_ema" -> maVsMa(key);
            case "sma_crosses_above_ema" -> maCross(key, true);
            case "sma_crosses_below_ema" -> maCross(key, false);
            case "price_above_pivot" -> pivot(key, PivotKind.ABOVE);
            case "price_below_pivot" -> pivot(key, PivotKind.BELOW);
            case "price_crosses_above_pivot" -> pivot(key, PivotKind.CROSS_ABOVE);
            case "price_crosses_below_pivot" -> pivot(key, PivotKind.CROSS_BELOW);
            default -> throw new IllegalArgumentException(
                    "not a Tier B primitive name: " + name);
        };
    }

    // ─── MA trend filter primitives (ha-track 0.52) ───────────────────────────

    private static KeySpec priceVsMa(Key key, MAType maType, boolean above) {
        int period = key.args().getFirst().intValueExact();
        DetectionRule rule = new PriceVsMARule(maType, period, PriceSource.CLOSE);
        Predicate<PatternMatch> filter = above
                ? m -> m instanceof PatternMatch.PriceAboveMA a
                        && a.maType() == maType && a.period() == period
                : m -> m instanceof PatternMatch.PriceBelowMA b
                        && b.maType() == maType && b.period() == period;
        return new KeySpec(key, rule, filter, false);
    }

    private static KeySpec priceMaCross(Key key, MAType maType, boolean up) {
        int period = key.args().getFirst().intValueExact();
        DetectionRule rule = new PriceMACrossRule(maType, period, PriceSource.CLOSE);
        Predicate<PatternMatch> filter = up
                ? m -> m instanceof PatternMatch.PriceCrossedAboveMA a
                        && a.maType() == maType && a.period() == period
                : m -> m instanceof PatternMatch.PriceCrossedBelowMA b
                        && b.maType() == maType && b.period() == period;
        return new KeySpec(key, rule, filter, false);
    }

    private static KeySpec maVsMa(Key key) {
        int smaPeriod = key.args().get(0).intValueExact();
        int emaPeriod = key.args().get(1).intValueExact();
        DetectionRule rule = new MAVsMARule(MAType.SMA, smaPeriod, MAType.EMA, emaPeriod,
                PriceSource.CLOSE);
        Predicate<PatternMatch> filter = m -> m instanceof PatternMatch.MAAboveMA x
                && x.aType() == MAType.SMA && x.aPeriod() == smaPeriod
                && x.bType() == MAType.EMA && x.bPeriod() == emaPeriod;
        return new KeySpec(key, rule, filter, false);
    }

    private static KeySpec maCross(Key key, boolean up) {
        int smaPeriod = key.args().get(0).intValueExact();
        int emaPeriod = key.args().get(1).intValueExact();
        DetectionRule rule = new MACrossMARule(MAType.SMA, smaPeriod, MAType.EMA, emaPeriod,
                PriceSource.CLOSE);
        Predicate<PatternMatch> filter = up
                ? m -> m instanceof PatternMatch.MACrossedAboveMA x
                        && x.aType() == MAType.SMA && x.aPeriod() == smaPeriod
                        && x.bType() == MAType.EMA && x.bPeriod() == emaPeriod
                : m -> m instanceof PatternMatch.MACrossedBelowMA x
                        && x.aType() == MAType.SMA && x.aPeriod() == smaPeriod
                        && x.bType() == MAType.EMA && x.bPeriod() == emaPeriod;
        return new KeySpec(key, rule, filter, false);
    }

    // ─── Pivot point primitives (ha-track 0.52) ───────────────────────────────

    private static final Timeframe DAILY_PIVOT_PERIOD = Timeframe.fromWire("1d");

    private enum PivotKind { ABOVE, BELOW, CROSS_ABOVE, CROSS_BELOW }

    /**
     * Builds a STANDARD daily-pivot KeySpec. The {@link PivotPointRule}
     * aggregates the source series to daily bars internally (UTC boundaries)
     * and reads the prior completed day's OHLC, so it runs against the same
     * primary OHLC stream as the other price primitives. The filter keeps only
     * matches of the requested flavour AND the requested level (e.g. R1), so a
     * second call on a different level reuses the one detection pass.
     */
    private static KeySpec pivot(Key key, PivotKind kind) {
        PivotLevel level = PivotLevel.valueOf(key.symbolicArgs().getFirst());
        DetectionRule rule = new PivotPointRule(
                DAILY_PIVOT_PERIOD, PivotPointVariant.STANDARD, PriceSource.CLOSE);
        Predicate<PatternMatch> filter = switch (kind) {
            case ABOVE -> m -> m instanceof PatternMatch.PriceAbovePivot p && p.level() == level;
            case BELOW -> m -> m instanceof PatternMatch.PriceBelowPivot p && p.level() == level;
            case CROSS_ABOVE ->
                    m -> m instanceof PatternMatch.PriceCrossedAbovePivot p && p.level() == level;
            case CROSS_BELOW ->
                    m -> m instanceof PatternMatch.PriceCrossedBelowPivot p && p.level() == level;
        };
        return new KeySpec(key, rule, filter, false);
    }
}
