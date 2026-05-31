package net.jacopobiscella.wichtelm.config;

import net.jacopobiscella.wichtelm.error.ConfigParseException;
import net.jacopobiscella.wichtelm.error.SweepConfigException;
import org.tomlj.Toml;
import org.tomlj.TomlParseResult;
import org.tomlj.TomlTable;

import java.math.BigDecimal;
import java.math.MathContext;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Parser for per-backtest TOML config files (CLAUDE.md section 5). Enforces the
 * config validation rules C1-C11; rule C9's environment-variable check is
 * deferred to runtime as the spec requires. Unknown top-level keys (C11) yield
 * warnings rather than errors.
 */
public final class ConfigParser {

    private static final MathContext DECIMAL = MathContext.DECIMAL64;
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private static final Set<String> KNOWN_TOP_LEVEL_KEYS = Set.of(
            "strategy", "symbol", "data_source", "date_range",
            "sizing", "parameters", "sweep", "output", "csv", "eodhd");

    private final String filePath;
    private final Path configDir;
    private final TomlParseResult toml;
    private final List<String> warnings = new ArrayList<>();

    private ConfigParser(String filePath, TomlParseResult toml) {
        this.filePath = filePath;
        Path absolute = Path.of(filePath).toAbsolutePath();
        this.configDir = absolute.getParent();
        this.toml = toml;
    }

    /** Parses TOML config content already loaded into memory. */
    public static BacktestConfig parse(String tomlContent, String filePath) {
        TomlParseResult toml = Toml.parse(tomlContent);
        return new ConfigParser(filePath, toml).parseInternal();
    }

    private BacktestConfig parseInternal() {
        if (!toml.errors().isEmpty()) {
            throw fail("", "C0", "malformed TOML: " + toml.errors().getFirst().getMessage());
        }

        Path strategyPath = parseStrategyPath();
        String symbol = parseSymbol();
        LocalDate[] range = parseDateRange();
        DataSource dataSource = parseDataSource();
        BigDecimal positionSizePct = parsePositionSizePct();
        boolean pyramiding = parsePyramiding();
        Map<String, Object> parameterOverrides = parseParameterOverrides();
        Optional<SweepDefinition> sweep = parseSweep(parameterOverrides);
        Optional<Path> outputDirectory = parseOutputDirectory();
        parseOutputFormat();
        Optional<Path> csvFile = parseCsvFile(dataSource);
        Optional<String> eodhdApiTokenEnv = parseEodhdApiTokenEnv(dataSource);
        collectUnknownTopLevelKeys();

        return new BacktestConfig(Path.of(filePath), strategyPath, symbol, range[0], range[1],
                dataSource, positionSizePct, pyramiding, parameterOverrides, sweep,
                outputDirectory, csvFile, eodhdApiTokenEnv, warnings);
    }

    private Path parseStrategyPath() {
        if (!toml.contains("strategy") || !toml.isString("strategy")) {
            throw fail("strategy", "C1", "config must declare a 'strategy' file path");
        }
        Path path = resolve(toml.getString("strategy"));
        if (!Files.isReadable(path)) {
            throw fail("strategy", "C1", "strategy file is not a readable path: " + path);
        }
        return path;
    }

    private String parseSymbol() {
        if (!toml.contains("symbol") || !toml.isString("symbol")
                || toml.getString("symbol").isBlank()) {
            throw fail("symbol", "C2", "config must declare a non-empty 'symbol'");
        }
        return toml.getString("symbol");
    }

    private LocalDate[] parseDateRange() {
        if (!toml.isLocalDate("date_range.from") || !toml.isLocalDate("date_range.to")) {
            throw fail("date_range", "C3", "date_range.from and date_range.to must be ISO-8601 dates");
        }
        LocalDate from = toml.getLocalDate("date_range.from");
        LocalDate to = toml.getLocalDate("date_range.to");
        if (!from.isBefore(to)) {
            throw fail("date_range", "C3", "date_range.from must be strictly before date_range.to");
        }
        return new LocalDate[]{from, to};
    }

