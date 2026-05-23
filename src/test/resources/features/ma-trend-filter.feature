Feature: MA trend filter Tier B primitives
  Eleven boolean primitives that gate scenarios on moving-average alignment.
  Eight price-vs-MA primitives resolve through nachtkrapp PriceVsMARule /
  PriceMACrossRule; three MA-vs-MA primitives resolve through the 0.52
  MAVsMARule / MACrossMARule. Source is CLOSE by default for both SMA and EMA.

  # ── Parse coverage: every primitive is a recognised boolean step ──────────

  Scenario: price_above_sma(period) and price_below_sma(period) parse
    Given a strategy that fires when price_above_sma(50) at the current bar
    When the strategy parses
    Then the Tier B strategy parses cleanly
    And a strategy that fires when price_below_sma(50) at the current bar parses

  Scenario: price_above_ema(period) and price_below_ema(period) parse
    Given a strategy that fires when price_above_ema(200) at the current bar
    When the strategy parses
    Then the Tier B strategy parses cleanly
    And a strategy that fires when price_below_ema(200) at the current bar parses

  Scenario: price_crosses_above_sma / price_crosses_below_sma parse
    Given a strategy that fires when price_crosses_above_sma(50) at the current bar
    When the strategy parses
    Then the Tier B strategy parses cleanly
    And a strategy that fires when price_crosses_below_sma(50) at the current bar parses

  Scenario: price_crosses_above_ema / price_crosses_below_ema parse
    Given a strategy that fires when price_crosses_above_ema(200) at the current bar
    When the strategy parses
    Then the Tier B strategy parses cleanly
    And a strategy that fires when price_crosses_below_ema(200) at the current bar parses

  Scenario: sma_above_ema(sma_period, ema_period) parses with two periods
    Given a strategy that fires when sma_above_ema(50, 200) at the current bar
    When the strategy parses
    Then the Tier B strategy parses cleanly

  Scenario: sma_crosses_above_ema / sma_crosses_below_ema parse with two periods
    Given a strategy that fires when sma_crosses_above_ema(50, 200) at the current bar
    When the strategy parses
    Then the Tier B strategy parses cleanly
    And a strategy that fires when sma_crosses_below_ema(50, 200) at the current bar parses

  # ── Parse-time validation (P-rules) ───────────────────────────────────────

  Scenario: A zero period in a price-vs-MA primitive is rejected by P21
    Given a strategy file with "When price_above_ema(0)"
    When the parser reads the file
    Then StrategyParseException is thrown
    And violatedRule is "P21"

  Scenario: A negative period in an MA-vs-MA primitive is rejected by P21
    Given a strategy file with "When sma_above_ema(50, -1)"
    When the parser reads the file
    Then StrategyParseException is thrown
    And violatedRule is "P21"

  Scenario: A fractional period in a price-vs-MA primitive is rejected by P21
    Given a strategy file with "When price_above_ema(2.5)"
    When the parser reads the file
    Then StrategyParseException is thrown
    And violatedRule is "P21"

  Scenario: Wrong arity on a price-vs-MA primitive is rejected by P14
    Given a strategy file with "When price_above_ema(50, 200)"
    When the parser reads the file
    Then StrategyParseException is thrown
    And violatedRule is "P14"

  # ── Prepass: each primitive resolves to a nachtkrapp detection key ─────────

  Scenario: A price-vs-MA primitive is indexed by the prepass
    Given a backtestable strategy whose entry fires when price_above_ema(20)
    And a CSV dataset of 60 primary bars
    When the backtest runs
    Then the backtest completes without throwing
    And the prepass indexed the "price_above_ema" key with arg "20"

  Scenario: An MA-vs-MA primitive is indexed by the prepass
    Given a backtestable strategy whose entry fires when sma_above_ema(5, 20)
    And a CSV dataset of 60 primary bars
    When the backtest runs
    Then the backtest completes without throwing
    And the prepass indexed the "sma_above_ema" key with args "5, 20"

  Scenario: A price-vs-MA primitive evaluates false before its MA warms up
    Given a backtestable strategy whose entry fires when price_above_ema(50)
    And a CSV dataset of 20 primary bars
    When the backtest runs
    Then the backtest completes without throwing
    And no entry fired before bar 50

  # ── Behaviour: trend gate ─────────────────────────────────────────────────

  Scenario: A trend gate reduces the trade count versus the ungated strategy
    Given a backtestable strategy that enters long on ha_bullish_reversal(2)
    And the same strategy with an added "And price_above_ema(20)" trend gate
    And a CSV dataset of 120 primary bars
    When both backtests run
    Then the trend-gated strategy fires no more entries than the ungated one
