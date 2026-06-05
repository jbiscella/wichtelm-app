# Example strategies

Six ready-to-adapt `.strat` files. The five `ha-*` examples pair Heikin-Ashi with a
confirming indicator — a well-established principle in trading-education material, since HA
smoothing lags and is prone to false signals when used on its own. Each example combines an
HA signal with a momentum or trend filter. These are illustrative starting points, **not**
tuned or recommended strategies, and carry no claim about real-world performance.

All six **parse cleanly** (`wichtelm validate <file>` → exit 0). Adapt one rather than
starting from a blank page.

| File | Popular combo | Primary TF | Catalog features exercised |
|---|---|---|---|
| `ha-rsi-reversal.strat` | HA reversal confirmed by an RSI extreme | 1h | `ha_bullish_reversal` / `ha_bearish_reversal`, `rsi_oversold` / `rsi_overbought`, `ha_strong_*` exits, % stop/take, OR-logic via duplicate scenarios |
| `ha-ema200-atr.strat` | HA + 200-EMA trend filter + ATR stop (SL 1.5×ATR / TP 2.5×ATR) | 1d | `price_above_ema` / `price_below_ema`, `price_crosses_*_ema`, `ha_strong_bullish` / `ha_strong_bearish`, `atr_value(period)` stops, warmup suppression |
| `ha-macd.strat` | HA momentum confirmed by a MACD cross | 4h | `macd_bullish_cross` / `macd_bearish_cross`, `macd_zero_cross_up` / `macd_zero_cross_down`, `ha_strong_*`, % stop/take |
| `ha-ma-crossover.strat` | HA + moving-average crossover (golden/death-cross style) | 1d | `sma_crosses_above_ema` / `sma_crosses_below_ema`, `price_crosses_below_sma`, `ha_strong_*`, % stop/take |
| `ha-multi-tf.strat` | Multi-timeframe HA reversals filtered by a daily EMA trend | 1h (+1d) | Background higher-TF series (`ema(...) on 1d`), lookahead-safe `close is above/below <series>`, `ha_*_reversal`, `ha_strong_*` |
| `canonical.strat` | Reference strategy (mean reversion + trend filter) | 1h (+1d) | parameters, multi-TF Background, `rsi(...)`, comparisons against series & `entry_price`, stop_loss on entries |

## How to adapt one

1. Copy the closest example and rename the `Feature:`.
2. Change parameters (periods, thresholds, stop %) — these are *defaults*; the TOML config can
   override them per run.
3. Swap or add confirmation steps from `../reference/function-catalog.md`. Keep Tier-B
   primitives (`ha_*`, `rsi_*`, `macd_*`, `price_*`, pivot) as **bare** `When`/`And` steps.
4. Re-check against `../reference/validation-rules.md`, then `wichtelm validate <file>`.
5. Write a TOML config (`../reference/config-cli-report.md`) and `wichtelm run` it.

## The catalog boundary (popular combos that v1 cannot express)

Some widely-used HA setups rely on indicators the DSL does **not** have. Don't write these;
use the suggested equivalent instead:

| Wanted | v1 status | Closest expressible alternative |
|---|---|---|
| HA + Stochastic | not in catalog | RSI primitives (`rsi_oversold` / `rsi_overbought` / `rsi_crosses_50`) |
| HA + Bollinger Bands | not in catalog | `stddev(period)` Background series + a comparison; or `highest_high`/`lowest_low` channels |
| HA + ATR **trailing** stop | **supported** | `And with trailing_stop at 3 * atr_value(14)` (ATR-distance) or `And with trailing_stop at 8` (percentage) on an entry scenario |
| HA + Supertrend | not in catalog | a fixed `atr_value(period)` stop/take (see `ha-ema200-atr.strat`), or a `trailing_stop` for a ratcheting exit |
| 50/200 **SMA**-cross golden cross | no SMA-vs-SMA primitive | `sma_crosses_above_ema(50, 200)` (SMA-vs-EMA), as in `ha-ma-crossover.strat` |
| HA + ADX / VWAP / Ichimoku | not in catalog | a trend filter via `price_above_ema` / `sma_above_ema`, or a pivot primitive |

## More examples in the repo

The application ships seven additional production demo strategies (one per timeframe plus two
feature showcases) under `demo/strategies/` — `trend-rider.strat`, `swing-multi-tf.strat`,
`pivot-levels.strat`, `macd-momentum.strat` (pyramiding + ATR-stop warmup suppression),
`ha-reversal.strat`, `showcase-ma-rsi.strat`, `showcase-macd-ha.strat` — each with a matching
TOML config and a committed reference report under `demo/reports/`. They are good references
for pivots, window-aggregate channels, pyramiding, and the numeric MACD series.
