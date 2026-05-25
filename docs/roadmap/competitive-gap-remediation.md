# Competitive Gap Remediation Roadmap

Living tracking document. It catalogs report-rendering gaps, classifies them into
two phases, proposes an implementation ordering, and defines a thin slice +
definition of done per gap. It began as planning-only; several Phase-1 items have
since shipped **on this branch** and are marked **STATUS** inline, with each
item's original symptom kept (past tense where resolved) for traceability. No
effort estimates and no dates by design — ordering only.

Scope of evidence: gaps are either (a) pre-listed in the remediation brief,
(b) observed directly in the committed `demo/reports/*.html` /
`demo/reports/screenshots/*.png`, or (c) code-level reconciliation from the Q2
follow-up (NEC-5 / NEC-6 — a TODO / stale comment vs the current code). No
speculative gaps were added. Shipped statuses below are verifiable against this
branch's tree (`pom.xml`, the named source files, and the regenerated demo
artifacts); they are not external-state claims.

## Phase 1 — Necessary

### NEC-1: Pivot levels emitted but not rendered (overlay auto-plot)

**STATUS: rendering RESOLVED on this branch** (the 0.55 driver draws the levels and
the demos regenerated — see Upstream dependencies). The render-boundary guard
(NEC-3) is still owed; the symptom below is the original, pre-fix state.

- **Symptom (original)**: On the showcase-MA demo
  (`demo/reports/tstx-showcase-ma-1d-report.html`) the strategy references
  `price_above_pivot(P)` / `price_below_pivot(P)`. The generator *emits* the pivot
  overlay — `pivotAnnotations` returns an `Annotation.PivotPointLevels` set and the
  green `OverlayWiringTest` pins it — yet no pivot levels appeared in any chart
  frame's legend, nor in the then-committed screenshot. The HTML, generator, and
  wiring tests were all present in the same committed tree, so this was not
  staleness: the emitted annotation did not survive into the rendered chart. This
  is the data-object-vs-render gap that is the canonical example motivating NEC-3.
- **Why it's necessary**: Half-done feature — a chart that omits the pivot levels
  the strategy reads its booleans off of misrepresents what it tested against.
  CLAUDE.md §7.5 requires the per-trade chart to show the referenced main-pane
  overlays (incl. STANDARD daily pivot levels). The failure mode here
  (emitted-but-not-drawn) passed the existing green test suite.
- **Thin slice**: Using the NEC-3 scaffolding, add a deterministic assertion at the
  *render boundary* (built `ChartSpec` → rendered image / legend) that a
  pivot-referencing showcase-MA trade carries the pivot levels in the rendered
  output, not merely in the returned annotation list. The fix lands in
  **wichtelm-app's spec build** (not upstream): Increment #2 confirms heerwisch
  already renders `Annotation.PivotPointLevels` unchanged, so the gap is that the
  emitted annotation does not reach the rendered `ChartSpec` from wichtelm's side.
  Make it pass and regenerate the showcase-MA report.
- **Definition of done**: The regenerated showcase-MA report visibly shows the
  STANDARD daily pivot levels on the primary pane for pivot-referencing trades,
  guarded by a green *render-boundary* assertion (not just the existing
  data-object assertion).
- **Depends on**: NEC-3 (render-boundary assertion scaffolding). NEC-2 should land
  first to avoid regenerating the committed report twice. **No upstream blocker** —
  Increment #2 confirms the heerwisch pivot renderer needs no change.
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

**STATUS: DONE on this branch** — `toIndicator`'s `stddev` case now builds
`Indicator.StdDev(period, CLOSE)` (σ sub-pane); see Upstream dependencies. The
symptom below is the original, pre-fix state.

- **Symptom (original)**: Every showcase-MA chart frame rendered `BB(20,2)` (Upper /
  Basis / Lower — present in all 47 frame legends) because the `Given a series vol
  defined as stddev(20)` Background series was mapped to a Bollinger Bands overlay
  (`HtmlReportGenerator.toIndicator`, the `stddev` → `Indicator.BollingerBands`
  case). The strategy only compares the scalar σ (`vol is above 1`) and never uses
  a band, so the 3-line price band (SMA ± 2σ) misrepresented the tested signal. The
  BB stand-in existed only because heerwisch had no σ-line indicator.
- **Why it's necessary**: A scalar volatility value charted as a price band is a
  conceptual misrepresentation — it confuses the reader about what fired the signal,
  and CLAUDE.md §7.5 ties the chart contents to what the strategy actually
  references.
- **Decision (resolved)**: Fix faithfully — render σ as its own sub-pane line, do
  NOT drop the overlay. This required an additive `Indicator.StdDev` in heerwisch
  (shipped in 0.55.0-alpha); wichtelm-app maps `stddev` to it. The drop-the-rendering
  alternative was rejected as tech debt.
- **Thin slice (done)**: `toIndicator`'s `stddev` case builds
  `Indicator.StdDev(period, CLOSE)`; `describeIndicator` emits `"σ(period)"`;
  `OverlayWiringTest` pins `toIndicator("stddev(20)", …)` → `Indicator.StdDev` (not
  `BollingerBands`); the showcase-MA report is regenerated. Subplot routing is
  automatic via `defaultPane()`.
