package net.jacopobiscella.wichtelm.strategy;

import java.util.Optional;

/** The four first-class conditions a Scenario may terminate with (CLAUDE.md section 3.3). */
public enum FirstClassCondition {
    LONG_ENTRY,
    LONG_EXIT,
    SHORT_ENTRY,
    SHORT_EXIT;

    public static Optional<FirstClassCondition> fromWire(String wire) {
        return switch (wire) {
            case "long_entry" -> Optional.of(LONG_ENTRY);
            case "long_exit" -> Optional.of(LONG_EXIT);
            case "short_entry" -> Optional.of(SHORT_ENTRY);
            case "short_exit" -> Optional.of(SHORT_EXIT);
            default -> Optional.empty();
        };
    }

    public boolean isEntry() {
        return this == LONG_ENTRY || this == SHORT_ENTRY;
    }
}
