Feature: Sweep grid expansion
  The sweep grid expands the per-parameter axes into the Cartesian product of
  parameter combinations in a deterministic order, with a configurable cap on
  the total number of combinations to guard against combinatorial explosion
  (CLAUDE.md section 18).

  Scenario: A single range axis materializes inclusive integer steps
    Given a sweep axis "rsi_period" ranging from 8 to 16 step 2 as INTEGER
    When the grid is expanded
    Then the grid has 5 combinations
    And combination 1 sets "rsi_period" to "8"
    And combination 5 sets "rsi_period" to "16"

  Scenario: Two axes form the Cartesian product in declaration order
    Given a sweep axis "rsi_period" ranging from 8 to 12 step 2 as INTEGER
    And a sweep axis "overbought" listing 65, 70 as INTEGER
    When the grid is expanded
    Then the grid has 6 combinations
    And combination 1 sets "rsi_period" to "8" and "overbought" to "65"
    And combination 2 sets "rsi_period" to "8" and "overbought" to "70"
    And combination 3 sets "rsi_period" to "10" and "overbought" to "65"

  Scenario: A decimal range steps in DECIMAL64 and keeps the inclusive endpoint
    Given a sweep axis "stop_loss_pct" ranging from 1.0 to 2.0 step 0.5 as DECIMAL
    When the grid is expanded
    Then the grid has 3 combinations
    And combination 3 sets "stop_loss_pct" to "2.0"

  Scenario: A grid exceeding the max-combinations cap is rejected before running
    Given a sweep axis "rsi_period" ranging from 1 to 100 step 1 as INTEGER
    And a sweep axis "overbought" ranging from 1 to 100 step 1 as INTEGER
    And a max-combinations cap of 500
    When the grid is expanded expecting a cap error
    Then a sweep cap error names the product 10000 and the cap 500
