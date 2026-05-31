package net.jacopobiscella.wichtelm.sweep;

import net.jacopobiscella.wichtelm.error.ReportGenerationException;
import org.hatrack.frauholle.result.BacktestMetrics;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;

/**
 * Writes a sweep's ranked results to a CSV file (CLAUDE.md section 18). One row
 * per parameter combination: its rank, the swept parameter values, the key
 * metrics, the objective value, and a run status. The filename follows the
 * report convention {@code {basename}_sweep_{timestamp}.csv} and is never
 * overwritten.
 */
public final class SweepCsvWriter {

    private static final DateTimeFormatter TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH-mm-ss");

    private SweepCsvWriter() {
    }

    /**
     * @param rows         ranked results, best first
     * @param axisNames    swept parameter names in column order
     * @param objective    the ranking objective (its value gets its own column)
     * @param outputDir    directory to write into (created if absent)
     * @param basename     config basename, e.g. {@code my_backtest}
     * @param generatedAt  timestamp for the filename
     * @return the path written
     */
    public static Path write(List<SweepResult> rows, List<String> axisNames,
                             SweepObjective objective, Path outputDir, String basename,
                             LocalDateTime generatedAt) {
        try {
            Files.createDirectories(outputDir);
            String content = render(rows, axisNames, objective);
            String stem = basename + "_sweep_" + TIMESTAMP.format(generatedAt);
            // Never overwrite (section 18.4 / 7.1). The timestamp is only
            // second-precise, so two sweeps of the same config in the same UTC
            // second would collide; CREATE_NEW makes each write atomic and a
            // numeric suffix gives the loser of the race its own file.
            for (int suffix = 0; ; suffix++) {
                Path file = outputDir.resolve(
                        suffix == 0 ? stem + ".csv" : stem + "-" + suffix + ".csv");
                try {
                    Files.writeString(file, content, StandardCharsets.UTF_8,
                            StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
                    return file;
                } catch (FileAlreadyExistsException collision) {
                    // try the next suffix
                }
            }
        } catch (IOException e) {
            throw new ReportGenerationException("failed to write sweep CSV to " + outputDir, e);
        }
    }

    static String render(List<SweepResult> rows, List<String> axisNames, SweepObjective objective) {
        StringBuilder csv = new StringBuilder();
        csv.append("rank");
        for (String name : axisNames) {
            csv.append(',').append(name);
        }
        csv.append(",total_return,sharpe,sortino,calmar,profit_factor,num_trades,win_rate,"
                + "max_drawdown,objective,objective_value,status,failure_reason\n");

        int rank = 1;
        for (SweepResult row : rows) {
            csv.append(rank++);
            for (String name : axisNames) {
                // Exact, unrounded: this CSV is the combination -> metrics record
                // (section 18.4), so a swept value must identify exactly what was
                // backtested. Rounding is reserved for the display metric columns.
                csv.append(',').append(exact(row.combination().get(name)));
            }
            if (row.metrics().isPresent()) {
                BacktestMetrics m = row.metrics().get();
                csv.append(',').append(plain(m.totalReturn()))
                        .append(',').append(plain(m.sharpeRatio()))
                        .append(',').append(plain(m.sortinoRatio()))
                        .append(',').append(plain(m.calmarRatio()))
                        .append(',').append(m.numTrades() == 0 ? "" : plain(m.profitFactor()))
                        .append(',').append(m.numTrades())
                        .append(',').append(plain(m.winRate()))
                        .append(',').append(plain(m.maxDrawdown()))
                        .append(',').append(objective.wire())
                        .append(',').append(objectiveCell(row, objective))
                        .append(",ok,");
            } else {
                // Params columns already written; pad the eight metric columns
                // (total_return..max_drawdown) with nine commas so 'objective'
                // lands in its own column, then objective name, empty
                // objective_value, status, and the failure reason.
                csv.append(",,,,,,,,,").append(objective.wire()).append(",,failed,")
                        .append(escape(row.failure().orElse("")));
            }
            csv.append('\n');
        }
        return csv.toString();
    }

    private static String objectiveCell(SweepResult row, SweepObjective objective) {
        Optional<BigDecimal> value = row.objectiveValue(objective);
        return value.map(SweepCsvWriter::plain).orElse("");
    }

    /** Exact, unrounded plain decimal — for swept-parameter cells (section 18.4). */
    private static String exact(BigDecimal value) {
        if (value == null) {
            return "";
        }
        return value.stripTrailingZeros().toPlainString();
    }

    private static String plain(BigDecimal value) {
        if (value == null) {
            return "";
        }
        return value.setScale(Math.min(value.scale(), 6), RoundingMode.HALF_UP)
                .stripTrailingZeros().toPlainString();
    }

    private static String escape(String text) {
        if (text.contains(",") || text.contains("\"") || text.contains("\n")) {
            return '"' + text.replace("\"", "\"\"") + '"';
        }
        return text;
    }
}
