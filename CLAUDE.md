# CLAUDE.md — `wichtelm-app`

## 0. Goal and scope

`wichtelm-app` is an end-user **backtesting application** for trading strategies on single financial instruments using historical OHLC data.

The user expresses a strategy as a `.strat` text file in a custom Gherkin-conformant DSL, invokes the app via the `wichtelm` CLI with a TOML config file specifying what to run and against what data, and receives a self-contained HTML report with aggregate metrics and per-condition visual breakdowns.

The application is built on top of `ha-track` libraries published to Maven Central under the `net.jacopobiscella` namespace.

**Goal:** allow a user with technical literacy (developer, power user, data analyst comfortable with CLI) to write strategies in a natural-language-like DSL, run them against historical data, and explore performance through structured visual reports, without writing Java.

**Out of scope for v1:** visual editor, web UI, real-time/live trading, multi-symbol portfolio strategies, slippage/commission models, dynamic position sizing, walk-forward optimization, parameter sweep, user-defined DSL functions/macros, output formats beyond HTML.

## 1. Runtime profile

| Aspect | Value |
|---|---|
| Java version | 25 |
| Packaging | single Maven module producing both an executable JAR and a CLI launcher named `wichtelm` |
| Threading | the DSL runtime is single-threaded per backtest. Parallel execution of multiple backtests is not implemented in v1 |
| I/O | reads `.strat` strategy files and TOML config files from local filesystem; loads historical data via `ha-track` data drivers (CSV files or EODHD HTTPS API); writes HTML reports to filesystem |
| External dependencies | published artifacts from `ha-track` (Maven Central), plus a TOML parser library (TBD at implementation time among toml4j, tomlj, 4koma — all permissive licenses) |
| Internal state mutability | parsed strategy AST is immutable. Runtime state (open positions, equity curve, etc.) lives inside the frau-holle BacktestSpec/Backtester contract |

## 2. Public surface

The "public API" of `wichtelm-app` is NOT a Java type catalog — `wichtelm-app` is an end-user application, not a library. The public surface consists of:

### 2.1 CLI surface

| Command | Effect |
|---|---|
| `wichtelm run <config-file>` | runs a backtest using the strategy and parameters declared in the TOML config file |
| `wichtelm run <config-file> --no-report` | runs the backtest without producing the HTML report |
| `wichtelm run <config-file> --output-dir <path>` | overrides the output directory configured globally |
| `wichtelm validate <strat-file>` | parses the strategy file and reports parse-time errors without running a backtest |
| `wichtelm --version` | prints the application version |
| `wichtelm --help` | prints CLI usage |

Exit codes: 0 on success, non-zero on any error. Specific exit codes per error type are TBD at implementation time.

### 2.2 Strategy DSL surface

The grammar of `.strat` files is Gherkin-conformant. See §4 for the full grammar specification.

### 2.3 Config file surface

The grammar of TOML config files is documented in §5.

### 2.4 Global preferences surface

The grammar of `~/.config/wichtelm/config.toml` (user preferences) is documented in §5.4.

## 3. Strategy DSL — semantic model

### 3.1 Paradigm

The DSL is **Gherkin-conformant** — uses only standard Gherkin keywords (Feature, Background, Scenario, Given, When, Then, And, But). Domain-specific clauses such as `And with stop_loss at <expr>` use the standard `And` keyword followed by domain-specific text; no custom Gherkin keywords are introduced.

### 3.2 Strategy structure

A valid `.strat` file MUST contain:

| Section | Cardinality | Content |
|---|---|---|
| `Feature:` header | exactly 1 | feature name followed by description block |
| Description block | 0 or 1 | free-text lines including `Primary timeframe: <TF>` (mandatory) and `Parameter <name> default <value>` declarations (optional, repeatable) |
| `Background:` | 0 or 1 | named-expression declarations of the form `Given a series <name> defined as <expression> on <TF>` (multi-TF series) or `Given a series <name> defined as <expression>` (primary-TF named expression) |
| `Scenario:` blocks | 1 or more | each Scenario emits one named condition; each Scenario MUST terminate with `Then <one of the 4 first-class conditions>` |

### 3.3 First-class conditions

The 4 first-class conditions emitted by Scenarios are:

| Condition | Mapped to frau-holle Signal |
|---|---|
| `long_entry` | `Buy` |
| `long_exit` | `ClosePosition` (close-evaluated) |
| `short_entry` | `Sell` |
| `short_exit` | `ClosePosition` (close-evaluated) |

Every Scenario in the body of the strategy MUST terminate its sequence of steps with `Then <one of the 4 conditions>`. Diagnostic/visualization-only Scenarios are NOT allowed in `.strat` files.

### 3.4 Stop-loss and take-profit clauses

A Scenario terminating with `Then long_entry` or `Then short_entry` MAY have one or both of the following standard Gherkin `And` steps appended:

- `And with stop_loss at <expression>` — declares the price at which the position will close intrabar if reached, monitored via frau-holle `ClosePositionAtPrice`.
- `And with take_profit at <expression>` — declares the price at which the position will close intrabar if reached, monitored via frau-holle `ClosePositionAtPrice`.

The expression is evaluated at the fill time of the entry, snapshotted, and compared against the high/low of subsequent bars until the position closes. The expression may reference: constants, declared `Parameter` values, and trade-context variables (`entry_price`, `position_size`). Indicators, window aggregates, and built-in functions are NOT accepted, with one exception: `atr_value(period)` — the frozen-at-fill ATR accessor (e.g. `entry_price - 2 * atr_value(14)`), evaluated once at the entry fill bar and held constant for the trade. It is valid ONLY inside `stop_loss` / `take_profit` (use `atr(period)` in conditions). (`entry_time` is reserved — see §15.)

Each open position is bound at fill time to the scenario that emitted its Buy/Sell signal; the protective-exit evaluator uses that scenario's stop_loss / take_profit expressions, not the first same-direction entry scenario in source order. Two `long_entry` scenarios with different stops therefore each apply their own stop to the position they opened.

**Warmup suppression.** Because `atr_value(period)` is snapshotted at the entry fill, an entry that matches before its ATR is warm (fewer than `period` bars precede the fill) cannot be given its declared stop. Rather than open a position without its declared protection (misrepresenting the strategy) or abort the backtest, the runtime **suppresses that entry**: no position opens, and the same scenario fires naturally on a later bar once the indicator warms. Each suppressed entry is recorded (bar time, scenario name, reason) and listed in a "Suppressed entries" diagnostics section of the HTML report (§7), so the author can see why early trades are missing.

