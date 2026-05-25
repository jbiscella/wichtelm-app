package net.jacopobiscella.wichtelm.report;

import net.jacopobiscella.wichtelm.config.BacktestConfig;
import net.jacopobiscella.wichtelm.config.DataSource;
import net.jacopobiscella.wichtelm.runtime.BacktestRunResult;
import net.jacopobiscella.wichtelm.runtime.BacktestRunner;
import net.jacopobiscella.wichtelm.strategy.ParsedStrategy;
import net.jacopobiscella.wichtelm.strategy.StrategyParser;
import org.hatrack.frauholle.error.BacktestException;
import org.hatrack.frauholle.model.Trade;
import org.hatrack.frauholle.result.BacktestMetrics;
import org.hatrack.frauholle.result.BacktestResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reconciles the headline win/loss counts the report displays — read straight
 * from frau-holle's {@code BacktestMetrics.winningTrades()} / {@code losingTrades()}
 * (see {@code HtmlReportGenerator.winsAndLosses}) — against an independent count
 * over the same run's {@code BacktestResult.trades()}. This is the guard that the
 * library counters are identical to the trade list the report prints beneath them
 * (a win is {@code pnl > 0}; a break-even {@code pnl == 0} is a loss).
 */
class WinLossCountReconciliationTest {

    @TempDir
    Path tempDir;

    // A pronounced hourly zig-zag so an sma(3) cross strategy round-trips several
    // times with a mix of winning and losing closes.
    private static final int[] CLOSES = {
            100, 102, 104, 101, 98, 97, 103, 106, 104, 99,
            96, 100, 105, 108, 103, 98, 95, 99, 104, 107,
            102, 97, 94, 100, 106, 103, 98, 101, 105, 99
    };

    @Test
    void libraryWinLossCountsEqualAnIndependentTradeListCount() throws IOException, BacktestException {
        writeHourlyCsv();
        ParsedStrategy strategy = StrategyParser.parse("""
                Feature: Win/loss reconciliation
                  Primary timeframe: 1h

                  Scenario: Enter
                    Given no open position
                    When close is above sma(3)
                    Then long_entry

                  Scenario: Exit
                    Given a long position is open
                    When close is below sma(3)
                    Then long_exit
                """, "winloss-reconcile.strat");

        BacktestRunResult run = new BacktestRunner().run(strategy, config(), Map.of());
        BacktestResult result = run.result();
        BacktestMetrics m = result.metrics();
        List<Trade> trades = result.trades();

        long independentWins = trades.stream().filter(t -> t.pnl().signum() > 0).count();
        long independentLosses = trades.stream().filter(t -> t.pnl().signum() <= 0).count();

        assertTrue(trades.size() >= 2,
                "fixture should produce multiple closed trades, got " + trades.size());
        // The library's counters must match an independent walk of the trade list…
        assertEquals(independentWins, (long) m.winningTrades(),
                "winningTrades() must equal count(pnl > 0) over the trade list");
        assertEquals(independentLosses, (long) m.losingTrades(),
                "losingTrades() must equal count(pnl <= 0) over the trade list (break-even = loss)");
        // …the trade list and numTrades() must agree…
        assertEquals(trades.size(), m.numTrades(),
                "numTrades() must equal the closed-trade-list size");
        // …and the headline split must reconcile exactly.
        assertEquals(m.numTrades(), m.winningTrades() + m.losingTrades(),
                "wins + losses must equal numTrades");
    }

    private void writeHourlyCsv() throws IOException {
        StringBuilder csv = new StringBuilder("time,open,high,low,close\n");
        Instant start = Instant.parse("2024-01-01T00:00:00Z");
        for (int i = 0; i < CLOSES.length; i++) {
            int c = CLOSES[i];
            csv.append(start.plus(Duration.ofHours(i))).append(',')
                    .append(c).append(',').append(c + 2).append(',')
                    .append(c - 2).append(',').append(c).append('\n');
        }
        Files.writeString(tempDir.resolve("AAPL_1h.csv"), csv);
    }

    private BacktestConfig config() {
        return new BacktestConfig(
                tempDir.resolve("config.toml"),
                tempDir.resolve("strategy.strat"),
                "AAPL",
                LocalDate.parse("2024-01-01"),
                LocalDate.parse("2024-01-02"),
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
