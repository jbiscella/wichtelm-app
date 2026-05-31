package net.jacopobiscella.wichtelm.cli;

import net.jacopobiscella.wichtelm.config.BacktestConfig;
import net.jacopobiscella.wichtelm.config.ConfigParser;
import net.jacopobiscella.wichtelm.config.ParameterResolver;
import net.jacopobiscella.wichtelm.error.ConfigParseException;
import net.jacopobiscella.wichtelm.error.StrategyParseException;
import net.jacopobiscella.wichtelm.error.SweepConfigException;
import net.jacopobiscella.wichtelm.error.WichtelmException;
import net.jacopobiscella.wichtelm.report.HtmlReportGenerator;
import net.jacopobiscella.wichtelm.report.ReportData;
import net.jacopobiscella.wichtelm.runtime.BacktestRunResult;
import net.jacopobiscella.wichtelm.runtime.BacktestRunner;
import net.jacopobiscella.wichtelm.strategy.ParsedStrategy;
import net.jacopobiscella.wichtelm.strategy.StrategyParser;
import net.jacopobiscella.wichtelm.sweep.SweepConsoleReport;
import net.jacopobiscella.wichtelm.sweep.SweepCsvWriter;
import net.jacopobiscella.wichtelm.sweep.SweepObjective;
import net.jacopobiscella.wichtelm.sweep.SweepResult;
import net.jacopobiscella.wichtelm.sweep.SweepRunner;
import org.hatrack.commons.OHLCSeries;
import org.hatrack.frauholle.error.BacktestException;

import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.UnaryOperator;

/**
 * Entry point for the {@code wichtelm} command-line tool (CLAUDE.md sections
 * 2.1 / 14). Exit codes: {@code 0} success, {@code 1} on an application error,
 * {@code 2} on a usage error.
 */
public final class WichtelmCli {

    static final String VERSION = "0.42.0-alpha";
    static final int EXIT_SUCCESS = 0;
    static final int EXIT_ERROR = 1;
    static final int EXIT_USAGE = 2;

    private final PrintStream out;
    private final PrintStream err;
    private final UnaryOperator<String> environment;

    public WichtelmCli(PrintStream out, PrintStream err, UnaryOperator<String> environment) {
        this.out = out;
        this.err = err;
        this.environment = environment;
    }

    public static void main(String[] args) {
        System.exit(new WichtelmCli(System.out, System.err, System::getenv).run(args));
    }

    /** Runs one CLI invocation and returns the process exit code. */
    public int run(String[] args) {
        if (args.length == 0) {
            printUsage(err);
            return EXIT_USAGE;
        }
        return switch (args[0]) {
            case "--version" -> {
                out.println("wichtelm " + VERSION);
                out.println("Backtesting tool for historical analysis. "
                        + "Provided \"AS IS\" under 0BSD license.");
                out.println("NOT financial advice. Past performance is not "
                        + "indicative of future results.");
                out.println("See LICENSE and README for full disclaimer.");
                yield EXIT_SUCCESS;
            }
            case "--help" -> {
                printUsage(out);
                yield EXIT_SUCCESS;
            }
            case "run" -> runBacktest(Arrays.copyOfRange(args, 1, args.length));
            case "sweep" -> runSweep(Arrays.copyOfRange(args, 1, args.length));
            case "validate" -> validateStrategy(Arrays.copyOfRange(args, 1, args.length));
            default -> {
                err.println("unknown command: " + args[0]);
                printUsage(err);
                yield EXIT_USAGE;
            }
        };
    }