### 3.5 Multi-timeframe expressions

The primary timeframe is declared in the Feature description block: `Primary timeframe: <TF>`.

Additional timeframes are referenced via `Background` declarations of the form:

```
Background:
  Given a series <name> defined as <expression> on <higher-TF>
```

At runtime, any reference to `<name>` in a Scenario at primary bar time T resolves to the value of `<expression>` computed against the most recently CLOSED `<higher-TF>` bar with `closeTime <= T`. This guarantee is enforced unconditionally and invisibly by the runtime — the DSL does not expose any operator to bypass it.

### 3.6 Expression language

Expressions in Scenarios use the following syntax:

| Element | Syntax | Examples |
|---|---|---|
| Comparison operators | English prose | `crosses below`, `crosses above`, `is above`, `is below`, `drops below`, `rises above`, `exceeds` |
| Arithmetic operators | mathematical notation | `+`, `-`, `*`, `/`, parentheses `( )` with standard precedence (`*` and `/` before `+` and `-`) |
| Variable references | unquoted identifiers | `entry_price`, `rsi_value`, `close`, `volume`, `trend` |
| Function calls | unquoted with parentheses | `rsi(14)`, `ema(200)`, `atr(14)`, `highest_close(10)` |
| Boolean composition between steps | Gherkin `And` / `But` | each step is implicitly AND-ed with the previous. OR-logic is expressed by duplicate Scenarios with the same `Then` |

### 3.7 Built-in DSL function/indicator catalog

| Category | Available |
|---|---|
| Market variables (no parameters) | `open`, `high`, `low`, `close`, `volume`, `bar_time`, `bar_index` |
| Base indicators (from `indicators` ha-track module) | `sma(period)`, `ema(period)`, `rsi(period)`, `atr(period)`, `stddev(period)` |
| Composite indicators (decomposed — see note) | `macd_line(fast, slow, signal)`, `macd_signal(fast, slow, signal)`, `macd_histogram(fast, slow, signal)` |
| Window aggregates (price-source variants — see note) | `highest_high(period)`, `lowest_low(period)`, `highest_close(period)`, `lowest_close(period)`, `avg_volume(period)` |
| HA primitives (from nachtkrapp) — Tier B booleans | `ha_doji()` / `ha_doji(maxBodyRatio)`, `ha_strong()`, `ha_strong_bullish()`, `ha_strong_bearish()`, `ha_bullish_reversal(streak)`, `ha_bearish_reversal(streak)` |
| RSI level primitives (from nachtkrapp) — Tier B booleans | `rsi_overbought(threshold)`, `rsi_oversold(threshold)`, `rsi_crosses_50()` |
| MACD primitives (from nachtkrapp) — Tier B booleans | `macd_bullish_cross()`, `macd_bearish_cross()`, `macd_zero_cross_up()`, `macd_zero_cross_down()` |
| MA trend filter primitives (from nachtkrapp 0.52) — Tier B booleans | `price_above_sma(period)`, `price_below_sma(period)`, `price_above_ema(period)`, `price_below_ema(period)`, `price_crosses_above_sma(period)`, `price_crosses_below_sma(period)`, `price_crosses_above_ema(period)`, `price_crosses_below_ema(period)`, `sma_above_ema(sma_period, ema_period)`, `sma_crosses_above_ema(sma_period, ema_period)`, `sma_crosses_below_ema(sma_period, ema_period)` — `PriceSource.CLOSE`; price-vs-MA via `PriceVsMARule`/`PriceMACrossRule`, MA-vs-MA via `MAVsMARule`/`MACrossMARule` |
| Pivot point primitives (from nachtkrapp 0.52) — Tier B booleans | `price_above_pivot(level)`, `price_below_pivot(level)`, `price_crosses_above_pivot(level)`, `price_crosses_below_pivot(level)` — the lone argument is a **symbolic STANDARD daily pivot level** token (`P`, `R1`, `R2`, `R3`, `S1`, `S2`, `S3`), NOT a numeric period. `PriceSource.CLOSE`; resolved via nachtkrapp `PivotPointRule(1d, STANDARD, CLOSE)`, which aggregates the primary series to daily bars (UTC boundaries) and reads the prior completed day's OHLC. CAMARILLA's `R4`/`S4`, the WOODIE/CAMARILLA variants, and weekly/non-daily periods are out of scope in v1 (a non-STANDARD level token is rejected by P21). The level stays symbolic end-to-end via a dedicated boolean-step path in `ExpressionEvaluator.condition` — it never flows through the numeric arithmetic evaluator. Consequently a pivot primitive is valid ONLY as a complete `When`/`And` condition step (e.g. `When price_above_pivot(R1)`); embedding it in a comparison, in arithmetic, or in a Background series is rejected at parse time (P14) |
| Trade-context variables (in exit Scenarios and `And with` clauses) | `entry_price`, `position_size`, `atr_value(period)` (stop_loss/take_profit only — frozen-at-fill ATR, §3.4) (`entry_time` reserved — see §15) |

Composite indicators are exposed as flat per-component functions in v1: there is no callable `macd` — its components are the three `macd_*` functions listed above, consistent with how every other indicator in the catalog is exposed flat. A field-accessor syntax (`macd(...).macd_line`) was considered and rejected: it would require a postfix-access layer in the expression parser plus indicator-specific parse-time validation, for no gain over flat functions. If field accessors are ever wanted, that is a general parser feature, not a MACD concern.

#### Tier B boolean primitives — semantics and runtime

The 13 boolean primitives listed under "HA / RSI level / MACD primitives" above evaluate to **true / false** rather than a numeric value. They resolve through a **one-shot nachtkrapp `DetectionEngine` pre-pass** built at backtest setup:

1. `NachtkrappMatchIndex.buildFor(strategy, parameters, primarySeries)` walks every Background series expression and every Scenario step text, collects each Tier B function call with its resolved numeric arguments, and builds the corresponding `DetectionRule` (e.g. `ha_doji(0.05)` → `HADojiRule(0.05)`).
2. The index runs **two detection passes**: HA rules against an `HASeries` (computed via `HeikinAshiCalculator.computeChain(Optional.empty(), primarySeries)`), price / RSI / MACD rules against the OHLCSeries with `PriceSource.CLOSE`.
3. Each per-bar evaluation of a Tier B primitive is an O(1) `Set<Instant>.contains(barTime)` against the prepass result.