- **Definition of done**: ✅ The regenerated showcase-MA frames show a σ sub-pane
  line (header reads `σ(20)`, with a legend entry) and no `BB(20)`; a green
  assertion enforces the `stddev → StdDev` mapping.
- **Depends on**: heerwisch `Indicator.StdDev` (shipped in 0.55.0-alpha, now pinned
  — see Upstream dependencies); NEC-3 for the render-boundary assertion.


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

**STATUS: DONE on this branch** — `winsAndLosses` reads the direct counters; see
Upstream dependencies. The symptom below is the original, pre-fix state.

- **Symptom (original)**: `HtmlReportGenerator.winsAndLosses` had no direct win/loss
  counters from frau-holle's `BacktestMetrics` (the pre-0.55 `BacktestMetrics`
  exposed `winRate` + `numTrades` only), so it reconstructed `wins = round(winRate ×
  numTrades)` and `losses = numTrades − wins`. The rounding can yield an off-by-one
  count that disagrees with the report's own trade list.
- **Why it's necessary**: Correctness floor — silently wrong win/loss counts in a
  backtest report are worse than a visual gap; a user cannot trust a tool whose
  headline counts don't reconcile with its own trade list.
- **Thin slice (done)**: read `winningTrades()` / `losingTrades()` from
  `BacktestMetrics` directly; the rounding round-trip and the TODO are deleted.
- **Definition of done**: ✅ The report's `N wins · M losses` line is read from
  frau-holle's direct counters (no rounding), `wins + losses == numTrades` holds
  exactly, and the TODO is gone. (frau-holle's accessor counts `pnl > 0` as a win
  and `pnl == 0` as a loss; wichtelm-app delegates to it.)
- **Depends on**: frau-holle's additive `winningTrades()` / `losingTrades()`
  accessor on `BacktestMetrics` (shipped in 0.55.0-alpha, now pinned — see Upstream
  dependencies). Independent of the visual track (NEC-1 / 2 / 3 / 4).

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
2. **NEC-2** *(DONE)* — replaced the `BB(20)` stand-in with a faithful σ sub-pane
   line via the shipped `Indicator.StdDev`. Its content decision (σ as a sub-pane)
   feeds NEC-4's header listing.
3. **NEC-1** *(rendering DONE; NEC-3 guard owed)* — the pivot levels now render; the
   render-boundary assertion that locks it in still depends on NEC-3.
4. **NEC-4** — make the frame-header descriptor name exactly the now-correct
   rendered contents; must follow NEC-1 / NEC-2 since it describes their result.
5. **NEC-5** *(DONE)* — correctness floor; read the direct frau-holle counters.
   Independent of the visual track.
6. **NEC-6** — doc-only reconciliation; trivial, do last (or fold into NEC-2).

## Phase 2 — Beneficial

### BEN-1: Golden-screenshot comparison (gated — requires explicit author opt-in)

> **Gate**: `AGENTS.md` says "Do NOT suggest adding CI/CD pipeline files unless
> explicitly asked." BEN-1's value is in a CI gate, so it is **not** proposed for
> implementation here — it is recorded as a candidate only, and must be requested
> explicitly by the author before any build/CI wiring is added. The local,
> developer-run form (a golden-image comparison invoked manually, no pipeline file)
> is the most that should be built absent that request.

- **Symptom**: There is no automated comparison of rendered report output against
  approved golden images; visual regressions rely on a human re-checking
  screenshots (the NEC-3 manual checklist).
- **Why it's beneficial**: Turns the NEC-3 manual review into a mechanical
  pass/fail. Strengthens the credibility-of-output differentiator; not a v1 blocker.
- **Thin slice (candidate, opt-in)**: A single golden-image comparison for one
  canonical demo report (e.g. showcase-MA) — render, compare against a committed PNG
  baseline within a tolerance, fail on diff — runnable as a local check first. Any
  promotion of that check into a CI pipeline is out of scope until explicitly asked.
- **Definition of done**: The showcase-MA rendered chart is compared against its
  committed golden beyond a tolerance, with a documented "bless" step to update the
  baseline intentionally.
- **Depends on**: NEC-3 (the visual contract it automates); should follow
  NEC-1 / NEC-2 / NEC-4 and BEN-2 so the frozen baseline is already correct; plus an
  explicit author request before any CI wiring.

### BEN-2: Legend distinguishes "referenced by strategy" vs "context" indicators

**STATUS: pivots-in-legend DONE; referenced-vs-context tagging OPEN.** The
annotation-legend half shipped on this branch; see Upstream dependencies.

- **Symptom**: The chart legend listed every overlay identically, with no visual
  distinction between indicators the strategy actually evaluates (SMA / EMA / pivot
  it reads booleans off of) and any context-only indicator kept for orientation.
  Pivot levels also carried no legend entry at all. *(The pivot / horizontal / fib
  annotation overlays are now legended; the referenced-vs-context distinction for
  indicator entries is not yet implemented.)*
