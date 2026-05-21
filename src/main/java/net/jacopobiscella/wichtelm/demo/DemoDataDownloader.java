package net.jacopobiscella.wichtelm.demo;

import org.hatrack.commons.OHLCBar;
import org.hatrack.commons.Timeframe;
import org.hatrack.frauholle.eodhd.EodhdMarketDataSource;
import org.hatrack.frauholle.error.MarketDataException;
import org.tomlj.Toml;
import org.tomlj.TomlArray;
import org.tomlj.TomlParseResult;
import org.tomlj.TomlTable;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

/**
 * Downloads real OHLC data from EODHD and writes it into the demo CSV folder
 * in the canonical {@code frau-holle-csv} schema
 * ({@code time,open,high,low,close,volume}), so the demo backtests run against
 * real intraday data instead of the synthetic generators.
 *
 * <p>Reads a TOML manifest of {@code [[dataset]]} entries, each carrying a
 * {@code symbol}, {@code timeframe}, {@code from}, {@code to}, an optional
 * {@code description}, and an optional {@code refetch} flag (default
 * {@code false} — an existing CSV is left untouched unless {@code refetch =
 * true}). Each entry is fetched via {@link EodhdMarketDataSource} using the
 * public free {@code "demo"} token, which covers a fixed set of tickers
 * (AAPL.US, TSLA.US, VTI.US, AMZN.US, BTC-USD.CC, EURUSD.FOREX) at all
 * timeframes without a subscription.
 *
 * <p>Output files are written to {@code demo/data/{symbol}_{timeframe}.csv}
 * (e.g. {@code demo/data/AAPL.US_1h.csv}), matching the {@code [csv].file}
 * pattern {@code data/&#123;symbol&#125;_&#123;timeframe&#125;.csv} the demo
 * configs already use.
 *
 * <p>Error handling is per-entry and non-fatal to the batch: an EODHD
 * {@link MarketDataException} (network error, unknown ticker, rate limit) is
 * logged and that entry skipped; an empty response is logged as a warning and
 * an empty CSV (header only) is written so the consumer surfaces a meaningful
 * {@code DataSourceUnavailableException} at backtest time. Other entries
 * continue regardless.
 *
 * <p>Usage:
 * <pre>{@code
 *   mvn -q exec:java \
 *     -Dexec.mainClass=net.jacopobiscella.wichtelm.demo.DemoDataDownloader \
 *     -Dexec.args="demo/data-sources.toml"
 * }</pre>
 *
 * <p>Network note: EODHD must be reachable from wherever this runs. In a
 * sandboxed environment with an outbound allowlist, {@code eodhd.com} has to
 * be on it; otherwise every fetch fails with "Host not in allowlist" and the
 * batch produces no data.
 */
public final class DemoDataDownloader {

    /** EODHD's public free token; covers the demo tickers without a subscription. */
    private static final String DEMO_TOKEN = "demo";

    private static final String CSV_HEADER = "time,open,high,low,close,volume";

    private DemoDataDownloader() {
    }