The **boolean-step evaluator extension** in `ExpressionEvaluator.condition(...)` allows these primitives to be used as bare boolean steps: `When ha_doji()`, `And rsi_oversold(30)`. If no comparison operator is found, the step text is evaluated as an arithmetic expression — Tier B primitives return `BigDecimal.ONE` / `BigDecimal.ZERO`, treated as truthy / falsy by `signum() != 0`. Numeric expressions that happen to evaluate non-zero are also treated as truthy (same convention as C / Python), but the canonical use is the boolean primitive.

Defaults for 0-arg primitives:

| Primitive | Underlying nachtkrapp rule + defaults |
|---|---|
| `ha_doji()` | `HADojiRule(maxBodyRatio = 0.1)` |
| `ha_strong()`, `ha_strong_bullish()`, `ha_strong_bearish()` | `HAStrongCandleRule(wickTolerance = 0.05, minBodyRatio = 0.6)` — the variants share one detection and filter by the emitted match subtype |
| `rsi_crosses_50()` | `RSILevel50CrossRule(period = 14, PriceSource.CLOSE)` |
| `macd_bullish_cross()` / `macd_bearish_cross()` | `MACDSignalCrossRule(12, 26, 9, PriceSource.CLOSE)` |
| `macd_zero_cross_up()` / `macd_zero_cross_down()` | `MACDZeroCrossRule(12, 26, 9, PriceSource.CLOSE)` |

For non-default thresholds or periods, the strategy author declares an explicit `Background` series (e.g. `Given a series rsi_value defined as rsi(20)`) and builds conditions around it.

The `RSIThresholdRule` carries BOTH overbought and oversold in a single rule; when the DSL only specifies one (`rsi_overbought(70)` OR `rsi_oversold(30)`), the unspecified threshold is filled in with a valid sentinel (70 / 30) and the per-key match-subtype filter selects only the requested side. Two DSL calls that resolve to the same underlying rule are deduplicated in the prepass — a single detection runs and both keys map to filtered subsets of its matches.

Naming convention: where a nachtkrapp rule has a **non-numeric** parameter (e.g. `MAType.SMA` / `MAType.EMA`, bullish / bearish direction), the DSL primitives are split into **flat per-value variants** rather than accepting a string argument. So `ha_strong_bullish` / `ha_strong_bearish` are separate catalog entries; `price_above_sma` / `price_above_ema` are reserved for a follow-up PR (the catalog currently does not list `price_above_ma`).

Window aggregates likewise use hard-coded price-source variants (`highest_high`, `lowest_low`, `highest_close`, `lowest_close`) rather than a generic expression-typed first argument, again consistent with the rest of the catalog being flat. Each takes a single `period` argument and reduces a fixed field over the last `period` bars. A generic expression-typed first argument (`highest(<expr>, period)`) is reserved for a future parser extension if real demand emerges.

The catalog is closed in v1. Adding new built-ins requires a v1.x additive release (japicmp-validated).

### 3.8 Parameter declarations

Strategy parameters are declared in the Feature description block:

```
Parameter <name> default <value>
```

Type is inferred from the default value:

| Default literal | Inferred type |
|---|---|
| Integer literal (e.g. `14`, `200`) | Integer |
| Decimal literal (e.g. `0.95`, `30.5`) | BigDecimal with `MathContext.DECIMAL64` |

Boolean and String parameter types are NOT supported in v1; they are reserved as future additive extensions.

Parameter values declared in the `.strat` are defaults. The TOML config file MAY override any parameter's value at runtime.

### 3.9 Canonical example

The following strategy exercises all design decisions and is the v1 reference for the DSL grammar:

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

## 4. Strategy DSL — parse-time validation rules

Each rule below MUST be enforced at parse time. On violation, the parser throws `StrategyParseException` (see §8) with the rule identifier in `violatedRule`, the file path, line number, column number, and a descriptive message.

| Rule | Description |
|---|---|
| P1 | The file MUST contain exactly one `Feature:` block at the top |
| P2 | The Feature description block MUST contain exactly one `Primary timeframe: <TF>` line. `<TF>` MUST be a recognized `Timeframe` value (per commons.Timeframe.fromWire) |
| P3 | Each `Parameter` line in the description block MUST match the form `Parameter <name> default <value>`. `<name>` MUST be a valid identifier (alphanumeric + underscore, starting with letter or underscore). `<value>` MUST be a numeric literal |
| P4 | Parameter names MUST be unique within a strategy |
| P5 | Each `Background:` step MUST start with `Given` (or `And` continuing a `Given`) |
| P6 | Background series declarations MUST match the form `Given a series <name> defined as <expression>` or `Given a series <name> defined as <expression> on <TF>` |
| P7 | Background-declared series names MUST be unique within a strategy and MUST NOT collide with built-in market variables (open, high, low, close, volume, bar_time, bar_index) or trade-context variables (entry_price, position_size) |
| P8 | Higher-TF references in Background MUST use a TF strictly higher than the primary TF (verified via Timeframe ordering) |
| P9 | Each `Scenario:` block MUST contain at least one step |
| P10 | Each Scenario MUST terminate with `Then <X>` where X is one of: `long_entry`, `long_exit`, `short_entry`, `short_exit` |
| P11 | A Scenario terminating with `Then long_entry` or `Then short_entry` MAY have appended one or both `And with stop_loss at <expr>` and `And with take_profit at <expr>` lines after the `Then` step |
| P12 | A Scenario terminating with `Then long_exit` or `Then short_exit` MUST NOT have `And with stop_loss at` or `And with take_profit at` clauses |
| P13 | Identifiers referenced in expressions MUST resolve to: a built-in market variable, a declared parameter, a declared Background series, a built-in function/indicator (from §3.7), or a trade-context variable (only in exit Scenarios and `And with` clauses on entry Scenarios) |
| P14 | Function calls MUST match the arity and parameter types of the built-in function. Unknown function names produce a parse error |
| P15 | Arithmetic expressions MUST be syntactically valid; unbalanced parentheses produce a parse error |
| P16 | `And with stop_loss at <expr>` and `And with take_profit at <expr>` expressions MUST NOT reference built-in functions/indicators (§3.7) or Background-declared series. Only constants, parameters, trade-context variables, and `atr_value(period)` (the sole admitted function — the frozen-at-fill ATR accessor, §3.4) are allowed. A non-integer or non-positive `atr_value` period is rejected by P21 |
| P17 | Trade-context variables (`entry_price`, `position_size`) MUST NOT appear in Scenarios terminating with `Then long_entry` or `Then short_entry` (no position exists at entry time) |
| P18 | A Scenario starting with `Given no open position` MUST terminate with `Then long_entry` or `Then short_entry` (semantic consistency) |
| P19 | A Scenario starting with `Given a long position is open` MUST terminate with `Then long_exit` (semantic consistency) |
| P20 | A Scenario starting with `Given a short position is open` MUST terminate with `Then short_exit` (semantic consistency) |
| P21 | Static numeric range checks: where parameters are passed to functions with known valid ranges (e.g. RSI threshold in (0, 100), period > 0), violations produce a parse error |
| P22 | Scenario names (the text after `Scenario:` in each block) MUST be unique within a strategy. Two Scenarios with the same name in the same `.strat` file produce a parse error. This is symmetric to P4 (unique Parameter names) and P7 (unique Background series names) |