- **Why it's beneficial**: The reader needs to know which overlays the strategy
  tested against versus which are kept only for orientation. Reinforces the "what
  did the strategy evaluate" clarity. (NEC-2 keeps the σ sub-pane and the demos do
  carry context overlays — e.g. MA-trend filters plotted from the primitives — so
  the distinction is meaningful.)
- **Thin slice (remaining)**: Add a referenced / context tag (or grouping) to the
  indicator legend entries; pin with an assertion that a strategy-referenced overlay
  is tagged `referenced`. *(Pivot-in-legend is already done.)*
- **Definition of done**: The regenerated report's legend visibly separates
  referenced overlays from context overlays (pivots already included), enforced by a
  green assertion on a referenced overlay's tag.
- **Depends on**: pairs with NEC-4 (the descriptor / legend pairing). The
  annotation-legend dependency (heerwisch `ChartImage.annotationLegend()`) shipped in
  0.55.0-alpha and is consumed (see Upstream dependencies).

## Phase 2 — Proposed ordering

1. **BEN-2** — settle the legend semantics (referenced vs context; pivots in the
   legend) before any golden image is frozen, so the baseline reflects the final
   legend.
2. **BEN-1** — *(gated; opt-in only)* a golden-image comparison over the now-final
   visual contract; depends on NEC-3 and on a settled BEN-2 so baselines aren't
   immediately re-blessed. Any CI wiring requires an explicit author request.

## Cross-phase notes

- **NEC-3 → BEN-1**: NEC-3 (manual + spec scaffolding: `ChartSpec` assertions plus
  a committed manual checklist) is the prerequisite for BEN-1 (golden-image
  automation). NEC-3 defines the visual contract; BEN-1 enforces it with snapshot
  diffing. Phase 1 must land first.
- **NEC-2 → BEN-2**: NEC-2 is resolved to **keep** the σ sub-pane (do not drop the
  overlay), and the demos carry other context overlays too, so BEN-2's scope is the
  full one: visually separate strategy-referenced overlays from context overlays.
  The "pivots in the legend" half of BEN-2 already shipped.
- **NEC-4 ↔ BEN-2**: NEC-4 (necessary: truthful header) and BEN-2 (beneficial:
  referenced-vs-context legend) are the descriptor / legend pair — NEC-4 is the
  correctness floor, BEN-2 the enhancement on top. Sequence NEC-4 before BEN-2.
- **Shared demo-regeneration overhead**: each visual slice (NEC-1, NEC-2, NEC-4,
  BEN-2) still verifies and regenerates its own output as its definition of done —
  slice acceptance is independent. This is purely a *commit-hygiene* note: when
  several visual slices land close together, the committed demo HTML/PNG (large
  files) need only be regenerated once at the end of that group to avoid churning
  the binaries on every intermediate commit. It does not gate any slice's red→green.
- **Q2 repo-side TODOs — promoted into Phase 1**: the two items flagged from the
  Q2 follow-up are confirmed and folded in as **NEC-5** (`winsAndLosses` rounding)
  and **NEC-6** (stale window-aggregate javadoc), inserted after NEC-4. NEC-5 is
  independent of the visual track but grouped here for correctness; NEC-6 is a
  doc-only follow-on (may fold into NEC-2). Both are sourced from code-level
  reconciliation (scope (c) in the header), not from demo output.
- **Upstream (ha-track) dependencies** — **Status: RELEASED & CONSUMED.** ha-track
  `0.55.0-alpha` shipped the additive API and wichtelm-app now pins it
  (`<hatrack.version>0.55.0-alpha`). The four upstream-gated consumptions below are
  done; the remaining gaps in each NEC item are wichtelm-app-internal (see per-item
  notes). Demo reports regenerated.
  - **NEC-5 → frau-holle `BacktestMetrics` — DONE**: `winsAndLosses` now reads
    `winningTrades()` / `losingTrades()` directly; the `Math.round(winRate ×
    numTrades)` round-trip and the line-400 TODO are gone. Counts reconcile with
    `numTrades` and the trade list (break-even = loss, per the library invariant).
  - **NEC-1 → heerwisch / jfreechart — DONE (no upstream change)**: the 0.55 driver
    renders `Annotation.PivotPointLevels` (STANDARD P / R1–R3 / S1–S3). The caller
    (`pivotAnnotations` + `builder.addAnnotation`) already added them correctly — no
    workaround existed to remove — so the pivot lines now appear (the pivot demo's
    legend went from empty to populated). A render-boundary assertion (NEC-3) is
    still owed to guard against future regressions.
  - **BEN-2 → heerwisch — partially DONE**: pivot / horizontal / fib overlays are now
    surfaced in the legend strip via `ChartImage.annotationLegend()` (label + colour
    swatch, grouped behind a divider, `leg-annotation` class). The *referenced-vs-
    context* tagging of indicator entries remains open.
  - **NEC-2 → heerwisch — DONE**: `toIndicator` maps `stddev(period)` to
    `Indicator.StdDev(period, CLOSE)` (σ sub-pane); `describeIndicator` reads
    `σ(period)`. The `BollingerBands` stand-in is gone — no `BB(20)` in any
    regenerated report; the showcase-MA frame header now reads `HA candles · σ(20)`.
