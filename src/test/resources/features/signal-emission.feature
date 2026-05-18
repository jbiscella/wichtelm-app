Feature: Signal emission and frau-holle mapping
  The SignalGenerator translates a matched Scenario into a frau-holle Signal
  (CLAUDE.md section 12). Intrabar stop_loss/take_profit precede close-
  evaluated exits; pyramiding turns a same-direction entry into AddToPosition.

  Scenario: long_entry condition emits a Buy signal
    Given an entry-only long strategy
    And position sizing of 50 percent with pyramiding disabled
    And no open position with equity 10000 and bar close 100
    When the SignalGenerator emits a signal for the bar
    Then the emitted signal is a Buy
    And the signal quantity equals 50 percent of equity over price

  Scenario: stop_loss snapshots the expression at the entry fill price
    Given a long strategy with stop_loss "entry_price * 0.98"
    And position sizing of 50 percent with pyramiding disabled
    And a long position opened at price 100
    When the stop price is snapshotted
    Then the snapshotted stop price is 98

  Scenario: intrabar stop_loss emits ClosePositionAtPrice when the low reaches the stop
    Given a long strategy with stop_loss "entry_price * 0.98"
    And position sizing of 50 percent with pyramiding disabled
    And a long position opened at price 100
    And the current bar is open 99 high 99.5 low 97 close 98.5
    When the SignalGenerator emits a signal for the bar
    Then a ClosePositionAtPrice signal is emitted with price 98
    And the fill time is strictly inside the current bar interval

  Scenario: intrabar stop_loss wins over a close-evaluated exit Scenario
    Given a long strategy with stop_loss "entry_price * 0.98" and a long_exit on "close drops below 99"
    And position sizing of 50 percent with pyramiding disabled
    And a long position opened at price 100
    And a previous bar with close 100
    And the current bar is open 99 high 99.5 low 97 close 98.5
    When the SignalGenerator emits a signal for the bar
    Then a ClosePositionAtPrice signal is emitted with price 98
    And no plain ClosePosition signal is emitted

  Scenario: pyramiding emits AddToPosition for a same-direction entry while in a position
    Given an entry-only long strategy
    And position sizing of 50 percent with pyramiding enabled
    And a long position already open with equity 10000 and bar close 100
    When the SignalGenerator emits a signal for the bar
    Then the emitted signal is an AddToPosition with direction LONG
    And the signal quantity equals 50 percent of equity over price
