Feature: HTML report generation
  The report generator writes a self-contained HTML file with aggregate
  metrics and a single chronological list of trade blocks (CLAUDE.md
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
