Feature: Gap-aware protective-exit fills (§19)
  When a bar opens already beyond a protective level in the exit direction, the
  price never traded at the level on that bar, so the fill is the bar open (the
  realistic price) rather than the stale level. When the level lies within the
  bar's traded range the fill is unchanged. Applies to stop_loss, take_profit and
  trailing_stop alike.

  Scenario: A long stop_loss gapped through fills at the bar open, not the stale level
    Given a long strategy with stop_loss "entry_price * 0.98"
    And position sizing of 50 percent with pyramiding disabled
    And a long position opened at price 100
    And the current bar is open 95 high 96 low 94 close 95
    When the SignalGenerator emits a signal for the bar
    Then a ClosePositionAtPrice signal is emitted with price 95

  Scenario: An intrabar touch without a gap still fills at the level
    Given a long strategy with stop_loss "entry_price * 0.98"
    And position sizing of 50 percent with pyramiding disabled
    And a long position opened at price 100
    And the current bar is open 99 high 99.5 low 97 close 98.5
    When the SignalGenerator emits a signal for the bar
    Then a ClosePositionAtPrice signal is emitted with price 98

  Scenario: A long trailing_stop gapped through fills at the bar open
    Given a long position opened at 100 with trailing_stop "10"
    When an in-position bar prints high 120 low 100
    And an in-position bar prints open 105 high 106 low 104 close 105
    Then a ClosePositionAtPrice signal is emitted at price 105
