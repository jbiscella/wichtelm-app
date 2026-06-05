package net.jacopobiscella.wichtelm.config;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable result of parsing a per-backtest TOML config file (CLAUDE.md
 * section 5.1). {@code parameterOverrides} holds the raw TOML values keyed by
 * parameter name; reconciliation against the strategy's declared parameters
 * happens in {@link ParameterResolver}.
 */
public record BacktestConfig(Path configPath,
                             Path strategyPath,
                             String symbol,
                             LocalDate dateFrom,
                             LocalDate dateTo,
                             DataSource dataSource,
                             BigDecimal positionSizePct,
                             boolean pyramiding,
                             Map<String, Object> parameterOverrides,
                             Optional<SweepDefinition> sweep,
                             Optional<Path> outputDirectory,
                             Optional<Path> csvFile,
                             Optional<String> eodhdApiTokenEnv,
                             List<String> warnings) {

    public BacktestConfig {
        Objects.requireNonNull(configPath, "configPath");
        Objects.requireNonNull(strategyPath, "strategyPath");
        Objects.requireNonNull(symbol, "symbol");
        Objects.requireNonNull(dateFrom, "dateFrom");
        Objects.requireNonNull(dateTo, "dateTo");
        Objects.requireNonNull(dataSource, "dataSource");
        Objects.requireNonNull(positionSizePct, "positionSizePct");
        Objects.requireNonNull(sweep, "sweep");
        Objects.requireNonNull(outputDirectory, "outputDirectory");
        Objects.requireNonNull(csvFile, "csvFile");
        Objects.requireNonNull(eodhdApiTokenEnv, "eodhdApiTokenEnv");
        parameterOverrides = Map.copyOf(parameterOverrides);
        warnings = List.copyOf(warnings);
    }
}
