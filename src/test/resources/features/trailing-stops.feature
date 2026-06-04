Feature: Trailing-stop signal emission (Block 4)
  The And with trailing_stop at <expr> clause: a high-water-mark stop that
  ratchets in the favourable direction (percentage or ATR-distance mode) and
  emits ClosePositionAtPrice when the trailing level is breached. The level on
  bar T is derived from the high-water mark THROUGH bar T-1 (lookahead-safe).

  Scenario: Percentage trailing_stop ratchets up and fires on a retrace
    Given a long position opened at 100 with trailing_stop "10"
    When an in-position bar prints high 120 low 100
    And an in-position bar prints high 110 low 108
    Then a ClosePositionAtPrice signal is emitted at price 108

  Scenario: Percentage trailing_stop never loosens on a pullback
    Given a long position opened at 100 with trailing_stop "10"
    When an in-position bar prints high 120 low 100
    And an in-position bar prints high 112 low 110
    Then no exit signal is emitted
    When an in-position bar prints high 112 low 108
    Then a ClosePositionAtPrice signal is emitted at price 108

  Scenario: Short percentage trailing_stop trails the low-water mark
    Given a short position opened at 100 with trailing_stop "10"
    When an in-position bar prints high 100 low 80
    And an in-position bar prints high 88 low 86
    Then a ClosePositionAtPrice signal is emitted at price 88

  Scenario: ATR-distance trailing_stop trails a frozen distance below the high
    Given a constant-range warmup history of 14 bars with range 2
    And a long position opened at 100 with trailing_stop "3 * atr_value(14)"
    When an in-position bar prints high 120 low 100
    And an in-position bar prints high 116 low 114
    Then a ClosePositionAtPrice signal is emitted at price 114