    private DataSource parseDataSource() {
        if (!toml.isString("data_source")) {
            throw fail("data_source", "C4", "config must declare a 'data_source'");
        }
        String wire = toml.getString("data_source");
        return DataSource.fromWire(wire)
                .orElseThrow(() -> fail("data_source", "C4", "unrecognized data_source: " + wire));
    }

    private BigDecimal parsePositionSizePct() {
        Object value = toml.get("sizing.position_size_pct");
        BigDecimal pct = switch (value) {
            case Long l -> new BigDecimal(l, DECIMAL);
            case Double d -> new BigDecimal(d, DECIMAL);
            case null, default ->
                    throw fail("sizing.position_size_pct", "C5", "sizing.position_size_pct must be numeric");
        };
        if (pct.signum() <= 0 || pct.compareTo(HUNDRED) > 0) {
            throw fail("sizing.position_size_pct", "C5",
                    "sizing.position_size_pct must be in (0, 100], was " + pct);
        }
        return pct;
    }

    private boolean parsePyramiding() {
        return toml.getBoolean("sizing.pyramiding", () -> false);
    }

    private Map<String, Object> parseParameterOverrides() {
        Map<String, Object> overrides = new LinkedHashMap<>();
        TomlTable table = toml.getTable("parameters");
        if (table != null) {
            for (String key : table.keySet()) {
                overrides.put(key, table.get(key));
            }
        }
        return overrides;
    }

    /**
     * Parses the optional {@code [sweep]} table (CLAUDE.md section 18). Each key
     * maps to either a range inline table ({@code from} / {@code to} / {@code
     * step}) or an explicit array of values. Enforces the structural sweep rules:
     * C12 (a parameter must not appear in both {@code [parameters]} and {@code
     * [sweep]}) and C14 (a well-formed range with a positive step and {@code from
     * <= to}, or a non-empty value list). C13 (every axis names a declared
     * strategy parameter) needs the strategy and is enforced later by
     * {@code SweepParameterResolver}.
     */
    private Optional<SweepDefinition> parseSweep(Map<String, Object> parameterOverrides) {
        TomlTable table = toml.getTable("sweep");
        if (table == null) {
            return Optional.empty();
        }
        Map<String, SweepDefinition.Axis> axes = new LinkedHashMap<>();
        for (String key : table.keySet()) {
            if (parameterOverrides.containsKey(key)) {
                throw sweepFail("sweep." + key, "C12",
                        "parameter '" + key + "' is both fixed in [parameters] and swept in "
                                + "[sweep]; choose one");
            }
            axes.put(key, parseAxis(key, table.get(key)));
        }
        return Optional.of(new SweepDefinition(axes));
    }

    private SweepDefinition.Axis parseAxis(String key, Object raw) {
        return switch (raw) {
            case TomlTable rangeTable -> parseRangeAxis(key, rangeTable);
            case org.tomlj.TomlArray array -> parseListAxis(key, array);
            case null, default -> throw sweepFail("sweep." + key, "C14",
                    "sweep parameter '" + key + "' must be a range table "
                            + "{ from = .., to = .., step = .. } or a non-empty array of values");
        };
    }

    private SweepDefinition.Axis parseRangeAxis(String key, TomlTable rangeTable) {
        BigDecimal from = numeric(rangeTable.get("from"), key, "from");
        BigDecimal to = numeric(rangeTable.get("to"), key, "to");
        BigDecimal step = numeric(rangeTable.get("step"), key, "step");
        if (step.signum() <= 0) {
            throw sweepFail("sweep." + key + ".step", "C14",
                    "sweep range step for '" + key + "' must be positive, was " + step);
        }
        if (from.compareTo(to) > 0) {
            throw sweepFail("sweep." + key, "C14",
                    "sweep range for '" + key + "' must have from <= to, was from " + from
                            + " to " + to);
        }
        return new SweepDefinition.Range(from, to, step);
    }

    private SweepDefinition.Axis parseListAxis(String key, org.tomlj.TomlArray array) {
        if (array.isEmpty()) {
            throw sweepFail("sweep." + key, "C14",
                    "sweep value list for '" + key + "' must not be empty");
        }
        List<BigDecimal> values = new ArrayList<>(array.size());
        for (int i = 0; i < array.size(); i++) {
            values.add(numeric(array.get(i), key, "value[" + i + "]"));
        }
        return new SweepDefinition.ValueList(values);
    }

