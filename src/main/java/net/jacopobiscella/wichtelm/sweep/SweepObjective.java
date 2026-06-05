package net.jacopobiscella.wichtelm.sweep;

import org.hatrack.frauholle.result.BacktestMetrics;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * The metric a sweep ranks parameter combinations by (CLAUDE.md section 18).
 * Every objective is "higher is better". The default is {@link #SHARPE}: a
 * risk-adjusted measure is the professional standard for picking a strategy
 * configuration, because ranking on raw return alone tends to crown the
 * combination that overfit a single lucky run.
 */
public enum SweepObjective {

    SHARPE("sharpe", "Sharpe ratio") {
        @Override
        public BigDecimal extract(BacktestMetrics m) {
            return m.sharpeRatio();
        }
    },
    TOTAL_RETURN("total_return", "Total return") {
        @Override
        public BigDecimal extract(BacktestMetrics m) {
            return m.totalReturn();
        }
    },
    SORTINO("sortino", "Sortino ratio") {
        @Override
        public BigDecimal extract(BacktestMetrics m) {
            return m.sortinoRatio();
        }
    },
    CALMAR("calmar", "Calmar ratio") {
        @Override
        public BigDecimal extract(BacktestMetrics m) {
            return m.calmarRatio();
        }
    },
    PROFIT_FACTOR("profit_factor", "Profit factor") {
        @Override
        public BigDecimal extract(BacktestMetrics m) {
            // Profit factor is undefined without closed trades; treat a tradeless
            // run as the worst possible so it never wins a ranking.
            return m.numTrades() == 0 ? null : m.profitFactor();
        }
    };

    private final String wire;
    private final String label;

    SweepObjective(String wire, String label) {
        this.wire = wire;
        this.label = label;
    }

    /** Extracts this objective's value from the backtest metrics. */
    public abstract BigDecimal extract(BacktestMetrics metrics);

    /** The CLI {@code --objective} token (e.g. {@code "sharpe"}). */
    public String wire() {
        return wire;
    }

    /** Human-readable label for reports and console output. */
    public String label() {
        return label;
    }

    /** The default objective when {@code --objective} is omitted. */
    public static SweepObjective defaultObjective() {
        return SHARPE;
    }

    /** Resolves a {@code --objective} token, case-insensitively. */
    public static Optional<SweepObjective> fromWire(String token) {
        if (token == null) {
            return Optional.empty();
        }
        String normalized = token.trim().toLowerCase(java.util.Locale.ROOT);
        for (SweepObjective objective : values()) {
            if (objective.wire.equals(normalized)) {
                return Optional.of(objective);
            }
        }
        return Optional.empty();
    }

    /** Comma-separated list of accepted tokens, for usage messages. */
    public static String wireList() {
        StringBuilder builder = new StringBuilder();
        for (SweepObjective objective : values()) {
            if (!builder.isEmpty()) {
                builder.append(", ");
            }
            builder.append(objective.wire);
        }
        return builder.toString();
    }
}
