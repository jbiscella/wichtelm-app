Feature: Sweep config validation
  The TOML config parser accepts an optional [sweep] section declaring a value
  range or explicit value list per parameter, and enforces the sweep validation
  rules C12-C14 (CLAUDE.md section 18). Step wording is sweep-scoped so the glue
  does not collide with the config-parser-validation feature.

  Scenario: A [sweep] range table parses into an axis
    Given a TOML config with a [sweep] range "rsi_period" from 8 to 16 step 2
    When the sweep config parser reads the file
    Then no sweep exception is thrown
    And the sweep declares axis "rsi_period"

  Scenario: A [sweep] explicit value list parses into an axis
    Given a TOML config with a [sweep] list "overbought" of 65, 70, 75
    When the sweep config parser reads the file
    Then no sweep exception is thrown
    And the sweep declares axis "overbought"

  Scenario: A parameter present in both [parameters] and [sweep] is rejected by C12
    Given a TOML config sweeping "rsi_period" that is also fixed in [parameters]
    When the sweep config parser reads the file
    Then SweepConfigException is thrown
    And the sweep violatedRule is "C12"

  Scenario: A range with a non-positive step is rejected by C14
    Given a TOML config with a [sweep] range "rsi_period" from 8 to 16 step 0
    When the sweep config parser reads the file
    Then SweepConfigException is thrown
    And the sweep violatedRule is "C14"

  Scenario: A range with from greater than to is rejected by C14
    Given a TOML config with a [sweep] range "rsi_period" from 16 to 8 step 2
    When the sweep config parser reads the file
    Then SweepConfigException is thrown
    And the sweep violatedRule is "C14"

  Scenario: An empty value list is rejected by C14
    Given a TOML config with a [sweep] empty list "overbought"
    When the sweep config parser reads the file
    Then SweepConfigException is thrown
    And the sweep violatedRule is "C14"

  Scenario: A [sweep] key not declared as a strategy parameter is rejected by C13
    Given a sweepable strategy declaring parameter "rsi_period"
    And a TOML config sweeping undeclared parameter "made_up"
    When the sweep resolver checks the axes against the strategy
    Then SweepConfigException is thrown
    And the sweep violatedRule is "C13"
