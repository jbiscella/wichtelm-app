package net.jacopobiscella.wichtelm.strategy;

import org.hatrack.commons.Timeframe;

import java.util.Objects;
import java.util.Optional;

/**
 * A {@code Given a series <name> defined as <expression>} declaration from the
 * Background block. The timeframe is present only for multi-timeframe series.
 */
public record BackgroundSeries(String name, String expression, Optional<Timeframe> timeframe, int line) {

    public BackgroundSeries {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(expression, "expression");
        Objects.requireNonNull(timeframe, "timeframe");
    }
}
