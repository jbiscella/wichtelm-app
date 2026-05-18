package net.jacopobiscella.wichtelm.strategy;

import java.util.Objects;

/**
 * A single {@code When}/{@code And}/{@code But} step inside a Scenario, holding
 * the raw clause text and the source line it appeared on.
 */
public record StrategyStep(String keyword, String text, int line) {

    public StrategyStep {
        Objects.requireNonNull(keyword, "keyword");
        Objects.requireNonNull(text, "text");
    }
}
