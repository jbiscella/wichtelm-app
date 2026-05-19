package net.jacopobiscella.wichtelm.cli;

import net.jacopobiscella.wichtelm.config.BacktestConfig;
import net.jacopobiscella.wichtelm.config.ConfigParser;
import net.jacopobiscella.wichtelm.config.ParameterResolver;
import net.jacopobiscella.wichtelm.error.ConfigParseException;
import net.jacopobiscella.wichtelm.error.StrategyParseException;
import net.jacopobiscella.wichtelm.error.WichtelmException;
import net.jacopobiscella.wichtelm.report.HtmlReportGenerator;
import net.jacopobiscella.wichtelm.report.ReportData;
import net.jacopobiscella.wichtelm.runtime.BacktestRunResult;
import net.jacopobiscella.wichtelm.runtime.BacktestRunner;
import net.jacopobiscella.wichtelm.strategy.ParsedStrategy;
import net.jacopobiscella.wichtelm.strategy.StrategyParser;
import org.hatrack.commons.OHLCSeries;
import org.hatrack.frauholle.error.BacktestException;

import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.UnaryOperator;

/**
 * Entry point for the {@code wichtelm} command-line tool (CLAUDE.md sections
 * 2.1 / 14). Exit codes: {@code 0} success, {@code 1} on an application error,
 * {@code 2} on a usage error.
 */
public final class WichtelmCli {

    static final String VERSION = "0.1.0-SNAPSHOT";
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
                yield EXIT_SUCCESS;
            }
            case "--help" -> {
                printUsage(out);
                yield EXIT_SUCCESS;
            }
            case "run" -> runBacktest(Arrays.copyOfRange(args, 1, args.length));
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
                Path report = writeReport(config, configFile, strategy, result, outputOverride);
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
            err.println("BacktestException: " + e.getMessage());
            return EXIT_ERROR;
        }
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
                             BacktestRunResult result, Path outputOverride) {
        String fileName = configFile.getFileName().toString();
        int dot = fileName.lastIndexOf('.');
        String basename = dot > 0 ? fileName.substring(0, dot) : fileName;
        Path outputDirectory = outputOverride != null
                ? outputOverride
                : config.outputDirectory().orElse(Path.of("."));

        Map<String, OHLCSeries> higherSeries = new LinkedHashMap<>();
        result.higherTimeframeBars()
                .forEach((wire, bars) -> higherSeries.put(wire, new OHLCSeries(bars)));

        ReportData data = new ReportData(basename, LocalDateTime.now(), outputDirectory, strategy,
                result.result(), new OHLCSeries(result.primarySeries()), higherSeries,
                result.triggerTimes());
        return new HtmlReportGenerator().generate(data);
    }

    private static String describe(WichtelmException e) {
        return switch (e) {
            case StrategyParseException s -> "StrategyParseException [" + s.violatedRule() + "] at "
                    + s.filePath() + ":" + s.lineNumber() + ":" + s.columnNumber()
                    + " — " + s.getMessage();
            case ConfigParseException c -> "ConfigParseException [" + c.violatedRule() + "] at "
                    + c.filePath() + " (" + c.keyPath() + ") — " + c.getMessage();
            default -> e.getClass().getSimpleName() + ": " + e.getMessage();
        };
    }

    private void printUsage(PrintStream stream) {
        stream.println("""
                Usage:
                  wichtelm run <config-file> [--no-report] [--output-dir <path>]
                  wichtelm validate <strat-file>
                  wichtelm --version
                  wichtelm --help""");
    }
}
