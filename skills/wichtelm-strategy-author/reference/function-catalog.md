# Function / indicator catalog (closed in v1)

This is the **complete** vocabulary of the DSL. The catalog is *closed*: a function name
that is not listed here is a parse error (rule P14). Argument counts (arities) are exact —
calling a function with the wrong number of arguments is also P14.

Mirrors `BuiltinCatalog.java` and CLAUDE.md §3.7.

## Market variables (no parentheses)

`open`, `high`, `low`, `close`, `volume`, `bar_time`, `bar_index`

Used as bare identifiers in expressions, e.g. `When close is above trend`,
`And volume is above avg_vol`.

## Trade-context variables

`entry_price`, `position_size`

Valid **only** in exit scenarios and in `And with stop_loss` / `And with take_profit`
clauses — never in an entry scenario's conditions (rule P17). `entry_time` does **not**
exist in v1 (referencing it is an undeclared-identifier error, P13).

## Numeric indicators (return a value — use in comparisons / arithmetic)

| Function | Args | Notes |
|---|---|---|
| `sma(period)` | 1 | simple moving average |
| `ema(period)` | 1 | exponential moving average |
| `rsi(period)` | 1 | relative strength index |
| `atr(period)` | 1 | average true range (in *conditions*; use `atr_value` in stops) |
| `stddev(period)` | 1 | standard deviation (volatility proxy) |
| `macd_line(fast, slow, signal)` | 3 | MACD line component |
| `macd_signal(fast, slow, signal)` | 3 | MACD signal-line component |
| `macd_histogram(fast, slow, signal)` | 3 | MACD histogram component |

There is no callable `macd(...)` — MACD is exposed as the three flat `macd_*` functions.

## Window aggregates (return a value)

| Function | Args | Reduces |
|---|---|---|
| `highest_high(period)` | 1 | max high over last `period` bars |
| `lowest_low(period)` | 1 | min low |
| `highest_close(period)` | 1 | max close |
| `lowest_close(period)` | 1 | min close |
| `avg_volume(period)` | 1 | mean volume |

There is no generic `highest(<expr>, period)` in v1 — only these fixed-field variants.

## Tier-B boolean primitives (true/false — use as a **bare** When/And step)

These evaluate to a boolean, so they stand alone as a step (`When ha_doji()`,
`And rsi_oversold(30)`). **Do not** put them inside a comparison or arithmetic expression.

### Heikin-Ashi

| Primitive | Args | Detection settings |
|---|---|---|
| `ha_doji()` or `ha_doji(maxBodyRatio)` | 0 or 1 | tunable; default `maxBodyRatio = 0.1` |
| `ha_strong()` | 0 | fixed `wickTolerance = 0.05, minBodyRatio = 0.6` (see note) |
| `ha_strong_bullish()` | 0 | same fixed detection as `ha_strong`, bullish subtype |
| `ha_strong_bearish()` | 0 | same fixed detection as `ha_strong`, bearish subtype |
| `ha_bullish_reversal(streak)` | 1 | tunable streak |
| `ha_bearish_reversal(streak)` | 1 | tunable streak |

> Note: `ha_doji` is tunable — its optional `maxBodyRatio` argument is honored. The
> `ha_strong*` primitives are **not** tunable: their thresholds are fixed at `0.05` / `0.6`.
> The parser tolerates an argument to `ha_strong(...)`, but the runtime ignores it, so call it
> as `ha_strong()` and don't rely on a passed value.

### RSI level

| Primitive | Args | Default when 0-arg |
|---|---|---|
| `rsi_overbought(threshold)` | 1 | threshold in (0, 100) |
| `rsi_oversold(threshold)` | 1 | threshold in (0, 100) |
| `rsi_crosses_50()` | 0 | `period = 14` |

### MACD

| Primitive | Args | Default |
|---|---|---|
| `macd_bullish_cross()` | 0 | 12/26/9 |
| `macd_bearish_cross()` | 0 | 12/26/9 |
| `macd_zero_cross_up()` | 0 | 12/26/9 |
| `macd_zero_cross_down()` | 0 | 12/26/9 |

### Moving-average trend filter

Price-vs-MA (1 period arg each):
`price_above_sma(period)`, `price_below_sma(period)`, `price_above_ema(period)`,
`price_below_ema(period)`, `price_crosses_above_sma(period)`,
`price_crosses_below_sma(period)`, `price_crosses_above_ema(period)`,
`price_crosses_below_ema(period)`

MA-vs-MA (2 args, SMA-vs-EMA only — there is no SMA-vs-SMA or EMA-vs-EMA primitive):
`sma_above_ema(sma_period, ema_period)`, `sma_crosses_above_ema(sma_period, ema_period)`,
`sma_crosses_below_ema(sma_period, ema_period)`

### Pivot points (STANDARD daily levels)

`price_above_pivot(level)`, `price_below_pivot(level)`, `price_crosses_above_pivot(level)`,
`price_crosses_below_pivot(level)`

The single argument is a **symbolic level token**, not a number:
`P`, `R1`, `R2`, `R3`, `S1`, `S2`, `S3`. Computed from the prior completed UTC day's OHLC.
These primitives may appear **only** as a complete When/And step — never embedded in a
comparison, arithmetic, or a Background series (P14). CAMARILLA R4/S4, WOODIE/CAMARILLA
variants, and non-daily pivot periods are out of scope in v1 (any other token → P21).

## `atr_value(period)` — stop/take only

`atr_value(period)` is the frozen-at-fill ATR accessor. It is the **only** function allowed
inside `And with stop_loss at ...` / `And with take_profit at ...`, and it is **not** allowed
in conditions (use `atr(period)` there). Example: `And with stop_loss at entry_price - 2 * atr_value(14)`.

## Tuning a primitive's period/threshold

Most 0-argument primitives use fixed defaults (e.g. `rsi_crosses_50()` is period 14,
`macd_bullish_cross()` is 12/26/9). To use non-default settings, declare a Background series
and build a comparison around it instead:

```gherkin
Background:
  Given a series rsi_fast defined as rsi(7)
Scenario: ...
  When rsi_fast crosses below 25
```

## Argument value rules (enforced by P21)

- A `period` / `streak` argument must be a **positive whole number** (literal or a declared
  integer parameter). `rsi(0)` or `rsi(14.5)` is rejected.
- An RSI `threshold` must be strictly inside `(0, 100)`.
- A pivot `level` must be one of `P, R1, R2, R3, S1, S2, S3`.

## Not in the catalog (don't suggest these in v1)

Stochastic, Bollinger Bands, ADX/DMI, Supertrend, Ichimoku, VWAP, OBV, parabolic SAR,
**trailing stops** (stops are fixed at fill — no per-bar updating), SMA-vs-SMA crosses,
and user-defined functions. If a popular setup needs one of these, offer the closest
expressible equivalent: `stddev(period)` as a volatility gauge, a fixed `atr_value`
stop instead of a trailing ATR stop, `sma_crosses_above_ema` instead of an SMA-SMA cross,
or a Background series + comparison for a custom level.
