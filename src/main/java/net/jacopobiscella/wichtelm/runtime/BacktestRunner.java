package net.jacopobiscella.wichtelm.runtime;

import net.jacopobiscella.wichtelm.config.BacktestConfig;
import net.jacopobiscella.wichtelm.error.DataSourceUnavailableException;
import net.jacopobiscella.wichtelm.strategy.BackgroundSeries;
import net.jacopobiscella.wichtelm.strategy.ParsedStrategy;
import net.jacopobiscella.wichtelm.strategy.Timeframes;
import org.hatrack.commons.OHLCBar;
import org.hatrack.commons.Timeframe;
import org.hatrack.frauholle.csv.CsvMarketDataSource;
import org.hatrack.frauholle.engine.Backtester;
import org.hatrack.frauholle.error.BacktestException;
import org.hatrack.frauholle.error.InvalidBacktestSpecException;
import org.hatrack.frauholle.error.MarketDataException;
import org.hatrack.frauholle.port.MarketDataSource;
import org.hatrack.frauholle.result.BacktestResult;
import org.hatrack.frauholle.spec.BacktestSpec;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Drives the backtest execution flow (CLAUDE.md section 6.1, steps 4-9): resolve
 * the data source, load the primary and higher-timeframe series, build the
 * SignalGenerator and BacktestSpec, and run the frau-holle backtester.
 */
public final class BacktestRunner {

    /** Position sizing is percentage-based, so the initial capital is a fixed normalized base. */
    private static final BigDecimal NORMALIZED_INITIAL_CAPITAL = new BigDecimal("100000");

    /**
     * Runs the backtest end to end.
     *
     * @throws BacktestException     rethrown from the frau-holle engine (CLAUDE.md section 8)
     * @throws DataSourceUnavailableException when market data cannot be loaded or is unusable
     */
    public BacktestRunResult run(ParsedStrategy strategy, BacktestConfig config,
                                 Map<String, BigDecimal> parameters) throws BacktestException {
        MarketDataSource dataSource = marketDataSource(config);
        Instant from = config.dateFrom().atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant until = config.dateTo().plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();

        List<OHLCBar> primarySeries =
                fetch(dataSource, config.symbol(), strategy.primaryTimeframe(), from, until);

        Map<String, List<OHLCBar>> higherTimeframeBars = new LinkedHashMap<>();
        for (BackgroundSeries series : strategy.backgroundSeries()) {
            if (series.timeframe().isEmpty()) {
                continue;
            }
            Timeframe tf = series.timeframe().get();
            if (higherTimeframeBars.containsKey(tf.wire())) {
                continue;
            }
            List<OHLCBar> bars = fetch(dataSource, config.symbol(), tf, from, until);
            if (bars.isEmpty()) {
                throw new DataSourceUnavailableException(
                        "higher-timeframe series " + tf.wire() + " returned no bars for the date range");
            }
            verifySpansPrimaryRange(tf, bars, primarySeries);
            higherTimeframeBars.put(tf.wire(), bars);
        }

        WichtelmSignalGenerator generator = new WichtelmSignalGenerator(strategy, parameters,
                config.positionSizePct(), config.pyramiding(), higherTimeframeBars);

        BacktestSpec spec;
        try {
            spec = BacktestSpec.builder()
                    .withSeries(primarySeries)
                    .withSignalGenerator(generator)
                    .withInitialCash(NORMALIZED_INITIAL_CAPITAL)
                    .build();
        } catch (InvalidBacktestSpecException e) {
            throw new DataSourceUnavailableException(
                    "loaded market data is insufficient to run a backtest: " + e.getMessage(), e);
        }

        BacktestResult result = new Backtester().run(spec);
        return new BacktestRunResult(result, primarySeries, higherTimeframeBars);
    }

    /**
     * Verifies a higher-timeframe series spans the primary series (CLAUDE.md
     * section 6.1 step 5): its first bar opens at or before the first primary
     * bar, and its last bar closes at or after the last primary bar. Interior
     * gaps from market closures (weekends, holidays) are acceptable —
     * {@link HigherTimeframeSeries} resolves to the most recently closed bar.
     */
    private void verifySpansPrimaryRange(Timeframe timeframe, List<OHLCBar> higherBars,
                                         List<OHLCBar> primarySeries) {
        if (primarySeries.isEmpty()) {
            return;
        }
        Instant primaryStart = primarySeries.getFirst().time();
        Instant primaryEnd = primarySeries.getLast().time();
        Instant higherStart = higherBars.getFirst().time();
        Instant higherEnd = Timeframes.advance(higherBars.getLast().time(), timeframe);
        if (higherStart.isAfter(primaryStart)) {
            throw new DataSourceUnavailableException("higher-timeframe series " + timeframe.wire()
                    + " starts at " + higherStart + ", after the primary range start "
                    + primaryStart + "; early bars cannot be resolved");
        }
        if (higherEnd.isBefore(primaryEnd)) {
            throw new DataSourceUnavailableException("higher-timeframe series " + timeframe.wire()
                    + " ends at " + higherBars.getLast().time() + ", before the primary range end "
                    + primaryEnd + "; late bars cannot be resolved");
        }
    }

    private MarketDataSource marketDataSource(BacktestConfig config) {
        return switch (config.dataSource()) {
            case CSV -> {
                Path file = config.csvFile().orElseThrow(() -> new DataSourceUnavailableException(
                        "csv data source requires the [csv].file pattern"));
                Path baseDirectory = file.getParent() == null ? Path.of(".") : file.getParent();
                yield new CsvMarketDataSource(baseDirectory, file.getFileName().toString());
            }
            case EODHD -> throw new DataSourceUnavailableException(
                    "the EODHD data source is wired with the CLI increment (6c)");
        };
    }

    private List<OHLCBar> fetch(MarketDataSource dataSource, String symbol, Timeframe timeframe,
                                Instant from, Instant until) {
        try {
            return dataSource.fetchHistory(symbol, timeframe, from, until);
        } catch (MarketDataException e) {
            throw new DataSourceUnavailableException(
                    "failed to load " + timeframe.wire() + " data for symbol " + symbol, e);
        }
    }
}
