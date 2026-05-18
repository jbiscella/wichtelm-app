package net.jacopobiscella.wichtelm.strategy;

import java.util.Optional;

/** The position state a Scenario's opening {@code Given} step asserts (CLAUDE.md section 6.2). */
public enum PositionPrecondition {
    NO_OPEN_POSITION,
    LONG_POSITION_OPEN,
    SHORT_POSITION_OPEN;

    public static Optional<PositionPrecondition> fromText(String text) {
        return switch (text.trim()) {
            case "no open position" -> Optional.of(NO_OPEN_POSITION);
            case "a long position is open" -> Optional.of(LONG_POSITION_OPEN);
            case "a short position is open" -> Optional.of(SHORT_POSITION_OPEN);
            default -> Optional.empty();
        };
    }
}
