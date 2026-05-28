<p align="center">
  <img src="wichtelm-app-logo.png" alt="wichtelm-app" width="420">
</p>

# wichtelm-app

<p align="center">
  <a href="https://github.com/jbiscella/wichtelm-app/actions/workflows/ci.yml"><img alt="CI" src="https://img.shields.io/github/actions/workflow/status/jbiscella/wichtelm-app/ci.yml?branch=main&style=flat-square&logo=githubactions&logoColor=white&label=CI"></a>
  <img alt="Java 25" src="https://img.shields.io/badge/Java-25-ED8B00?style=flat-square&logo=openjdk&logoColor=white">
  <img alt="Build: Maven" src="https://img.shields.io/badge/build-Maven-C71A36?style=flat-square&logo=apachemaven&logoColor=white">
  <img alt="Version" src="https://img.shields.io/badge/version-0.42.0--alpha-blue?style=flat-square">
  <a href="LICENSE"><img alt="License: 0BSD" src="https://img.shields.io/badge/license-0BSD-2ea44f?style=flat-square"></a>
  <img alt="Domain: backtesting" src="https://img.shields.io/badge/domain-backtesting-7B61FF?style=flat-square">
  <img alt="Charts: Heikin-Ashi" src="https://img.shields.io/badge/charts-Heikin--Ashi-0E7C66?style=flat-square">
</p>

`wichtelm-app` is an end-user **backtesting application** for trading
strategies on a single financial instrument using historical OHLC data, built
around **Heikin-Ashi (HA)** candles as its visual and analytical backbone:
every chart in the HTML report is rendered with HA candles, and the strategy
DSL exposes first-class HA pattern primitives (doji, strong candles, bullish /
bearish reversals) alongside the usual RSI, MACD, moving-average and
pivot-point families.

You write a strategy as a plain-text `.strat` file in a natural-language-like,
Gherkin-conformant DSL, point the `wichtelm` CLI at a TOML config file that
says *what to run* and *against which data*, and you get back a self-contained
HTML report with aggregate performance metrics and per-condition visual
breakdowns.

No Java required. The application is built on top of the `ha-track`
(Heikin-Ashi-track) libraries published to Maven Central under the
`net.jacopobiscella` namespace.

> **Who it is for:** developers, power users, and data analysts who are
> comfortable on the command line and want to express strategies in readable
> prose rather than code.

<p align="center">
  <img src="docs/screenshots/report-trade-detail.png" alt="Per-trade chart frame from a generated HTML report" width="820"><br>
  <sub><em>A per-trade card from a generated report — HA candles, strategy-referenced SMA / EMA overlays on the main pane, σ sub-pane, entry / exit markers, dashed Entry / Stop / Exit reference lines, and the referenced-vs-context legend.</em></sub>
</p>

---

## Table of contents

