# wichtelm-app demo

Runnable end-to-end examples of `wichtelm-app`: strategies in the Gherkin DSL,
synthetic market data, config files, and the **HTML reports the tool actually
produced** from them. `run_demo.sh` doubles as an end-to-end smoke test
(build → parse → load → backtest → render).

## The demo suite

Five strategies, one per **timeframe**, chosen to cover the whole DSL feature
surface with the fewest demos. Two synthetic instruments (deliberately fake
names per CLAUDE.md §17, never real tickers), each modelling its asset class
**realistically**:

- **`TSTX`** — a synthetic **equity** at `1d` / `1w`. Bars exist on **weekdays
  only** (Mon–Fri), exactly like a real stock, so daily/weekly charts show the
  normal small weekend gaps — realistic, and still clean.
- **`TSTC`** — a synthetic **crypto** asset at `1h` / `4h`. Crypto trades 24/7,
  so continuous hourly bars are the realistic shape here — and the intraday
  charts stay gap-free.

| Demo (timeframe) | Strategy | Showcases | CSV report | EODHD config |
|---|---|---|---|---|
| **Trend Rider** · `1w` (equity) | `trend-rider.strat` | MA trend-filter primitives (`price_crosses_above/below_ema`, `sma_above_ema`), long+short, stop/take | `reports/tstx-trend-rider-1w-report.html` | `eodhd-vti-trend-rider.toml` |
| **Swing** · `1d` (+`1w` background, equity) | `swing-multi-tf.strat` | multi-timeframe, RSI primitives (`rsi_oversold/overbought`), window aggregates (`highest_high`/`lowest_low` on 1w), stop+take | `reports/tstx-swing-1d-report.html` | `eodhd-aapl-swing.toml` |
| **Pivot Levels** · `1d` (equity) | `pivot-levels.strat` | pivot primitives (`price_crosses_above/below_pivot` on R1/P/S1), long+short | `reports/tstx-pivot-1d-report.html` | `eodhd-aapl-pivot.toml` |
| **MACD Momentum** · `4h` (crypto) | `macd-momentum.strat` | all four MACD primitives, `avg_volume`/`volume`, **`atr_value` stop + warmup-suppression** (INC2), pyramiding | `reports/tstc-macd-4h-report.html` | — (driver has no 4h) |
| **Heikin-Ashi Reversal** · `1h` (crypto) | `ha-reversal.strat` | HA primitives (`ha_bullish/bearish_reversal`, `ha_strong_bullish/bearish`), RSI extremes, long+short, stop/take | `reports/tstc-ha-1h-report.html` | `eodhd-btc-ha.toml` (crypto, 24/7) |

Between them the demos exercise: base indicators, MACD, RSI primitives, HA
primitives, MA trend-filter primitives, pivot primitives, window aggregates,
`atr_value` dynamic stops + warmup suppression, percentage stop/take, multi-
timeframe Background series, long/short entries and exits, pyramiding, and
parameter overrides — across weekly, daily, 4-hour and hourly charts.

## Running it

From the repository root:

```sh
./demo/run_demo.sh                 # build, regenerate data, run all 5 CSV demos
```

Or step by step:

```sh
mvn clean package -DskipTests                       # build target/wichtelm.jar
java demo/GenerateData.java                          # (re)create the TSTX/TSTC CSVs
java -jar target/wichtelm.jar validate demo/strategies/swing-multi-tf.strat
java -jar target/wichtelm.jar run demo/tstx-swing-1d.toml
```

Each run writes a new timestamped HTML file under `demo/reports/` (reports are
never overwritten); the committed `tstx-*` / `tstc-*-report.html` files are
stable copies.

## The synthetic data

`GenerateData.java` is a deterministic, JDK-only single-file program (it lives
under `demo/`, outside `src/` — it is never compiled into the app, only run on
demand). It emits two instruments:

- **`TSTX`** (equity): a daily price path over ~3 calendar years, emitting bars
  on **weekdays only**, then grouping them into Monday-anchored weekly bars
  (`TSTX_1d.csv`, `TSTX_1w.csv`).
