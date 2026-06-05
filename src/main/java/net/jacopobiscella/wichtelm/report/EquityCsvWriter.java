package net.jacopobiscella.wichtelm.report;

import net.jacopobiscella.wichtelm.error.ReportGenerationException;
import org.hatrack.frauholle.model.EquityPoint;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Writes a backtest's per-bar mark-to-market equity curve to a CSV file,
 * triggered by {@code wichtelm run --dump-equity}. The HTML report (section 7.4)
 * renders this same series as an SVG panel but exposes no machine-readable form
 * (section 15 restricts report output to HTML); this side-channel emits the raw
 * series so an analyst can build aggregate equity / drawdown curves across many
 * backtests without scraping the rendered SVG.
 *
 * <p>One row per primary-TF bar: {@code time,equity,cash,position_value}. Values
 * are exact and unrounded — this file is a derived performance record, not a
 * display table — so {@code equity} is the absolute account value at each bar,
 * not indexed to base 100 (the report does the rebasing for display; a consumer
 * aggregating across instruments needs the raw series to choose its own base).
 *
 * <p>The filename follows the report convention
 * {@code {basename}_equity_{timestamp}.csv} and is never overwritten
 * (section 7.1).
 */
public final class EquityCsvWriter {

    private static final DateTimeFormatter TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH-mm-ss");

    private EquityCsvWriter() {
    }

    /**
     * @param curve       the per-bar equity points, in chronological order
     * @param outputDir   directory to write into (created if absent)
     * @param basename    config basename, e.g. {@code my_backtest}
     * @param generatedAt timestamp for the filename
     * @return the path written
     */
    public static Path write(List<EquityPoint> curve, Path outputDir, String basename,
                             LocalDateTime generatedAt) {
        try {
            Files.createDirectories(outputDir);
            String content = render(curve);
            String stem = basename + "_equity_" + TIMESTAMP.format(generatedAt);
            // Never overwrite (section 7.1). The timestamp is only second-precise,
            // so two runs of the same config in the same UTC second would collide;
            // CREATE_NEW makes each write atomic and a numeric suffix gives the
            // loser of the race its own file. Mirrors SweepCsvWriter.
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
            throw new ReportGenerationException("failed to write equity CSV to " + outputDir, e);
        }
    }

    static String render(List<EquityPoint> curve) {
        StringBuilder csv = new StringBuilder();
        csv.append("time,equity,cash,position_value\n");
        for (EquityPoint point : curve) {
            csv.append(point.time())
                    .append(',').append(plain(point.equity()))
                    .append(',').append(plain(point.cash()))
                    .append(',').append(plain(point.positionValue()))
                    .append('\n');
        }
        return csv.toString();
    }

    private static String plain(BigDecimal value) {
        if (value == null) {
            return "";
        }
        return value.stripTrailingZeros().toPlainString();
    }
}
