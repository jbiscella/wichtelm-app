package net.jacopobiscella.wichtelm;

import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import net.jacopobiscella.wichtelm.error.SweepConfigException;
import net.jacopobiscella.wichtelm.sweep.SweepGrid;
import net.jacopobiscella.wichtelm.sweep.SweepObjective;
import net.jacopobiscella.wichtelm.sweep.SweepSpec;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Step definitions for {@code sweep-grid.feature} (CLAUDE.md section 18). */
public class SweepGridSteps {

    private final Map<String, List<BigDecimal>> axes = new LinkedHashMap<>();
    private int maxCombos = 10_000;
    private List<Map<String, BigDecimal>> grid;
    private SweepConfigException capError;

    @Given("a sweep axis {string} ranging from {int} to {int} step {int} as INTEGER")
    public void integerRangeAxis(String name, int from, int to, int step) {
        List<BigDecimal> values = new ArrayList<>();
        for (int v = from; v <= to; v += step) {
            values.add(BigDecimal.valueOf(v));
        }
        axes.put(name, values);
    }

    @Given("a sweep axis {string} listing {int}, {int} as INTEGER")
    public void integerListAxis(String name, int a, int b) {
        axes.put(name, List.of(BigDecimal.valueOf(a), BigDecimal.valueOf(b)));
    }

    @Given("a sweep axis {string} ranging from {double} to {double} step {double} as DECIMAL")
    public void decimalRangeAxis(String name, double from, double to, double step) {
        MathContext mc = MathContext.DECIMAL64;
        BigDecimal stepBd = BigDecimal.valueOf(step);
        BigDecimal bound = BigDecimal.valueOf(to).add(stepBd.divide(BigDecimal.valueOf(2), mc), mc);
        List<BigDecimal> values = new ArrayList<>();
        for (BigDecimal v = BigDecimal.valueOf(from); v.compareTo(bound) <= 0; v = v.add(stepBd, mc)) {
            values.add(v);
        }
        axes.put(name, values);
    }

    @Given("a max-combinations cap of {int}")
    public void maxCombinationsCap(int cap) {
        this.maxCombos = cap;
    }

    @When("the grid is expanded")
    public void theGridIsExpanded() {
        SweepSpec spec = new SweepSpec(axes, SweepObjective.defaultObjective(), 5, maxCombos);
        grid = SweepGrid.expand(spec, "test-config.toml");
    }

    @When("the grid is expanded expecting a cap error")
    public void theGridIsExpandedExpectingCapError() {
        SweepSpec spec = new SweepSpec(axes, SweepObjective.defaultObjective(), 5, maxCombos);
        try {
            grid = SweepGrid.expand(spec, "test-config.toml");
        } catch (SweepConfigException e) {
            capError = e;
        }
    }

    @Then("the grid has {int} combinations")
    public void theGridHasCombinations(int count) {
        assertNotNull(grid);
        assertEquals(count, grid.size());
    }

    @Then("combination {int} sets {string} to {string}")
    public void combinationSets(int ordinal, String name, String value) {
        BigDecimal actual = grid.get(ordinal - 1).get(name);
        assertEquals(0, new BigDecimal(value).compareTo(actual),
                () -> "combination " + ordinal + " " + name + " was " + actual);
    }

    @Then("combination {int} sets {string} to {string} and {string} to {string}")
    public void combinationSetsTwo(int ordinal, String n1, String v1, String n2, String v2) {
        Map<String, BigDecimal> combo = grid.get(ordinal - 1);
        assertEquals(0, new BigDecimal(v1).compareTo(combo.get(n1)));
        assertEquals(0, new BigDecimal(v2).compareTo(combo.get(n2)));
    }

    @Then("a sweep cap error names the product {int} and the cap {int}")
    public void capErrorNamesProductAndCap(int product, int cap) {
        assertNotNull(capError, "expected a SweepConfigException for the cap");
        assertEquals("C15", capError.violatedRule());
        assertTrue(capError.getMessage().contains(Integer.toString(product)),
                () -> "message lacks product " + product + ": " + capError.getMessage());
        assertTrue(capError.getMessage().contains(Integer.toString(cap)),
                () -> "message lacks cap " + cap + ": " + capError.getMessage());
    }
}
