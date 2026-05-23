Feature: Pivot point Tier B primitives
  Four boolean primitives gate scenarios on the price's relation to a daily
  STANDARD pivot level. The valid levels are the STANDARD set: P, R1, R2, R3,
  S1, S2, S3. The DSL uses STANDARD daily pivots in v1 — CAMARILLA's R4/S4,
  the WOODIE/CAMARILLA variants and weekly pivots are out of scope (selectable
  later). They resolve through the 0.52 nachtkrapp PivotPointRule, which
  aggregates the primary series to daily bars internally (UTC boundaries) and
  reads the prior completed day's OHLC for the levels.

  Scenario: price_*_pivot(level) parses for the STANDARD level set
    Given a strategy that fires when price_above_pivot(P) at the current bar
    When the strategy parses
    Then the Tier B strategy parses cleanly
    And a strategy that fires when price_above_pivot(R1) at the current bar parses
    And a strategy that fires when price_below_pivot(S2) at the current bar parses
    And a strategy that fires when price_crosses_above_pivot(R3) at the current bar parses
    And a strategy that fires when price_crosses_below_pivot(S1) at the current bar parses

  Scenario: A level outside the STANDARD set (R4) is rejected at parse time
    Given a strategy file with "When price_above_pivot(R4)"
    When the parser reads the file
    Then StrategyParseException is thrown
    And violatedRule is "P21"

  Scenario: An unknown pivot-level token is rejected at parse time
    Given a strategy file with "When price_above_pivot(X9)"
    When the parser reads the file
    Then StrategyParseException is thrown
    And violatedRule is "P21"

  Scenario: A pivot primitive is indexed by the prepass
    Given a backtestable strategy whose entry fires when price_above_pivot(R1)
    And a CSV dataset of 120 primary bars
    When the backtest runs
    Then the backtest completes without throwing
    And the prepass indexed the "price_above_pivot" key with arg "R1"
