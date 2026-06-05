Feature: CLI behavior
  The wichtelm CLI runs and validates strategies, returning 0 on success and
  a non-zero exit code on any error (CLAUDE.md sections 2.1 / 14).

  Scenario: wichtelm run with a valid config exits 0 and writes a report
    Given a valid backtest config with a parseable strategy and CSV data
    When wichtelm runs the config
    Then the CLI exit code is 0
    And an HTML report file is created

  Scenario: wichtelm run with --dump-equity writes the equity CSV alongside the report
    Given a valid backtest config with a parseable strategy and CSV data
    When wichtelm runs the config with "--dump-equity"
    Then the CLI exit code is 0
    And an HTML report file is created
    And an equity CSV file is created

  Scenario: wichtelm run with --no-report --dump-equity writes only the equity CSV
    Given a valid backtest config with a parseable strategy and CSV data
    When wichtelm runs the config with "--no-report --dump-equity"
    Then the CLI exit code is 0
    And no HTML report file is created
    And an equity CSV file is created

  Scenario: wichtelm run with a malformed strategy exits non-zero
    Given a backtest config referencing a malformed strategy file
    When wichtelm runs the config
    Then the CLI exit code is non-zero
    And stderr mentions "StrategyParseException"
    And stderr mentions the violated rule and a line number

  Scenario: wichtelm validate parses without running a backtest
    Given a valid strategy file
    When wichtelm validates the strategy file
    Then the CLI exit code is 0
    And no HTML report file is created
    And stdout confirms the strategy parsed

  Scenario: wichtelm run with a missing EODHD env var exits non-zero
    Given a backtest config using the EODHD data source
    And the api_token_env variable is not set
    When wichtelm runs the config
    Then the CLI exit code is non-zero
    And stderr names the missing "EODHD_API_TOKEN" variable

  Scenario: wichtelm --version prints the version
    When wichtelm is invoked with "--version"
    Then the CLI exit code is 0
    And stdout contains "wichtelm"
    And stdout contains "0BSD"
    And stdout contains "NOT financial advice"

  Scenario: wichtelm --help prints usage
    When wichtelm is invoked with "--help"
    Then the CLI exit code is 0
    And stdout contains "Usage"

  Scenario: wichtelm sweep runs the grid, prints a ranked table and writes a CSV
    Given a sweep backtest config with a parseable strategy and CSV data
    When wichtelm sweeps the config
    Then the CLI exit code is 0
    And stdout contains a ranked sweep table
    And a sweep CSV file is created

  Scenario: wichtelm sweep with --no-report writes no CSV but still prints the table
    Given a sweep backtest config with a parseable strategy and CSV data
    When wichtelm sweeps the config with "--no-report"
    Then the CLI exit code is 0
    And stdout contains a ranked sweep table
    And no sweep CSV file is created

  Scenario: wichtelm sweep with an unknown objective exits with a usage error
    Given a sweep backtest config with a parseable strategy and CSV data
    When wichtelm sweeps the config with "--objective bogus"
    Then the CLI exit code is 2
    And stderr mentions "objective"
