---
name: wichtelm-strategy-author
description: >-
  Write, review, and debug trading strategies in the wichtelm-app `.strat` DSL — a
  Gherkin-conformant backtesting language built around Heikin-Ashi candles. Use when a
  user is authoring or fixing a .strat strategy file, writing the TOML backtest config,
  choosing indicators / Heikin-Ashi / MACD / RSI / moving-average / pivot primitives,
  resolving a StrategyParseException (rules P1–P22) or ConfigParseException (rules
  C1–C11), or interpreting a wichtelm HTML backtest report.
---

# Authoring wichtelm-app strategies

`wichtelm-app` is a backtesting tool. A user describes a trading strategy in a `.strat`
file written in a **Gherkin-conformant DSL**, points a TOML config at some historical
price data, and runs `wichtelm run <config>` to get a self-contained HTML report. This
skill helps you write valid `.strat` files (the hard part), plus the TOML config, and to
read the report.

You do **not** need to know Java or the app's internals — this is for the person writing
strategies, not the person building the app.

## How to use this skill

1. **Reach for the reference files** rather than guessing. They are the ground truth:
   - `reference/dsl-grammar.md` — the file structure, the four conditions, expression
     syntax, multi-timeframe series, stop-loss / take-profit.
   - `reference/function-catalog.md` — the **complete, closed** list of every built-in
     function/indicator with its exact argument count and defaults. If a name is not in
     this file, it does not exist — do not invent functions.
   - `reference/validation-rules.md` — all 22 parse-time rules (P1–P22) with the exact
     error message the parser emits and how to fix each. Use this to pre-empt errors and
     to debug a `StrategyParseException`.
   - `reference/config-cli-report.md` — the TOML config schema (rules C1–C11), the CLI
     commands and exit codes, and how to read the HTML report.
   - `reference/guided-builder.md` — a menu-driven, clause-by-clause flow for building a
     strategy interactively (see "Guided vs direct" below).
2. **Start from a worked example.** `examples/` contains five strategies pairing Heikin-Ashi
   with a confirming indicator, plus the canonical reference. Adapt one rather than writing
   from a blank page. See `examples/README.md` for which is which.
3. **Self-check against the rules before handing back a strategy.** Mentally run the
   "Non-negotiable rules" checklist below; if anything is uncertain, cite the specific
   P-rule. Tell the user they can verify with `wichtelm validate <file>.strat` (exit 0 =
   clean).

## Guided vs direct authoring

Choose the mode that fits the user:

- **Guided builder (default when the user is unsure or open-ended).** If someone says "help me
  make a strategy", "I don't know the syntax", or otherwise seems unsure, offer to build it
  with them and follow `reference/guided-builder.md`: ask one question per turn, present a
  short numbered menu of catalog-valid choices, and assemble + validate the `.strat` as you go.
- **Direct authoring (for specifics).** If the user pastes a strategy, asks a targeted
  question, requests a specific setup, or clearly knows the DSL, just write or fix it directly
  using the references — don't force them through menus. Always honor "just write it for me".

## The shape of a `.strat` file

```gherkin
Feature: <strategy name>
  Primary timeframe: <TF>
  Parameter <name> default <number>

  Background:
    Given a series <name> defined as <expression> [on <higher-TF>]

  Scenario: <unique name>
    Given <position precondition>
    When <condition step>
    And <condition step>
    Then <long_entry | long_exit | short_entry | short_exit>
    And with stop_loss at <expression>
    And with take_profit at <expression>
```

- `Primary timeframe:` is mandatory (e.g. `1h`, `4h`, `1d`, `1w`); the `Background:` block,
  `Parameter` lines, and the `stop_loss` / `take_profit` clauses (entries only) are optional.
- Steps within a scenario are AND-ed.
- **Comments must be on their own line.** The parser only skips *whole-line* `#` comments — an
  inline `# ...` after `Primary timeframe:`, a `Parameter` default, or any clause is read as
  part of that line and breaks parsing. Put explanatory text on a separate `#` line, not at
  the end of a clause.

## Authoring workflow

1. **Pick the primary timeframe** and declare it (`Primary timeframe: 1h`). Required.
2. **Declare parameters** for any number you'd want to tune (periods, thresholds, stop %).
3. **Declare Background series** for: anything on a *higher* timeframe (trend filters), and
   any indicator you reference more than once or with a non-default setting (e.g.
   `Given a series rsi_value defined as rsi(20)`).
4. **Write entry scenarios** — `Given no open position` → `Then long_entry` / `short_entry`.
   Combine an entry trigger with confirmations using `And`. Attach `stop_loss` / `take_profit`.
5. **Write exit scenarios** — one per way out. The precondition must match the side
   (`Given a long position is open` → `Then long_exit`).
6. **Use Heikin-Ashi + a confirming indicator**, never HA alone — HA lags, so pair it with
   RSI / MACD / a moving average (see the examples and the note in `examples/README.md`).
7. **Validate against the rules** (checklist below), then suggest `wichtelm validate`.
8. **Write the TOML config** (`reference/config-cli-report.md`) and show the run command.

## Non-negotiable rules (the ones that actually trip people up)

- **Every scenario ends with exactly one `Then`**, and it must be one of `long_entry`,
  `long_exit`, `short_entry`, `short_exit` (P10). Diagnostic/visualization-only scenarios
  are not allowed.
- **The opening `Given` must match the `Then`** (P18–P20):
  `Given no open position` → an *entry*; `Given a long position is open` → `long_exit`;
  `Given a short position is open` → `short_exit`.
- **`stop_loss` / `take_profit` only on entry scenarios** (P12). Inside them you may use
  *only* constants, parameters, trade-context variables (`entry_price`, `position_size`),
  and `atr_value(period)` — **no other indicators, no Background series** (P16). Note the
  split: use `atr(period)` in conditions, `atr_value(period)` in stops/targets.
- **Tier-B boolean primitives are bare steps**, not values. Write `When ha_doji()` or
  `And rsi_oversold(30)` on their own line — never inside a comparison or arithmetic. Pivot
  primitives especially (`price_above_pivot(R1)`) may appear *only* as a complete
  When/And step (P14).
- **The function catalog is closed** (`reference/function-catalog.md`). An unknown function
  name is a parse error (P14). Match the exact argument count.
- **Names must be unique**: parameters (P4), Background series (P7), scenarios (P22). A
  Background series name must not collide with a market variable like `close` or `volume` (P7).
- **Higher-timeframe Background series** must use a timeframe *strictly higher* than the
  primary (P8); the runtime resolves them lookahead-safely to the most recently closed
  higher-TF bar — you never need to (and cannot) work around this.
- **OR-logic = duplicate the scenario** with the same `Then`. Steps within a scenario are
  always AND-ed; there is no `Or` keyword.
- **Trade-context variables** (`entry_price`, `position_size`) may appear only in exit
  scenarios and in `And with` clauses — never in an entry's conditions (P17).

## What the DSL cannot do (don't suggest these)

The catalog has no Stochastic, no Bollinger Bands, no Supertrend, and **no trailing
stops** (stops are fixed at fill time). Multi-symbol portfolios, commissions/slippage,
and dynamic position sizing are out of scope in v1. If a user asks for one of these,
say so and offer the closest expressible equivalent (e.g. `stddev(period)` for a
volatility gauge, a fixed `atr_value`-based stop instead of a trailing ATR stop). See
`reference/function-catalog.md` for the full boundary.
