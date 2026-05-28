# Guided strategy builder (clause-by-clause)

A menu-driven flow for building a `.strat` file one decision at a time. Use it to walk a
user through a strategy when they are unsure or ask open-endedly ("help me make a strategy",
"I don't know the syntax"). **Skip it** when the user pastes a strategy, asks a specific
question, or clearly knows the DSL — just author/fix directly. Always honor "just write it
for me" by collecting the essentials in one go instead of stepping through menus.

## How to run the flow

- **One decision per turn.** Ask a single question, present a **numbered menu**, wait for the
  reply. The user answers with a number, several numbers, or free text.
- **Only offer catalog-valid options** (see `function-catalog.md`). Never list a function
  that doesn't exist.
- **Track state** as you go: chosen primary TF, direction(s), each scenario, and a running
  list of parameters (every period / threshold / multiplier the user picks).
- **Echo progress.** After each completed scenario, show the growing `.strat` so far.
- **Validate at the end** against every P-rule (`validation-rules.md`) before presenting the
  final file, then offer the TOML config (`config-cli-report.md`) and the run command.
- Let the user **go back / change an answer** at any point.

## Step 1 — Primary timeframe

> "What timeframe are the bars?"
> `1) 1m  2) 5m  3) 15m  4) 1h  5) 4h  6) 1d  7) 1w` (or type another, e.g. `30m`)

Emit `Primary timeframe: <TF>`.

## Step 2 — Direction

> "Which sides do you want to trade?"
> `1) Long only  2) Short only  3) Both long and short`

Build entry+exit scenarios for each chosen side. For "both", run Steps 3–4 once per side.

## Step 3 — Entry (for each side)

A long entry starts `Given no open position` → `Then long_entry`; a short entry the same →
`Then short_entry`.

### 3a. Entry trigger — full-coverage menu

Present the families; let the user drill in. The bracketed argument is asked for after the
pick (offer a sensible default).

```
Heikin-Ashi
  1) Bullish/Bearish HA reversal      ha_bullish_reversal(streak) / ha_bearish_reversal(streak)
  2) Strong bullish/bearish HA candle ha_strong_bullish() / ha_strong_bearish()
  3) Any strong HA candle             ha_strong()
  4) HA doji (indecision)             ha_doji()
RSI
  5) RSI oversold / overbought        rsi_oversold(threshold) / rsi_overbought(threshold)
  6) RSI crosses 50                   rsi_crosses_50()
  7) Custom RSI level cross           series rsi_x = rsi(period); rsi_x crosses below/above <level>
MACD
  8) MACD bullish/bearish cross       macd_bullish_cross() / macd_bearish_cross()
  9) MACD zero-line cross up/down     macd_zero_cross_up() / macd_zero_cross_down()
 10) MACD line vs signal (numeric)    series macd=macd_line(f,s,sig), sig=macd_signal(f,s,sig); macd crosses above/below sig
Moving averages
 11) Price above/below SMA or EMA     price_above_sma(p) / price_below_ema(p) / ...
 12) Price crosses SMA or EMA         price_crosses_above_ema(p) / price_crosses_below_sma(p) / ...
 13) SMA vs EMA (state or cross)      sma_above_ema(s,e) / sma_crosses_above_ema(s,e) / sma_crosses_below_ema(s,e)
Pivot points (STANDARD daily)
 14) Price above/below a pivot level  price_above_pivot(L) / price_below_pivot(L)   (L = P,R1,R2,R3,S1,S2,S3)
 15) Price crosses a pivot level      price_crosses_above_pivot(L) / price_crosses_below_pivot(L)
Channels & volume (numeric, via a Background series)
 16) Price vs N-bar high/low channel  series hi=highest_high(N)/lo=lowest_low(N)/highest_close/lowest_close; close is above/below it
 17) Volume vs average                series av=avg_volume(N); volume is above av
Custom comparison
 18) Any indicator compared to a value/series  e.g. close is above ema(50); stddev(20) is above <x>
```