## 5. Config file — TOML grammar

### 5.1 Per-backtest config file

A per-backtest config file is a TOML document. Below is the canonical schema. Fields are documented as required or optional.

```toml
# Required
strategy = "<path-to-strat-file>"
symbol = "<symbol-string>"

# Required: date range
[date_range]
from = <ISO-8601 date>
to = <ISO-8601 date>

# Required: data source selection
data_source = "<source-name>"  # one of: "csv", "eodhd"

# Required: position sizing
[sizing]
position_size_pct = <numeric, 0 < value <= 100>
pyramiding = <boolean>  # default false

# Optional: parameter overrides
[parameters]
<param_name_1> = <value>
<param_name_2> = <value>
# ... matching the Parameter declarations in the strategy file

# Optional: output configuration
[output]
directory = "<path>"  # if absent, uses global config or default
format = "html"  # only "html" supported in v1; reserved for future formats

# Required if data_source = "csv"
[csv]
file = "<path-to-csv-file>"  # name pattern supports {symbol} and {timeframe} placeholders

# Required if data_source = "eodhd"
[eodhd]
api_token_env = "<env-var-name>"  # name of env var holding the token; the token itself is never in this file
```

### 5.2 Config validation rules

| Rule | Description |
|---|---|
| C1 | `strategy` field is required and MUST be a readable file path |
| C2 | `symbol` field is required and MUST be a non-empty string |
| C3 | `date_range.from` and `date_range.to` MUST be valid ISO-8601 dates, with `from < to` |
| C4 | `data_source` MUST be one of the recognized values |
| C5 | `sizing.position_size_pct` MUST be a numeric value in (0, 100] |
| C6 | `sizing.pyramiding` defaults to false if absent |
| C7 | `[parameters]` keys MUST match names declared in the strategy's Parameter declarations. Unknown parameter names produce a parse-time error. Missing parameters use the strategy's declared default |
| C8 | If `data_source = "csv"`, `[csv].file` is required and MUST contain the `{symbol}` placeholder — the `frau-holle-csv` driver rejects literal paths without it, so a literal path is rejected at parse time with a `ConfigParseException`. The `{timeframe}` placeholder is optional. Placeholders MUST appear only in the file name (not in a directory component), and the parent directory MUST exist and be readable. Per-file validation occurs at data-load time, where a missing file raises `DataSourceUnavailableException` |
| C9 | If `data_source = "eodhd"`, `[eodhd].api_token_env` is required and the referenced environment variable MUST be set with a non-empty value (verified at runtime, not parse-time) |
| C10 | `output.format` is restricted to `"html"` in v1. Other values produce a parse-time error |
| C11 | Unknown top-level keys produce a warning (P2 severity), not a parse error, to allow forward compatibility |

### 5.3 Global preferences file

The optional global preferences file at `~/.config/wichtelm/config.toml` (XDG Base Directory standard) MAY contain default values for selected fields:

```toml
[defaults]
output_dir = "<path>"
data_source = "<source-name>"
# ... other defaults TBD

[eodhd]
api_token_env = "EODHD_API_TOKEN"  # default env var name to look up
```

Per-backtest config file values override global preferences.

## 6. Runtime semantics

### 6.1 Execution flow

| Step | Action |
|---|---|
| 1 | Parse strategy file: enforce all P1-P21 rules; produce a parsed strategy AST |
| 2 | Parse config file: enforce all C1-C11 rules |
| 3 | Resolve parameter values: per-config overrides take precedence over strategy defaults |
| 4 | Resolve data source: instantiate `MarketDataSource` (frau-holle-csv or frau-holle-eodhd) and load OHLC bars for the primary TF and any Background higher-TFs |
| 5 | Verify each higher-TF series spans the primary series: its first bar opens at or before the first primary bar, and its last bar closes at or after the last primary bar. Interior gaps from market closures (weekends, holidays) are acceptable — `HigherTimeframeSeries` resolves to the most recently closed bar. Failure raises `DataSourceUnavailableException` |
| 6 | Construct a `SignalGenerator` from the parsed AST; the generator captures all parameters, all Background series (with lookahead-safe accessors), and emits Signal variants based on Scenario evaluation |
| 7 | Construct a `BacktestSpec` with the primary-TF series, the synthesized SignalGenerator, and the normalized initial capital |
| 8 | Run the backtest via `Backtester.run(spec)` |
| 9 | Collect the `BacktestResult` (metrics + trade list + equity curve + diagnostics) |
| 10 | Generate the HTML report unless `--no-report` was passed |
| 11 | Write the report to the resolved output directory |

### 6.2 Signal emission rules

For each primary-TF bar T, the runtime evaluates Scenarios in source order:

| Scenario `Given` clause | When evaluated |
|---|---|
| `Given no open position` | only when no position is open at bar T |
| `Given a long position is open` | only when a long position is open at bar T |
| `Given a short position is open` | only when a short position is open at bar T |

For each Scenario whose `Given` matches the current state, the runtime evaluates the conjunction of `When` and `And` steps. If the conjunction evaluates to true, the runtime emits the Scenario's `Then` signal.

If multiple Scenarios with the same `Then` would fire on the same bar, the first in source order wins (the others are no-op; they may be reported in diagnostics).

If a Scenario with `Then long_entry` has a `And with stop_loss at <expr>` clause, the runtime evaluates `<expr>` at the fill time of the entry, snapshots the result as the stop price, and emits `ClosePositionAtPrice(snapshotted_price, intrabar_time)` on the first subsequent bar whose `low` reaches the stop price (for long) or whose `high` reaches the stop price (for short).

