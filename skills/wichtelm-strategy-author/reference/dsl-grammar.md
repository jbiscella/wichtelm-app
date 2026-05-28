# The `.strat` DSL grammar

A `.strat` file is **Gherkin-conformant**: it uses only the standard keywords `Feature`,
`Background`, `Scenario`, `Given`, `When`, `Then`, `And`, `But`. Everything domain-specific
is plain text after one of those keywords — there are no custom keywords. Tags (`@x`),
`Rule`, `Scenario Outline`, `Examples`, doc-strings, and data tables are **not** supported.

## File structure

| Section | Cardinality | Purpose |
|---|---|---|
| `Feature:` header | exactly 1 | names the strategy |
| Description block | 0 or 1 | free text; **must** contain `Primary timeframe: <TF>`; **may** contain `Parameter` lines |
| `Background:` | 0 or 1 | declares named series (incl. higher-timeframe series) |
| `Scenario:` blocks | 1 or more | each emits exactly one trading condition |

```gherkin
Feature: <name>
  Primary timeframe: <TF>
  Parameter <name> default <number>

  Background:
    Given a series <name> defined as <expression> [on <higher-TF>]

  Scenario: <unique name>
    Given <precondition>
    When <condition>
    And  <condition>
    Then <long_entry|long_exit|short_entry|short_exit>
    And with stop_loss at <expression>
    And with take_profit at <expression>
```

## Primary timeframe (required)

`Primary timeframe: <TF>` must appear exactly once in the description block. `<TF>` is a wire
token like `1m`, `5m`, `15m`, `1h`, `4h`, `1d`, `1w` (number + unit; `m`=minute, `h`=hour,
`d`=day, `w`=week, `M`=month, `Y`=year). An unrecognized token is rejected (P2).

## Parameters

```gherkin
Parameter rsi_period default 14      # integer (no decimal point)
Parameter stop_loss_pct default 2.5  # decimal
```

Type is inferred from the literal. Names must be valid identifiers (letter/underscore start)
and unique (P4). Values in the `.strat` are *defaults*; the TOML config can override any of
them at run time. Boolean and string parameters are not supported in v1.

## The four first-class conditions

Every scenario must end with `Then <condition>` where the condition is exactly one of:

| Condition | Meaning |
|---|---|
| `long_entry` | open a long (Buy) |
| `long_exit` | close a long |
| `short_entry` | open a short (Sell) |
| `short_exit` | close a short |

## Position precondition (the opening `Given`)

A scenario begins with one of three preconditions, which gates when it is evaluated **and**
must be consistent with the terminal condition:

| Precondition | Evaluated when | Must terminate with |
|---|---|---|
| `Given no open position` | flat | `long_entry` or `short_entry` (P18) |
| `Given a long position is open` | long open | `long_exit` (P19) |
| `Given a short position is open` | short open | `short_exit` (P20) |

## Background series and multi-timeframe

```gherkin
Background:
  Given a series trend defined as ema(trend_period) on 1d   # higher-TF series
  And a series rsi_value defined as rsi(rsi_period)         # primary-TF named expression
```

- A series **without** `on <TF>` is a named expression on the primary timeframe — handy for
  reusing or renaming an indicator (e.g. `rsi_value`).
- A series **with** `on <higher-TF>` is a higher-timeframe series. The higher TF must be
  *strictly higher* than the primary (P8: `on 1d` is valid when primary is `1h`, invalid
  when primary is `1d`). At any primary bar T it resolves to the most recently **closed**
  higher-TF bar (closeTime ≤ T) — the runtime enforces this lookahead-safety automatically;
  there is no way (or need) to bypass it.

Series names must be unique and must not collide with a market variable like `close` (P7).

## Expression language

| Element | Syntax | Examples |
|---|---|---|
| Comparison | English prose | `crosses below`, `crosses above`, `is above`, `is below`, `drops below`, `rises above`, `exceeds` |
| Arithmetic | math notation | `+ - * /`, parentheses, standard precedence (`* /` before `+ -`) |
| Variables | bare identifiers | `close`, `volume`, `entry_price`, a parameter, a Background series |
| Functions | name + parentheses | `rsi(14)`, `ema(200)`, `macd_line(12, 26, 9)` |
| Boolean composition | Gherkin `And` / `But` | steps are AND-ed |

A `When`/`And` step is either:
1. a **comparison** — `<expression> <operator> <expression>`, e.g. `rsi_value crosses below oversold`, or
2. a **bare boolean** — a Tier-B primitive on its own, e.g. `ha_doji()`, `rsi_oversold(30)`.

Within a scenario, steps are always AND-ed. There is **no `Or`**: express OR-logic by
writing two scenarios with the same `Then`.

## Stop-loss and take-profit

Only on entry scenarios (`long_entry` / `short_entry`), appended after the `Then`:

```gherkin
Then long_entry
And with stop_loss at entry_price * (1 - stop_loss_pct / 100)
And with take_profit at entry_price + 2.5 * atr_value(14)
```

The expression is snapshotted at the entry's fill and monitored intrabar. It may reference
**only**: numeric constants, declared parameters, trade-context variables (`entry_price`,
`position_size`), and `atr_value(period)`. Any other indicator, window aggregate, or
Background series is rejected (P16). Putting these clauses on an *exit* scenario is rejected
(P12). When both a stop and a target sit inside one bar's range, the stop wins (pessimistic
fill); an intrabar stop/target also wins over a close-evaluated exit scenario on the same bar.

## Warmup suppression (good to know)

An entry whose `atr_value(period)` has not warmed up at fill time (fewer than `period` bars
precede it) is **suppressed** — the position is not opened, and the scenario fires naturally
on a later bar once the indicator warms. Suppressed entries are listed in the report's
"Suppressed entries" diagnostics rather than silently dropped.
