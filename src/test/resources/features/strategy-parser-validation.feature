Feature: Strategy parser parse-time validation
  The hand-written .strat parser enforces rules P1-P22 at parse time and
  rejects malformed strategies with a typed StrategyParseException.

  Scenario: Empty strategy file is rejected by P1
    Given a strategy file with no Feature header
    When the parser reads the file
    Then StrategyParseException is thrown
    And violatedRule is "P1"

  Scenario: Missing Primary timeframe is rejected by P2
    Given a strategy file with a Feature header but no Primary timeframe declaration
    When the parser reads the file
    Then StrategyParseException is thrown
    And violatedRule is "P2"

  Scenario: Duplicate parameter declarations are rejected by P4
    Given a strategy file with two "Parameter rsi_period default 14" lines
    When the parser reads the file
    Then StrategyParseException is thrown
    And violatedRule is "P4"

  Scenario: Duplicate Scenario names are rejected by P22
    Given a strategy file with two Scenarios both named "Enter long"
    When the parser reads the file
    Then StrategyParseException is thrown
    And violatedRule is "P22"

  Scenario: Scenario not terminating with a first-class condition is rejected by P10
    Given a strategy file with a Scenario that ends with "Then unknown_condition"
    When the parser reads the file
    Then StrategyParseException is thrown
    And violatedRule is "P10"

  Scenario: stop_loss clause on a long_exit Scenario is rejected by P12
    Given a strategy file with a Scenario terminating with "Then long_exit"
    And the same Scenario has "And with stop_loss at entry_price * 0.98" appended
    When the parser reads the file
    Then StrategyParseException is thrown
    And violatedRule is "P12"

  Scenario: Indicator function in stop_loss expression is rejected by P16
    Given a strategy file with "And with stop_loss at atr(14) * 2"
    When the parser reads the file
    Then StrategyParseException is thrown
    And violatedRule is "P16"

  Scenario: Background series referencing a TF lower than primary is rejected by P8
    Given a strategy file with Primary timeframe 1d
    And a Background declaring "Given a series ... on 1h"
    When the parser reads the file
    Then StrategyParseException is thrown
    And violatedRule is "P8"

  Scenario: Reference to undeclared identifier is rejected by P13
    Given a strategy file with "When my_undeclared_var crosses below 30"
    When the parser reads the file
    Then StrategyParseException is thrown
    And violatedRule is "P13"

  Scenario: A well-formed canonical strategy parses successfully
    Given the canonical example strategy from section 3.9
    When the parser reads the file
    Then no exception is thrown
    And the parsed AST contains 6 parameters
    And the parsed AST contains 2 Background series declarations
    And the parsed AST contains 5 Scenarios
    And each Scenario terminates with one of long_entry/long_exit/short_entry/short_exit
