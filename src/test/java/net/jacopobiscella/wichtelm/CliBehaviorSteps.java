package net.jacopobiscella.wichtelm;

import io.cucumber.java.After;
import io.cucumber.java.Before;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import net.jacopobiscella.wichtelm.cli.WichtelmCli;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Step definitions for {@code cli-behavior.feature} (increment 6c). */
public class CliBehaviorSteps {

    private static final String VALID_STRATEGY = """
            Feature: CLI test strategy
              Primary timeframe: 1h

              Scenario: Enter
                Given no open position
                When close is above sma(3)
                Then long_entry

              Scenario: Exit
                Given a long position is open
                When close is below sma(3)
                Then long_exit
            """;

    private static final String MALFORMED_STRATEGY = """
            Feature: Malformed test strategy
              Primary timeframe: 1h

              Scenario: Bad
                Given no open position
                When close exceeds 1
                Then bogus_condition
            """;

    private final Map<String, String> environment = new HashMap<>();
    private Path tempDir;
    private Path configFile;
    private Path strategyFile;
    private int exitCode;
    private String stdout;
    private String stderr;

    @Before
    public void setUp() throws IOException {
        tempDir = Files.createTempDirectory("wichtelm-block6c");
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

    private void writeHourlyCsv() throws IOException {
        StringBuilder csv = new StringBuilder("time,open,high,low,close\n");
        Instant start = Instant.parse("2024-01-01T00:00:00Z");
        for (int i = 0; i < 30; i++) {
            int close = i < 15 ? 100 + i : 100 + (29 - i);
            csv.append(start.plus(Duration.ofHours(i))).append(',').append(close).append(',')
                    .append(close + 2).append(',').append(close - 2).append(',')
                    .append(close).append('\n');
        }
        Files.writeString(tempDir.resolve("AAPL_1h.csv"), csv);
    }

    private void writeConfig(String dataSourceSection) throws IOException {
        String toml = "strategy = \"" + strategyFile + "\"\n"
                + "symbol = \"AAPL\"\ndata_source = \"" + dataSourceLine(dataSourceSection) + "\"\n\n"
                + "[date_range]\nfrom = 2024-01-01\nto = 2024-01-10\n\n"
                + "[sizing]\nposition_size_pct = 50\n\n"
                + "[output]\ndirectory = \"" + tempDir.resolve("reports") + "\"\n\n"
                + dataSourceSection;
        configFile = tempDir.resolve("my_backtest.toml");
        Files.writeString(configFile, toml);
    }

    private String dataSourceLine(String section) {
        return section.startsWith("[eodhd]") ? "eodhd" : "csv";
    }

    @Given("a valid backtest config with a parseable strategy and CSV data")
    public void aValidBacktestConfig() throws IOException {
        strategyFile = tempDir.resolve("strategy.strat");
        Files.writeString(strategyFile, VALID_STRATEGY);
        writeHourlyCsv();
        writeConfig("[csv]\nfile = \"" + tempDir.resolve("{symbol}_{timeframe}.csv") + "\"\n");
    }

    @Given("a backtest config referencing a malformed strategy file")
    public void aConfigReferencingAMalformedStrategy() throws IOException {
        strategyFile = tempDir.resolve("strategy.strat");
        Files.writeString(strategyFile, MALFORMED_STRATEGY);
        writeConfig("[csv]\nfile = \"" + tempDir.resolve("{symbol}_{timeframe}.csv") + "\"\n");
    }

    @Given("a valid strategy file")
    public void aValidStrategyFile() throws IOException {
        strategyFile = tempDir.resolve("strategy.strat");
        Files.writeString(strategyFile, VALID_STRATEGY);
    }

    @Given("a backtest config using the EODHD data source")
    public void aConfigUsingEodhd() throws IOException {
        strategyFile = tempDir.resolve("strategy.strat");
        Files.writeString(strategyFile, VALID_STRATEGY);
        writeConfig("[eodhd]\napi_token_env = \"EODHD_API_TOKEN\"\n");
    }

    @Given("the api_token_env variable is not set")
    public void theApiTokenEnvIsNotSet() {
        environment.remove("EODHD_API_TOKEN");
    }

    @When("wichtelm runs the config")
    public void wichtelmRunsTheConfig() {
        invoke("run", configFile.toString());
    }

    @When("wichtelm validates the strategy file")
    public void wichtelmValidatesTheStrategyFile() {
        invoke("validate", strategyFile.toString());
    }

    @When("wichtelm is invoked with {string}")
    public void wichtelmIsInvokedWith(String argument) {
        invoke(argument);
    }

    private void invoke(String... args) {
        ByteArrayOutputStream outBuffer = new ByteArrayOutputStream();
        ByteArrayOutputStream errBuffer = new ByteArrayOutputStream();
        PrintStream out = new PrintStream(outBuffer, true, StandardCharsets.UTF_8);
        PrintStream err = new PrintStream(errBuffer, true, StandardCharsets.UTF_8);
        exitCode = new WichtelmCli(out, err, environment::get).run(args);
        stdout = outBuffer.toString(StandardCharsets.UTF_8);
        stderr = errBuffer.toString(StandardCharsets.UTF_8);
    }

    @Then("the CLI exit code is {int}")
    public void theCliExitCodeIs(int expected) {
        assertEquals(expected, exitCode, () -> "stderr: " + stderr);
    }

    @Then("the CLI exit code is non-zero")
    public void theCliExitCodeIsNonZero() {
        assertNotEquals(0, exitCode);
    }

    @Then("an HTML report file is created")
    public void anHtmlReportFileIsCreated() throws IOException {
        assertTrue(htmlReportExists(), "no HTML report file was produced");
    }

    @Then("no HTML report file is created")
    public void noHtmlReportFileIsCreated() throws IOException {
        assertFalse(htmlReportExists(), "an HTML report file was unexpectedly produced");
    }

    private boolean htmlReportExists() throws IOException {
        try (Stream<Path> paths = Files.walk(tempDir)) {
            return paths.anyMatch(p -> p.toString().endsWith(".html"));
        }
    }

    @Then("stdout confirms the strategy parsed")
    public void stdoutConfirmsTheStrategyParsed() {
        assertTrue(stdout.contains("parsed successfully"),
                () -> "stdout did not confirm parsing: " + stdout);
    }

    @Then("stdout contains {string}")
    public void stdoutContains(String token) {
        assertTrue(stdout.contains(token), () -> "stdout: " + stdout);
    }

    @Then("stderr mentions {string}")
    public void stderrMentions(String token) {
        assertTrue(stderr.contains(token), () -> "stderr: " + stderr);
    }

    @Then("stderr mentions the violated rule and a line number")
    public void stderrMentionsTheRuleAndLineNumber() {
        assertTrue(stderr.matches("(?s).*\\b[PC]\\d+\\b.*"),
                () -> "stderr has no rule identifier: " + stderr);
        assertTrue(stderr.matches("(?s).*:\\d+:.*"),
                () -> "stderr has no line:column reference: " + stderr);
    }

    @Then("stderr names the missing {string} variable")
    public void stderrNamesTheMissingVariable(String variableName) {
        assertTrue(stderr.contains(variableName), () -> "stderr: " + stderr);
    }
}
