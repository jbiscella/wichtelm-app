package net.jacopobiscella.wichtelm;

import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import net.jacopobiscella.wichtelm.error.StrategyParseException;
import net.jacopobiscella.wichtelm.error.WichtelmException;
import net.jacopobiscella.wichtelm.strategy.ParsedStrategy;
import net.jacopobiscella.wichtelm.strategy.StrategyParser;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Step definitions for {@code strategy-parser-validation.feature} (Block 1). */
public class StrategyParserSteps {

    private String content;
    private ParsedStrategy result;
    private WichtelmException thrown;

    private static String valid(String body) {
        return "Feature: Test strategy\n  Primary timeframe: 1h\n\n" + body;
    }

    private static final String ENTRY_SCENARIO = """
              Scenario: Enter
                Given no open position
                When close exceeds 1
                Then long_entry
            """;

    @Given("a strategy file with no Feature header")
    public void strategyWithNoFeatureHeader() {
        content = "Scenario: Enter\n  Given no open position\n  Then long_entry\n";
    }

    @Given("a strategy file with a Feature header but no Primary timeframe declaration")
    public void strategyWithNoPrimaryTimeframe() {
        content = "Feature: Test strategy\n\n" + ENTRY_SCENARIO;
    }

    @Given("a strategy file with two {string} lines")
    public void strategyWithDuplicateParameter(String parameterLine) {
        content = "Feature: Test strategy\n  Primary timeframe: 1h\n  "
                + parameterLine + "\n  " + parameterLine + "\n\n" + ENTRY_SCENARIO;
    }

    @Given("a strategy file with two Scenarios both named {string}")
    public void strategyWithDuplicateScenarioName(String scenarioName) {
        String scenario = "  Scenario: " + scenarioName + "\n    Given no open position\n"
                + "    When close exceeds 1\n    Then long_entry\n";
        content = valid(scenario + "\n" + scenario);
    }

    @Given("a strategy file with a Scenario that ends with {string}")
    public void strategyWithScenarioEndingWith(String terminalStep) {
        content = valid("  Scenario: Bad\n    Given no open position\n"
                + "    When close exceeds 1\n    " + terminalStep + "\n");
    }

    @Given("a strategy file with a Scenario terminating with {string}")
    public void strategyWithScenarioTerminatingWith(String terminalStep) {
        content = valid("  Scenario: Exit\n    Given a long position is open\n"
                + "    When close exceeds 1\n    " + terminalStep + "\n");
    }

    @Given("the same Scenario has {string} appended")
    public void sameScenarioHasClauseAppended(String clause) {
        content = content + "    " + clause + "\n";
    }

    @Given("a strategy file with {string}")
    public void strategyFileWithStep(String step) {
        if (step.startsWith("And with ")) {
            content = valid("  Scenario: Enter\n    Given no open position\n"
                    + "    When close exceeds 1\n    Then long_entry\n    " + step + "\n");
        } else {
            content = valid("  Scenario: Enter\n    Given no open position\n"
                    + "    " + step + "\n    Then long_entry\n");
        }
    }

    @Given("a strategy file with Primary timeframe {word}")
    public void strategyFileWithPrimaryTimeframe(String timeframe) {
        content = "Feature: Test strategy\n  Primary timeframe: " + timeframe + "\n\n" + ENTRY_SCENARIO;
    }

    @Given("a Background declaring {string}")
    public void backgroundDeclaring(String declaration) {
        content = "Feature: Test strategy\n  Primary timeframe: 1d\n\n"
                + "  Background:\n    Given a series trend defined as ema(200) on 1h\n\n"
                + ENTRY_SCENARIO;
    }

    @Given("the canonical example strategy from section {int}.{int}")
    public void theCanonicalExampleStrategy(int major, int minor) {
        content = readResource("strategies/canonical.strat");
    }

    @When("the parser reads the file")
    public void theParserReadsTheFile() {
        try {
            result = StrategyParser.parse(content, "test.strat");
        } catch (WichtelmException e) {
            thrown = e;
        }
    }

    @Then("StrategyParseException is thrown")
    public void strategyParseExceptionIsThrown() {
        assertNotNull(thrown, "expected a StrategyParseException to be thrown");
        assertInstanceOf(StrategyParseException.class, thrown);
    }

    @Then("violatedRule is {string}")
    public void violatedRuleIs(String rule) {
        assertInstanceOf(StrategyParseException.class, thrown);
        assertEquals(rule, ((StrategyParseException) thrown).violatedRule());
    }

    @Then("no exception is thrown")
    public void noExceptionIsThrown() {
        assertNull(thrown, () -> "unexpected exception: " + thrown);
        assertNotNull(result);
    }

    @Then("the parsed AST contains {int} parameters")
    public void astContainsParameters(int count) {
        assertEquals(count, result.parameters().size());
    }

    @Then("the parsed AST contains {int} Background series declarations")
    public void astContainsBackgroundSeries(int count) {
        assertEquals(count, result.backgroundSeries().size());
    }

    @Then("the parsed AST contains {int} Scenarios")
    public void astContainsScenarios(int count) {
        assertEquals(count, result.scenarios().size());
    }

    @Then("each Scenario terminates with one of long_entry\\/long_exit\\/short_entry\\/short_exit")
    public void eachScenarioTerminatesWithFirstClassCondition() {
        assertTrue(result.scenarios().stream().allMatch(s -> s.terminalCondition() != null));
        assertEquals(result.scenarios().size(),
                result.scenarios().stream().filter(s -> s.terminalCondition() != null).count());
    }

    private static String readResource(String name) {
        try (InputStream in = Thread.currentThread().getContextClassLoader().getResourceAsStream(name)) {
            assertNotNull(in, () -> "missing test resource: " + name);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("cannot read test resource: " + name, e);
        }
    }
}