Translation rules:
- Picks **1–6, 8–9, 11–15** become **bare boolean steps** (`When ha_strong_bullish()`).
- Picks **7, 10, 16, 17, 18** need a **Background series** plus a comparison step (the
  fixed-default cross primitives can't take custom periods — route custom periods here).
- Pivot level token is symbolic (`R1`), never a number.

### 3b. Confirmations (optional, repeatable)

> "Add a confirmation? It's AND-ed with the trigger." → same menu as 3a, or `done`.

Each confirmation is another `And <step>`. (For OR-logic, that's a second *scenario* — see 3d.)

### 3c. Protective exit (entry scenarios only)

> "Stop-loss?"  `1) none  2) percent below/above entry  3) ATR multiple  4) fixed level`
> "Take-profit?" `1) none  2) percent  3) ATR multiple  4) fixed level`

Emit (entries only, after the `Then`):
- percent long: `And with stop_loss at entry_price * (1 - stop_loss_pct / 100)` /
  `take_profit at entry_price * (1 + take_profit_pct / 100)` (short: flip the signs).
- ATR long: `And with stop_loss at entry_price - atr_sl_mult * atr_value(atr_period)` /
  `take_profit at entry_price + atr_tp_mult * atr_value(atr_period)` (short: flip).
- fixed: any expression of constants/parameters/`entry_price`/`position_size`/`atr_value` only.

Reminder: inside stops you may use **only** those — never `atr(...)`, another indicator, or a
Background series (P16). Use `atr_value(...)` here, `atr(...)` in conditions.

### 3d. Another entry for this side? (OR-logic)

> "Add another way to enter <side>?" → yes builds a second `Given no open position …` scenario
> with the same `Then`; no moves on.

## Step 4 — Exit (for each side)

A long exit starts `Given a long position is open` → `Then long_exit`; a short exit
`Given a short position is open` → `Then short_exit`. (Matching the side is mandatory:
P19/P20.) Offer one or more exit scenarios; each extra one is OR-logic.

### Exit trigger menu

Same families as 3a, plus exit-specific options that reference the trade:

```
Signal-based (close-evaluated)
  - opposite momentum: rsi_overbought (close a long) / rsi_oversold (close a short)
  - ha_strong_bearish() (close a long) / ha_strong_bullish() (close a short)
  - macd_zero_cross_down (close a long) / macd_zero_cross_up (close a short)
  - price_crosses_below_ema(p) / sma_crosses_below_ema(s,e) / price_crosses_below_pivot(L) ...
Trade-relative (uses entry_price)
  - price floor:  When close drops below entry_price * <factor>     (e.g. 0.95)
  - price target: When close rises above entry_price * <factor>     (e.g. 1.10)
```

`entry_price` / `position_size` are allowed in exit conditions (they're not in entries — P17).
Note that a percentage `stop_loss` / `take_profit` set on the entry already gives an intrabar
protective exit; the Step-4 exits are the close-evaluated, signal-driven ways out.

## Step 5 — Parameters

Collect every period / threshold / multiplier the user chose. Propose a `Parameter` line per
value with the chosen number as the default, e.g.:

```
Parameter trend_period default 200
Parameter stop_loss_pct default 2
```

> "These become tunable parameters (overridable in the TOML config). Keep the defaults, change
> any, or inline some as fixed numbers?"

Use a parameter name everywhere that value appears. Names must be unique (P4) and not collide
with a market variable (P7).

## Step 6 — Assemble, validate, configure

1. Emit the complete `.strat` (Feature → Parameters → Background → Scenarios).
2. Run the self-check from `validation-rules.md`. In particular confirm: every scenario ends
   in one `Then` (P10); preconditions match (P18–P20); stops only on entries with allowed
   contents (P12/P16); Tier-B/pivot primitives are bare steps (P14); names unique (P4/P7/P22);
   higher-TF series strictly above primary (P8).
3. Tell the user they can confirm with `wichtelm validate <file>.strat` (exit 0 = clean).
4. Offer to write the matching TOML config (`config-cli-report.md`) and show the
   `wichtelm run <config>.toml` command.

## Worked micro-example

```
Q: Timeframe? → 6 (1d)
Q: Sides? → 3 (both)
Q: Long entry trigger? → 11 → "price above EMA" → period? 200   => price_above_ema(200)
Q: Confirmation? → 2 → strong bullish HA            => And ha_strong_bullish()
Q: Stop-loss? → 3 (ATR) → mult 1.5, period 14
Q: Take-profit? → 3 (ATR) → mult 2.5
Q: Another long entry? → no
Q: Long exit trigger? → price_crosses_below_ema(200)
... (mirror for short) ...
```
→ produces the `ha-ema200-atr.strat` example shape. Always end by showing the assembled,
validated file.
