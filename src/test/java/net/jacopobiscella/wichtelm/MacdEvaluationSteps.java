package net.jacopobiscella.wichtelm;

import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import org.hatrack.dsl.BarIndicatorSource;
import org.hatrack.dsl.ExpressionEvaluator;
import org.hatrack.commons.OHLCBar;
import org.hatrack.indicators.Indicators;
import org.hatrack.indicators.MacdResult;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Step definitions for {@code macd-evaluation.feature} (Block 7 Tier A). */
public class MacdEvaluationSteps {

    private List<OHLCBar> bars;
    private BigDecimal result;

    @Given("a deterministic price series of {int} bars")
    public void aDeterministicPriceSeries(int barCount) {
        bars = new ArrayList<>(barCount);
        Instant start = Instant.parse("2024-01-01T00:00:00Z");
        for (int i = 0; i < barCount; i++) {
            // Gentle upward drift plus a wave, so the MACD line and signal
            // line diverge and the histogram is non-trivially signed.
            double price = 100 + i * 0.5 + 8 * Math.sin(i / 5.0);
            BigDecimal close = BigDecimal.valueOf(price);
            bars.add(new OHLCBar(start.plus(Duration.ofHours(i)),
                    close, close.add(BigDecimal.ONE), close.subtract(BigDecimal.ONE),
                    close, Optional.empty()));
        }
    }

    @When("the expression {string} is evaluated")
    public void theExpressionIsEvaluated(String expression) {
        result = evaluate(expression);
    }

    @Then("the result equals the indicators-module macd {word} for periods {int}, {int}, {int}")
    public void theResultEqualsTheIndicatorsModuleMacd(String component, int fast,
                                                       int slow, int signal) {
        MacdResult macd = Indicators.macd(closes(), fast, slow, signal);
        BigDecimal[] line = switch (component) {
            case "line" -> macd.macdLine();
            case "signal" -> macd.signalLine();
            case "histogram" -> macd.histogram();
            default -> throw new AssertionError("unknown MACD component: " + component);
        };
        BigDecimal expected = line[line.length - 1];
        assertEquals(0, result.compareTo(expected),
                () -> "expected " + expected + " but evaluated to " + result);
    }

    @Then("the result equals the expression {string}")
    public void theResultEqualsTheExpression(String expression) {
        BigDecimal other = evaluate(expression);
        assertEquals(0, result.compareTo(other),
                () -> "expected " + other + " but evaluated to " + result);
    }

    private BigDecimal evaluate(String expression) {
        OHLCBar bar = bars.getLast();
        long index = bars.size() - 1;
        BarIndicatorSource indicators =
                new BarIndicatorSource(bars, "macd-eval", bar.time(), index);
        ExpressionEvaluator evaluator =
                new ExpressionEvaluator("macd-eval", bar.time(), index);
        ExpressionEvaluator.Scope scope = new ExpressionEvaluator.Scope(
                id -> {
                    throw new AssertionError("unexpected bare identifier: " + id);
                },
                indicators);
        return evaluator.arithmetic(expression, scope);
    }

    private List<BigDecimal> closes() {
        return bars.stream().map(OHLCBar::close).toList();
    }
}
