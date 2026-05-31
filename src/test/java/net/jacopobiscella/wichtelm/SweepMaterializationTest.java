package net.jacopobiscella.wichtelm;

import net.jacopobiscella.wichtelm.config.BacktestConfig;
import net.jacopobiscella.wichtelm.config.ConfigParser;
import net.jacopobiscella.wichtelm.config.SweepParameterResolver;
import net.jacopobiscella.wichtelm.error.SweepConfigException;
import net.jacopobiscella.wichtelm.strategy.ParsedStrategy;
import net.jacopobiscella.wichtelm.strategy.StrategyParser;
import net.jacopobiscella.wichtelm.sweep.SweepGrid;
import net.jacopobiscella.wichtelm.sweep.SweepObjective;
import net.jacopobiscella.wichtelm.sweep.SweepSpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Focused regression tests for {@link SweepParameterResolver} axis
 * materialization (CLAUDE.md section 18.1): integer ranges must be wholly
 * integral, decimal ranges must not overshoot the declared {@code to}, and a
 * single axis cannot allocate beyond the combinatorial cap.
 */
class SweepMaterializationTest {

    private static final String STRATEGY = """
            Feature: Sweep materialization strategy
              Primary timeframe: 1h
              Parameter rsi_period default 14
              Parameter stop_loss_pct default 2.0
              Parameter trend_period default 200

              Scenario: Enter
                Given no open position
                When close exceeds 1
                Then long_entry
            """;

    private BacktestConfig config(Path dir, String sweepBody, String fixedBody) throws IOException {
        Path strat = dir.resolve("strategy.strat");
        Files.writeString(strat, STRATEGY);
        String toml = "strategy = \"" + strat + "\"\n"
                + "symbol = \"AAPL\"\ndata_source = \"eodhd\"\n\n"
                + "[date_range]\nfrom = 2024-01-01\nto = 2024-12-31\n\n"
                + "[sizing]\nposition_size_pct = 50\n\n"
                + "[eodhd]\napi_token_env = \"EODHD_API_TOKEN\"\n"
                + fixedBody
                + "\n[sweep]\n" + sweepBody;
        return ConfigParser.parse(toml, dir.resolve("config.toml").toString());
    }

    private ParsedStrategy strategy(Path dir) {
        return StrategyParser.parse(STRATEGY, dir.resolve("strategy.strat").toString());
    }

    @Test
    void integerRangeWithFractionalEndpointsIsRejectedByC14(@TempDir Path dir) throws IOException {
        BacktestConfig config = config(dir, "rsi_period = { from = 1.5, to = 3.5, step = 1 }\n", "");
        SweepConfigException e = assertThrows(SweepConfigException.class,
                () -> SweepParameterResolver.resolveAxes(strategy(dir), config));
        assertEquals("C14", e.violatedRule());
    }

    @Test
    void decimalRangeDoesNotOvershootTo(@TempDir Path dir) throws IOException {
        BacktestConfig config = config(dir, "stop_loss_pct = { from = 0, to = 1, step = 0.6 }\n", "");
        Map<String, List<BigDecimal>> axes = SweepParameterResolver.resolveAxes(strategy(dir), config);
        List<BigDecimal> values = axes.get("stop_loss_pct");
        assertEquals(2, values.size(), () -> "expected [0, 0.6], got " + values);
        assertEquals(0, new BigDecimal("0").compareTo(values.get(0)));
        assertEquals(0, new BigDecimal("0.6").compareTo(values.get(1)));
    }

    @Test
    void decimalRangeKeepsExactEndpoint(@TempDir Path dir) throws IOException {
        BacktestConfig config = config(dir, "stop_loss_pct = { from = 1.0, to = 2.0, step = 0.5 }\n", "");
        Map<String, List<BigDecimal>> axes = SweepParameterResolver.resolveAxes(strategy(dir), config);
        List<BigDecimal> values = axes.get("stop_loss_pct");
        assertEquals(3, values.size(), () -> "expected [1.0, 1.5, 2.0], got " + values);
        assertEquals(0, new BigDecimal("2.0").compareTo(values.get(2)));
    }

    @Test
    void aSingleAxisLargerThanTheCapIsRejectedBeforeMaterializing(@TempDir Path dir) throws IOException {
        BacktestConfig config = config(dir, "trend_period = { from = 1, to = 1000000, step = 1 }\n", "");
        SweepConfigException e = assertThrows(SweepConfigException.class,
                () -> SweepParameterResolver.resolveAxes(strategy(dir), config, 500));
        assertEquals("C15", e.violatedRule());
        // The fast-path C15 message names the exact axis size and the cap.
        assertTrue(e.getMessage().contains("1000000") && e.getMessage().contains("500"),
                () -> "C15 message should name size and cap: " + e.getMessage());
    }

