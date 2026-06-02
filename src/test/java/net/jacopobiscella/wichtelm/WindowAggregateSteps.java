package net.jacopobiscella.wichtelm;

import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import org.hatrack.dsl.BarIndicatorSource;
import org.hatrack.dsl.ExpressionEvaluator;
import org.hatrack.commons.OHLCBar;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Step definitions for {@code window-aggregates.feature} (Block 7 Tier A). */
public class WindowAggregateSteps {

    private List<OHLCBar> bars;
    private BigDecimal result;

    @Given("a price history of {int} bars with varied prices and volume")
    public void aPriceHistoryWithVolume(int barCount) {
        bars = new ArrayList<>(barCount);
        Instant start = Instant.parse("2024-01-01T00:00:00Z");
        for (int i = 0; i < barCount; i++) {
            // Non-monotonic so the window extreme is not trivially the first
            // or last bar, and so high/low/close peak at different bars.
            double base = 100 + 6 * Math.sin(i / 3.0) + i * 0.1;
            BigDecimal close = BigDecimal.valueOf(base);
            BigDecimal high = BigDecimal.valueOf(base + 2 + (i % 4));
            BigDecimal low = BigDecimal.valueOf(base - 2 - (i % 3));
            BigDecimal volume = BigDecimal.valueOf(1000 + (i % 7) * 50L);
            bars.add(new OHLCBar(start.plus(Duration.ofHours(i)),
                    close, high, low, close, Optional.of(volume)));
        }
    }

    @When("the window aggregate {string} is evaluated")
    public void theWindowAggregateIsEvaluated(String expression) {
        OHLCBar bar = bars.getLast();
        long index = bars.size() - 1;
        BarIndicatorSource indicators =
                new BarIndicatorSource(bars, "window-test", bar.time(), index);
        ExpressionEvaluator evaluator =
                new ExpressionEvaluator("window-test", bar.time(), index);
        ExpressionEvaluator.Scope scope = new ExpressionEvaluator.Scope(
                id -> {
                    throw new AssertionError("unexpected bare identifier: " + id);
                },
                indicators);
        result = evaluator.arithmetic(expression, scope);
    }

    @Then("the result equals the {word} {word} over the last {int} bars")
    public void theResultEquals(String aggregate, String field, int period) {
        List<OHLCBar> window = bars.subList(bars.size() - period, bars.size());
        BigDecimal expected = switch (aggregate) {
            case "maximum" -> window.stream().map(bar -> field(bar, field))
                    .max(BigDecimal::compareTo).orElseThrow();
            case "minimum" -> window.stream().map(bar -> field(bar, field))
                    .min(BigDecimal::compareTo).orElseThrow();
            case "mean" -> {
                BigDecimal sum = window.stream().map(bar -> field(bar, field))
                        .reduce(BigDecimal.ZERO, (a, b) -> a.add(b, MathContext.DECIMAL64));
                yield sum.divide(BigDecimal.valueOf(period), MathContext.DECIMAL64);
            }
            default -> throw new AssertionError("unknown aggregate: " + aggregate);
        };
        assertEquals(0, result.compareTo(expected),
                () -> "expected " + expected + " but evaluated to " + result);
    }

    private static BigDecimal field(OHLCBar bar, String field) {
        return switch (field) {
            case "high" -> bar.high();
            case "low" -> bar.low();
            case "close" -> bar.close();
            case "volume" -> bar.volume().orElseThrow();
            default -> throw new AssertionError("unknown field: " + field);
        };
    }
}
