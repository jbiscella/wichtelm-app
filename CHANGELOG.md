# Changelog

All notable changes to `wichtelm-app` are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the project adheres
to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Changed

- **Clearer insufficient-data error** — when the data source returns fewer than
  two bars for the primary timeframe, `BacktestRunner` now fails with an
  actionable `DataSourceUnavailableException` naming the bar count, symbol,
  primary timeframe and date window, plus a CSV- or EODHD-specific hint (for
  EODHD, the free-token ~1-year EOD history limit), instead of surfacing
  frau-holle's opaque `invalid backtest spec [V6]: 1`.

### Fixed

- **Stop/take ownership (Concern 1)** — `WichtelmSignalGenerator` now binds each
  open position to the scenario that emitted its Buy/Sell signal, keyed by
  `Position.entryTime()`. Two competing `long_entry` scenarios with different
  stop_loss / take_profit expressions previously fell back to a
  direction-and-presence `findFirst` heuristic, so the wrong stop could fire
  against a position opened by the unrelated scenario. The binding is
  established on the bar after the entry signal and evicted when the position
  closes; protective-exit lookup is now O(1) by `entryTime`. A direction-based
  fallback is retained for positions never observed through `generate` (unit
  tests that synthesise a `Position` directly).

### Removed

- **`entry_time` trade-context variable (Concern 2)** — removed from the
  parser-accepted set in `BuiltinCatalog.TRADE_CONTEXT_VARIABLES`. The runtime
  never resolved `entry_time`, so any strategy referencing it parsed cleanly
  and then threw `IllegalStateException` at the first evaluation. Reintroduction
  is reserved for a future time-typed expression sub-language — see CLAUDE.md
  §15. The parser now rejects `entry_time` references via P13
  (undeclared identifier).

### Documented

- **Same-bar stop_loss vs take_profit (Concern 3)** — CLAUDE.md §6.3 now spells
  out the existing-and-correct behaviour: when both stop_loss and take_profit
  prices fall inside the same OHLC bar's `[low, high]` range, stop_loss wins
  (pessimistic convention, mirroring industry tooling). A pair of Gherkin
  scenarios in `signal-emission.feature` exercise the rule for long and short
  positions; the implementation in `WichtelmSignalGenerator.protectiveExit`
  carries a comment pointing at the spec section.
- **Stop/take ownership semantic (Concern 1)** — CLAUDE.md §3.4 now states
  that each open position is bound at fill time to the originating scenario.

### Added

- **Lookahead-causality regression test (Concern 4)** —
  `NachtkrappPrepassCausalityTest` builds the Tier B prepass twice (once on
  `series.subList(0, k)`, once on the full series) and asserts identical match
  sets at every `Instant <= prefix.getLast().time()`. Parameterised over four
  prefix sizes that cover warmup, mid-series and end-of-series boundaries.
  Failure of this test implies a non-causal nachtkrapp rule and warrants a
  ha-track issue with the failing reproducer.
- **Per-trade chart overlays now auto-plot** — `HtmlReportGenerator` draws the
  indicators a trade's conditions key off, so a reader can see what each rule
  reads: MA-trend-filter primitives (`price_*_sma/ema`, `sma_*_ema`) → `SMA` /
  `EMA` main-pane overlays; pivot primitives (`price_*_pivot`) → a STANDARD
  daily `Annotation.PivotPointLevels` set computed from the prior completed UTC
  day; window-aggregate Background series (`highest_high` / `lowest_low` /
  `highest_close` / `lowest_close`) → `RollingMax` / `RollingMin` (HHV / LLV)
  per-bar channel overlays, with the period read from the strategy and a
  field-matched `PriceSource`. Covered by `OverlayWiringTest`.

### Changed

- **ha-track 0.53.0-alpha → 0.54.0-alpha** — picks up the `RollingMax` /
  `RollingMin` (Donchian-style) overlay indicators that back the
  window-aggregate channel rendering above; that channel was deferred in the
  prior increment until the indicator landed upstream. `THIRD-PARTY.txt`
  regenerated.

### Changed (housekeeping)

- **Report metrics annotation (Concern 5a)** — added a `TODO(ha-track)` next
  to `HtmlReportGenerator.winsAndLosses` documenting that frau-holle's
  `BacktestMetrics` (v0.47.0-alpha) exposes only `winRate` and `numTrades`;
  the rounding reconstruction stays in place until explicit wins/losses
  accessors land upstream.
- **Trigger-collision diagnostic (Concern 5b)** — the silent `putIfAbsent`
  in `HtmlReportGenerator.buildTriggerByFillTime` now emits a
  `Logger.fine` message when two scenarios resolve to the same fill `Instant`,
  citing CLAUDE.md §6.3's source-order tiebreaker. Behaviour is unchanged;
  the drop is now observable under `-Djava.util.logging.config.file=...`.
