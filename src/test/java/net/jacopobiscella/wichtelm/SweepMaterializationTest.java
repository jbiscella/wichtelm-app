package net.jacopobiscella.wichtelm;

import net.jacopobiscella.wichtelm.config.BacktestConfig;
import net.jacopobiscella.wichtelm.config.ConfigParser;
import net.jacopobiscella.wichtelm.config.SweepParameterResolver;
import net.jacopobiscella.wichtelm.error.SweepConfigException;
import net.jacopobiscella.wichtelm.strategy.ParsedStrategy;
import net.jacopobiscella.wichtelm.strategy.StrategyParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
    }
}
