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

  # Regression coverage for the report features added on the 0.5x chart-quality line:
  # Strategy-rules section, Tier B natural-language mapping, per-trade Rule line,
  # lossless-PNG chart export, and the indicator legend strip.

  Scenario: The Strategy rules section renders each scenario as Given/When/Then
    Given a trend-gated Tier B strategy fixture
    And the report output directory is a fresh temporary directory
    When the report is generated
    Then the report contains the strategy rules section
    And the rules section uses Given, When and Then labels
    And the rules section translates a Tier B step keeping its call form verbatim
    And the rules section shows the stop_loss and take_profit expressions untranslated

  Scenario: Chart images are embedded as lossless PNG, not JPEG
    Given a trend-gated Tier B strategy fixture with a stop-hit trade
    And the report output directory is a fresh temporary directory
    When the report is generated
    Then every embedded chart image is a PNG
    And no embedded chart image is a JPEG

  Scenario: Each expanded trade shows the compact Rule line with the fired primitives
    Given a trend-gated Tier B strategy fixture with a stop-hit trade
    And the report output directory is a fresh temporary directory
    When the report is generated
    Then a trade Rule line names the fired entry primitives
    And the forced exit Rule line is tagged as a stop_loss or take_profit hit

  Scenario: Each rendered chart carries an indicator legend strip
    Given a trend-gated Tier B strategy fixture with a stop-hit trade
    And the report output directory is a fresh temporary directory
    When the report is generated
    Then the report contains a chart legend strip

  Scenario: A report renders an atr_value stop line without crashing
    Given an ATR-stop strategy fixture with a stop-hit trade
    And the report output directory is a fresh temporary directory
    When the report is generated
    Then every embedded chart image is a PNG
