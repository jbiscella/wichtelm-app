Feature: Config parser validation
  The TOML config parser enforces rules C1-C11 (CLAUDE.md section 5.2).
  Step wording is config-scoped so the glue does not collide with the
  strategy-parser feature.

  Scenario: Missing strategy field is rejected by C1
    Given a TOML config file with no "strategy" field
    When the config parser reads the file
    Then ConfigParseException is thrown
    And the config violatedRule is "C1"

  Scenario: Invalid date range with from after to is rejected by C3
    Given a TOML config file with date_range from "2024-12-31" to "2024-01-01"
    When the config parser reads the file
    Then ConfigParseException is thrown
    And the config violatedRule is "C3"

  Scenario: position_size_pct out of range is rejected by C5
    Given a TOML config file with position_size_pct of 150
    When the config parser reads the file
    Then ConfigParseException is thrown
    And the config violatedRule is "C5"

  Scenario: Parameter override for undeclared parameter is rejected by C7
    Given a strategy declaring parameter "rsi_period"
    And a TOML config overriding parameter "unknown_param"
    When the resolver merges parameters
    Then ConfigParseException is thrown
    And the config violatedRule is "C7"

  Scenario: Unknown top-level keys produce a warning, not error
    Given a TOML config file with an unknown top-level key "experimental_flag"
    When the config parser reads the file
    Then no config exception is thrown
    And a warning is emitted referencing "experimental_flag"
