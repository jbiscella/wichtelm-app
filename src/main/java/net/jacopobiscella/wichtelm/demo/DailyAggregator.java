package net.jacopobiscella.wichtelm.demo;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

/**
 * Aggregates a 1-hour OHLCV CSV (in the canonical {@code frau-holle-csv}
 * schema {@code time,open,high,low,close,volume}) into a 1-day series by
 * grouping rows on their UTC calendar date: daily open is the first bar's
 * open, daily close is the last bar's close, high/low are the day's
 * extremes, and volume is the daily sum.
 *
 * <p>Companion to {@link SyntheticDataGenerator}: that one produces the 1h
 * datasets and this one derives the matching 1d series that the multi-
 * timeframe demo strategies consume on the higher timeframe.
 *
 * <p>Usage:
 * <pre>{@code
 *   java -cp target/classes net.jacopobiscella.wichtelm.demo.DailyAggregator \
 *        demo/data/SPX2020_1h.csv demo/data/SPX2020_1d.csv
 * }</pre>
 */
public final class DailyAggregator {

    private DailyAggregator() {
    }

    public static void main(String[] args) throws IOException {
        if (args.length != 2) {
            System.err.println("Usage: DailyAggregator <hourly.csv> <daily.csv>");
            System.exit(2);
        }
        Path hourly = Path.of(args[0]);
        Path daily = Path.of(args[1]);
        String csv = aggregate(Files.readString(hourly));
        Files.writeString(daily, csv);
        System.out.println("Wrote " + daily + " (" + csv.lines().count() + " lines)");
    }

    /** Aggregates the body of a 1h CSV into a 1d CSV. */
    public static String aggregate(String hourlyCsv) {
        TreeMap<LocalDate, List<String[]>> byDay = new TreeMap<>();
        List<String> lines = hourlyCsv.lines().toList();
        if (lines.isEmpty() || !lines.getFirst().startsWith("time,")) {
            throw new IllegalArgumentException("expected header 'time,open,high,low,close,volume'");
        }
        for (int i = 1; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.isBlank()) {
                continue;
            }
            String[] cells = line.split(",");
            Instant time = Instant.parse(cells[0]);
            LocalDate date = time.atOffset(ZoneOffset.UTC).toLocalDate();
            byDay.computeIfAbsent(date, k -> new ArrayList<>()).add(cells);
        }

        StringBuilder out = new StringBuilder("time,open,high,low,close,volume\n");
        for (var entry : byDay.entrySet()) {
            List<String[]> day = entry.getValue();
            String open = day.getFirst()[1];
            String close = day.getLast()[4];
            BigDecimal high = day.stream().map(c -> new BigDecimal(c[2]))
                    .max(BigDecimal::compareTo).orElseThrow();
            BigDecimal low = day.stream().map(c -> new BigDecimal(c[3]))
                    .min(BigDecimal::compareTo).orElseThrow();
            BigDecimal volume = day.stream().map(c -> new BigDecimal(c[5]))
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            Instant dayStart = entry.getKey().atStartOfDay(ZoneOffset.UTC).toInstant();
            out.append(dayStart).append(',')
                    .append(open).append(',')
                    .append(high.toPlainString()).append(',')
                    .append(low.toPlainString()).append(',')
                    .append(close).append(',')
                    .append(volume.toPlainString()).append('\n');
        }
        return out.toString();
    }
}
