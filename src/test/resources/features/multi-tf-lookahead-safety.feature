Feature: Multi-timeframe lookahead-safety
  A higher-timeframe Background series referenced at primary bar time T
  resolves to the most recently CLOSED higher-TF bar whose closeTime is at
  or before T (CLAUDE.md section 3.5 / Block 3).

  Scenario: Higher-TF series resolves to the most recent closed bar at or before T
    Given a higher-timeframe series on 1d with daily bars from "2024-03-13" to "2024-03-16"
    When the series is resolved at primary bar time "2024-03-15T14:00:00Z"
    Then the resolved bar is the one covering "2024-03-14"
    And the resolved bar is not the one covering "2024-03-15"

  Scenario: Every resolution across the backtest is lookahead-safe
    Given a higher-timeframe series on 1d with daily bars from "2024-03-01" to "2024-03-31"
    When the series is resolved at every hour of the primary timeframe range
    Then every resolved bar has closeTime at or before its primary bar time
