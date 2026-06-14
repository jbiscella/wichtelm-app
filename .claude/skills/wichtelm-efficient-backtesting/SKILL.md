---
name: wichtelm-efficient-backtesting
description: "Run wichtelm-app backtests resource-effectively when asked to test strategies in bulk — across many instruments, parameter combinations, windows, or ablation variants. Use this skill BEFORE launching a batch of `wichtelm run` invocations or a `wichtelm sweep`: it is a pre-flight checklist for the cheapest correct way to get the numbers you need (skip report rendering, sweep-once vs shell-loop, right-size parallelism to the box, cache provider data). Backtests are deterministic, so every choice here affects wall-clock time only, never the results."
---

# Running wichtelm-app backtests resource-effectively

When the user asks you to **test a strategy** — especially many of them (a grid of
parameters, a cross-section of instruments, several windows, ablation variants) —
do **not** immediately shell out `java -jar wichtelm.jar run` once per case with a
full HTML report. First spend ten seconds on the checklist below and pick the
cheapest path that still produces the numbers being asked for.

## The one invariant that makes optimization free

Per the spec (CLAUDE.md §1) the DSL runtime is **single-threaded per backtest** and
fully deterministic: identical strategy + config + data → identical result, every
time, regardless of how the run is scheduled. So **resource choices only change
wall-clock time and machine load — never the reported metrics.** Optimize
aggressively; you cannot change an answer by running it more cheaply.

## Pre-flight checklist (decide before launching the batch)

### 1. Do you need the HTML report, or just the metrics?
Generating the report is the **single biggest per-run cost**: the report renders a
chart *per trade* (heerwisch/JFreeChart), so a strategy with hundreds of trades pays
hundreds of chart renders — often more than the backtest itself.

- **Need only return / drawdown / Sharpe across many runs** → pass
  `--no-report --dump-equity`. This writes the per-bar equity CSV
  (`time,equity,cash,position_value`, CLAUDE.md §2.1) with no chart rendering;
  compute total return, max drawdown and Sharpe from that series yourself.
- **Need the trade-level metrics** (win rate, profit factor, avg win, avg loss —
  these need the trade list and are **not** in the equity CSV; Sortino and Calmar,
  by contrast, *are* derivable from the per-bar equity series) → you do need the
  report, but generate it only for the *final* configs you care about, not for
  every cell of an exploratory grid.
- **Never** generate a full report just to regex out return / drawdown / Sharpe
  (those come from `--dump-equity`) — reserve it for when you genuinely need the
  trade-level cards.

### 2. Varying parameters on the same instrument + window + data? Use `wichtelm sweep`.
`wichtelm sweep` (CLAUDE.md §18) loads the market data **once** and re-runs only the
per-parameter phase for every grid combination — and ranks them for you. A hand-rolled
bash/Python loop over `wichtelm run` reloads the data and pays a fresh JVM cold-start
for every combination. Prefer a `[sweep]` table + `wichtelm sweep` whenever the only
thing changing is parameter values. (It is still single-threaded — §1 — so this is
about avoiding redundant data loads and JVM churn, not multi-core.)

### 3. Bulk across instruments? Cache the provider data to CSV once.
Snapshot the data once with the EODHD driver into **local** CSVs (`data_source = "csv"`),
then run everything offline against them. Re-hitting the EODHD HTTPS API per run is
slow, rate-limited, and (for licensed data) needlessly re-downloads the same bars.
Reuse that local cached CSV set across the whole batch — but **keep EODHD-derived
CSVs and reports out of git**: provider data is licensed and must stay local (see
`demo/README.md` / CLAUDE.md §17; only the synthetic demo fixtures are committed).

### 4. Parallelism: size workers to the box, not to the core count.
Each `wichtelm run` is a **separate JVM**, and each JVM is itself multi-threaded
(GC + JIT compiler threads + chart rendering). Empirically, ~**4 concurrent runs
already drive an 8-core box to a load average of ~10** — i.e. it saturates near
`cores / 2` concurrent runs, not `cores`. Setting workers = core count
**oversubscribes and thrashes** (context-switch contention), running slower overall.

- Start around `max(2, cores // 2)` concurrent runs.
- Check actual saturation with `uptime` / `load average`; target load ≈ cores, and
  back off if it climbs well past that.
- Memory is rarely the limit (a handful of JVMs on multi-year 1h data fit in a few
  GiB), so CPU/load is the constraint to watch.

### 5. Many short runs? Trim JVM startup.
For hundreds of small backtests, cold JVM start + JIT warmup dominates each run.
Two levers (in order of impact):
- **Amortize the JVM** — `wichtelm sweep` runs an entire grid in one process (see #2).
  Whenever the work fits a sweep, this is the biggest single win.
- **Cheaper startup per process** — for the genuinely one-run-per-invocation cases,
  `JAVA_TOOL_OPTIONS="-XX:TieredStopAtLevel=1 -XX:+UseSerialGC"` cuts JIT/GC startup
  cost for short-lived JVMs (at the expense of peak throughput you won't reach in a
  short run anyway).

## Quick decision table

| You are asked to… | Cheapest correct path |
|---|---|
| Tune parameters on one instrument | `[sweep]` table + `wichtelm sweep` (loads data once, ranks for you) |
| Screen N instruments on a fixed strategy | cache CSVs once; `--no-report --dump-equity`; ~cores/2 parallel runs |
| Produce a publication-grade report for a chosen config | full `wichtelm run` (report on) — but only for that config |
| Compare ablation variants across a cross-section | one strat file per variant; `--no-report --dump-equity`; parallel by instrument |
| Re-run an existing study | reuse its cached CSV fixtures; don't re-download |

## Anti-patterns to call out

- A bash loop over `wichtelm run` for a parameter grid (use `sweep`).
- Full HTML reports generated solely to scrape return / drawdown / Sharpe (use
  `--dump-equity`; only the trade-level metrics — win rate, profit factor, avg
  win/loss — actually require the report).
- Your orchestration pool size (the bash/Python harness that fans out `wichtelm run`
  calls) set to the core count (oversubscribes; each run is a multi-threaded JVM —
  wichtelm itself is single-threaded, so the parallelism is yours to size).
- Re-fetching EODHD data on every run instead of caching to CSV.

When you spot one of these in a request or an existing harness, say so and propose the
cheaper path before launching — the results are identical, only the time and load change.