If pyramiding is enabled and a `long_entry` (or `short_entry`) fires while a position of the same direction is open, the runtime emits `AddToPosition(quantity, direction)` instead of an ignored Buy/Sell.

### 6.3 Priority rules

| Concurrent event in same bar T | Resolution |
|---|---|
| Intrabar stop_loss/take_profit AND close-evaluated exit Scenario both trigger | stop_loss/take_profit wins (intrabar precedes close) |
| Intrabar stop_loss AND take_profit both within `[low, high]` of the same bar | stop_loss wins (pessimistic convention — the backtest assumes the worse fill, mirroring industry tooling) |
| Multiple exit Scenarios match in source order | first in source order wins |
| Multiple entry Scenarios match (no position open, multiple matching `Given no open position` Scenarios) | first in source order wins |

## 7. HTML report — structure

### 7.1 Filename

The report file is written to the resolved output directory with the following naming convention:

```
{config_basename}_{timestamp}.html
```

Where:
- `<config_basename>` is the filename of the per-backtest config file without its extension (e.g. `my_backtest.toml` → `my_backtest`)
- `<timestamp>` is an ISO-8601 datetime with filesystem-safe `-` replacing the `:` separators (e.g. `2026-05-18T14-30-00`)

Reports are NEVER overwritten — every run produces a new file.

The report is styled against the finalized design system in `src/main/resources/report/template.css`: Inter sans for body, JetBrains Mono for numerics, off-white surface (`#fafaf7`), oklch-defined semantic colours (`--win`, `--loss`, `--long`, `--short`, `--accent`). The CSS targets the page chrome — header, metrics grid, equity / drawdown panels, trade summary rows, expanded body, chart frames, footer — **not the chart contents themselves**. Chart contents are produced by the heerwisch-jfreechart driver and embedded as raster images inside the styled frames, deliberately accepting a visual mismatch between the polished template typography and the JFreeChart-native chart palette.

### 7.2 Header band

The header carries the inline monochrome wichtelm-app logo, the wordmark `wichtelm-app · backtest report · v<version>`, the page title `Backtest report`, and a single mono-spaced line `Strategy <name> · Symbol <symbol> · Window <from → to> · Bars <primary tf>(<multi-TF descriptor>)`. The right edge of the title row shows the generation timestamp. A condensed `NOT financial advice · Past performance is not indicative of future results · Use at your own risk` disclaimer sits below the row.

### 7.3 Aggregate metrics

Ten cards in a 2 × 5 grid, each with a small uppercase label, a large monospace primary value (semantic-coloured for total return and max drawdown), and a small muted context line. Cards: Total return, Trades, Win rate, Max drawdown, Sharpe, Sortino, Calmar, Profit factor, Avg win, Avg loss. Profit factor renders as `—` when `numTrades == 0`.

### 7.4 Equity curve and drawdown

Two panel frames stacked vertically immediately after the metrics. Each frame has a panel header (title left, context label right — `indexed · base 100.0` for the equity curve, `% from peak` for the drawdown). The chart bodies are hand-rendered SVG (these predate the JFreeChart integration and match the template aesthetic): monthly X-axis ticks, 5%-step Y grid, dashed reference line at 100 for the equity curve, filled red area under the drawdown curve.

### 7.5 Trade-by-trade breakdown — chronological list

The body is a **single chronological list of every trade** in the backtest, all rendered as native HTML5 `<details>` elements collapsed by default. **Zero JavaScript.**

Closed trades come first, sorted strictly by entry timestamp ascending; the still-open position at end-of-series, if any, is appended last with a `still open` tag. Trade ordinals (`#01`, `#02`, …) are zero-padded to the digit count of the total.

#### Collapsed summary row

| Cell | Content |
|---|---|
| Ordinal | `#NN` |
| Direction pill | `long` (green soft fill) or `short` (red soft fill) — both monospace, uppercase |
| Time range | `YYYY-MM-DDTHH:MMZ → YYYY-MM-DDTHH:MMZ` over a duration line `N sessions · Mh in position` |
| Price range | `<entry> → <exit>` |
| P/L | signed percent, semantic-coloured, with a `price <±X.XX%>` sub-line. For open trades, replaces the sub-line with a `STILL OPEN` tag |
| Chevron | rotates 180° when expanded |

Directly under the summary, a compact monospace **conditions row** lists the entry Scenario's When/And step expressions and the exit Scenario's, separated by `→`. Each step is followed by a green `✓` (every step holds at trigger time by construction). For forced-close exits the exit term shows `stop_loss / take_profit`; for open trades it shows `still open at window end`.

#### Expanded body

Per-trade stats grid (6 columns): **Entry · Exit · Hold · P/L · MFE · MAE**. Hold is rendered as `N × <tf> bars`. **MFE** (maximum favourable excursion) and **MAE** (maximum adverse excursion) are computed at report generation time by walking the primary bars within the trade window:
- LONG: `MFE = max((bar.high − entry) / entry)`, `MAE = min((bar.low − entry) / entry)`
- SHORT: `MFE = max((entry − bar.low) / entry)`, `MAE = min((entry − bar.high) / entry)`

Both rendered as signed percent, MFE in semantic green, MAE in semantic red, P/L semantic.

Below the stats, a scenario row spells out the full entry and exit Scenario names. For open trades the exit name is `still open`; for forced-close exits it is `stop_loss / take_profit` in monospace.

Below the scenario row come **one or two chart frames**:

