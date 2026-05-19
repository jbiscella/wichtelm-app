Feature: Indicator function evaluation
  Increment 6a. The expression evaluator resolves indicator function calls
  (sma, ema, rsi, atr) against the bars visible up to the current bar,
  delegating the arithmetic to the ha-track indicators module.

  Scenario: sma is evaluated against the close series
    Given a strategy whose entry condition is "close is above sma(3)"
    And a rising price history of 16 bars
    When the SignalGenerator evaluates the bar
    Then an entry signal is emitted

  Scenario: ema is evaluated against the close series
    Given a strategy whose entry condition is "close is above ema(3)"
    And a rising price history of 16 bars
    When the SignalGenerator evaluates the bar
    Then an entry signal is emitted

  Scenario: rsi reflects a strong uptrend
    Given a strategy whose entry condition is "rsi(14) is above 70"
    And a rising price history of 16 bars
    When the SignalGenerator evaluates the bar
    Then an entry signal is emitted

  Scenario: atr is positive for a non-flat series
    Given a strategy whose entry condition is "atr(14) exceeds 0"
    And a rising price history of 16 bars
    When the SignalGenerator evaluates the bar
    Then an entry signal is emitted

  Scenario: stddev is positive for a non-flat series
    Given a strategy whose entry condition is "stddev(5) exceeds 0"
    And a rising price history of 16 bars
    When the SignalGenerator evaluates the bar
    Then an entry signal is emitted

  Scenario: a window aggregate with insufficient history emits no entry
    Given a strategy whose entry condition is "highest_high(20) exceeds 0"
    And a rising price history of 8 bars
    When the SignalGenerator evaluates the bar
    Then no entry signal is emitted

  Scenario: a condition that does not hold emits no entry
    Given a strategy whose entry condition is "close is below sma(3)"
    And a rising price history of 16 bars
    When the SignalGenerator evaluates the bar
    Then no entry signal is emitted
