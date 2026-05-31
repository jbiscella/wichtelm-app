Feature: Sweep execution and ranking
  The sweep runner loads market data once, runs the backtest for every
  parameter combination, and ranks the results by the chosen objective so the
  best combination surfaces first (CLAUDE.md section 18).

  Scenario: Every combination produces a result row
    Given a sweepable sma strategy with a "fast" period parameter
    And a CSV dataset for the sweep on the 1h timeframe
    And a sweep over "fast" of 2, 3, 4
    When the sweep runs ranked by "total_return"
    Then the sweep produces 3 result rows
    And every result row ran without failure

  Scenario: Results are ranked best-first by the objective
    Given a sweepable sma strategy with a "fast" period parameter
    And a CSV dataset for the sweep on the 1h timeframe
    And a sweep over "fast" of 2, 3, 4
    When the sweep runs ranked by "total_return"
    Then the rows are ordered by total_return descending

  Scenario: A tradeless combination never ranks above a trading one
    Given a sweepable sma strategy with a "fast" period parameter
    And a CSV dataset for the sweep on the 1h timeframe
    And a sweep over "fast" of 2, 3, 4
    When the sweep runs ranked by "profit_factor"
    Then any tradeless row sorts below every row that traded

  Scenario: The market data is loaded once for the whole sweep
    Given a sweepable sma strategy with a "fast" period parameter
    And a CSV dataset for the sweep on the 1h timeframe
    And a sweep over "fast" of 2, 3, 4
    When the sweep runs ranked by "total_return"
    Then the market data was loaded exactly once