- **Price · primary** — the primary-TF chart over the window `[entry − max(P × 1.5, 30) bars, exit + 10 bars]` where `P` is the largest indicator period referenced by the entry / exit Scenarios on the primary timeframe. Frame header lists the chart contents (HA candles + main-pane overlays + RSI sub-pane when applicable). The chart image itself is produced by heerwisch-jfreechart and contains BOTH the main pane (HA candles + SMA / EMA overlays) AND any sub-pane indicators (RSI, ATR, MACD, …) in a single image with a shared X axis — `Indicator.RSI` is constructed with `RsiVisualization.DANGER_ZONES_ON` so the sub-pane already has bounded `[0, 100]` Y axis, semantic threshold lines and shaded danger zones. Entry and exit bars are annotated with `Annotation.EntryExitMarkerAuto`, which auto-positions the glyph outside the bar (below low for entries, above high for exits) per industry convention. The glyph follows the **direction-of-capital-flow matrix**:

  | Trade event   | Scheduled (Scenario-driven) | Forced (stop_loss / take_profit / end-of-series) |
  |---|---|---|
  | `LONG_ENTRY`  (buying)            | `UP_TRIANGLE`   | — (entries are always scheduled) |
  | `SHORT_ENTRY` (selling)           | `DOWN_TRIANGLE` | — (entries are always scheduled) |
  | `LONG_EXIT`   (selling to close)  | `DOWN_TRIANGLE` | `ARROW_DOWN` |
  | `SHORT_EXIT`  (buying to close)   | `UP_TRIANGLE`   | `ARROW_UP`   |

  The held interval is shaded with `Annotation.TimeRangeHighlight(fillColor, opacity = 0.15)` using the **outcome-oriented** `FillColor` variants — `WIN` (green) for winning closed trades, `LOSS` (red) for losing closed trades, `OPEN` (muted grey) for the still-open position. This matches the TradingView Strategy Tester convention: a trader scanning the report should see at a glance which trades won and which lost, and direction is already encoded in the `LONG` / `SHORT` pill on each trade summary row. The direction-oriented `LONG_POSITION` / `SHORT_POSITION` `FillColor` variants are deliberately not used here. The marker colors above remain direction-based (industry standard for entry / exit markers). The frame's footer shows `▲ entry <ts> · in position · Mh · exit <ts> ▼` (or the open-trade variant `mark <ts> · window end`).
- **Background · higher-TF** (rendered once per distinct higher timeframe referenced by either Scenario): the higher-TF chart over the equivalent window on that timeframe. Frame footer shows the date span and a neutral `higher-timeframe context` label (open trades append `· trade still open`). A direction-aligned trend-filter verdict was deliberately dropped — the generator does not yet evaluate the entry conditions, so it cannot truthfully assert which higher-TF series acted as a trend filter or which way it was checked.

The heerwisch chart images are deliberately produced **as the chart engine renders them today**. The styled frame around the image — header, footer, typography, palette — matches the design system; the chart contents themselves carry JFreeChart's native rendering. This is an accepted visual mismatch.

### 7.6 Suppressed entries (diagnostics)

When one or more entries were suppressed for indicator warmup (§3.4), a "Suppressed entries" section renders after the trade list: a table of `bar time · scenario · reason` (e.g. `ATR not warm: needs 14 pre-fill bars, only 6 available`), introduced by a note that these entries are deferred rather than lost. The section is omitted entirely for a clean run (zero suppressions), so it never adds noise to a normal report.

### 7.7 Footer

`Strategy · Symbol · Bars` left, `wichtelm-app <version> · <date>` right. Below: the full disclaimer covering hypothetical-results / past-performance / look-ahead-bias / no-liability language.

## 8. Exception hierarchy

All exceptions raised by `wichtelm-app` extend a single root type, mirroring the convention of other ha-track modules.

| Type | Thrown when |
|---|---|
| `WichtelmException` | abstract root |
| `StrategyParseException extends WichtelmException` | a `.strat` file violates any of P1-P21 (§4). Carries: filePath, lineNumber, columnNumber, violatedRule, message, optional suggestion |
| `ConfigParseException extends WichtelmException` | a TOML config file violates any of C1-C11 (§5.2). Carries: filePath, key path, violatedRule, message |
| `DslEvaluationException extends WichtelmException` | a runtime error occurs during DSL evaluation (e.g. division by zero in an expression, NaN propagation). Carries: filePath, lineNumber, barTime, barIndex, expression text, message, optional cause |
| `DataSourceUnavailableException extends WichtelmException` | the data source (CSV file or EODHD API) cannot be reached or returns malformed data not caught by the driver's own validation. Wraps the underlying data-source exception as cause |
| `ReportGenerationException extends WichtelmException` | HTML rendering fails (e.g. filesystem write error, chart driver error). Wraps the underlying exception as cause |

Exceptions thrown by ha-track libraries (BacktestException, MarketDataException, DetectionException, ChartSpecException) are caught at the runtime boundary and either rethrown with added Wichtelm-app context or wrapped in one of the types above.

## 9. Block 1 — Strategy parser validation (Gherkin scenarios)

The following scenarios specify the parse-time validation behavior. They are implemented as Cucumber features in `src/test/resources/features/strategy-parser-validation.feature`.

```gherkin
Scenario: Empty strategy file is rejected by P1
  Given a strategy file with no Feature header
  When the parser reads the file
  Then StrategyParseException is thrown
  And violatedRule is "P1"

Scenario: Missing Primary timeframe is rejected by P2
  Given a strategy file with a Feature header but no Primary timeframe declaration
  When the parser reads the file
  Then StrategyParseException is thrown
  And violatedRule is "P2"

Scenario: Duplicate parameter declarations are rejected by P4
  Given a strategy file with two "Parameter rsi_period default 14" lines
  When the parser reads the file
  Then StrategyParseException is thrown
  And violatedRule is "P4"

Scenario: Scenario not terminating with a first-class condition is rejected by P10
  Given a strategy file with a Scenario that ends with "Then unknown_condition"
  When the parser reads the file
  Then StrategyParseException is thrown
  And violatedRule is "P10"

Scenario: stop_loss clause on a long_exit Scenario is rejected by P12
  Given a strategy file with a Scenario terminating with "Then long_exit"
  And the same Scenario has "And with stop_loss at entry_price * 0.98" appended
  When the parser reads the file
  Then StrategyParseException is thrown
  And violatedRule is "P12"

Scenario: Indicator function in stop_loss expression is rejected by P16
  Given a strategy file with "And with stop_loss at atr(14) * 2"
  When the parser reads the file
  Then StrategyParseException is thrown
  And violatedRule is "P16"

Scenario: Background series referencing a TF lower than primary is rejected by P8
  Given a strategy file with Primary timeframe 1d
  And a Background declaring "Given a series ... on 1h"
  When the parser reads the file
  Then StrategyParseException is thrown
  And violatedRule is "P8"

Scenario: Reference to undeclared identifier is rejected by P13
  Given a strategy file with "When my_undeclared_var crosses below 30"
  When the parser reads the file
  Then StrategyParseException is thrown
  And violatedRule is "P13"

Scenario: A well-formed canonical strategy parses successfully
  Given the canonical example strategy from §3.9
  When the parser reads the file
  Then no exception is thrown
  And the parsed AST contains 6 parameters
  And the parsed AST contains 2 Background series declarations
  And the parsed AST contains 5 Scenarios
  And each Scenario terminates with one of long_entry/long_exit/short_entry/short_exit
```

