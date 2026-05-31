package net.jacopobiscella.wichtelm;

import io.cucumber.java.After;
import io.cucumber.java.Before;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import net.jacopobiscella.wichtelm.config.BacktestConfig;
import net.jacopobiscella.wichtelm.config.ConfigParser;
import net.jacopobiscella.wichtelm.config.SweepParameterResolver;
import net.jacopobiscella.wichtelm.error.SweepConfigException;
import net.jacopobiscella.wichtelm.error.WichtelmException;
import net.jacopobiscella.wichtelm.strategy.ParsedStrategy;
import net.jacopobiscella.wichtelm.strategy.StrategyParser;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Step definitions for {@code sweep-config-validation.feature} (CLAUDE.md section 18). */
public class SweepConfigSteps {

    private static final String STRATEGY_CONTENT = """
            Feature: Sweep test strategy
              Primary timeframe: 1h
              Parameter rsi_period default 14
              Parameter overbought default 70

              Scenario: Enter
                Given no open position
                When close exceeds 1
                Then long_entry
            """;

    private Path tempDir;
    private Path strategyFile;
    private String tomlContent;
    private BacktestConfig config;
    private ParsedStrategy strategy;
    private WichtelmException thrown;

    @Before
    public void setUp() throws IOException {
        tempDir = Files.createTempDirectory("wichtelm-sweep");
        strategyFile = tempDir.resolve("strategy.strat");
        Files.writeString(strategyFile, STRATEGY_CONTENT);
    }

    @After
    public void tearDown() throws IOException {
        try (Stream<Path> paths = Files.walk(tempDir)) {
            paths.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // best-effort cleanup
                }
            });
        }
    }

    private String base() {
        return "strategy = \"" + strategyFile + "\"\n"
                + "symbol = \"AAPL\"\ndata_source = \"eodhd\"\n\n"
                + "[date_range]\nfrom = 2024-01-01\nto = 2024-12-31\n\n"
                + "[sizing]\nposition_size_pct = 50\n\n"
                + "[eodhd]\napi_token_env = \"EODHD_API_TOKEN\"\n";
    }

    @Given("a TOML config with a [sweep] range {string} from {int} to {int} step {int}")
    public void sweepRange(String param, int from, int to, int step) {
        tomlContent = base() + "\n[sweep]\n" + param
                + " = { from = " + from + ", to = " + to + ", step = " + step + " }\n";
    }

    @Given("a TOML config with a [sweep] list {string} of {int}, {int}, {int}")
    public void sweepList(String param, int a, int b, int c) {
        tomlContent = base() + "\n[sweep]\n" + param + " = [" + a + ", " + b + ", " + c + "]\n";
    }

    @Given("a TOML config with a [sweep] empty list {string}")
    public void sweepEmptyList(String param) {
        tomlContent = base() + "\n[sweep]\n" + param + " = []\n";
    }

    @Given("a TOML config sweeping {string} that is also fixed in [parameters]")
    public void sweepOverlap(String param) {
        tomlContent = base() + "\n[parameters]\n" + param + " = 14\n"
                + "\n[sweep]\n" + param + " = { from = 8, to = 16, step = 2 }\n";
    }

    @Given("a sweepable strategy declaring parameter {string}")
    public void strategyDeclaringParameter(String name) {
        assertEquals("rsi_period", name);
        strategy = StrategyParser.parse(STRATEGY_CONTENT, strategyFile.toString());
    }

    @Given("a TOML config sweeping undeclared parameter {string}")
    public void sweepUndeclared(String param) {
        tomlContent = base() + "\n[sweep]\n" + param + " = { from = 1, to = 3, step = 1 }\n";
        config = ConfigParser.parse(tomlContent, tempDir.resolve("config.toml").toString());
    }

    @When("the sweep config parser reads the file")
    public void sweepConfigParserReads() {
        try {
            config = ConfigParser.parse(tomlContent, tempDir.resolve("config.toml").toString());
        } catch (WichtelmException e) {
            thrown = e;
        }
    }

    @When("the sweep resolver checks the axes against the strategy")
    public void sweepResolverChecks() {
        try {
            SweepParameterResolver.resolveAxes(strategy, config);
        } catch (WichtelmException e) {
            thrown = e;
        }
    }

    @Then("SweepConfigException is thrown")
    public void sweepConfigExceptionThrown() {
        assertNotNull(thrown, "expected a SweepConfigException to be thrown");
        assertInstanceOf(SweepConfigException.class, thrown);
    }

    @Then("the sweep violatedRule is {string}")
    public void sweepViolatedRuleIs(String rule) {
        assertInstanceOf(SweepConfigException.class, thrown);
        assertEquals(rule, ((SweepConfigException) thrown).violatedRule());
    }

    @Then("no sweep exception is thrown")
    public void noSweepExceptionThrown() {
        assertNull(thrown, () -> "unexpected exception: " + thrown);
        assertNotNull(config);
    }

    @Then("the sweep declares axis {string}")
    public void sweepDeclaresAxis(String name) {
        assertTrue(config.sweep().isPresent(), "expected a [sweep] section");
        assertTrue(config.sweep().get().axes().containsKey(name),
                () -> "no axis named '" + name + "': " + config.sweep().get().parameterNames());
    }
}
