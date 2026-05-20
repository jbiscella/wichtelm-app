package net.jacopobiscella.wichtelm;

import net.jacopobiscella.wichtelm.runtime.NachtkrappMatchIndex;
import net.jacopobiscella.wichtelm.strategy.ParsedStrategy;
import net.jacopobiscella.wichtelm.strategy.StrategyParser;
import org.hatrack.commons.OHLCBar;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Concern 4 (external review): the Tier B prepass builds
 * {@link NachtkrappMatchIndex} once against the full primary series. That is
 * only equivalent to per-bar evaluation if every nachtkrapp detection rule
 * the wichtelm catalog uses is CAUSAL — i.e. each match's instant only
 * depends on bars at or before that instant. If any rule starts looking
 * forward (e.g. peak confirmation, smoothing, future-bar lookback), prepass
 * results would silently drift from the prefix view.
 *
 * <p>This test enforces the invariant: builds the index twice — once over
 * the full synthetic series, once over the first {@code k} bars — and
 * asserts that for every instant up to {@code series.get(k-1).time()}, both
 * passes return the same match set. Several {@code k} values are exercised
 * so warmup boundaries and mid-series intervals are both covered. If this
 * test ever fails, the offending rule is non-causal and a ha-track issue
 * should be filed with the failing strategy / series.
 */
class NachtkrappPrepassCausalityTest {

    private static final BigDecimal POINT_FIVE = new BigDecimal("0.5");
    private static final BigDecimal POINT_THREE = new BigDecimal("0.3");
    private static final BigDecimal POINT_ONE = new BigDecimal("0.1");

    /**
     * A strategy that exercises both an HA rule ({@code ha_doji}) and a
     * non-HA rule ({@code rsi_oversold}) so the test covers both detection
     * branches inside {@link NachtkrappMatchIndex#buildFor}.
     */
    private static final String STRATEGY = """
            Feature: Causality test
              Primary timeframe: 1h

              Scenario: Enter
                Given no open position
                When ha_doji()
                And rsi_oversold(30)
                Then long_entry

              Scenario: Exit
                Given a long position is open
                When close drops below 0
                Then long_exit
            """;

    /** 200 bars: well past RSI(14) and MACD(26) warmups, enough cross events. */
    private static final List<OHLCBar> SERIES = buildSeries(200);

    /**
     * A meandering OHLC series that produces several HA dojis (small bodies
     * from alternating direction) and RSI oversold events (a deliberate
     * dip in the middle).
     */
    private static List<OHLCBar> buildSeries(int barCount) {
        List<OHLCBar> bars = new ArrayList<>(barCount);
        Instant start = Instant.parse("2024-01-01T00:00:00Z");
        BigDecimal price = new BigDecimal("100");
        for (int i = 0; i < barCount; i++) {
            BigDecimal drift;
            if (i >= 60 && i < 120) {
                // Sustained dip: drives RSI into oversold territory.
                drift = POINT_THREE.negate();
            } else if (i % 5 == 0) {
                drift = POINT_FIVE;
            } else {
                drift = i % 2 == 0 ? POINT_ONE.negate() : POINT_ONE;
            }
            BigDecimal open = price;
            BigDecimal close = price.add(drift);
            BigDecimal high = open.max(close).add(POINT_THREE);
            BigDecimal low = open.min(close).subtract(POINT_THREE);
            bars.add(new OHLCBar(start.plus(Duration.ofHours(i)), open, high, low, close,
                    Optional.empty()));
            price = close;
        }
        return List.copyOf(bars);
    }

    @ParameterizedTest(name = "prepass(prefix k={0}) ≡ prepass(full)[t ≤ k-1]")
    @ValueSource(ints = { 30, 100, 150, 199 })
    void prepassOnPrefixMatchesFullSeriesUpToPrefixEnd(int k) {
        ParsedStrategy strategy = StrategyParser.parse(STRATEGY, "causality.strat");

        List<OHLCBar> prefix = SERIES.subList(0, k);
        Instant prefixEnd = prefix.getLast().time();

        NachtkrappMatchIndex prefixIndex =
                NachtkrappMatchIndex.buildFor(strategy, Map.of(), prefix, Map.of());
        NachtkrappMatchIndex fullIndex =
                NachtkrappMatchIndex.buildFor(strategy, Map.of(), SERIES, Map.of());

        NachtkrappMatchIndex.Key dojiKey =
                new NachtkrappMatchIndex.Key("ha_doji", List.of(),
                        strategy.primaryTimeframe().wire());
        NachtkrappMatchIndex.Key oversoldKey =
                new NachtkrappMatchIndex.Key("rsi_oversold", List.of(new BigDecimal("30")),
                        strategy.primaryTimeframe().wire());

        assertCausalForKey(prefixIndex, fullIndex, dojiKey, prefix, prefixEnd, k);
        assertCausalForKey(prefixIndex, fullIndex, oversoldKey, prefix, prefixEnd, k);
    }

    private static void assertCausalForKey(NachtkrappMatchIndex prefixIndex,
                                            NachtkrappMatchIndex fullIndex,
                                            NachtkrappMatchIndex.Key key,
                                            List<OHLCBar> prefix,
                                            Instant prefixEnd,
                                            int k) {
        assertNotNull(prefixIndex);
        assertNotNull(fullIndex);
        // For every bar in the prefix, the prepass-on-prefix and the
        // prepass-on-full result MUST agree. Disagreement at any t means the
        // detection rule used a bar after t — i.e. it is non-causal.
        for (OHLCBar bar : prefix) {
            Instant t = bar.time();
            boolean prefixHit = prefixIndex.matches(key, t);
            boolean fullHit = fullIndex.matches(key, t);
            assertEquals(fullHit, prefixHit,
                    () -> "non-causal rule for " + key.name() + " at " + t
                            + " (prefix k=" + k + ", prefixEnd=" + prefixEnd + ")");
        }
    }

    @Test
    void prefixAndFullProduceIdenticalKeySetsForReferencedCalls() {
        ParsedStrategy strategy = StrategyParser.parse(STRATEGY, "causality.strat");
        List<OHLCBar> prefix = SERIES.subList(0, 80);

        NachtkrappMatchIndex prefixIndex =
                NachtkrappMatchIndex.buildFor(strategy, Map.of(), prefix, Map.of());
        NachtkrappMatchIndex fullIndex =
                NachtkrappMatchIndex.buildFor(strategy, Map.of(), SERIES, Map.of());

        NachtkrappMatchIndex.Key doji =
                new NachtkrappMatchIndex.Key("ha_doji", List.of(),
                        strategy.primaryTimeframe().wire());
        // Both indices must KNOW the call (i.e. the AST scan registered it),
        // regardless of whether the rule fired during the prefix or not.
        assertEquals(true, prefixIndex.hasKey(doji),
                "ha_doji() must be indexed even when it never fires on the prefix");
        assertEquals(true, fullIndex.hasKey(doji));
    }
}
