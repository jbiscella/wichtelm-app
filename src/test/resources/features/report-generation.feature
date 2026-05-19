Feature: HTML report generation
  The report generator writes a self-contained HTML file with aggregate
  metrics, one box per Scenario, and per-timeframe charts (CLAUDE.md
  section 7 / Block 5).

  Scenario: A backtest produces a report file with the configured naming convention
    Given a report for config basename "my_backtest" generated at "2026-05-18T14:30:00"
    And the report output directory is a fresh temporary directory
    When the report is generated
    Then a report file named "my_backtest_2026-05-18T14-30-00.html" exists
    And the report file is non-empty

  Scenario: Every generated HTML report contains the disclaimer footer
    Given a report for config basename "disclaimer" generated at "2026-05-18T10:00:00"
    And the report output directory is a fresh temporary directory
    When the report is generated
    Then the report contains the disclaimer footer

  Scenario: The no-report option suppresses report generation
    Given a report for config basename "run" generated at "2026-05-18T09:00:00"
    And the report output directory is a fresh temporary directory
    When the run completes with reporting disabled
    Then no HTML report file is produced
    And the backtest result is still accessible

  Scenario: The report contains one box per Scenario sorted alphabetically
    Given a strategy with Scenarios named "A", "C", "B", "D"
    And the report output directory is a fresh temporary directory
    When the report is generated
    Then the report contains 4 scenario boxes
    And the scenario box order is "A", "B", "C", "D"

  Scenario: A box for a Scenario referencing two timeframes contains two charts
    Given a strategy with an entry Scenario referencing a 1d Background series on primary 1h
    And the report output directory is a fresh temporary directory
    When the report is generated
    Then the box for that Scenario contains 2 charts
    And the chart timeframes of that box are "1h" and "1d"

  Scenario: A trigger marker lands on both the primary and the higher-timeframe chart
    Given a strategy with an entry Scenario referencing a 1d Background series on primary 1h
    And the "Multi" Scenario has a recorded trigger at "2024-01-03T03:00:00Z"
    And the report output directory is a fresh temporary directory
    When the report is generated
    Then the box for "Multi" has 2 chart markers

  Scenario: A Scenario that fires three times shows its trigger count in the report box
    Given a pyramiding strategy whose entry Scenario always fires
    And a CSV dataset of 3 primary bars
    And the report output directory is a fresh temporary directory
    When a backtest is run and its report is generated
    Then the box for "Always enter" shows "Trigger count: 3"

  Scenario: A Scenario box renders one chart marker and one sub-report row per trigger
    Given a pyramiding strategy whose entry Scenario always fires
    And a CSV dataset of 3 primary bars
    And the report output directory is a fresh temporary directory
    When a backtest is run and its report is generated
    Then the box for "Always enter" has 3 chart markers
    And the box for "Always enter" has 3 sub-report rows
    And the chart markers and sub-report rows of the "Always enter" box correspond to the same trigger count
