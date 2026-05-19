Feature: MACD function evaluation
  Block 7 Tier A. macd_line, macd_signal and macd_histogram resolve to the
  ha-track indicators module's MACD line, signal line and histogram for the
  bars visible up to the current bar.

  Scenario: macd_line resolves to the indicators-module MACD line
    Given a deterministic price series of 60 bars
    When the expression "macd_line(12, 26, 9)" is evaluated
    Then the result equals the indicators-module macd line for periods 12, 26, 9

  Scenario: macd_signal resolves to the indicators-module signal line
    Given a deterministic price series of 60 bars
    When the expression "macd_signal(12, 26, 9)" is evaluated
    Then the result equals the indicators-module macd signal for periods 12, 26, 9

  Scenario: macd_histogram equals macd_line minus macd_signal
    Given a deterministic price series of 60 bars
    When the expression "macd_histogram(12, 26, 9)" is evaluated
    Then the result equals the expression "macd_line(12, 26, 9) - macd_signal(12, 26, 9)"