    @Test
    void integerValueListWithFractionalEntryIsRejectedByC14(@TempDir Path dir) throws IOException {
        Path strat = dir.resolve("strategy.strat");
        Files.writeString(strat, STRATEGY);
        String toml = "strategy = \"" + strat + "\"\n"
                + "symbol = \"AAPL\"\ndata_source = \"eodhd\"\n\n"
                + "[date_range]\nfrom = 2024-01-01\nto = 2024-12-31\n\n"
                + "[sizing]\nposition_size_pct = 50\n\n"
                + "[eodhd]\napi_token_env = \"EODHD_API_TOKEN\"\n"
                + "\n[sweep]\nrsi_period = [12, 1.5, 16]\n";
        BacktestConfig config = ConfigParser.parse(toml, dir.resolve("config.toml").toString());
        SweepConfigException e = assertThrows(SweepConfigException.class,
                () -> SweepParameterResolver.resolveAxes(strategy(dir), config));
        assertEquals("C14", e.violatedRule());
    }

    @Test
    void hugeIntegerRangeStepsExactlyBeyondDecimal64Precision(@TempDir Path dir) throws IOException {
        BacktestConfig config = config(dir,
                "trend_period = { from = 99999999999999990, to = 100000000000000000, step = 1 }\n", "");
        Map<String, List<BigDecimal>> axes = SweepParameterResolver.resolveAxes(strategy(dir), config);
        List<BigDecimal> values = axes.get("trend_period");
        assertEquals(11, values.size(),
                () -> "exact integer stepping should yield 11 values, got " + values);
        assertEquals(0, new BigDecimal("100000000000000000").compareTo(values.get(values.size() - 1)),
                () -> "last value must be exactly 'to', got " + values);
        assertEquals(0, new BigDecimal("99999999999999991").compareTo(values.get(1)),
                () -> "second value must advance by exactly one, got " + values);
    }

    @Test
    void largeEndpointDoesNotOvershootTo(@TempDir Path dir) throws IOException {
        BacktestConfig config = config(dir,
                "trend_period = { from = 999999999998, to = 1000000000000, step = 1 }\n", "");
        Map<String, List<BigDecimal>> axes =
                SweepParameterResolver.resolveAxes(strategy(dir), config, 500);
        List<BigDecimal> values = axes.get("trend_period");
        assertEquals(3, values.size(), () -> "expected the three endpoint values, got " + values);
        assertEquals(0, new BigDecimal("1000000000000").compareTo(values.get(values.size() - 1)),
                () -> "last value must be exactly 'to', got " + values);
    }

    @Test
    void nonFiniteSweepValueIsRejectedByC14(@TempDir Path dir) throws IOException {
        Path strat = dir.resolve("strategy.strat");
        Files.writeString(strat, STRATEGY);
        String toml = "strategy = \"" + strat + "\"\n"
                + "symbol = \"AAPL\"\ndata_source = \"eodhd\"\n\n"
                + "[date_range]\nfrom = 2024-01-01\nto = 2024-12-31\n\n"
                + "[sizing]\nposition_size_pct = 50\n\n"
                + "[eodhd]\napi_token_env = \"EODHD_API_TOKEN\"\n"
                + "\n[sweep]\nstop_loss_pct = { from = nan, to = 1, step = 0.5 }\n";
        SweepConfigException e = assertThrows(SweepConfigException.class,
                () -> ConfigParser.parse(toml, dir.resolve("config.toml").toString()));
        assertEquals("C14", e.violatedRule());
    }

    @Test
    void manyInRangeAxesWhoseProductOverflowsAreStillRejectedByC15() {
        List<BigDecimal> axis = new ArrayList<>();
        for (int v = 0; v < 500; v++) {
            axis.add(BigDecimal.valueOf(v));
        }
        Map<String, List<BigDecimal>> axes = new LinkedHashMap<>();
        for (int i = 0; i < 8; i++) {   // 500^8 overflows a long
            axes.put("p" + i, axis);
        }
        SweepSpec spec = new SweepSpec(axes, SweepObjective.SHARPE, 5, 500);
        assertEquals(Long.MAX_VALUE, spec.combinationCount(), "product must saturate, not wrap");
        SweepConfigException e = assertThrows(SweepConfigException.class,
                () -> SweepGrid.expand(spec, "config.toml"));
        assertEquals("C15", e.violatedRule());
    }
}
