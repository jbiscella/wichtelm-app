# Visual-review checklist — backtest HTML reports

A manual review aid for the things automated tests **cannot** assert: the
raster chart contents produced by the heerwisch / JFreeChart driver, and the
overall page composition of a generated report. Run through this whenever a
demo report is regenerated (or a report-generator change could plausibly alter
rendering) and eyeball each item against the §7 contract in `CLAUDE.md`.

This complements — does not replace — the automated visual-contract coverage,
which already pins:

- `GoldenChartImageTest` — a representative chart rendered and diffed pixel-wise
  against a committed baseline (catches silent rendering drift).
- `OverlayWiringTest` — the emitted overlay/annotation objects (tier-B SMA / EMA
  overlays, pivot level sets, window-aggregate RollingMax / RollingMin, σ sub-pane).
- `describeChartIndicators` tests — the frame-header descriptor equals the chart's
  overlay set (tier-B overlays + sub-panes + pivots; higher-TF panes name only
  their Background series).
- `WinLossCountReconciliationTest` — headline win/loss counts reconcile with the
  trade list.

Anything below that is **not** in that list needs a human to look at the rendered
PNG, because the chart pixels are outside the assertion surface.

## How to review

1. Regenerate the reports in the canonical rendering environment:
   `./demo/run_demo.sh` (CSV, offline, deterministic data).
2. Open each `demo/reports/*-report.html` in a browser.
3. Walk the checklist below per report. Note that chart **contents** carry
   JFreeChart's native palette/typography — the deliberate, accepted mismatch
   with the styled page chrome (§7.1). Review the chart for *correctness*, not
   for matching the page's font/colour.

## Page chrome (§7.2–§7.4, §7.7)

- [ ] **Header band**: monochrome logo, `wichtelm-app · backtest report · v<version>`
      wordmark, `Backtest report` title, the mono line
      `Strategy … · Symbol … · Window <from → to> · Bars <tf>(<multi-TF>)`,
      generation timestamp at the right, condensed disclaimer below.
- [ ] **Metrics grid**: ten cards (Total return, Trades, Win rate, Max drawdown,
      Sharpe, Sortino, Calmar, Profit factor, Avg win, Avg loss). Total return and
      Max drawdown are semantic-coloured. Profit factor renders `—` when there
      are zero trades.
- [ ] **Equity curve** and **drawdown** SVG panels: monthly X ticks, 5%-step Y
      grid, dashed reference line at 100 on the equity curve, red filled area
      under the drawdown curve.
- [ ] **Footer**: `Strategy · Symbol · Bars` left, `wichtelm-app <version> · <date>`
      right, full hypothetical-results / past-performance / look-ahead / no-liability
      disclaimer below.
- [ ] **Zero JavaScript**: trades expand/collapse via native `<details>`; no script
      tags, no console errors.

## Trade list (§7.5)

- [ ] One `<details>` per trade, **collapsed by default**, closed trades first by
      entry timestamp ascending, the still-open position (if any) appended last
      with a `still open` tag.
- [ ] Ordinals `#NN` zero-padded to the digit count of the total.
- [ ] Collapsed row: direction pill (`long` green / `short` red), time range +
      duration line, price range, semantic P/L with `price <±X.XX%>` sub-line
      (or `STILL OPEN` for the open trade), chevron that rotates when expanded.
- [ ] Conditions row: entry When/And steps `→` exit steps, each with a green `✓`;
      forced exits show `stop_loss / take_profit`; open trades show
      `still open at window end`.
- [ ] Expanded stats grid: Entry · Exit · Hold (`N × <tf> bars`) · P/L · **MFE**
      (green) · **MAE** (red), signs and colours correct.
- [ ] Scenario row spells out full entry / exit Scenario names (`still open` /
      `stop_loss / take_profit` where applicable).

## Chart frames (§7.5) — the raster contents

For the **Price · primary** frame on representative trades of each strategy:

- [ ] **HA candles** render as the price body (not OHLC bars).
- [ ] **MA overlays**: `SMA(n)` / `EMA(n)` from MA-trend-filter primitives appear
      on the main pane with the periods the strategy declares.
- [ ] **Pivot levels**: STANDARD daily P / R1–R3 / S1–S3 appear on the main pane
      **only** for trades whose entry/exit scenarios reference a pivot primitive,
      computed from the prior completed UTC day.
- [ ] **Window-aggregate channels**: `highest_high` / `lowest_low` /
      `highest_close` / `lowest_close` Background series render as RollingMax /
      RollingMin per-bar channel overlays (field-matched price source).
- [ ] **Sub-panes**: RSI sub-pane has a bounded [0, 100] axis with semantic
      threshold lines and shaded danger zones matching the strategy's
      overbought/oversold; ATR / MACD sub-panes present when referenced; sub-pane
      axis titles do not overflow into neighbouring panes.
- [ ] **σ sub-pane**: a `stddev(n)` Background series renders as a `σ(n)` sub-pane
      line — **never** a Bollinger band.
- [ ] **Frame-header descriptor** lists exactly the contents drawn (e.g.
      `HA candles · SMA(10) · EMA(30) · RSI(14) · pivots`); nothing named that
      isn't drawn and nothing drawn that isn't named.
- [ ] **Entry / exit markers** follow the direction-of-capital-flow matrix:
      LONG_ENTRY ▲ / SHORT_ENTRY ▼ (always triangles — entries are scheduled);
      LONG_EXIT ▼ scheduled / arrow-down forced; SHORT_EXIT ▲ scheduled /
      arrow-up forced. Glyphs sit outside the bar (entries below low, exits above
      high).
- [ ] **Held-interval shading** is outcome-oriented: green (WIN) for a winning
      closed trade, red (LOSS) for a losing one, muted grey (OPEN) for the
      still-open trade — *not* direction-coloured.
- [ ] **Frame footer**: `▲ entry <ts> · in position · Mh · exit <ts> ▼`
      (open-trade variant `mark <ts> · window end`).
- [ ] **Reference lines**: dashed Entry (neutral), Stop (red) / Take (green) when
      the entry scenario declares them, Exit (outcome-coloured) on closed trades.

For the **Background · higher-TF** frame (once per distinct higher TF referenced):

- [ ] Renders the higher-TF chart over the equivalent window with a neutral
      `higher-timeframe context` footer label (open trades append
      `· trade still open`); no trade-reference overlays (pivots / stop / take).

## Legend strip (§7.5, BEN-2)

- [ ] Indicator entries (swatch + label) match the overlays/sub-panes drawn.
- [ ] Annotation entries (pivots / horizontal levels / fib) appear after a divider.
- [ ] *(BEN-2, once implemented)* referenced vs context indicators are visually
      distinguished.

## Diagnostics & data hygiene (§7.6, §17)

- [ ] **Suppressed entries** section appears **only** when entries were suppressed
      for indicator warmup, as a `bar time · scenario · reason` table; absent on a
      clean run.
- [ ] Instrument names are the synthetic fixtures (`TSTX`, `TSTC`) — **never** a
      real ticker — and the committed reports are the CSV (offline) runs, not
      EODHD-derived.
- [ ] Reports are never overwritten — each run writes a new timestamped file.
