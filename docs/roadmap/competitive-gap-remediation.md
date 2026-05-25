# Competitive Gap Remediation Roadmap

Planning document. It catalogs gaps already raised in review or observable in
the current committed demo output, classifies them into two phases, proposes an
implementation ordering, and defines a thin slice per gap so each ships
independently. It does **not** implement any fix. No effort estimates and no
dates by design — ordering only.

Scope of evidence: gaps below are either (a) pre-listed in the remediation
brief, or (b) observed directly in the committed `demo/reports/*.html` and
`demo/reports/screenshots/*.png`. No speculative gaps were added.

## Phase 1 — Necessary

### NEC-1: Pivot levels emitted but not rendered (overlay auto-plot)

- **Symptom**: On the showcase-MA demo (`demo/reports/tstx-showcase-ma-1d-report.html`)
  the strategy references `price_above_pivot(P)` / `price_below_pivot(P)`. The
  generator provably *emits* the pivot overlay — `pivotAnnotations` returns an
  `Annotation.PivotPointLevels` set and the green `OverlayWiringTest` pins it — yet
  no pivot levels appear in any chart frame's legend, nor in the committed
  screenshot (`tstx-showcase-ma-1d.png`). The committed report is *current* (HTML,
  generator, and the wiring tests all landed in the same commit, the demo rebuild
  #42), so this is not staleness: the emitted annotation does not survive into the
  rendered chart. This is the data-object-vs-render gap that is the canonical
  example motivating NEC-3.
- **Why it's necessary**: Half-done feature — a chart that omits the pivot levels
  the strategy reads its booleans off of misrepresents what it tested against
  (a baseline expectation versus TradingView / QuantConnect), and the failure mode
  here (emitted-but-not-drawn) currently passes CI green.
- **Thin slice**: Using the NEC-3 scaffolding, add a deterministic assertion at the
  *render boundary* (built `ChartSpec` → rendered image / legend) that a
  pivot-referencing showcase-MA trade carries the pivot levels in the rendered
  output, not merely in the returned annotation list. Make it pass — whether the
  fix lands in wichtelm-app's spec build or an ha-track heerwisch render path — and
  regenerate the showcase-MA report.
- **Definition of done**: The regenerated showcase-MA report visibly shows the
  STANDARD daily pivot levels on the primary pane for pivot-referencing trades,
  guarded by a green *render-boundary* assertion (not just the existing
  data-object assertion).
- **Depends on**: NEC-3 (render-boundary assertion scaffolding). NEC-2 should land
  first to avoid regenerating the committed report twice.
- **Caveat (briefing vs verification)**: The original brief also assumed
  `SMA(fast)` was missing on this demo — inferred from a single SHORT-trade
  screenshot (trade #12, whose entry/exit scenarios reference only EMA + pivot, so
  SMA is *correctly* absent from that one frame). Inspecting all 47 frame legends
  shows `SMA(10)` (= `fast`) renders in 23 of them and `SMA(30)` in the lone frame
  that references it, so SMA auto-plot works. NEC-1's scope was therefore narrowed
  from "SMA + pivot missing" to "pivot levels emitted but not rendered." The
  brief's companion premise that pivot R/S levels "render in other demos" was not
  verifiable either: the dedicated pivot demo's chart legend is empty and no pivot
  screenshot is committed.

### NEC-2: Scalar `stddev` misrendered as a Bollinger Bands band

- **Symptom**: Every showcase-MA chart frame renders `BB(20,2)` (Upper / Basis /
  Lower — present in all 47 frame legends) because the `Given a series vol defined
  as stddev(20)` Background series is mapped to a Bollinger Bands overlay
  (`HtmlReportGenerator.toIndicator`, the `stddev` → `Indicator.BollingerBands`
  case, line 1680). The strategy only compares the scalar σ (`vol is above 1`) and
  never uses a band, so the 3-line price band (SMA ± 2σ) misrepresents the tested
  signal. The BB stand-in exists only because heerwisch has no σ-line indicator.
- **Why it's necessary**: A scalar volatility value charted as a price band is a
  conceptual misrepresentation — it confuses the reader about what fired the signal
  and looks unpolished next to tools that plot only what the script references.
- **Decision (resolved)**: Fix faithfully — render σ as its own sub-pane line, do
  NOT drop the overlay. This requires an additive `Indicator.StdDev` in heerwisch
  (see Upstream dependencies); wichtelm-app then maps `stddev` to it. The
  drop-the-rendering alternative was rejected as tech debt.
- **Thin slice**: (after the heerwisch release) point `toIndicator`'s `stddev` case
  at `Indicator.StdDev(period, CLOSE)`, add a `describeIndicator` case →
  `"σ(period)"`, pin it with a unit assertion that `toIndicator("stddev(20)", …)`
  returns `Indicator.StdDev` (not `BollingerBands`), and regenerate the showcase-MA
  report. Subplot routing is automatic via `defaultPane()` — no other change.
- **Definition of done**: The regenerated showcase-MA frames show a σ sub-pane line
  (header reads `σ(20)`, with a legend entry) and no `BB(20)`; a green assertion
  enforces the `stddev → StdDev` mapping.
- **Depends on**: heerwisch `Indicator.StdDev` (hard upstream — see Upstream
  dependencies); NEC-3 for the assertion scaffolding.


### NEC-3: Visual-contract regression coverage gap

- **Symptom**: The GWT / unit suite asserts on emitted *data objects* — e.g.
  `OverlayWiringTest` pins the `Indicator` / `Annotation` values returned by
  `tierBIndicators` / `pivotAnnotations` — but nothing asserts the rendered
  report's *visual contract*: overlay presence on the actual built `ChartSpec`,
  the frame-header descriptor text, the legend, or entry/exit markers. The BB(20),
  missing-pivot, and header-descriptor defects all shipped green and were
  surfaced only by manual screenshot review.
- **Why it's necessary**: Process gap — without a visual-contract test layer,
  half-done rendering features pass CI. It is the root enabler of NEC-1 / NEC-2 /
  NEC-4 and the prerequisite for any automated snapshot diffing (BEN-1).
- **Thin slice**: Add a test harness that builds the report's `ChartSpec` for a
  given strategy + trade and exposes its overlays, annotations, sub-panes, and
  the frame-header descriptor for assertion; commit a manual screenshot-review
  checklist (under `docs/`) enumerating the visual invariants a human must eyeball.
  Seed the harness with one assertion (e.g. entry/exit marker presence) to prove it.
- **Definition of done**: A test exists that goes red when a strategy-referenced
  overlay is absent from the built `ChartSpec` (or when the header descriptor
  disagrees with the overlay set), plus a committed manual review checklist —
  demonstrated by the seed assertion failing when an overlay is removed.
- **Depends on**: none.

### NEC-4: Frame-header descriptor under-reports chart contents (newly identified)

- **Symptom**: The per-trade chart frame header always reads `HA candles · BB(20)`
  on the showcase-MA demo, even though the rendered chart's legend draws
  `SMA(10)`, `SMA(30)`, `EMA(10)`, `EMA(30)` and an `RSI(14)` sub-pane.
  `describeChartIndicators` only walks Background series, so it omits the tier-B
  MA / pivot overlays and the indicator sub-panes. §7.5 of CLAUDE.md requires the
  header to list the chart contents (HA candles + main-pane overlays + RSI sub-pane
  when applicable).
- **Why it's necessary**: Half-done convention / spec violation — the header
  misstates what the chart shows. This is the mirror of NEC-1 (there, overlays are
  missing; here, present overlays go unnamed), and it directly misleads the reader
  about the tested signal.
- **Thin slice**: Extend `describeChartIndicators` to include the tier-B overlays
  (SMA / EMA), pivot levels, and any RSI / MACD / ATR sub-panes actually added to
  the built `ChartSpec` (reconciled with NEC-2's `stddev` decision), and pin it
  with an assertion that the descriptor equals the overlay set on the `ChartSpec`.
- **Definition of done**: The regenerated showcase-MA header reads the chart's
  actual contents (e.g. `HA candles · SMA(10) · EMA(30) · pivot P · RSI(14)`), tied
  to the `ChartSpec` overlay set by a green assertion.
- **Depends on**: NEC-1 and NEC-2 (final contents must be settled before naming
  them); NEC-3 (assertion scaffolding).

### NEC-5: Win/loss counts reconstructed by rounding (metric correctness)

- **Symptom**: `HtmlReportGenerator.winsAndLosses` (line 400) has no direct
  win/loss counters from frau-holle's `BacktestMetrics` (v0.47.0-alpha exposes
  `winRate` + `numTrades` only), so it reconstructs `wins = round(winRate ×
  numTrades)` and `losses = numTrades − wins`. The rounding can yield an
  off-by-one count that disagrees with the report's own trade list.
- **Why it's necessary**: Correctness floor — silently wrong win/loss counts in a
  backtest report are worse than a visual gap; a user cannot trust a tool whose
  headline counts don't reconcile with its own trade list.
- **Thin slice**: Once an additive frau-holle `wins()` / `losses()` (or
  `winningTrades()` / `losingTrades()`) accessor on `BacktestMetrics` is published,
  read the counts directly and delete the rounding round-trip plus the line-400
  TODO. Blocked upstream until that accessor ships (see Upstream dependencies).
- **Definition of done**: The report's `N wins · M losses` line is read from
  frau-holle's direct counters (no rounding), `wins + losses == numTrades` holds
  exactly for a backtest with a known split, and the line-400 TODO is gone.
- **Depends on**: external — an additive frau-holle `BacktestMetrics` accessor
  (ha-track release). Independent of the visual track (NEC-1 / 2 / 3 / 4).

### NEC-6: Stale "not plotted" javadoc for window-aggregate overlays (doc reconciliation)

- **Symptom**: `OverlayWiringTest` javadoc (lines 33–36) states window-aggregate
  Background series (`highest_high` etc.) are "deliberately NOT plotted … until an
  additive ha-track indicator lands," but ha-track 0.54's `RollingMax` /
  `RollingMin` did land — `toIndicator` and the
  `windowAggregatesMapToFieldMatchedRollingExtremumOverlays` test now plot them.
  The comment contradicts the code it documents (and CLAUDE.md §7.5, which lists
  the RollingMax / RollingMin overlays as present).
- **Why it's necessary**: Half-done convention residue — a stale "deliberately NOT
  done" comment misleads the next contributor into thinking a shipped feature is
  still pending.
- **Thin slice**: Rewrite the `OverlayWiringTest` javadoc to describe the
  now-implemented `RollingMax` / `RollingMin` field-matched plotting (single
  commit, doc-only, no behavior change).
- **Definition of done**: The javadoc matches the code + tests (window aggregates
  ARE plotted via `RollingMax` / `RollingMin` since 0.54).
- **Depends on**: none. Kept stand-alone rather than folded into NEC-2: it touches
  a different overlay family (window aggregates, not the `stddev` / BB band) and is
  a doc-only one-commit change, so folding it in would muddy NEC-2's thin slice.
  Fold into NEC-2 only if a single overlay-convention PR is preferred.

## Phase 1 — Proposed ordering

1. **NEC-3** — build the visual-contract scaffolding first so every subsequent fix
   lands red→green and can't silently regress; this is the missing discipline.
2. **NEC-2** — replace the `BB(20)` stand-in with a faithful σ sub-pane line. Scope
   is decided, but the wichtelm-app side is gated on the heerwisch `Indicator.StdDev`
   release, so open that upstream ask early; land the consumption once it ships. Its
   content decision (σ shown as a sub-pane) feeds NEC-4's header listing.
3. **NEC-1** — fix the emitted-but-unrendered pivot levels, guarded by NEC-3's
   render-boundary assertion (the data-object test already passes green).
4. **NEC-4** — make the frame-header descriptor name exactly the now-correct
   rendered contents; must follow NEC-1 / NEC-2 since it describes their result.
5. **NEC-5** — correctness floor; independent of the visual track but grouped in
   Phase 1. Blocked on an upstream frau-holle accessor, so open that request early
   and land the wichtelm-app side as soon as it ships.
6. **NEC-6** — doc-only reconciliation; trivial, do last (or fold into NEC-2).

## Phase 2 — Beneficial

### BEN-1: Snapshot diff in CI (golden-screenshot comparison)

- **Symptom**: There is no automated comparison of rendered report output against
  approved golden images; visual regressions rely on a human re-checking
  screenshots.
- **Why it's beneficial**: Automates the NEC-3 manual review into CI, turning a
  visual regression into a hard build failure rather than a review-discipline
  expectation. Strengthens the credibility-of-output differentiator; not a v1
  blocker.
- **Thin slice**: Wire a single golden-image comparison for one canonical demo
  report (e.g. showcase-MA) into the build — render, compare against a committed
  PNG baseline within a tolerance, fail on diff — starting with one frame to prove
  the harness.
- **Definition of done**: CI fails when the showcase-MA rendered chart diverges
  from its committed golden beyond tolerance, with a documented "bless" step to
  update the baseline intentionally.
- **Depends on**: NEC-3 (the visual contract it automates); should follow
  NEC-1 / NEC-2 / NEC-4 and BEN-2 so the frozen baseline is already correct.

### BEN-2: Legend distinguishes "referenced by strategy" vs "context" indicators

- **Symptom**: The chart legend lists every overlay identically; there is no visual
  distinction between indicators the strategy actually evaluates (SMA / EMA / pivot
  it reads booleans off of) and any context-only indicator kept for orientation.
  Pivot levels also carry no legend entry at all (the pivot demo's legend is empty).
- **Why it's beneficial**: If NEC-2 keeps any context indicators, the reader needs
  to know which overlays the strategy tested against versus which are decoration;
  it also surfaces annotation-based overlays (pivots) in the legend. Reinforces the
  "what did the strategy evaluate" clarity that differentiates the tool.
- **Thin slice**: Add a referenced / context tag (or grouping) to legend entries
  and include pivot levels in the legend; pin with an assertion that a
  strategy-referenced overlay is tagged `referenced`.
- **Definition of done**: The regenerated report's legend visibly separates
  referenced overlays from context overlays and includes pivot levels, enforced by
  a green assertion on a referenced overlay's tag.
- **Depends on**: NEC-2 resolution (whether context indicators are kept at all);
  pairs with NEC-4 (the descriptor / legend pairing).

## Phase 2 — Proposed ordering

1. **BEN-2** — settle the legend semantics (referenced vs context; pivots in the
   legend) before any golden image is frozen, so the baseline reflects the final
   legend.
2. **BEN-1** — automate the now-final visual contract as golden-image CI; depends
   on NEC-3 and on a settled BEN-2 so baselines aren't immediately re-blessed.

## Cross-phase notes

- **NEC-3 → BEN-1**: NEC-3 (manual + spec scaffolding: `ChartSpec` assertions plus
  a committed manual checklist) is the prerequisite for BEN-1 (golden-image
  automation). NEC-3 defines the visual contract; BEN-1 enforces it with snapshot
  diffing. Phase 1 must land first.
- **NEC-2 → BEN-2**: NEC-2's resolution (drop vs keep context indicators) sets
  BEN-2's scope. If context indicators are dropped, BEN-2 reduces to labeling
  referenced overlays and adding pivots to the legend; if kept, BEN-2 must visually
  separate the two classes.
- **NEC-4 ↔ BEN-2**: NEC-4 (necessary: truthful header) and BEN-2 (beneficial:
  referenced-vs-context legend) are the descriptor / legend pair — NEC-4 is the
  correctness floor, BEN-2 the enhancement on top. Sequence NEC-4 before BEN-2.
- **Shared demo-regeneration overhead**: NEC-1, NEC-2, NEC-4, and BEN-2 each
  require regenerating the committed demo HTML (large files). Regenerating once,
  after NEC-4, avoids churning the committed reports repeatedly.
- **Q2 repo-side TODOs — promoted into Phase 1**: the two items flagged from the
  Q2 follow-up are confirmed and folded in as **NEC-5** (`winsAndLosses` rounding)
  and **NEC-6** (stale window-aggregate javadoc), inserted after NEC-4. NEC-5 is
  independent of the visual track but grouped here for correctness; NEC-6 is a
  doc-only follow-on (may fold into NEC-2). No open GitHub issues exist in the repo.
- **Upstream (ha-track) dependencies** — some increments need an additive ha-track
  release before they can complete (wichtelm-app consumes published artifacts):
  - **NEC-5 → frau-holle (hard blocker)**: requires `int wins()` / `int losses()`
    (or `winningTrades()` / `losingTrades()`) on `BacktestMetrics`. The rounding
    round-trip cannot be removed until this lands.
  - **NEC-1 → heerwisch / jfreechart (likely)**: the renderer must actually draw
    `Annotation.PivotPointLevels`. Other annotation types (EntryExitMarker,
    TimeRangeHighlight, HorizontalLevel) already render, so pivots appear to be an
    unimplemented annotation in the driver — confirm via NEC-1's render-boundary
    investigation before opening the upstream change.
  - **BEN-2 → heerwisch (conditional)**: surfacing pivot levels in
    `ChartImage.legend()` needs heerwisch to legend annotation-based overlays
    (today `legend()` lists indicators only).
  - **NEC-2 → heerwisch (hard blocker, resolved scope)**: requires an additive
    `Indicator.StdDev(int period, PriceSource)` variant of the sealed `Indicator`
    type — a subplot σ line (population σ, divisor = period, matching
    `BarIndicatorSource.stddev`), jfreechart driver rendering, and a `LegendEntry`.
    Baseline pin is ha-track `0.54.0-alpha`; target the next additive release. The
    faithful-fix decision (not dropping the overlay) makes this a hard dependency,
    not optional.
