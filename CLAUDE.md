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

The expression is evaluated at the fill time of the entry, snapshotted, and compared against the high/low of subsequent bars until the position closes. The expression may reference: constants, declared `Parameter` values, and trade-context variables (`entry_price`, `entry_time`, `position_size`). Indicators, window aggregates, and built-in functions are NOT accepted in v1.

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
| Function calls | unquoted with parentheses | `rsi(14)`, `ema(200)`, `atr(14)`, `highest(close, 10)` |
| Boolean composition between steps | Gherkin `And` / `But` | each step is implicitly AND-ed with the previous. OR-logic is expressed by duplicate Scenarios with the same `Then` |

### 3.7 Built-in DSL function/indicator catalog

| Category | Available |
|---|---|
| Market variables (no parameters) | `open`, `high`, `low`, `close`, `volume`, `bar_time`, `bar_index` |
| Base indicators (from `indicators` ha-track module) | `sma(period)`, `ema(period)`, `rsi(period)`, `atr(period)`, `stddev(period)` |
| Composite indicators | `macd(fast, slow, signal)` (with field accessors `.macd_line`, `.signal_line`, `.histogram` — syntax TBD at parser implementation time) |
| Window aggregates | `highest(<expr>, period)`, `lowest(<expr>, period)`, `avg_volume(period)` |
| HA primitives (from nachtkrapp) | `ha_bullish_reversal(streak)`, `ha_bearish_reversal(streak)`, `ha_strong(...)`, `ha_doji(...)` |
| Price/MA primitives (from nachtkrapp) | `price_above_ma(...)`, `price_crosses_ma(...)` |
| RSI level primitives (from nachtkrapp) | `rsi_crosses_50()`, `rsi_overbought(threshold)`, `rsi_oversold(threshold)` |
| MACD primitives (from nachtkrapp) | `macd_bullish_cross()`, `macd_bearish_cross()`, `macd_zero_cross_up()`, `macd_zero_cross_down()` |
| Trade-context variables (in exit Scenarios and `And with` clauses) | `entry_price`, `entry_time`, `position_size` |

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
| P7 | Background-declared series names MUST be unique within a strategy and MUST NOT collide with built-in market variables (open, high, low, close, volume, bar_time, bar_index) or trade-context variables (entry_price, entry_time, position_size) |
| P8 | Higher-TF references in Background MUST use a TF strictly higher than the primary TF (verified via Timeframe ordering) |
| P9 | Each `Scenario:` block MUST contain at least one step |
| P10 | Each Scenario MUST terminate with `Then <X>` where X is one of: `long_entry`, `long_exit`, `short_entry`, `short_exit` |
| P11 | A Scenario terminating with `Then long_entry` or `Then short_entry` MAY have appended one or both `And with stop_loss at <expr>` and `And with take_profit at <expr>` lines after the `Then` step |
| P12 | A Scenario terminating with `Then long_exit` or `Then short_exit` MUST NOT have `And with stop_loss at` or `And with take_profit at` clauses |
| P13 | Identifiers referenced in expressions MUST resolve to: a built-in market variable, a declared parameter, a declared Background series, a built-in function/indicator (from §3.7), or a trade-context variable (only in exit Scenarios and `And with` clauses on entry Scenarios) |
| P14 | Function calls MUST match the arity and parameter types of the built-in function. Unknown function names produce a parse error |
| P15 | Arithmetic expressions MUST be syntactically valid; unbalanced parentheses produce a parse error |
| P16 | `And with stop_loss at <expr>` and `And with take_profit at <expr>` expressions MUST NOT reference built-in functions/indicators (§3.7) or Background-declared series. Only constants, parameters, and trade-context variables are allowed |
| P17 | Trade-context variables (`entry_price`, `entry_time`, `position_size`) MUST NOT appear in Scenarios terminating with `Then long_entry` or `Then short_entry` (no position exists at entry time) |
| P18 | A Scenario starting with `Given no open position` MUST terminate with `Then long_entry` or `Then short_entry` (semantic consistency) |
| P19 | A Scenario starting with `Given a long position is open` MUST terminate with `Then long_exit` (semantic consistency) |
| P20 | A Scenario starting with `Given a short position is open` MUST terminate with `Then short_exit` (semantic consistency) |
| P21 | Static numeric range checks: where parameters are passed to functions with known valid ranges (e.g. RSI threshold in (0, 100), period > 0), violations produce a parse error |

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
| C8 | If `data_source = "csv"`, `[csv].file` is required and the file MUST be readable |
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
| 5 | Verify all higher-TF series cover the same `date_range` as the primary series (no gaps that would prevent lookahead-safe resolution) |
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

### 7.2 Top section: aggregate metrics

The report begins with a header summarizing the backtest, followed by the 10 aggregate metrics from `frau-holle.BacktestResult.metrics`: `totalReturn`, `numTrades`, `winRate`, `maxDrawdown`, `sharpeRatio`, `sortinoRatio`, `calmarRatio`, `profitFactor`, `avgWin`, `avgLoss`.

`profitFactor == 0` is rendered as "undefined" with a tooltip explaining the sentinel value.

### 7.3 Body section: per-Scenario boxes

For each declared Scenario in the strategy, the report contains one "box" with:

| Element | Content |
|---|---|
| Scenario name (as written in the `.strat` file) | as box title |
| Trigger count | number of bars at which the Scenario fired (signal was emitted, regardless of frau-holle accepting or ignoring it) |
| Chart visualization | one chart for the primary TF + one chart for each higher-TF referenced via Background, with markers at all trigger bar times for this Scenario |
| Sub-report table | tabular view with one row per trigger; columns include timestamp and the values of every sub-condition (When/And steps) at the trigger time |

Boxes are sorted alphabetically by Scenario name. The 4 first-class condition types (`long_entry`, `long_exit`, `short_entry`, `short_exit`) appear in the order their Scenarios were declared in the source file, broken alphabetically within the same condition type if multiple Scenarios share it.

### 7.4 Trailing section: full trade list and equity curve

After the per-Scenario boxes, the report includes:

- The full equity curve as a chart (`BacktestResult.equityCurve`)
- The full drawdown curve derived from the equity curve
- A tabular trade list with `entryTime`, `exitTime`, `direction`, `entryPrice`, `exitPrice`, `pnl_pct` for every closed trade (`BacktestResult.trades`)
- A summary of diagnostic counters (`BacktestDiagnostics`)

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
  And the parsed AST contains 7 parameters
  And the parsed AST contains 2 Background series declarations
  And the parsed AST contains 6 Scenarios
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
- Output formats beyond HTML (PDF, JSON, CSV trade export)
- Boolean and String parameter types
- Indicators and window aggregates in `And with stop_loss at` / `And with take_profit at` clauses (only constants, parameters, and trade-context variables allowed)
- Trailing stops (dynamic stop-loss that updates per bar)
- Diagnostic / visualization-only Scenarios in `.strat` files
- Tags (`@xxx`), Rule, Scenario Outline, Examples, DocStrings, DataTable Gherkin features
- Parallel execution of multiple backtests in a single CLI invocation

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
