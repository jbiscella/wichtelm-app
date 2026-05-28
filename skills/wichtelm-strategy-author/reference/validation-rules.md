# Parse-time validation rules (P1–P22)

The parser enforces 22 rules. A violation throws a `StrategyParseException` carrying the file
path, line, column, the violated rule id, and a message — printed like:

```
StrategyParseException [P10] at my-strategy.strat:14:5 — Scenario must terminate with ...
```

Run `wichtelm validate <file>.strat` to check all rules without running a backtest (exit 0 =
clean). The messages below are the exact strings the parser emits — use them to diagnose a
failure and to self-check a strategy before handing it back.

| Rule | What it checks | Exact message (representative) | Fix |
|---|---|---|---|
| **P1** | exactly one `Feature:` at the top | `strategy file must begin with a Feature: header` / `strategy file must contain exactly one Feature: block` | Start the file with a single `Feature:` line. |
| **P2** | exactly one valid `Primary timeframe:` | `Feature description must declare exactly one Primary timeframe` / `duplicate Primary timeframe declaration` | One `Primary timeframe: <TF>` with a recognized TF. |
| **P3** | `Parameter <name> default <value>` form | `Parameter line must match 'Parameter <name> default <value>'` / `Parameter default value must be a numeric literal: <x>` | Use a valid identifier and a numeric default. |
| **P4** | parameter names unique | `duplicate parameter declaration: <name>` | Rename or remove the duplicate. |
| **P5** | Background steps start with Given/And | `Background step must start with Given or And` | Use `Given`/`And` in Background. |
| **P6** | Background series form | `Background series must match 'Given a series <name> defined as <expression>'` | Match `Given a series <name> defined as <expr> [on <TF>]`. |
| **P7** | series names unique, no collisions | `series name collides with a built-in variable: <name>` / `duplicate Background series name: <name>` | Don't reuse `close`/`volume`/etc. or another series name. |
| **P8** | higher-TF strictly above primary | `Background series timeframe must be strictly higher than the primary timeframe` | Use a larger TF (e.g. `on 1d` when primary is `1h`). |
| **P9** | scenario non-empty | `Scenario must contain at least one step` | Add steps. |
| **P10** | terminates with a first-class condition | `Scenario must terminate with 'Then <long_entry\|long_exit\|short_entry\|short_exit>'` / `Scenario must contain exactly one Then step` | End with exactly one of the four conditions. |
| **P11** | only stop/take may follow `Then` | `only 'And with stop_loss at' / 'And with take_profit at' may follow Then` | Don't put other steps after `Then`. |
| **P12** | no stop/take on exits | `stop_loss/take_profit clauses are not allowed on exit Scenarios` | Move protective clauses to the entry scenario. |
| **P13** | identifiers resolve | `undeclared identifier: <word>` / `undeclared identifier in Background series: <word>` | Declare it as a parameter/series, or use a known variable. (`entry_time` does not exist.) |
| **P14** | function name + arity; pivot/Tier-B usage | `unknown function: <name>` / `function <name> expects <n> argument(s) but got <m>` / `<word> is a boolean pivot primitive and may only be used as a complete When/And step` | Use a catalog function with the right arg count; keep pivot/Tier-B primitives as bare steps. |
| **P15** | balanced parentheses | `unbalanced parentheses in expression` / `missing closing parenthesis` | Balance `(` and `)`. |
| **P16** | stop/take expression contents | `atr_value(...) is only valid in stop_loss/take_profit expressions; use atr(...) in conditions` / `stop_loss/take_profit expressions must not reference functions or indicators: <name>` / `stop_loss/take_profit expressions may only reference constants, parameters and trade-context variables: <word>` | In stops use only constants, parameters, `entry_price`/`position_size`, and `atr_value(period)`. |
| **P17** | no trade-context vars in entries | `trade-context variable not allowed in an entry Scenario: <word>` | `entry_price`/`position_size` only in exits & `And with` clauses. |
| **P18** | flat → entry | `'Given no open position' must terminate with long_entry or short_entry` / `Scenario must begin with a position precondition (...)` | Match precondition to terminal condition. |
| **P19** | long open → long_exit | `'Given a long position is open' must terminate with long_exit` | Use `long_exit`. |
| **P20** | short open → short_exit | `'Given a short position is open' must terminate with short_exit` | Use `short_exit`. |
| **P21** | numeric range checks | `period argument of <name> must be > 0, was <x>` / `period argument of <name> must be a whole number, was <x>` / `threshold argument of <name> must be in (0, 100), was <x>` / `pivot level of <name> must be one of P, R1, R2, R3, S1, S2, S3, was <level>` | Use positive whole-number periods, RSI thresholds in (0,100), valid pivot tokens. |
| **P22** | scenario names unique | `duplicate Scenario name: <name>` | Rename the duplicate scenario. |

## Most common mistakes (and the rule they trip)

- Forgetting the `Then`, or ending with something other than the four conditions → **P10**.
- A short exit written as `Given a long position is open` → **P19/P20**. The precondition
  must match the side being exited.
- Putting `stop_loss`/`take_profit` on an exit → **P12**, or referencing an indicator/series
  inside a stop → **P16** (use `atr_value`, not `atr`, and nothing else).
- Embedding a Tier-B/pivot primitive inside a comparison (`When price_above_pivot(R1) is above close`) → **P14**. Keep them as bare steps.
- Inventing a function (`bollinger(...)`, `stochastic(...)`, `macd(...)`) → **P14**. The
  catalog is closed (see `function-catalog.md`).
- Reusing a parameter, series, or scenario name → **P4 / P7 / P22**.
- A higher-TF series at or below the primary TF → **P8**.
