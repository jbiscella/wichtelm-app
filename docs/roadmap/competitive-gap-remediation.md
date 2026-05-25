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

### NEC-1: Overlay auto-plot incomplete (pivot levels missing; per-trade overlay completeness)

- **Symptom**: On the showcase-MA demo (`demo/reports/tstx-showcase-ma-1d-report.html`)
  the strategy references `price_above_pivot(P)` / `price_below_pivot(P)`, yet no
  STANDARD daily pivot levels appear on any chart frame — pivot levels never
  produce a legend entry and none are visible in the committed screenshot
  (`tstx-showcase-ma-1d.png`). The dedicated pivot demo's chart legend is likewise
  empty. SMA/EMA overlays *do* render in the legend for frames whose scenarios
  reference them (`SMA(10)` ×23, `EMA(30)` ×38, etc.), so the MA path works while
  the pivot path appears unwired on this demo.
- **Why it's necessary**: Half-done feature — auto-plotting the indicators a
  strategy evaluates against (pivots included) is a baseline expectation versus
  TradingView / QuantConnect; a chart that omits referenced pivot levels
  misrepresents what the strategy actually tested against.
- **Thin slice**: Using the NEC-3 scaffolding, add a deterministic assertion that
  the `ChartSpec` built for a showcase-MA trade whose scenarios reference a
  `price_*_pivot` primitive contains the `Annotation.PivotPointLevels` set (and
  that each strategy-referenced SMA/EMA overlay is present on the frames that
  reference it). Make it pass, then regenerate the showcase-MA report.
- **Definition of done**: The regenerated showcase-MA report visibly shows the
  STANDARD daily pivot levels on the primary pane for pivot-referencing trades,
  and every strategy-referenced MA overlay is present on its relevant frames —
  confirmed by the committed report and a green `ChartSpec` assertion.
- **Depends on**: NEC-3 (assertion scaffolding). NEC-2 should land first to avoid
  regenerating the committed report twice.

### NEC-2: Decorative Bollinger Bands from the `stddev` Background series

- **Symptom**: Every showcase-MA chart frame renders `BB(20,2)` (Upper / Basis /
  Lower — present in all 47 frame legends) because the `Given a series vol defined
  as stddev(20)` Background series is auto-mapped to a Bollinger Bands overlay
  (`HtmlReportGenerator.toIndicator`, the `stddev` → `Indicator.BollingerBands`
  case). The strategy only compares the scalar σ (`vol is above 1`) and never uses
  a band, so the 3-line band is noise that misrepresents the tested signal.
- **Why it's necessary**: Half-done auto-plot convention — drawing an indicator the
  strategy does not evaluate as a band confuses the reader about what fired the
  signal and looks unpolished next to tools that plot only what the script
  references.
- **Thin slice**: Resolve the `stddev` rendering policy — either drop the
  `stddev` → `BollingerBands` mapping, or render σ as the quantity the strategy
  actually evaluates — and pin it with a `ChartSpec` assertion that a
  `stddev`-only Background series produces no `BollingerBands` overlay. Then
  regenerate the showcase-MA report. (The drop-vs-re-represent choice is a binary
  decision to confirm at implementation time.)
- **Definition of done**: The regenerated showcase-MA frames no longer carry a
  `BB(20,2)` band the strategy never evaluates (or carry only a representation it
  does evaluate), enforced by a green assertion.
- **Depends on**: NEC-3.

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

## Phase 1 — Proposed ordering

1. **NEC-3** — build the visual-contract scaffolding first so every subsequent fix
   lands red→green and can't silently regress; this is the missing discipline.
2. **NEC-2** — subtract the decorative `BB(20)`; smallest, lowest-risk content
   change, and it settles what "correct contents" means before we add to them.
3. **NEC-1** — add the missing pivot levels and verify per-trade overlay
   completeness, guarded by NEC-3's assertions.
4. **NEC-4** — make the frame-header descriptor name exactly the now-correct
   rendered contents; must follow NEC-1 / NEC-2 since it describes their result.

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
- **Q2 repo-side TODOs (flagged, not auto-included pending confirmation)**:
  (a) `HtmlReportGenerator.java:400` — `winsAndLosses` reconstructs win/loss
  counts by rounding through `winRate`; gated on an additive frau-holle
  `wins()` / `losses()` accessor (a metric-accuracy nicety).
  (b) `OverlayWiringTest` javadoc (lines 33–36) still claims window aggregates are
  "deliberately NOT plotted" while the code + tests now plot them via
  `RollingMax` / `RollingMin` (ha-track 0.54) — a stale-comment reconciliation.
  Neither is currently placed in Phase 1 or Phase 2; both await confirmation
  before inclusion.
