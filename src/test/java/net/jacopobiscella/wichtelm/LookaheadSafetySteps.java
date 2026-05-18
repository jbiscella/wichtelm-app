package net.jacopobiscella.wichtelm;

import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import net.jacopobiscella.wichtelm.runtime.HigherTimeframeSeries;
import org.hatrack.commons.OHLCBar;
import org.hatrack.commons.Timeframe;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Step definitions for {@code multi-tf-lookahead-safety.feature} (Block 3). */
public class LookaheadSafetySteps {

    private HigherTimeframeSeries series;
    private LocalDate rangeFrom;
    private LocalDate rangeTo;
    private OHLCBar resolved;

    private static Instant startOfDay(String isoDate) {
        return LocalDate.parse(isoDate).atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    private static OHLCBar flatBar(Instant time) {
        BigDecimal price = new BigDecimal("100");
        return new OHLCBar(time, price, price, price, price, Optional.empty());
    }

    @Given("a higher-timeframe series on 1d with daily bars from {string} to {string}")
    public void aHigherTimeframeSeries(String from, String to) {
        rangeFrom = LocalDate.parse(from);
        rangeTo = LocalDate.parse(to);
        List<OHLCBar> bars = new ArrayList<>();
        for (LocalDate d = rangeFrom; !d.isAfter(rangeTo); d = d.plusDays(1)) {
            bars.add(flatBar(d.atStartOfDay(ZoneOffset.UTC).toInstant()));
        }
        series = new HigherTimeframeSeries(Timeframe.fromWire("1d"), bars);
    }

    @When("the series is resolved at primary bar time {string}")
    public void theSeriesIsResolvedAt(String isoInstant) {
        resolved = series.resolveAt(Instant.parse(isoInstant)).orElse(null);
    }

    @Then("the resolved bar is the one covering {string}")
    public void theResolvedBarIsTheOneCovering(String isoDate) {
        assertNotNull(resolved, "expected a resolved bar");
        assertEquals(startOfDay(isoDate), resolved.time());
    }

    @Then("the resolved bar is not the one covering {string}")
    public void theResolvedBarIsNotTheOneCovering(String isoDate) {
        assertNotNull(resolved, "expected a resolved bar");
        assertNotEquals(startOfDay(isoDate), resolved.time());
    }

    @When("the series is resolved at every hour of the primary timeframe range")
    public void theSeriesIsResolvedAtEveryHour() {
        // The assertion runs in the Then step; this step only marks the range as iterated.
        assertNotNull(series);
    }

    @Then("every resolved bar has closeTime at or before its primary bar time")
    public void everyResolvedBarIsLookaheadSafe() {
        Instant start = rangeFrom.atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant end = rangeTo.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        int checked = 0;
        for (Instant t = start; t.isBefore(end); t = t.plus(Duration.ofHours(1))) {
            Optional<OHLCBar> bar = series.resolveAt(t);
            if (bar.isPresent()) {
                assertFalse(series.closeTimeOf(bar.get()).isAfter(t),
                        () -> "lookahead violation: resolved bar closes after primary bar time");
                checked++;
            }
        }
        assertTrue(checked > 0, "expected at least one resolution across the range");
    }
}
