package net.jacopobiscella.wichtelm;

import io.cucumber.java.After;
import io.cucumber.java.Before;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import net.jacopobiscella.wichtelm.config.BacktestConfig;
import net.jacopobiscella.wichtelm.config.ConfigParser;
import net.jacopobiscella.wichtelm.config.ParameterResolver;
import net.jacopobiscella.wichtelm.error.ConfigParseException;
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

/** Step definitions for {@code config-parser-validation.feature} (Block 2). */
public class ConfigParserSteps {

    private static final String STRATEGY_CONTENT = """
            Feature: Test strategy
              Primary timeframe: 1h
              Parameter rsi_period default 14

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
        tempDir = Files.createTempDirectory("wichtelm-block2");
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
                    // best-effort cleanup of the per-scenario temp directory
                }
            });
        }
    }

    private String requiredSections(boolean withStrategy) {
        return (withStrategy ? "strategy = \"" + strategyFile + "\"\n" : "")
                + "symbol = \"AAPL\"\n"
                + "data_source = \"eodhd\"\n\n"
                + "[date_range]\nfrom = 2024-01-01\nto = 2024-12-31\n\n"
                + "[sizing]\nposition_size_pct = 50\n\n"
                + "[eodhd]\napi_token_env = \"EODHD_API_TOKEN\"\n";
    }

    @Given("a TOML config file with no {string} field")
    public void configWithNoField(String field) {
        assertEquals("strategy", field);
        tomlContent = requiredSections(false);
    }

    @Given("a TOML config file with date_range from {string} to {string}")
    public void configWithDateRange(String from, String to) {
        tomlContent = "strategy = \"" + strategyFile + "\"\n"
                + "symbol = \"AAPL\"\ndata_source = \"eodhd\"\n\n"
                + "[date_range]\nfrom = " + from + "\nto = " + to + "\n\n"
                + "[sizing]\nposition_size_pct = 50\n\n"
                + "[eodhd]\napi_token_env = \"EODHD_API_TOKEN\"\n";
    }

    @Given("a TOML config file with position_size_pct of {int}")
    public void configWithPositionSizePct(int pct) {
        tomlContent = "strategy = \"" + strategyFile + "\"\n"
                + "symbol = \"AAPL\"\ndata_source = \"eodhd\"\n\n"
                + "[date_range]\nfrom = 2024-01-01\nto = 2024-12-31\n\n"
                + "[sizing]\nposition_size_pct = " + pct + "\n\n"
                + "[eodhd]\napi_token_env = \"EODHD_API_TOKEN\"\n";
    }

    @Given("a TOML config file with an unknown top-level key {string}")
    public void configWithUnknownKey(String key) {
        tomlContent = key + " = true\n" + requiredSections(true);
    }

    private String csvConfig(String csvFilePath) {
        return "strategy = \"" + strategyFile + "\"\n"
                + "symbol = \"AAPL\"\ndata_source = \"csv\"\n\n"
                + "[date_range]\nfrom = 2024-01-01\nto = 2024-12-31\n\n"
                + "[sizing]\nposition_size_pct = 50\n\n"
                + "[csv]\nfile = \"" + csvFilePath + "\"\n";
    }

    @Given("a TOML config with a csv literal file path that exists")
    public void csvLiteralFilePathExists() throws IOException {
        Path csv = tempDir.resolve("data.csv");
        Files.writeString(csv, "time,open,high,low,close\n");
        tomlContent = csvConfig(csv.toString());
    }

    @Given("a TOML config with a csv literal file path that does not exist")
    public void csvLiteralFilePathMissing() {
        tomlContent = csvConfig(tempDir.resolve("missing.csv").toString());
    }

    @Given("a TOML config with a csv placeholder pattern in an existing directory")
    public void csvPlaceholderInExistingDirectory() {
        tomlContent = csvConfig(tempDir.resolve("{symbol}_{timeframe}.csv").toString());
    }

    @Given("a TOML config with a csv placeholder pattern in a missing directory")
    public void csvPlaceholderInMissingDirectory() {
        tomlContent = csvConfig(tempDir.resolve("nope").resolve("{symbol}_{timeframe}.csv").toString());
    }

    @Given("a strategy declaring parameter {string}")
    public void aStrategyDeclaringParameter(String parameterName) {
        assertEquals("rsi_period", parameterName);
        strategy = StrategyParser.parse(STRATEGY_CONTENT, strategyFile.toString());
    }

    @Given("a TOML config overriding parameter {string}")
    public void configOverridingParameter(String parameterName) {
        tomlContent = requiredSections(true)
                + "\n[parameters]\n" + parameterName + " = 99\n";
        config = ConfigParser.parse(tomlContent, tempDir.resolve("config.toml").toString());
    }

    @When("the config parser reads the file")
    public void theConfigParserReadsTheFile() {
        try {
            config = ConfigParser.parse(tomlContent, tempDir.resolve("config.toml").toString());
        } catch (WichtelmException e) {
            thrown = e;
        }
    }

    @When("the resolver merges parameters")
    public void theResolverMergesParameters() {
        try {
            ParameterResolver.resolve(strategy, config);
        } catch (WichtelmException e) {
            thrown = e;
        }
    }

    @Then("ConfigParseException is thrown")
    public void configParseExceptionIsThrown() {
        assertNotNull(thrown, "expected a ConfigParseException to be thrown");
        assertInstanceOf(ConfigParseException.class, thrown);
    }

    @Then("the config violatedRule is {string}")
    public void theConfigViolatedRuleIs(String rule) {
        assertInstanceOf(ConfigParseException.class, thrown);
        assertEquals(rule, ((ConfigParseException) thrown).violatedRule());
    }

    @Then("no config exception is thrown")
    public void noConfigExceptionIsThrown() {
        assertNull(thrown, () -> "unexpected exception: " + thrown);
        assertNotNull(config);
    }

    @Then("a warning is emitted referencing {string}")
    public void aWarningIsEmittedReferencing(String token) {
        assertTrue(config.warnings().stream().anyMatch(w -> w.contains(token)),
                () -> "no warning references '" + token + "': " + config.warnings());
    }
}