(Additional Block 1 scenarios for every P-rule are implemented similarly.)

## 10. Block 2 — Config parser validation

`src/test/resources/features/config-parser-validation.feature`

```gherkin
Scenario: Missing strategy field is rejected by C1
  Given a TOML config file with no "strategy" field
  When the parser reads the file
  Then ConfigParseException is thrown
  And violatedRule is "C1"

Scenario: Invalid date range with from >= to is rejected by C3
  Given a TOML config file with from = 2024-12-31 and to = 2024-01-01
  When the parser reads the file
  Then ConfigParseException is thrown
  And violatedRule is "C3"

Scenario: position_size_pct out of range is rejected by C5
  Given a TOML config file with position_size_pct = 150
  When the parser reads the file
  Then ConfigParseException is thrown
  And violatedRule is "C5"

Scenario: Parameter override for undeclared parameter is rejected by C7
  Given a strategy declaring "Parameter rsi_period default 14"
  And a TOML config overriding parameter "unknown_param" = 99
  When the resolver merges parameters
  Then ConfigParseException is thrown
  And violatedRule is "C7"

Scenario: Unknown top-level keys produce a warning, not error (C11)
  Given a TOML config file with an unknown key "experimental_flag = true"
  When the parser reads the file
  Then no exception is thrown
  And a warning is emitted referencing "experimental_flag"
```

## 11. Block 3 — Multi-timeframe lookahead-safety

`src/test/resources/features/multi-tf-lookahead-safety.feature`

```gherkin
Scenario: Higher-TF series at primary bar T resolves to most recent closed bar with closeTime <= T
  Given primary timeframe 1h and a Background series "trend" defined on 1d
  And at primary bar with time 2024-03-15T14:00:00Z
  And the 1d bar covering 2024-03-14 has closeTime 2024-03-15T00:00:00Z
  And the 1d bar covering 2024-03-15 has closeTime 2024-03-16T00:00:00Z
  When "trend" is referenced during evaluation of the 1h bar at 14:00:00
  Then "trend" resolves to the value computed from the 1d bar covering 2024-03-14
  And "trend" does NOT resolve to the value computed from the 1d bar covering 2024-03-15

Scenario: A strategy attempting to access a future higher-TF bar produces no value-leak path
  Given any well-formed strategy with multi-TF Background references
  When the strategy is executed against historical data
  Then for every bar at primary time T and every reference to a higher-TF series, the resolved bar has closeTime <= T
```

## 12. Block 4 — Signal emission and frau-holle mapping

`src/test/resources/features/signal-emission.feature`

```gherkin
Scenario: long_entry condition emits Buy signal
  Given a strategy with a Scenario terminating with "Then long_entry"
  And no position is open at bar T
  And the Scenario's When/And conjunction evaluates to true at bar T
  When the SignalGenerator emits a signal for bar T
  Then the emitted Signal is Buy
  And the quantity is derived from the configured position_size_pct

Scenario: long_entry with stop_loss snapshots the expression at fill time
  Given a long_entry Scenario with "And with stop_loss at entry_price * 0.98"
  When the entry fills at bar T+1 open with price 100
  Then the snapshotted stop price is 98

Scenario: Intrabar stop_loss emits ClosePositionAtPrice when low reaches the stop
  Given a long position opened at price 100 with stop_loss at 98
  And bar T+2 has open 99, high 99.5, low 97, close 98.5
  When the runtime evaluates bar T+2
  Then a ClosePositionAtPrice signal is emitted with price 98
  And the fillTime is strictly between bar T+1 close and bar T+2 close

Scenario: Stop_loss intrabar wins over close-evaluated exit Scenario in the same bar
  Given a long position opened at price 100 with stop_loss at 98
  And a long_exit Scenario "When close drops below 99"
  And bar T+2 has open 99, high 99.5, low 97, close 98.5
  When the runtime evaluates bar T+2
  Then ClosePositionAtPrice with price 98 is emitted
  And the long_exit Scenario does NOT emit a separate ClosePosition signal

Scenario: pyramiding enabled allows long_entry while long position is open
  Given a strategy declaring pyramiding = true in config
  And a long position is already open at bar T
  And a long_entry Scenario's conjunction evaluates to true at bar T
  When the SignalGenerator emits a signal for bar T
  Then the emitted Signal is AddToPosition with direction LONG
  And the position size grows by the configured per-entry amount
```

## 13. Block 5 — Report generation

`src/test/resources/features/report-generation.feature`

```gherkin
Scenario: A backtest produces a report file with the configured naming convention
  Given a backtest config at "my_backtest.toml"
  And the configured output directory is "./reports"
  When the backtest runs at time 2026-05-18T14:30:00
  Then a file named "my_backtest_2026-05-18T14-30-00.html" exists in ./reports
  And the file is non-empty

Scenario: --no-report flag suppresses report generation
  Given a backtest config file
  When the CLI runs with --no-report
  Then no HTML file is produced
  And the BacktestResult is still computed and accessible

Scenario: The report contains one box per declared Scenario, sorted alphabetically
  Given a strategy with 4 Scenarios named A, C, B, D in source order
  When the report is generated
  Then the report body contains 4 boxes
  And the box order is A, B, C, D

Scenario: A box for a Scenario referencing 2 TFs contains 2 charts
  Given a strategy with a long_entry Scenario referencing primary 1h and Background series on 1d
  When the report is generated
  Then the box for that Scenario contains exactly 2 charts
  And one chart is labeled "1h" and the other "1d"
```

## 14. Block 6 — CLI behavior

`src/test/resources/features/cli-behavior.feature`

```gherkin
Scenario: wichtelm run with valid config exits 0
  Given a valid TOML config file
  And a parseable .strat file referenced by it
  When "wichtelm run config.toml" is executed
  Then the exit code is 0
  And the report file is created

Scenario: wichtelm run with invalid strategy exits non-zero
  Given a TOML config referencing a malformed .strat file
  When "wichtelm run config.toml" is executed
  Then the exit code is non-zero
  And stderr contains "StrategyParseException"
  And stderr contains the rule identifier and line number

Scenario: wichtelm validate parses without running the backtest
  Given a valid .strat file
  When "wichtelm validate mystrategy.strat" is executed
  Then the exit code is 0
  And no report file is created
  And stdout confirms successful parsing

Scenario: Missing EODHD env var produces a clear error
  Given a config with data_source = "eodhd"
  And the env var named in api_token_env is not set
  When "wichtelm run config.toml" is executed
  Then the exit code is non-zero
  And stderr identifies the missing env var by name
```

