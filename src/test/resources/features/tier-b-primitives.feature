Feature: Tier B boolean pattern primitives
  The Tier B primitives resolve via a one-shot nachtkrapp DetectionEngine
  pre-pass over the closed primary series. Each per-bar evaluation is an
  O(1) match-index lookup that returns 1 / 0; the ExpressionEvaluator's
  boolean-step extension treats non-zero as true.

  Scenario: ha_doji() works as a bare boolean step
    Given a strategy that fires when ha_doji() at the current bar
    When the strategy parses
    Then the Tier B strategy parses cleanly

  Scenario: ha_doji(maxBodyRatio) accepts an explicit body-ratio argument
    Given a strategy that fires when ha_doji(0.05) at the current bar
    When the strategy parses
    Then the Tier B strategy parses cleanly

  Scenario: ha_strong() works as a bare boolean step
    Given a strategy that fires when ha_strong() at the current bar
    When the strategy parses
    Then the Tier B strategy parses cleanly

  Scenario: ha_strong_bullish() and ha_strong_bearish() are recognised
    Given a strategy that fires when ha_strong_bullish() at the current bar
    When the strategy parses
    Then the Tier B strategy parses cleanly
    And a strategy that fires when ha_strong_bearish() at the current bar parses

  Scenario: ha_bullish_reversal(n) and ha_bearish_reversal(n) parse with the streak arg
    Given a strategy that fires when ha_bullish_reversal(2) at the current bar
    When the strategy parses
    Then the Tier B strategy parses cleanly
    And a strategy that fires when ha_bearish_reversal(3) at the current bar parses

  Scenario: rsi_overbought(t) and rsi_oversold(t) parse with their threshold
    Given a strategy that fires when rsi_overbought(70) at the current bar
    When the strategy parses
    Then the Tier B strategy parses cleanly
    And a strategy that fires when rsi_oversold(30) at the current bar parses

  Scenario: rsi_crosses_50() parses as a zero-arg boolean primitive
    Given a strategy that fires when rsi_crosses_50() at the current bar
    When the strategy parses
    Then the Tier B strategy parses cleanly

  Scenario: the four MACD boolean primitives parse as zero-arg
    Given a strategy that fires when macd_bullish_cross() at the current bar
    When the strategy parses
    Then the Tier B strategy parses cleanly
    And a strategy that fires when macd_bearish_cross() at the current bar parses
    And a strategy that fires when macd_zero_cross_up() at the current bar parses
    And a strategy that fires when macd_zero_cross_down() at the current bar parses

  Scenario: A backtest using a Tier B primitive runs end-to-end
    Given a strategy that enters long when ha_doji() and exits on the next bar
    And a CSV dataset of 100 primary bars
    When the backtest runs
    Then the backtest completes without throwing
    And the prepass-derived match count for ha_doji() is queryable
