package net.jacopobiscella.wichtelm;

import net.jacopobiscella.wichtelm.sweep.SweepConsoleReport;
import net.jacopobiscella.wichtelm.sweep.SweepCsvWriter;
import net.jacopobiscella.wichtelm.sweep.SweepObjective;
import net.jacopobiscella.wichtelm.sweep.SweepResult;
import org.hatrack.frauholle.result.BacktestMetrics;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tests for sweep output: the winner paste block and CSV no-overwrite guarantee. */
class SweepOutputTest {

    private static BacktestMetrics metrics(String totalReturn, int trades, String sharpe) {
        BigDecimal z = BigDecimal.ZERO;
        return new BacktestMetrics(new BigDecimal(totalReturn), z, trades, z,
                new BigDecimal(sharpe), z, z, z, z, z);
    }

    @Test
    void winnerBlockIncludesFixedParametersNotJustSweptAxes() {
        SweepResult winner = SweepResult.success(
                Map.of("rsi_period", new BigDecimal("10")), metrics("0.2", 5, "1.5"));
        Map<String, BigDecimal> base = Map.of(
                "rsi_period", new BigDecimal("14"),   // overlaid by the swept value
                "trend_period", new BigDecimal("200")); // fixed [parameters] entry

        String out = SweepConsoleReport.render(List.of(winner), List.of("rsi_period"),
                SweepObjective.SHARPE, 10, base);

        int block = out.indexOf("[parameters]");
        assertTrue(block >= 0, () -> "no [parameters] block:\n" + out);
        String paste = out.substring(block);
        assertTrue(paste.contains("rsi_period = 10"), () -> "swept value missing:\n" + paste);
        assertTrue(paste.contains("trend_period = 200"),
                () -> "fixed parameter dropped from winner block:\n" + paste);
    }

    @Test
    void csvIsNeverOverwrittenInTheSameSecond(@TempDir Path dir) {
        SweepResult row = SweepResult.success(
                Map.of("rsi_period", new BigDecimal("10")), metrics("0.2", 5, "1.5"));
        List<SweepResult> rows = List.of(row);
        List<String> axes = List.of("rsi_period");
        LocalDateTime when = LocalDateTime.parse("2026-05-31T12-00-00",
                java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH-mm-ss"));

        Path first = SweepCsvWriter.write(rows, axes, SweepObjective.SHARPE, dir, "bt", when);
        Path second = SweepCsvWriter.write(rows, axes, SweepObjective.SHARPE, dir, "bt", when);

        assertNotEquals(first, second, "second write must not reuse the first filename");
        assertTrue(Files.exists(first), "first CSV was removed");
        assertTrue(Files.exists(second), "second CSV was not written");
        assertEquals("bt_sweep_2026-05-31T12-00-00.csv", first.getFileName().toString());
    }

    private static final DateTimeFormatter TS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH-mm-ss");

    @Test
    void failedRowColumnsStayAlignedWithTheHeader(@TempDir Path dir) throws IOException {
        SweepResult ok = SweepResult.success(
                Map.of("rsi_period", new BigDecimal("10")), metrics("0.2", 5, "1.5"));
        SweepResult bad = SweepResult.failed(
                Map.of("rsi_period", new BigDecimal("12")), "BoomException: bad period");
        Path f = SweepCsvWriter.write(List.of(ok, bad), List.of("rsi_period"),
                SweepObjective.SHARPE, dir, "bt", LocalDateTime.parse("2026-05-31T12-00-00", TS));

        List<String> lines = Files.readAllLines(f);
        List<String> header = Arrays.asList(lines.get(0).split(",", -1));
        String[] failed = lines.get(2).split(",", -1); // line 1 = header, line 2 = ok, line 3 = bad

        assertEquals(header.size(), failed.length, () -> "failed row width != header: " + lines);
        assertEquals(SweepObjective.SHARPE.wire(), failed[header.indexOf("objective")],
                "objective name must sit in the objective column");
        assertEquals("failed", failed[header.indexOf("status")], "status column must read 'failed'");
        assertEquals("BoomException: bad period", failed[header.indexOf("failure_reason")],
                "failure reason must land in failure_reason");
    }

    @Test
    void exactDecimalSweptValuesArePreservedInWinnerBlockAndCsv(@TempDir Path dir) throws IOException {
        SweepResult winner = SweepResult.success(
                Map.of("stop_loss_pct", new BigDecimal("0.1234567")), metrics("0.2", 5, "1.5"));
        Map<String, BigDecimal> base = Map.of("stop_loss_pct", new BigDecimal("2.0"));

        String console = SweepConsoleReport.render(List.of(winner), List.of("stop_loss_pct"),
                SweepObjective.SHARPE, 10, base);
        String paste = console.substring(console.indexOf("[parameters]"));
        assertTrue(paste.contains("stop_loss_pct = 0.1234567"),
                () -> "winner block rounded the swept value:\n" + paste);

        Path f = SweepCsvWriter.write(List.of(winner), List.of("stop_loss_pct"),
                SweepObjective.SHARPE, dir, "bt", LocalDateTime.parse("2026-05-31T12-00-00", TS));
        assertTrue(Files.readString(f).contains("0.1234567"),
                "CSV rounded the swept value");
    }
}
