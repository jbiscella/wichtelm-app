Feature: Window aggregate evaluation
  Block 7 Tier A. highest_high, lowest_low, highest_close, lowest_close and
  avg_volume reduce a fixed field over the last <period> bars ending at (and
  including) the current bar.

  Scenario: highest_high returns the maximum high over the window
    Given a price history of 30 bars with varied prices and volume
    When the window aggregate "highest_high(10)" is evaluated
    Then the result equals the maximum high over the last 10 bars

  Scenario: lowest_low returns the minimum low over the window
    Given a price history of 30 bars with varied prices and volume
    When the window aggregate "lowest_low(10)" is evaluated
    Then the result equals the minimum low over the last 10 bars

  Scenario: highest_close returns the maximum close over the window
    Given a price history of 30 bars with varied prices and volume
    When the window aggregate "highest_close(10)" is evaluated
    Then the result equals the maximum close over the last 10 bars

  Scenario: lowest_close returns the minimum close over the window
    Given a price history of 30 bars with varied prices and volume
    When the window aggregate "lowest_close(10)" is evaluated
    Then the result equals the minimum close over the last 10 bars

  Scenario: avg_volume returns the mean volume over the window
    Given a price history of 30 bars with varied prices and volume
    When the window aggregate "avg_volume(20)" is evaluated
    Then the result equals the mean volume over the last 20 bars
