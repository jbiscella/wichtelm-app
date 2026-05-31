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
import org.hatrack.frauholle.eodhd.EodhdMarketDataSource;
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
import java.util.function.UnaryOperator;

/**
 * Drives the backtest execution flow (CLAUDE.md section 6.1, steps 4-9): resolve
 * the data source, load the primary and higher-timeframe series, build the
 * SignalGenerator and BacktestSpec, and run the frau-holle backtester.
 */
public class BacktestRunner {

    /** Position sizing is percentage-based, so the initial capital is a fixed normalized base. */
    private static final BigDecimal NORMALIZED_INITIAL_CAPITAL = new BigDecimal("100000");

    private final UnaryOperator<String> environment;

    public BacktestRunner() {
        this(System::getenv);
    }

    /**
     * @param environment environment-variable lookup, injectable so the EODHD
     *                    api_token_env resolution can be exercised in tests
     */
    public BacktestRunner(UnaryOperator<String> environment) {
        this.environment = environment;
    }

    /**
     * Runs the backtest end to end.
     *
     * @throws BacktestException     rethrown from the frau-holle engine (CLAUDE.md section 8)
     * @throws DataSourceUnavailableException when market data cannot be loaded or is unusable
     */
    public BacktestRunResult run(ParsedStrategy strategy, BacktestConfig config,
                                 Map<String, BigDecimal> parameters) throws BacktestException {
        return runWith(strategy, config, parameters, loadMarketData(strategy, config));
    }

    /**
     * Loads the market data a backtest needs (CLAUDE.md section 6.1 steps 4-5):
     * the primary-timeframe series and any higher-timeframe Background series,
     * each verified to span the primary range. This phase depends only on the
     * symbol, timeframes and date range — not on parameter values — so a sweep
     * loads it once and reuses it across every parameter combination
     * (CLAUDE.md section 18).
     *
     * @throws DataSourceUnavailableException when market data cannot be loaded
     */
    public LoadedMarketData loadMarketData(ParsedStrategy strategy, BacktestConfig config) {
        MarketDataSource dataSource = dataSourceFor(config);
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
            // Widen the lower bound by one higher-TF period so the bar that
            // already contains `from` (e.g. the week a Wednesday start falls in)
            // is fetched rather than dropped by a since-honoring data source.
            Instant higherSince = Timeframes.retreat(from, tf);
            List<OHLCBar> bars = fetch(dataSource, config.symbol(), tf, higherSince, until);
            if (bars.isEmpty()) {
                throw new DataSourceUnavailableException(
                        "higher-timeframe series " + tf.wire() + " returned no bars for the date range");
            }
            verifySpansPrimaryRange(tf, bars, primarySeries);
            higherTimeframeBars.put(tf.wire(), bars);
        }
        return new LoadedMarketData(primarySeries, higherTimeframeBars);
    }

    /**
     * Runs a backtest against already-loaded market data for a single parameter
     * set (CLAUDE.md section 6.1 steps 6-9): build the SignalGenerator and the
     * parameter-dependent Tier B prepass, then run the frau-holle backtester.
     * This phase varies per parameter combination in a sweep; the data passed in
     * does not.
     *
     * @throws BacktestException     rethrown from the frau-holle engine (CLAUDE.md section 8)
     * @throws DataSourceUnavailableException when the loaded data is too small to run
     */
    public BacktestRunResult runWith(ParsedStrategy strategy, BacktestConfig config,
                                     Map<String, BigDecimal> parameters,
                                     LoadedMarketData data) throws BacktestException {
        List<OHLCBar> primarySeries = data.primarySeries();
        Map<String, List<OHLCBar>> higherTimeframeBars = data.higherTimeframeBars();

        WichtelmSignalGenerator generator = new WichtelmSignalGenerator(strategy, parameters,
                config.positionSizePct(), config.pyramiding(), higherTimeframeBars);
        // Tier B pattern prepass: build the nachtkrapp match index ONCE over
        // the closed primary series so per-bar boolean primitives are an
        // O(1) Set membership check. The empty index returned for strategies
        // that declare no Tier B calls is a cheap no-op. This is rebuilt per
        // parameter set because the resolved rule arguments are parameter-driven.
        generator.setNachtkrappMatchIndex(
                NachtkrappMatchIndex.buildFor(strategy, parameters, primarySeries,
                        higherTimeframeBars));

        // The most common run-time failure is that the data source returned too
        // few bars for the primary timeframe (frau-holle's BacktestSpec rejects a
        // series of fewer than 2 bars as [V6]). Pre-empt it with a message that
        // names the symbol, timeframe, window and bar count and points at the
        // likely cause, instead of surfacing the opaque "invalid backtest spec [V6]: 1".
        if (primarySeries.size() < 2) {
            throw new DataSourceUnavailableException(
                    insufficientDataMessage(config, strategy, primarySeries.size()));
        }

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
        return new BacktestRunResult(result, primarySeries, higherTimeframeBars,
                generator.triggerTimes(), generator.suppressedEntries());
    }

    /**
     * Builds an actionable message for the "too few bars" failure (frau-holle's
     * {@code [V6]}): it names the bar count, symbol, primary timeframe and window,
     * then adds a data-source-specific hint at the likely cause.
     */
    private static String insufficientDataMessage(BacktestConfig config, ParsedStrategy strategy,
                                                  int barsLoaded) {
        String base = "insufficient market data: only " + barsLoaded + " "
                + strategy.primaryTimeframe().wire() + " bar" + (barsLoaded == 1 ? "" : "s")
                + " loaded for " + config.symbol() + " over " + config.dateFrom() + " → "
                + config.dateTo() + "; a backtest needs at least 2 bars.";
        String hint = switch (config.dataSource()) {
            case EODHD -> " The EODHD response was too small for this symbol/timeframe/range. A"
                    + " free/registered EODHD token returns only ~1 year of EOD history, intraday"
                    + " history is limited, and the 'demo' token only serves a fixed set of tickers."
                    + " Widen [date_range], use a token/plan with enough history, or switch to"
                    + " data_source = \"csv\".";
            case CSV -> " The CSV file had too few rows in this window. Check that it has data for"
                    + " this symbol and timeframe and that [date_range] overlaps the rows.";
        };
        return base + hint;
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

    /**
     * Resolves the {@link MarketDataSource} for a config (CLAUDE.md section 6.1
     * step 4): a CSV driver or an EODHD driver. For EODHD, the API token is read
     * from the environment variable named by {@code [eodhd].api_token_env}; a
     * missing or empty variable raises {@link DataSourceUnavailableException}
     * (the runtime half of rule C9). The token value itself is never logged.
     */
    public MarketDataSource dataSourceFor(BacktestConfig config) {
        return switch (config.dataSource()) {
            case CSV -> {
                Path file = config.csvFile().orElseThrow(() -> new DataSourceUnavailableException(
                        "csv data source requires the [csv].file pattern"));
                Path baseDirectory = file.getParent() == null ? Path.of(".") : file.getParent();
                yield new CsvMarketDataSource(baseDirectory, file.getFileName().toString());
            }
            case EODHD -> {
                String variableName = config.eodhdApiTokenEnv().orElseThrow(
                        () -> new DataSourceUnavailableException(
                                "eodhd data source requires [eodhd].api_token_env"));
                String token = environment.apply(variableName);
                if (token == null || token.isBlank()) {
                    throw new DataSourceUnavailableException("environment variable " + variableName
                            + " is not set; it must hold the EODHD API token");
                }
                yield new EodhdMarketDataSource(token);
            }
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