- **`TSTC`** (crypto): a continuous 1h base over one year, aggregated to 4h
  (`TSTC_1h.csv`, `TSTC_4h.csv`).

Each price path layers a multi-year regime cycle (bull → bear → bull), medium
and short swings, and a small wobble — enough structure for a realistic mix of
winning and losing trades. Volume is synthetic but always positive (the
volume-gated demo needs it). Re-running reproduces the committed `data/*.csv`
exactly.

## Running on real data (EODHD)

The same five strategies run against live EODHD data — just `data_source =
"eodhd"` instead of `"csv"`. Committed `eodhd-*.toml` configs use EODHD's free
public `demo` token (no signup):

```sh
export EODHD_API_TOKEN=demo
java -jar target/wichtelm.jar run demo/eodhd-aapl-swing.toml
# or, to run every EODHD config in one pass:
./demo/run_eodhd_demos.sh
```

The free `demo` token serves `AAPL.US`, `TSLA.US`, `VTI.US`, `AMZN.US`,
`BTC-USD.CC`, `EURUSD.FOREX`.

> **Do NOT commit the generated EODHD reports (or any EODHD-derived CSV).** EODHD
> market data is licensed for your own use, not redistribution, and the `demo`
> token is for evaluation only. A backtest report embeds the real prices (the
> charts are the data; the trade tables list exact OHLC/entry/exit values), so
> publishing one redistributes their data. This is why the committed reference
> reports use **synthetic** `TSTX` data — run the EODHD demos locally and view
> them there. `reports/.gitignore` excludes `eodhd-*-report.html` so they can't
> be committed by accident.

> **Daily/weekly vs. intraday history.** The daily and weekly EODHD configs use
> the EOD endpoint, which serves years of history. **Intraday (the 1h crypto
> config) only gets a rolling ~4-month window from the `demo` token**, so its
> `[date_range]` rots over time — refresh it when a run fails with
> `... insufficient ... [V5]`. Probe the currently-served range with:
>
> ```sh
> curl -s 'https://eodhd.com/api/intraday/BTC-USD.CC?api_token=demo&interval=1h&fmt=json' \
>   | python3 -c 'import sys,json;d=json.load(sys.stdin);print(d[0]["datetime"],"→",d[-1]["datetime"])'
> ```
>
> A paid key has full intraday history and is not subject to this limit — point
> `api_token_env` at it.

To run a different ticker/window/key, copy a config and edit `symbol` /
`[date_range]` / `[eodhd].api_token_env`.

### Snapshotting EODHD data to CSV (optional, local only)

To make a live dataset reproducible offline, capture it once with `curl` and
reshape it to the loader's `time,open,high,low,close,volume` schema:

```sh
curl -s "https://eodhd.com/api/intraday/AAPL.US?api_token=demo&interval=1h&from=1704067200&to=1711843200&fmt=csv" \
  | tail -n +2 \
  | awk -F, 'BEGIN{print "time,open,high,low,close,volume"} {gsub(" ","T",$3); print $3"Z,"$4","$5","$6","$7","$8}' \
  > demo/data/MYSNAP_1h.csv
```

Then a `data_source = "csv"` config with `file = "data/{symbol}_{timeframe}.csv"`
reads it unchanged. (Such a CSV still contains licensed data — keep it local,
don't commit it.)

### Notes on real data

- **Raw, not adjusted.** Intraday EODHD data is not adjusted for splits or
  dividends. Choose windows that don't cross a corporate action (AAPL's last
  split was Aug 2020), or use a broad ETF (`VTI`), forex, or crypto.
- **Continuous vs. gapped charts.** The synthetic data matches each asset class:
  `TSTX` (equity) is weekday-only, `TSTC` (crypto) is 24/7. Real **equity
  intraday** (1h) is market-hours-only, so its charts look "jumpier" than the
  daily/weekly views; a 24/7 instrument like `BTC-USD.CC` is the realistic
  analogue for the intraday demos.
- **Network.** A live EODHD run needs `eodhd.com` reachable; the CSV path needs
  no network.
