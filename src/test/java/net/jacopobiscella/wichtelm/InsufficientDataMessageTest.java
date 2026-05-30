package net.jacopobiscella.wichtelm;

import net.jacopobiscella.wichtelm.config.BacktestConfig;
import net.jacopobiscella.wichtelm.config.DataSource;
import net.jacopobiscella.wichtelm.error.DataSourceUnavailableException;
import net.jacopobiscella.wichtelm.runtime.BacktestRunner;
import net.jacopobiscella.wichtelm.strategy.ParsedStrategy;
import net.jacopobiscella.wichtelm.strategy.StrategyParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * When the data source returns a single bar, the run must fail with an actionable
 * message rather than the opaque frau-holle {@code invalid backtest spec [V6]: 1}.
 * The runner pre-checks the primary series size and names the bar count, symbol,
 * timeframe and window, plus a data-source-specific hint.
 */
class InsufficientDataMessageTest {

    private static final String DAILY_STRATEGY = """
            Feature: insufficient-data
              Primary timeframe: 1d

              Scenario: enter
                Given no open position
                When close is above 0
                Then long_entry

              Scenario: exit
                Given a long position is open
                When close is below 0
                Then long_exit
            """;

    @TempDir
    Path tempDir;

    @Test
    void singleBarCsvProducesAnActionableMessage() throws Exception {
        // One daily bar inside the requested window → series size 1 (< 2).
        Files.writeString(tempDir.resolve("AAPL_1d.csv"),
                "time,open,high,low,close\n2024-01-02T00:00:00Z,100,102,98,100\n");
        ParsedStrategy strategy = StrategyParser.parse(DAILY_STRATEGY, "insufficient-data.strat");

        DataSourceUnavailableException ex = assertThrows(DataSourceUnavailableException.class,
                () -> new BacktestRunner().run(strategy, csvConfig(), Map.of()));

        String msg = ex.getMessage();
        assertTrue(msg.contains("only 1 1d bar"), () -> "bar count/timeframe missing: " + msg);
        assertTrue(msg.contains("AAPL"), () -> "symbol missing: " + msg);
        assertTrue(msg.contains("at least 2"), () -> "minimum-bars hint missing: " + msg);
        assertTrue(msg.toLowerCase().contains("csv"), () -> "data-source hint missing: " + msg);
        // The opaque frau-holle code must no longer be the entire message.
        assertTrue(!msg.contains("[V6]"), () -> "still surfacing the raw [V6] code: " + msg);
    }

    private BacktestConfig csvConfig() {
        return new BacktestConfig(
                tempDir.resolve("config.toml"),
                tempDir.resolve("strategy.strat"),
                "AAPL",
                LocalDate.parse("2024-01-01"),
                LocalDate.parse("2024-01-10"),
                DataSource.CSV,
                BigDecimal.valueOf(50),
                false,
                Map.of(),
                Optional.empty(),
                Optional.of(tempDir.resolve("{symbol}_{timeframe}.csv")),
                Optional.empty(),
                List.of());
    }
}