    public static void main(String[] args) throws IOException {
        if (args.length != 1) {
            System.err.println("usage: DemoDataDownloader <manifest.toml>");
            System.exit(2);
            return;
        }
        Path manifest = Path.of(args[0]);
        Path outputDir = manifest.toAbsolutePath().getParent().resolve("data");
        Files.createDirectories(outputDir);

        List<Dataset> datasets = parseManifest(manifest);
        System.out.println("Loaded " + datasets.size() + " dataset spec(s) from " + manifest);

        EodhdMarketDataSource source = new EodhdMarketDataSource(DEMO_TOKEN);
        int written = 0;
        int skipped = 0;
        int failed = 0;
        for (Dataset dataset : datasets) {
            Path target = outputDir.resolve(dataset.symbol() + "_" + dataset.timeframe().wire() + ".csv");
            if (Files.exists(target) && !dataset.refetch()) {
                System.out.printf("SKIP  %-12s %-3s -> %s already exists (refetch=false)%n",
                        dataset.symbol(), dataset.timeframe().wire(), target.getFileName());
                skipped++;
                continue;
            }
            try {
                List<OHLCBar> bars = source.fetchHistory(
                        dataset.symbol(), dataset.timeframe(), dataset.from(), dataset.until());
                writeCsv(target, bars);
                if (bars.isEmpty()) {
                    System.out.printf("WARN  %-12s %-3s -> EODHD returned 0 bars for %s..%s; "
                                    + "wrote empty CSV %s%n",
                            dataset.symbol(), dataset.timeframe().wire(),
                            dataset.from(), dataset.until(), target.getFileName());
                } else {
                    System.out.printf("OK    %-12s %-3s -> %d bars [%s .. %s] %s%n",
                            dataset.symbol(), dataset.timeframe().wire(), bars.size(),
                            bars.getFirst().time(), bars.getLast().time(), target.getFileName());
                }
                written++;
            } catch (MarketDataException e) {
                System.err.printf("FAIL  %-12s %-3s -> %s (skipping, batch continues)%n",
                        dataset.symbol(), dataset.timeframe().wire(), e.getMessage());
                failed++;
            }
        }
        System.out.printf("Done: %d written, %d skipped, %d failed.%n", written, skipped, failed);
    }

    /** Parses the {@code [[dataset]]} array of tables from the manifest. */
    static List<Dataset> parseManifest(Path manifest) throws IOException {
        TomlParseResult toml = Toml.parse(manifest);
        if (toml.hasErrors()) {
            throw new IllegalArgumentException("manifest has TOML errors: " + toml.errors());
        }
        TomlArray array = toml.getArray("dataset");
        if (array == null) {
            throw new IllegalArgumentException("manifest has no [[dataset]] entries");
        }
        List<Dataset> datasets = new ArrayList<>(array.size());
        for (int i = 0; i < array.size(); i++) {
            TomlTable entry = array.getTable(i);
            String symbol = required(entry, "symbol", i);
            String tfWire = required(entry, "timeframe", i);
            String from = required(entry, "from", i);
            String to = required(entry, "to", i);
            boolean refetch = entry.contains("refetch") && Boolean.TRUE.equals(entry.getBoolean("refetch"));
            datasets.add(new Dataset(symbol, Timeframe.fromWire(tfWire),
                    startOfDay(from), endOfRange(to), refetch));
        }
        return datasets;
    }

    private static String required(TomlTable entry, String key, int index) {
        String value = entry.getString(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                    "[[dataset]] entry " + index + " is missing required key '" + key + "'");
        }
        return value;
    }

    private static Instant startOfDay(String isoDate) {
        return LocalDate.parse(isoDate).atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    /** Inclusive end date — widen by a day so the final day's bars are fetched. */
    private static Instant endOfRange(String isoDate) {
        return LocalDate.parse(isoDate).plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    /**
     * Writes {@code bars} in the canonical CSV schema. Prices are written with
     * {@link BigDecimal#toPlainString()} so no precision is lost; volume is
     * written as its plain string when present, {@code 0} when absent (the
     * column is always emitted to keep the header honest).
     */
    static void writeCsv(Path target, List<OHLCBar> bars) {
        StringBuilder csv = new StringBuilder(CSV_HEADER).append('\n');
        for (OHLCBar bar : bars) {
            csv.append(bar.time()).append(',')
                    .append(bar.open().toPlainString()).append(',')
                    .append(bar.high().toPlainString()).append(',')
                    .append(bar.low().toPlainString()).append(',')
                    .append(bar.close().toPlainString()).append(',')
                    .append(bar.volume().map(BigDecimal::toPlainString).orElse("0"))
                    .append('\n');
        }
        try {
            Files.writeString(target, csv);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to write " + target, e);
        }
    }

    /** A single dataset to fetch: symbol, timeframe, and a resolved instant range. */
    record Dataset(String symbol, Timeframe timeframe, Instant from, Instant until, boolean refetch) {
    }
}
