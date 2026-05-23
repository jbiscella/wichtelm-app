Feature: ATR-based dynamic stops
  atr_value(period) is admitted inside stop_loss / take_profit expressions — a
  deliberate relaxation of P16, which otherwise bans functions and indicators
  there. It is evaluated once at the entry fill bar and frozen for the life of
  the trade. ATR reuses the indicators-module ATR; no ha-track change required.
  (Per-bar value + frozen-at-fill correctness is asserted in
  AtrDynamicStopEvaluationTest.)

  Scenario: A stop_loss expression may reference atr_value(period)
    Given a strategy file with "And with stop_loss at entry_price - 2 * atr_value(14)"
    When the parser reads the file
    Then no exception is thrown

  Scenario: A take_profit expression may reference atr_value(period)
    Given a strategy file with "And with take_profit at entry_price + 3 * atr_value(14)"
    When the parser reads the file
    Then no exception is thrown

  Scenario: Indicators other than atr_value stay banned in stop_loss by P16
    Given a strategy file with "And with stop_loss at ema(200) * 0.98"
    When the parser reads the file
    Then StrategyParseException is thrown
    And violatedRule is "P16"

  Scenario: A non-positive ATR period is rejected by P21
    Given a strategy file with "And with stop_loss at entry_price - atr_value(0)"
    When the parser reads the file
    Then StrategyParseException is thrown
    And violatedRule is "P21"

  Scenario: A fractional ATR period is rejected by P21
    Given a strategy file with "And with stop_loss at entry_price - atr_value(2.5)"
    When the parser reads the file
    Then StrategyParseException is thrown
    And violatedRule is "P21"

  Scenario: An ATR-based stop runs end-to-end, resolving the ATR at fill time
    Given an ATR-stop long strategy whose stop_loss is "entry_price - 2 * atr_value(14)"
    And a CSV dataset of 120 primary bars
    When the backtest runs
    Then the backtest completes without throwing