- [What it does](#what-it-does)
- [Requirements](#requirements)
- [Build and install](#build-and-install)
- [Quick start](#quick-start)
- [CLI reference](#cli-reference)
- [Writing a strategy — the `.strat` DSL](#writing-a-strategy--the-strat-dsl)
- [Authoring help — the Claude chat skill](#authoring-help--the-claude-chat-skill)
- [The TOML config file](#the-toml-config-file)
- [Configuration precedence](#configuration-precedence)
- [The HTML report](#the-html-report)
- [Exit codes and errors](#exit-codes-and-errors)
- [Worked example](#worked-example)
- [Demo](#demo)
- [Limitations (v1 scope)](#limitations-v1-scope)
- [Development](#development)
- [Disclaimer](#disclaimer)
- [License](#license)

---

## What it does

1. **Parses** your `.strat` strategy file, enforcing a strict set of
   parse-time validation rules so mistakes are caught before any data is
   loaded.
2. **Parses** your TOML config file (date range, symbol, data source,
   position sizing, parameter overrides).
3. **Loads** historical OHLC bars for the primary timeframe — and for any
   higher timeframes your strategy references — from a local CSV file or the
   EODHD HTTPS API.
4. **Runs** the backtest single-threaded, evaluating your strategy's
   scenarios bar by bar and emitting buy/sell/close signals.
5. **Generates** a timestamped, self-contained HTML report: aggregate
   metrics, one visual box per scenario, the full trade list, and the equity
   and drawdown curves.

The strategy AST is immutable; multi-timeframe references are resolved in a
**lookahead-safe** way (a higher-timeframe value at primary bar `T` always
comes from the most recently *closed* higher-timeframe bar).

---

## Requirements

| Requirement | Value |
|---|---|
| Java | 25 (JDK, not just a JRE — `jpackage` is used at build time) |
| Build tool | Maven 3.9+ |
| Network | Only needed at build time (to fetch dependencies) and at run time if you use the `eodhd` data source |

---

## Build and install

Clone the repository and build with Maven:

```sh
git clone https://github.com/jbiscella/wichtelm-app.git
cd wichtelm-app
mvn clean package
```

The `package` phase produces these artifacts in `target/`:

| Artifact | What it is |
|---|---|
| `target/wichtelm.jar` | The executable JAR. All permissively-licensed dependencies are shaded in; the LGPL JFreeChart library is **not** — see [License](#license) |
| `target/lib/jfreechart.jar` | The LGPL JFreeChart library, loaded at runtime via the JAR's manifest `Class-Path`. Must stay next to `wichtelm.jar` (as `lib/jfreechart.jar`) when the JAR is copied or distributed |
| `target/dist/wichtelm/` | A native CLI launcher app-image produced by `jpackage` (bundles both JARs); the executable is `target/dist/wichtelm/bin/wichtelm` |

You can run the tool either way:

```sh
# via the executable JAR
java -jar target/wichtelm.jar --help

# via the native launcher
target/dist/wichtelm/bin/wichtelm --help
```

To make `wichtelm` available everywhere, put the launcher's `bin` directory on
your `PATH`, or define a shell alias:

```sh
alias wichtelm='java -jar /absolute/path/to/wichtelm-app/target/wichtelm.jar'
```

The rest of this document writes `wichtelm` for brevity; substitute whichever
form you installed.

---

## Quick start

```sh
# 1. Validate a strategy file without running anything
wichtelm validate my-strategy.strat

# 2. Run a backtest described by a TOML config
wichtelm run my-backtest.toml

# 3. Run, but skip HTML report generation
wichtelm run my-backtest.toml --no-report

# 4. Run and override the output directory
wichtelm run my-backtest.toml --output-dir ./reports
```

A successful `run` prints the path of the report it wrote:

```
Backtest complete; report written to ./reports/my-backtest_2026-05-19T14-30-00.html
```

---

## CLI reference

```
Usage:
  wichtelm run <config-file> [--no-report] [--output-dir <path>]
  wichtelm validate <strat-file>
  wichtelm --version
  wichtelm --help
```

| Command | Effect |
|---|---|
| `wichtelm run <config-file>` | Runs a backtest using the strategy and parameters declared in the TOML config file, then writes an HTML report |
| `wichtelm run <config-file> --no-report` | Runs the backtest but does not produce an HTML report |
| `wichtelm run <config-file> --output-dir <path>` | Runs the backtest and writes the report to `<path>`, overriding the `[output].directory` set in the config file |
| `wichtelm validate <strat-file>` | Parses the strategy file and reports parse-time errors; does **not** run a backtest or load any data |
| `wichtelm --version` | Prints the application version |
| `wichtelm --help` | Prints CLI usage |

---

## Writing a strategy — the `.strat` DSL

A `.strat` file is written in a **Gherkin-conformant** DSL: it uses only the
standard Gherkin keywords (`Feature`, `Background`, `Scenario`, `Given`,
`When`, `Then`, `And`, `But`). Domain-specific clauses are expressed as plain
text after a standard keyword — no custom keywords are introduced.

> 💡 **Prefer to write strategies with an assistant?** A ready-made
> [Claude chat skill](#authoring-help--the-claude-chat-skill) lives under
> [`skills/wichtelm-strategy-author/`](skills/wichtelm-strategy-author/) — it
> teaches the grammar, the full catalog, and every validation rule, and can
> build a strategy with you clause by clause.

### File structure

| Section | Cardinality | Purpose |
|---|---|---|
| `Feature:` header | exactly 1 | Names the strategy |
| Description block | 0 or 1 | Free text; **must** contain `Primary timeframe: <TF>`; **may** contain `Parameter` declarations |
| `Background:` | 0 or 1 | Declares named series (including higher-timeframe series) |
| `Scenario:` blocks | 1 or more | Each scenario emits exactly one trading condition |

### The four first-class conditions

Every scenario **must** end with `Then <condition>`, where the condition is one
of:

| Condition | Meaning |
|---|---|
| `long_entry` | Open a long position (Buy) |
| `long_exit` | Close a long position |
| `short_entry` | Open a short position (Sell) |
| `short_exit` | Close a short position |

### Parameters

Declared in the Feature description block; the type is inferred from the
literal:

```gherkin
Parameter rsi_period default 14      # integer
Parameter stop_loss_pct default 2.5  # decimal (BigDecimal, DECIMAL64)
```

Parameter values in the `.strat` file are **defaults** — the TOML config may
override any of them at run time.

### Background series and multi-timeframe

```gherkin
Background:
  Given a series trend defined as ema(trend_period) on 1d
  And a series rsi_value defined as rsi(rsi_period)
```

A series declared `on <higher-TF>` is evaluated against the most recently
*closed* bar of that timeframe — the runtime enforces this lookahead-safety
unconditionally. A series without `on <TF>` is a named expression on the
primary timeframe.

### Expressions

| Element | Syntax | Examples |
|---|---|---|
| Comparison | English prose | `crosses below`, `crosses above`, `is above`, `is below`, `drops below`, `rises above`, `exceeds` |
| Arithmetic | math notation | `+ - * /` and parentheses, standard precedence |
| Variables | bare identifiers | `close`, `volume`, `entry_price` |
| Functions | name with parentheses | `rsi(14)`, `ema(200)`, `highest(close, 10)` |
| Boolean composition | Gherkin `And` / `But` | steps are AND-ed; express OR by duplicating the scenario |

### Built-in function / indicator catalog

The DSL grammar defines the catalog below. **Not every entry is evaluable by
the backtest runtime yet** — entries marked *runtime pending* parse and
validate but fail at runtime if a backtest actually reaches them (indicator
functions raise a `DslEvaluationException`; `bar_time` raises an
unresolved-identifier error). Stick to the runtime-supported entries for a
strategy that runs end to end.

**Runtime-supported today:**

| Category | Available |
|---|---|
| Market variables | `open`, `high`, `low`, `close`, `volume`, `bar_index` |
| Base indicators | `sma(period)`, `ema(period)`, `rsi(period)`, `atr(period)`, `stddev(period)` |
| Trade-context variables | `entry_price`, `position_size` (exit scenarios and `And with` clauses only); `atr_value(period)` (the frozen-at-fill ATR accessor — `And with stop_loss` / `And with take_profit` clauses only) |

**Parser-accepted but *runtime pending* (will fail during a backtest):**

| Category | Names |
|---|---|
| Market variables | `bar_time` |
| Composite | `macd(fast, slow, signal)` |
| Window aggregates | `highest(<expr>, period)`, `lowest(<expr>, period)`, `avg_volume(period)` |
| Heikin-Ashi primitives | `ha_bullish_reversal(streak)`, `ha_bearish_reversal(streak)`, `ha_strong(...)`, `ha_doji(...)` |
| Price / MA primitives | `price_above_ma(...)`, `price_crosses_ma(...)` |
| RSI level primitives | `rsi_crosses_50()`, `rsi_overbought(threshold)`, `rsi_oversold(threshold)` |
| MACD primitives | `macd_bullish_cross()`, `macd_bearish_cross()`, `macd_zero_cross_up()`, `macd_zero_cross_down()` |

The catalog is closed in v1; the *runtime pending* entries are wired into the
evaluator in subsequent increments.

### Stop-loss and take-profit

A scenario ending in `Then long_entry` or `Then short_entry` may append one or
both of:

```gherkin
And with stop_loss at entry_price * (1 - stop_loss_pct / 100)
And with take_profit at entry_price * (1 + take_profit_pct / 100)
```

The expression is snapshotted at the entry's fill time and monitored intrabar.
It may reference **only** constants, parameters, trade-context variables, and
`atr_value(period)` — the frozen-at-fill ATR accessor admitted as the sole
function exception (e.g. `entry_price - 2 * atr_value(14)`). Other indicators,
window aggregates, and background series are not allowed.

### Canonical example

```gherkin
Feature: Mean Reversion with Trend Filter
  Primary timeframe: 1h
  Parameter rsi_period default 14
  Parameter oversold default 30
  Parameter overbought default 70
  Parameter trend_period default 200
  Parameter stop_loss_pct default 2
  Parameter position_size_pct default 50

  Background:
    Given a series trend defined as ema(trend_period) on 1d
    And a series rsi_value defined as rsi(rsi_period)

  Scenario: Enter long on oversold mean reversion
    Given no open position
    When rsi_value crosses below oversold
    And close is above trend
    Then long_entry
    And with stop_loss at entry_price * (1 - stop_loss_pct / 100)

  Scenario: Exit long on overbought
    Given a long position is open
    When rsi_value crosses above overbought
    Then long_exit

  Scenario: Exit long on price floor
    Given a long position is open
    When close drops below entry_price * 0.95
    Then long_exit

  Scenario: Enter short on overbought reversal
    Given no open position
    When rsi_value crosses above overbought
    And close is below trend
    Then short_entry
    And with stop_loss at entry_price * (1 + stop_loss_pct / 100)

  Scenario: Exit short on oversold
    Given a short position is open
    When rsi_value crosses below oversold
    Then short_exit
```

A copy of the canonical strategy lives at
`src/test/resources/strategies/canonical.strat`.

### Validation rules (P1–P22)

The parser enforces 22 parse-time rules. A violation throws a
`StrategyParseException` carrying the file path, line, column, and the
violated rule identifier. Highlights:

- **P1/P2** — exactly one `Feature:`; exactly one `Primary timeframe:`.
- **P4/P7/P22** — parameter names, background series names, and scenario names
  must each be unique within a strategy.
- **P8** — a higher-timeframe background series must use a timeframe strictly
  higher than the primary.
- **P10** — every scenario must end with one of the four first-class
  conditions.
- **P12/P16** — `stop_loss`/`take_profit` clauses are allowed only on entry
  scenarios and may not reference indicators or background series, except
  `atr_value(period)` — the frozen-at-fill ATR accessor.
- **P13/P14** — every identifier and function call must resolve to a known
  variable, parameter, series, or built-in.
- **P18–P20** — the opening `Given` must be semantically consistent with the
  terminating `Then` (e.g. `Given no open position` must end with an entry).

Run `wichtelm validate <file>` to check a strategy against all rules without
running a backtest.

---

## Authoring help — the Claude chat skill

Writing the DSL by hand means juggling a closed function catalog and 22
parse-time rules. To make that easier, the repo ships a **[Claude](https://claude.ai)
chat skill** (for claude.ai, *not* Claude Code) at
[`skills/wichtelm-strategy-author/`](skills/wichtelm-strategy-author/). Point
Claude at it and it will help you write, review, and debug strategies — plus
the TOML config and reading the report.

The skill contains:

- **`SKILL.md`** — the authoring workflow and the rules that most often trip
  people up.
- **`reference/`** — the complete function/indicator catalog with exact
  arities, the full grammar, all 22 validation rules (P1–P22) with the exact
  parser error messages, and the TOML / CLI / report guide.
- **`reference/guided-builder.md`** — a menu-driven flow that builds a strategy
  **clause by clause**: it asks one question at a time, offers only valid
  choices, and assembles + validates the `.strat` as you go.
- **`examples/`** — six ready-to-adapt strategies pairing Heikin-Ashi with a
  confirming indicator (HA + RSI, HA + 200-EMA + ATR stop, HA + MACD,
  HA + moving-average crossover, multi-timeframe HA), plus the canonical
  reference. Illustrative starting points, not tuned strategies.

To use it on [claude.ai](https://claude.ai), package the skill as a **`.zip`** whose
root contains the `wichtelm-strategy-author/` folder (with `SKILL.md` inside it). From
the repo root:

```sh
( cd skills && zip -r ../wichtelm-strategy-author.zip wichtelm-strategy-author )
```

Then, on any plan (Free, Pro, Max, Team, or Enterprise): enable **Code execution and file
creation** in **Settings → Capabilities**, open **Customize → Skills**, click **+ → Create
skill**, and upload the `.zip`. The exact menu labels vary by plan/version, so see the Claude
Help Center's [How to create custom Skills](https://support.claude.com/en/articles/12512198-how-to-create-custom-skills)
for the current steps. Once it's added, ask Claude to help you build a strategy.

---

## The TOML config file

A per-backtest config file tells `wichtelm` which strategy to run, against
which symbol and date range, and where the data comes from.

> **TOML note:** every bare key must appear *before* the first `[table]`
> header — otherwise TOML treats it as a key of that table. Keep `strategy`,
> `symbol`, and `data_source` at the top of the file.

```toml
# Required — top-level keys, before any [table] header
strategy    = "strategies/mean-reversion.strat"
symbol      = "AAPL"
data_source = "csv"            # one of "csv" or "eodhd"

# Required: date range
[date_range]
from = 2024-01-01
to   = 2024-12-31

# Required: position sizing
[sizing]
position_size_pct = 50      # 0 < value <= 100
pyramiding        = false   # default false

# Optional: override strategy parameter defaults
[parameters]
rsi_period = 21
oversold   = 25

# Optional: output configuration
[output]
directory = "./reports"
format    = "html"          # only "html" is supported in v1

# Required when data_source = "csv"
[csv]
file = "data/{symbol}_{timeframe}.csv"

# Required when data_source = "eodhd"
[eodhd]
api_token_env = "EODHD_API_TOKEN"
```

### Field rules (C1–C11)

| Rule | Summary |
|---|---|
| C1 | `strategy` is required and must be a readable file path |
| C2 | `symbol` is required and non-empty |
| C3 | `date_range.from` / `date_range.to` are ISO-8601 dates with `from < to` |
| C4 | `data_source` is `"csv"` or `"eodhd"` |
| C5 | `sizing.position_size_pct` is in `(0, 100]` |
| C6 | `sizing.pyramiding` defaults to `false` |
| C7 | `[parameters]` keys must match `Parameter` names declared in the strategy |
| C8 | For `data_source = "csv"`, `[csv].file` is required and **must contain the `{symbol}` placeholder** (the `{timeframe}` placeholder is optional). Placeholders may appear only in the file name, not in a directory component, and the parent directory must exist and be readable |
| C9 | For `data_source = "eodhd"`, `[eodhd].api_token_env` is required and the named environment variable must be set at run time |
| C10 | `output.format` must be `"html"` in v1 |
| C11 | Unknown top-level keys produce a warning, not an error |

A config violation throws a `ConfigParseException` identifying the offending
key and rule.

### CSV data source

The `[csv].file` value is a *pattern*. `{symbol}` is mandatory and `{timeframe}`
is optional; both are substituted at data-load time. For example, with
`symbol = "AAPL"` and a strategy on `1h`, the pattern
`data/{symbol}_{timeframe}.csv` resolves to `data/AAPL_1h.csv`.

> A literal `[csv].file` path without `{symbol}` is rejected at parse time —
> the underlying `frau-holle-csv` driver requires the placeholder.

### EODHD data source

The EODHD HTTPS API token is **never** stored in the config file. Instead you
name an environment variable, and `wichtelm` reads the token from it at run
time:

```sh
export EODHD_API_TOKEN="your-token-here"
wichtelm run my-backtest.toml
```

If the named variable is unset or empty, the run fails with a clear error
naming the variable.

---

## Configuration precedence

The output directory is resolved in this order, highest first:

1. The `--output-dir` CLI flag
2. The per-backtest config's `[output].directory`
3. The current working directory (`.`)

> **Note:** a global preferences file at `~/.config/wichtelm/config.toml`
> (XDG Base Directory standard) is described in the specification but is **not
> read by the CLI in v1** — every backtest takes its `data_source`, output
> directory, and other settings from the per-backtest config file (and
> CLI flags). Global preferences are reserved for a future release.

---

## The HTML report

<p align="center">
  <img src="docs/screenshots/report-overview.png" alt="Top of a generated HTML report: header, strategy summary, and the strategy rules block" width="820"><br>
  <sub><em>Top of a generated report — masthead with the strategy / symbol / window line and the disclaimer, followed by a human-readable strategy-rules summary (a normalized Given / When / Then rendering of each scenario, not the verbatim <code>.strat</code> source).</em></sub>
</p>

Each run writes a new, self-contained HTML file — reports are **never**
overwritten. The file name is:

```
<config-basename>_<timestamp>.html
```

e.g. `my-backtest_2026-05-19T14-30-00.html` (the `:` of the ISO-8601 time is
replaced with `-` for filesystem safety).

The report contains, in order:

1. **Aggregate metrics** — `totalReturn`, `numTrades`, `winRate`,
   `maxDrawdown`, `sharpeRatio`, `sortinoRatio`, `calmarRatio`,
   `profitFactor`, `avgWin`, `avgLoss`. A `profitFactor` of `0` renders as
   "undefined".
2. **Per-scenario boxes** — one box per declared scenario (sorted
   alphabetically), each with a trigger count, a chart per timeframe with a
   marker at every trigger bar, and a sub-report table of trigger times and
   the sub-conditions that held.
3. **Trailing section** — the full equity curve, the derived drawdown curve, a
   tabular trade list (`entryTime`, `exitTime`, `direction`, `entryPrice`,
   `exitPrice`, `pnl_pct`), and a summary of diagnostic counters.

---

## Exit codes and errors

| Exit code | Meaning |
|---|---|
| `0` | Success |
| `1` | Application error (parse failure, data source unavailable, report failure, backtest error) |
| `2` | Usage error (missing or unexpected CLI arguments) |

Most application errors derive from a single root exception,
`WichtelmException`:

| Exception | Raised when |
|---|---|
| `StrategyParseException` | A `.strat` file violates a P-rule (P1–P22) |
| `ConfigParseException` | A TOML config file violates a C-rule (C1–C11) |
| `DslEvaluationException` | A runtime error occurs while evaluating a DSL expression (e.g. division by zero) |
| `DataSourceUnavailableException` | The CSV file or EODHD API cannot be reached or returns malformed data |
| `ReportGenerationException` | HTML rendering fails (e.g. a filesystem write error) |

A `BacktestException` thrown by the underlying `ha-track` backtester is *not*
a `WichtelmException`; the CLI catches it separately and still reports it with
exit code `1`.

Parse errors are printed to stderr with the rule identifier and source
location, for example:

```
StrategyParseException [P10] at my-strategy.strat:14:5 — Scenario must terminate with a first-class condition
ConfigParseException [C8] at my-backtest.toml (csv.file) — [csv].file must contain the {symbol} placeholder ...
```

---

## Worked example

```sh
# project layout
# .
# ├── strategies/mean-reversion.strat
# ├── data/AAPL_1h.csv
# ├── data/AAPL_1d.csv
# └── backtests/aapl-2024.toml
```

`backtests/aapl-2024.toml`:

```toml
strategy    = "strategies/mean-reversion.strat"
symbol      = "AAPL"
data_source = "csv"

[date_range]
from = 2024-01-01
to   = 2024-12-31

[sizing]
position_size_pct = 50
pyramiding        = false

[parameters]
rsi_period = 21

[output]
directory = "./reports"

[csv]
file = "data/{symbol}_{timeframe}.csv"
```

Run it:

```sh
wichtelm validate strategies/mean-reversion.strat
wichtelm run backtests/aapl-2024.toml
# -> Backtest complete; report written to ./reports/aapl-2024_2026-05-19T14-30-00.html
```

Open the resulting HTML file in any browser.

---

## Demo

The [`demo/`](demo/) directory ships seven runnable examples — five "clean"
strategies across four timeframes (weekly, daily, 4-hour, hourly; the daily
timeframe carries two) plus two daily "showcase" strategies — covering the whole
DSL feature surface on synthetic CSV data, each with the HTML report the tool
produced from it. Run the whole pipeline end to end with:

```sh
./demo/run_demo.sh
```

See [`demo/README.md`](demo/README.md) for the demo matrix, and
[`demo/reports/tstx-swing-1d-report.html`](demo/reports/tstx-swing-1d-report.html)
for a sample (multi-timeframe) report.

---

## Limitations (v1 scope)

The following are **not** implemented in v1:

- Visual editor or web UI for strategy authoring
- Real-time / live trading
- Multi-symbol portfolio strategies
- Slippage and commission models
- Dynamic position sizing (ATR-/volatility-proportional)
- Walk-forward optimization and parameter sweeps
- User-defined DSL functions and macros
- Output formats other than HTML
- Boolean and String parameter types
- Indicators or window aggregates inside `stop_loss` / `take_profit` clauses,
  **except** `atr_value(period)` — the frozen-at-fill ATR accessor that
  graduated into stop / take scope in the 0.52 increment
- Trailing stops
- Literal `[csv].file` paths without the `{symbol}` placeholder
- Parallel execution of multiple backtests in one invocation
- A global preferences file (`~/.config/wichtelm/config.toml`) — specified but
  not yet read by the CLI

These are reserved for future additive releases or a future major version.

---

## Development

```sh
mvn clean verify    # compile, run the full Cucumber test suite, build artifacts
mvn test            # run tests only
```

The behavioral specification lives in `CLAUDE.md` and is exercised by the
Cucumber feature files under `src/test/resources/features/`. Each feature file
corresponds to a block of the specification: strategy parsing, config parsing,
multi-timeframe lookahead-safety, signal emission, report generation, and CLI
behavior.

Runtime profile: Java 25, single Maven module, single-threaded backtest
runtime, `BigDecimal` arithmetic with `MathContext.DECIMAL64`, UTC `Instant`
for time. Dependencies are the published `ha-track` artifacts plus the `tomlj`
TOML parser.

---

## Disclaimer

This software is a tool for **historical analysis only**.
It is NOT a trading platform, brokerage, financial advice service,
or investment product.

**No warranties.** The software is provided "AS IS" under its license.
The author makes no warranties regarding accuracy, completeness,
reliability, or fitness for any purpose.

**Past performance is not indicative of future results.** Results
obtained with this software reflect hypothetical performance on
historical data and have inherent limitations:
- They cannot account for all market conditions
- They cannot model unforeseen events
- They cannot guarantee future returns

**Use at your own risk.** Any trading decisions based on output
from this software are made solely at the user's discretion and
risk. The author accepts no liability for any financial losses,
regulatory issues, or other consequences arising from use of this
software.

**No financial advice.** Nothing in this software, its documentation,
its output, or its associated artifacts constitutes financial,
investment, legal, or tax advice. Users should consult qualified
professionals before making investment decisions.

**No regulatory endorsement.** This software is not registered with,
endorsed by, or approved by any financial regulatory authority.
Users are solely responsible for compliance with applicable laws
and regulations in their jurisdiction.

---

## License

`wichtelm-app` is released under the **BSD Zero Clause License (0BSD)** — a
permissive, public-domain-equivalent license. See the [`LICENSE`](LICENSE)
file for the full text, and the generated
[`THIRD-PARTY.txt`](THIRD-PARTY.txt) for the licenses of every dependency.

One dependency, **JFreeChart**, is licensed under the LGPL 2.1. It is
therefore *not* merged into the executable JAR: it ships as a separate
`lib/jfreechart.jar` referenced from the JAR's manifest `Class-Path`, so it
stays independently replaceable as the LGPL requires.
