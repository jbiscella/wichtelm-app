package net.jacopobiscella.wichtelm.config;

import java.util.Optional;

/** Recognized data-source values for the {@code data_source} config field (CLAUDE.md section 5.1). */
public enum DataSource {
    CSV,
    EODHD;

    public static Optional<DataSource> fromWire(String wire) {
        return switch (wire) {
            case "csv" -> Optional.of(CSV);
            case "eodhd" -> Optional.of(EODHD);
            default -> Optional.empty();
        };
    }
}