    private BigDecimal numeric(Object value, String key, String field) {
        return switch (value) {
            case Long l -> new BigDecimal(l, DECIMAL);
            // TOML's nan / inf reach here as a non-finite Double; new BigDecimal(d)
            // would throw NumberFormatException and escape the C14 path, so reject
            // them explicitly with the documented sweep config error.
            case Double d when !Double.isFinite(d) -> throw sweepFail(
                    "sweep." + key + "." + field, "C14",
                    "sweep '" + key + "' " + field + " must be a finite number, was " + d);
            case Double d -> new BigDecimal(d, DECIMAL);
            case null, default -> throw sweepFail("sweep." + key + "." + field, "C14",
                    "sweep '" + key + "' " + field + " must be a numeric value");
        };
    }

    private Optional<Path> parseOutputDirectory() {
        if (toml.isString("output.directory")) {
            return Optional.of(resolve(toml.getString("output.directory")));
        }
        return Optional.empty();
    }

    private void parseOutputFormat() {
        if (toml.contains("output.format")) {
            String format = toml.getString("output.format");
            if (!"html".equals(format)) {
                throw fail("output.format", "C10",
                        "output.format is restricted to \"html\" in v1, was: " + format);
            }
        }
    }

    private Optional<Path> parseCsvFile(DataSource dataSource) {
        if (dataSource != DataSource.CSV) {
            return Optional.empty();
        }
        if (!toml.isString("csv.file")) {
            throw fail("csv.file", "C8", "data_source \"csv\" requires [csv].file");
        }
        String raw = toml.getString("csv.file");
        if (!raw.contains("{symbol}")) {
            throw fail("csv.file", "C8", "[csv].file must contain the {symbol} placeholder "
                    + "when data_source = \"csv\". Example: \"data/{symbol}_1h.csv\" for symbol AAPL");
        }
        Path path = resolve(raw);
        if (containsPlaceholder(raw)) {
            Path directory = path.getParent();
            if (directory != null && containsPlaceholder(directory.toString())) {
                throw fail("csv.file", "C8", "[csv].file placeholders must appear only in the "
                        + "file name, not in a directory component: " + raw);
            }
            Path baseDirectory = directory != null
                    ? directory : (configDir != null ? configDir : Path.of("."));
            if (!Files.isDirectory(baseDirectory) || !Files.isReadable(baseDirectory)) {
                throw fail("csv.file", "C8",
                        "[csv].file base directory is not a readable directory: " + baseDirectory);
            }
        } else if (!Files.isReadable(path)) {
            throw fail("csv.file", "C8", "[csv].file is not a readable path: " + path);
        }
        return Optional.of(path);
    }

    private static boolean containsPlaceholder(String csvFile) {
        return csvFile.contains("{symbol}") || csvFile.contains("{timeframe}");
    }

    private Optional<String> parseEodhdApiTokenEnv(DataSource dataSource) {
        if (dataSource != DataSource.EODHD) {
            return Optional.empty();
        }
        if (!toml.isString("eodhd.api_token_env") || toml.getString("eodhd.api_token_env").isBlank()) {
            throw fail("eodhd.api_token_env", "C9", "data_source \"eodhd\" requires [eodhd].api_token_env");
        }
        return Optional.of(toml.getString("eodhd.api_token_env"));
    }

    private void collectUnknownTopLevelKeys() {
        for (String key : toml.keySet()) {
            if (!KNOWN_TOP_LEVEL_KEYS.contains(key)) {
                warnings.add("unknown top-level config key ignored: " + key);
            }
        }
    }

    private Path resolve(String raw) {
        Path path = Path.of(raw);
        if (path.isAbsolute() || configDir == null) {
            return path;
        }
        return configDir.resolve(path);
    }

    private ConfigParseException fail(String keyPath, String rule, String message) {
        return new ConfigParseException(filePath, keyPath, rule, message);
    }

    private SweepConfigException sweepFail(String keyPath, String rule, String message) {
        return new SweepConfigException(filePath, keyPath, rule, message);
    }
}