## 15. Out of scope for v1

The following are explicitly NOT implemented in v1:

- Visual editor / web UI for strategy authoring
- Real-time / live trading
- Multi-symbol portfolio strategies
- Slippage and commission models
- Dynamic position sizing (ATR-proportional, volatility-proportional, etc.)
- Walk-forward optimization
- Parameter sweep tooling
- User-defined DSL functions and macros
- Expression-typed first arguments for window aggregates (e.g. `highest(<expression>, period)` where the first arg is computed bar-by-bar). v1 covers the common cases via hard-coded variants (`highest_high`, `lowest_low`, `highest_close`, `lowest_close`). Generic expression-typed args may be introduced if Tier B's boolean primitives or future features require them.
- Output formats beyond HTML (PDF, JSON, CSV trade export)
- Boolean and String parameter types
- Indicators and window aggregates in `And with stop_loss at` / `And with take_profit at` clauses, EXCEPT `atr_value(period)` — the frozen-at-fill ATR accessor graduated into stop/take scope in the 0.52 increment (§3.4, P16). All other indicators/window aggregates remain disallowed there
- Trailing stops (dynamic stop-loss that updates per bar)
- ~~`price_above_sma` / `price_above_ema` / price-MA cross variants~~ — GRADUATED to the §3.7 catalog in the 0.52 MA-trend-filter increment as 11 flat Tier B primitives (8 price-vs-MA via nachtkrapp `PriceVsMARule`/`PriceMACrossRule`, 3 MA-vs-MA via `MAVsMARule`/`MACrossMARule`)
- ~~pivot point primitives~~ — GRADUATED to the §3.7 catalog in the 0.52 pivot-point increment as 4 flat Tier B primitives (`price_above_pivot` / `price_below_pivot` / `price_crosses_above_pivot` / `price_crosses_below_pivot`) over STANDARD daily levels via nachtkrapp `PivotPointRule`. Non-STANDARD levels (CAMARILLA R4/S4), the WOODIE/CAMARILLA variants, and non-daily pivot periods remain out of scope (selectable in a follow-up additive release)
- Diagnostic / visualization-only Scenarios in `.strat` files
- Tags (`@xxx`), Rule, Scenario Outline, Examples, DocStrings, DataTable Gherkin features
- Parallel execution of multiple backtests in a single CLI invocation
- Literal `[csv].file` paths without the `{symbol}` placeholder — the `frau-holle-csv` driver requires the placeholder; supporting literal paths is reserved as a future enhancement contingent on an additive `frau-holle-csv` change
- `entry_time` trade-context variable — removed in v0.1.0 pending a time-typed expression sub-language. Numeric epoch exposure is poor UX; type-aware support will be added when a strategy demo requires time-based arithmetic. The parser currently rejects `entry_time` references via P13 (undeclared identifier)

These are reserved for future additive releases (japicmp-validated) or future major versions.

## 16. Implementation delegation to Claude Code

This CLAUDE.md is the authoritative specification. Claude Code should:

1. Implement each Block (§9 through §14) as a Cucumber feature file in `src/test/resources/features/`
2. Implement the parser, evaluator, runtime, CLI, and report generator to satisfy all scenarios
3. Use the `incremental-implementation-workflow` skill: prereq → red → green → refactor, one Block at a time
4. Follow the conventions of other ha-track modules: sealed hierarchies for closed sets, builder validation with eager checks, typed exception hierarchy rooted at `WichtelmException`, JDK-only where possible, no DI framework, BigDecimal arithmetic with MathContext.DECIMAL64, UTC Instant for time
5. Add only the dependencies explicitly required by the spec: published ha-track Maven artifacts plus a TOML parser library (with a justified choice — toml4j, tomlj, or 4koma)
6. NEVER suggest or implement features marked "out of scope" in §15
7. When ambiguous, stop and ask a binary question before proceeding (one at a time)
8. All factual claims in code or comments must be web-verified before being written

The first implementation milestone is the parser (Block 1). Subsequent Blocks build on a working AST.

## 17. Demo data and reports

The `demo/` directory ships runnable examples designed to cover the whole DSL feature surface with the fewest demos: **five strategies, one per timeframe** (weekly `1w`, daily `1d`, 4-hour `4h`, hourly `1h`), with the daily one also multi-timeframe. Demo market data is **synthetic fixtures** under a deliberately fake instrument name (`TSTX`, never a real ticker) so it can never be mistaken for real prices: one price path with bull → bear → bull regimes, generated at 1h/4h/1d/1w by `demo/GenerateData.java` (deterministic, JDK-only) and mutually consistent across timeframes. It is continuous (a bar every period, weekends included), so the charts are clean at every timeframe.

Each demo runs two ways, both through the `wichtelm` CLI with no bespoke tooling:

- `data_source = "csv"` — offline, against the committed `demo/data/TSTX_*.csv` fixtures. Zero network, zero credentials. The committed `demo/reports/tstx-*-report.html` are these CSV runs and are the stable reference reports.
- `data_source = "eodhd"` — live, against real market data through the `frau-holle-eodhd` driver: set `[eodhd].api_token_env`, export the free EODHD `demo` token, run `wichtelm run`. The free token serves a fixed set — `AAPL.US`, `TSLA.US`, `VTI.US`, `AMZN.US`, `BTC-USD.CC`, `EURUSD.FOREX`. **EODHD-derived reports are NEVER committed** — that data is licensed (the `demo` token is evaluation-only) and a report embeds the real prices, so publishing one redistributes it; `demo/reports/.gitignore` excludes `eodhd-*-report.html`. Daily/weekly EODHD runs use the EOD endpoint (years of history); intraday from the `demo` token is a rolling ~4-month window, so 1h `[date_range]`s need periodic refreshing.

**Raw-data limitation.** Intraday data from EODHD (and most providers) is *raw* — not adjusted for splits or dividends, the industry-standard pattern for intraday endpoints. Demo windows are chosen to avoid corporate-action discontinuities (AAPL's last split was Aug 2020 4:1); use a broad ETF (`VTI`), forex, crypto, or a split-free window. A single-name backtest crossing a split would produce meaningless results.

See `demo/README.md` for the full runbook (the demo matrix, running the CSV demos, running the EODHD demos locally, and snapshotting EODHD data to CSV).