    private int runBacktest(String[] args) {
        String configPath = null;
        boolean noReport = false;
        Path outputOverride = null;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--no-report" -> noReport = true;
                case "--output-dir" -> {
                    if (++i >= args.length) {
                        err.println("--output-dir requires a path argument");
                        return EXIT_USAGE;
                    }
                    outputOverride = Path.of(args[i]);
                }
                default -> {
                    if (configPath != null) {
                        err.println("unexpected argument: " + args[i]);
                        return EXIT_USAGE;
                    }
                    configPath = args[i];
                }
            }
        }
        if (configPath == null) {
            err.println("wichtelm run requires a config file");
            return EXIT_USAGE;
        }

        Path configFile = Path.of(configPath);
        try {
            String toml = Files.readString(configFile);
            BacktestConfig config = ConfigParser.parse(toml, configFile.toString());
            config.warnings().forEach(warning -> err.println("warning: " + warning));
            ParsedStrategy strategy = StrategyParser.parse(config.strategyPath());
            Map<String, BigDecimal> parameters = ParameterResolver.resolve(strategy, config);
            BacktestRunResult result = new BacktestRunner(environment).run(strategy, config, parameters);
            if (noReport) {
                out.println("Backtest complete; report suppressed by --no-report.");
            } else {
                Path report = writeReport(config, configFile, strategy, result, parameters, outputOverride);
                out.println("Backtest complete; report written to " + report);
            }
            return EXIT_SUCCESS;
        } catch (IOException | UncheckedIOException e) {
            err.println("cannot read a required file: " + e.getMessage());
            return EXIT_ERROR;
        } catch (WichtelmException e) {
            err.println(describe(e));
            return EXIT_ERROR;
        } catch (BacktestException e) {
            err.println("BacktestException: " + e.getMessage() + causeChain(e));
            return EXIT_ERROR;
        }
    }

    private int runSweep(String[] args) {
        String configPath = null;
        boolean noReport = false;
        Path outputOverride = null;
        SweepObjective objective = SweepObjective.defaultObjective();
        int top = 10;
        int maxCombos = 500;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--no-report" -> noReport = true;
                case "--output-dir" -> {
                    if (++i >= args.length) {
                        err.println("--output-dir requires a path argument");
                        return EXIT_USAGE;
                    }
                    outputOverride = Path.of(args[i]);
                }
                case "--objective" -> {
                    if (++i >= args.length) {
                        err.println("--objective requires a metric name");
                        return EXIT_USAGE;
                    }
                    Optional<SweepObjective> parsed = SweepObjective.fromWire(args[i]);
                    if (parsed.isEmpty()) {
                        err.println("unknown --objective '" + args[i] + "'; expected one of: "
                                + SweepObjective.wireList());
                        return EXIT_USAGE;
                    }
                    objective = parsed.get();
                }
                case "--top" -> {
                    if (++i >= args.length) {
                        err.println("--top requires a positive integer");
                        return EXIT_USAGE;
                    }
                    Integer parsed = positiveInt(args[i], "--top");
                    if (parsed == null) {
                        return EXIT_USAGE;
                    }
                    top = parsed;
                }
                case "--max-combos" -> {
                    if (++i >= args.length) {
                        err.println("--max-combos requires a positive integer");
                        return EXIT_USAGE;
                    }
                    Integer parsed = positiveInt(args[i], "--max-combos");
                    if (parsed == null) {
                        return EXIT_USAGE;
                    }
                    maxCombos = parsed;
                }
                default -> {
                    if (configPath != null) {
                        err.println("unexpected argument: " + args[i]);
                        return EXIT_USAGE;
                    }
                    configPath = args[i];
                }
            }
        }
        if (configPath == null) {
            err.println("wichtelm sweep requires a config file");
            return EXIT_USAGE;
        }

        Path configFile = Path.of(configPath);
        try {
            String toml = Files.readString(configFile);
            BacktestConfig config = ConfigParser.parse(toml, configFile.toString());
            config.warnings().forEach(warning -> err.println("warning: " + warning));
            // Treat a present-but-empty [sweep] table (every axis commented out)
            // the same as an absent one: otherwise the grid would expand to a
            // single empty combination and report a bogus "successful" sweep.
            if (config.sweep().isEmpty() || config.sweep().get().isEmpty()) {
                err.println("config has no [sweep] axes; nothing to sweep. Use "
                        + "'wichtelm run' for a single backtest, or add axes to the [sweep] table.");
                return EXIT_USAGE;
            }
            ParsedStrategy strategy = StrategyParser.parse(config.strategyPath());
            List<SweepResult> rows = new SweepRunner(new BacktestRunner(environment))
                    .run(strategy, config, objective, top, maxCombos);
            List<String> axisNames = config.sweep().get().parameterNames();
            Map<String, BigDecimal> baseParameters = ParameterResolver.resolve(strategy, config);
            out.println(SweepConsoleReport.render(rows, axisNames, objective, top, baseParameters));
            if (noReport) {
                out.println("Sweep complete; CSV suppressed by --no-report.");
            } else {
                Path csv = writeSweepCsv(config, configFile, rows, axisNames, objective,
                        outputOverride);
                out.println("Sweep complete; results written to " + csv);
            }
            return EXIT_SUCCESS;
        } catch (IOException | UncheckedIOException e) {
            err.println("cannot read a required file: " + e.getMessage());
            return EXIT_ERROR;
        } catch (WichtelmException e) {
            // SweepRunner records a failed combination as a result row rather
            // than throwing, so a BacktestException never escapes the run; the
            // exceptions that reach here are parse/config/data-source failures.
            err.println(describe(e));
            return EXIT_ERROR;
        }
    }

    private Integer positiveInt(String raw, String flag) {
        try {
            int value = Integer.parseInt(raw);
            if (value < 1) {
                err.println(flag + " must be a positive integer, was " + raw);
                return null;
            }
            return value;
        } catch (NumberFormatException e) {
            err.println(flag + " must be a positive integer, was " + raw);
            return null;
        }
    }

    private Path writeSweepCsv(BacktestConfig config, Path configFile, List<SweepResult> rows,
                               List<String> axisNames, SweepObjective objective,
                               Path outputOverride) {
        String fileName = configFile.getFileName().toString();
        int dot = fileName.lastIndexOf('.');
        String basename = dot > 0 ? fileName.substring(0, dot) : fileName;
        Path outputDirectory = outputOverride != null
                ? outputOverride
                : config.outputDirectory().orElse(Path.of("."));
        LocalDateTime generatedAt = LocalDateTime.now(ZoneOffset.UTC);
        return SweepCsvWriter.write(rows, axisNames, objective, outputDirectory, basename,
                generatedAt);
    }

    private int validateStrategy(String[] args) {
        if (args.length != 1) {
            err.println("wichtelm validate requires exactly one strategy file");
            return EXIT_USAGE;
        }
        try {
            ParsedStrategy strategy = StrategyParser.parse(Path.of(args[0]));
            out.println("Strategy parsed successfully: \"" + strategy.featureName() + "\" — "
                    + strategy.parameters().size() + " parameter(s), "
                    + strategy.scenarios().size() + " scenario(s).");
            return EXIT_SUCCESS;
        } catch (WichtelmException e) {
            err.println(describe(e));
            return EXIT_ERROR;
        } catch (UncheckedIOException e) {
            err.println("cannot read strategy file: " + args[0]);
            return EXIT_ERROR;
        }
    }

    private Path writeReport(BacktestConfig config, Path configFile, ParsedStrategy strategy,
                             BacktestRunResult result, Map<String, BigDecimal> parameters,
                             Path outputOverride) {
        String fileName = configFile.getFileName().toString();
        int dot = fileName.lastIndexOf('.');
        String basename = dot > 0 ? fileName.substring(0, dot) : fileName;
        Path outputDirectory = outputOverride != null
                ? outputOverride
                : config.outputDirectory().orElse(Path.of("."));

        Map<String, OHLCSeries> higherSeries = new LinkedHashMap<>();
        result.higherTimeframeBars()
                .forEach((wire, bars) -> higherSeries.put(wire, new OHLCSeries(bars)));

        // The report header labels generatedAt as "UTC"; populate it from
        // UTC-now so the label is truthful regardless of host clock zone.
        LocalDateTime generatedAt = LocalDateTime.now(ZoneOffset.UTC);
        ReportData data = new ReportData(basename, generatedAt, outputDirectory, strategy,
                result.result(), new OHLCSeries(result.primarySeries()), higherSeries,
                result.triggerTimes(), parameters, config.symbol(), result.suppressedEntries());
        return new HtmlReportGenerator().generate(data);
    }

    static String describe(WichtelmException e) {
        String head = switch (e) {
            case StrategyParseException s -> "StrategyParseException [" + s.violatedRule() + "] at "
                    + s.filePath() + ":" + s.lineNumber() + ":" + s.columnNumber()
                    + " — " + s.getMessage();
            case ConfigParseException c -> "ConfigParseException [" + c.violatedRule() + "] at "
                    + c.filePath() + " (" + c.keyPath() + ") — " + c.getMessage();
            case SweepConfigException s -> "SweepConfigException [" + s.violatedRule() + "] at "
                    + s.filePath() + " (" + s.keyPath() + ") — " + s.getMessage();
            default -> e.getClass().getSimpleName() + ": " + e.getMessage();
        };
        return head + causeChain(e);
    }

    /**
     * Appends the wrapped-cause chain. A data-source / report failure carries the
     * actionable detail (e.g. the EODHD 403 / 404 / 429 reason) in its cause; the
     * top-level message alone ("failed to load ... data for symbol ...") is not
     * enough to act on, so each cause is rendered on its own line.
     */
    static String causeChain(Throwable error) {
        StringBuilder out = new StringBuilder();
        Throwable cause = error.getCause();
        int depth = 0;
        while (cause != null && cause != error && depth < 8) {
            out.append(System.lineSeparator()).append("  caused by: ")
                    .append(cause.getClass().getSimpleName());
            if (cause.getMessage() != null) {
                out.append(": ").append(cause.getMessage());
            }
            error = cause;
            cause = cause.getCause();
            depth++;
        }
        return out.toString();
    }

    private void printUsage(PrintStream stream) {
        stream.println("""
                Usage:
                  wichtelm run <config-file> [--no-report] [--output-dir <path>]
                  wichtelm sweep <config-file> [--objective <metric>] [--top <N>]
                                               [--max-combos <N>] [--no-report] [--output-dir <path>]
                  wichtelm validate <strat-file>
                  wichtelm --version
                  wichtelm --help

                sweep runs every combination declared in the config's [sweep] table and
                ranks them by --objective (default sharpe; also total_return, sortino,
                calmar, profit_factor), printing a ranked table and writing a CSV.

                NOT financial advice. Use at your own risk. See README for full disclaimer.""");
    }
}
