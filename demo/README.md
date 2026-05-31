# wichtelm-app demo

Runnable end-to-end examples of `wichtelm-app`: strategies in the Gherkin DSL,
synthetic market data, config files, and the **HTML reports the tool actually
produced** from them. `run_demo.sh` doubles as an end-to-end smoke test
(build → parse → load → backtest → render).

## The demo suite

**Seven** strategies. **Five** "clean" demos — one per **timeframe**, realistic
per asset class — cover most of the surface; **two** "showcase" demos sweep up
every remaining primitive. Two synthetic instruments (deliberately fake names
per CLAUDE.md §17, never real tickers), each modelling its asset class
**realistically**:

- **`TSTX`** — a synthetic **equity** at `1d` / `1w`. Bars exist on **weekdays
  only** (Mon–Fri), exactly like a real stock, so daily/weekly charts show the
  normal small weekend gaps — realistic, and still clean.
- **`TSTC`** — a synthetic **crypto** asset at `1h` / `4h`. Crypto trades 24/7,
  so continuous hourly bars are the realistic shape here — and the intraday
  charts stay gap-free.

| Demo (timeframe) | Strategy | Showcases | CSV report | EODHD config |
|---|---|---|---|---|
| **Trend Rider** · `1w` (equity) | `trend-rider.strat` | MA trend-filter primitives (`price_crosses_above/below_ema`, `sma_above_ema`), long+short, stop/take | `reports/tstx-trend-rider-1w-report.html` | `eodhd-aapl-trend-rider.toml` *(profit)* |
| **Swing** · `1d` (+`1w` background, equity) | `swing-multi-tf.strat` | multi-timeframe, RSI primitives (`rsi_oversold/overbought`), window aggregates (`highest_high`/`lowest_low` on 1w), stop+take | `reports/tstx-swing-1d-report.html` | `eodhd-vti-swing.toml` *(profit)* |
| **Pivot Levels** · `1d` (equity) | `pivot-levels.strat` | pivot primitives (`price_crosses_above/below_pivot` on R1/P/S1), long+short | `reports/tstx-pivot-1d-report.html` | `eodhd-tsla-pivot.toml` *(profit)* |
| **MACD Momentum** · `4h` (crypto) | `macd-momentum.strat` | all four MACD primitives, `avg_volume`/`volume`, **`atr_value` stop + warmup-suppression** (INC2), pyramiding | `reports/tstc-macd-4h-report.html` | — (driver has no 4h) |
| **Heikin-Ashi Reversal** · `1h` (crypto) | `ha-reversal.strat` | HA primitives (`ha_bullish/bearish_reversal`, `ha_strong_bullish/bearish`), RSI extremes, long+short, stop/take | `reports/tstc-ha-1h-report.html` | — (free demo token serves only ~4 mo of intraday) |
| **MA & RSI Showcase** · `1d` (equity) | `showcase-ma-rsi.strat` | the remaining MA-filter primitives (`price_above/below_sma/ema`, `price_crosses_above/below_sma`, `sma_crosses_above/below_ema`), `rsi_crosses_50`, pivot **states** (`price_above/below_pivot`), `stddev` | `reports/tstx-showcase-ma-1d-report.html` | `eodhd-aapl-showcase-ma.toml` *(showcase)* |
| **MACD & HA Showcase** · `1d` (equity) | `showcase-macd-ha.strat` | MACD **numeric** series (`macd_line`/`signal`/`histogram`) + crosses, `ha_doji`, `ha_strong`, `highest_close`/`lowest_close`, `avg_volume`, `atr_value` stop | `reports/tstx-showcase-macd-1d-report.html` | `eodhd-vti-showcase-macd.toml` *(showcase)* |

The five clean demos + two showcases together exercise **every** primitive in
the §3.7 catalog, across weekly / daily / 4-hour / hourly charts. The two
showcase demos are deliberately *not* profit-tuned — their job is feature
coverage, not returns.

## Running it

From the repository root:

```sh
./demo/run_demo.sh                 # build, regenerate data, run all 7 CSV demos
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

**Always regenerate the committed `tstx-*` / `tstc-*-report.html` reports from
CSV after any change that affects report rendering** (the generator, the report
template/CSS, or the demo strategies/data), and commit them — otherwise the
committed reference reports drift silently from the code. Regenerate in this
canonical environment (where `GoldenChartImageTest` passes, so chart PNGs are
stable): run each `demo/tstx-*.toml` / `demo/tstc-*.toml`, then rename each
fresh `{config}_{timestamp}.html` to its committed `{config}-report.html`.
EODHD-derived reports are never committed (see below).

### Parameter sweep (CLAUDE.md §18)

`demo/tstx-swing-1d-sweep.toml` reuses the swing strategy and CSV fixtures but
adds a `[sweep]` table that ranges over four parameters (81 combinations). The
`sweep` command loads the data once, runs every combination, prints a ranked
table and writes a CSV of combination → metrics:

```sh
java -jar target/wichtelm.jar sweep demo/tstx-swing-1d-sweep.toml --objective sharpe --top 5
java -jar target/wichtelm.jar sweep demo/tstx-swing-1d-sweep.toml --objective total_return --top 5
```

The default objective is `sharpe` (risk-adjusted; also `total_return`,
`sortino`, `calmar`, `profit_factor`). To explore a winning combination in a
full per-trade HTML report, copy its printed `[parameters]` block into a plain
`run` config and `wichtelm run` it.

Each run writes a timestamped `{basename}_sweep_{timestamp}.csv`; those are
regenerable run artifacts and are git-ignored (not committed), like the
timestamped HTML. As a stable reference — parity with the committed
`*-report.html` demos — one promoted leaderboard is checked in at
`demo/reports/tstx-swing-1d-sweep-leaderboard.csv` (the `--objective sharpe` run
of the config above; 81 combinations, ranked best-first). Regenerate it with:

```sh
cd demo && java -jar ../target/wichtelm.jar sweep tstx-swing-1d-sweep.toml --objective sharpe
mv reports/tstx-swing-1d-sweep_sweep_*.csv reports/tstx-swing-1d-sweep-leaderboard.csv
```

## The synthetic data

`GenerateData.java` is a deterministic, JDK-only single-file program (it lives
under `demo/`, outside `src/` — it is never compiled into the app, only run on
demand). It emits two instruments:

- **`TSTX`** (equity): a daily price path over ~3 calendar years, emitting bars
  on **weekdays only**, then grouping them into Monday-anchored weekly bars
  (`TSTX_1d.csv`, `TSTX_1w.csv`).
- **`TSTC`** (crypto): a continuous 1h base over two years, aggregated to 4h
  (`TSTC_1h.csv`, `TSTC_4h.csv`).

Each price path layers a multi-year regime cycle (bull → bear → bull), medium
and short swings, and a small wobble — enough structure for a realistic mix of
winning and losing trades. Volume is synthetic but always positive (the
volume-gated demo needs it). Re-running reproduces the committed `data/*.csv`
exactly.

## Running on real data (EODHD)

The daily/weekly strategies also run against live EODHD data — just
`data_source = "eodhd"` instead of `"csv"`. There are **five** `eodhd-*.toml`
configs, all on EODHD's free public `demo` token (no signup), in two groups:

- **Profit** (3) — instrument + window selected so the strategy's *style* matches
  the *regime* and the run is positive: trend-follow on AAPL's 2021-2024 uptrend
  (`eodhd-aapl-trend-rider`), pivot breakouts on volatile TSLA 2023-2024
  (`eodhd-tsla-pivot`), mean-reversion on range-bound VTI 2015-2016
  (`eodhd-vti-swing`).
- **Showcase** (2) — *not* profit-tuned; they just exercise the remaining
  primitives on real daily data (`eodhd-aapl-showcase-ma`, `eodhd-vti-showcase-macd`).

```sh
export EODHD_API_TOKEN=demo
java -jar target/wichtelm.jar run demo/eodhd-aapl-trend-rider.toml
# or, to run every EODHD config in one pass:
./demo/run_eodhd_demos.sh
```

(Picking a favourable instrument+window for the "profit" group is *selection*,
not prediction — the report disclaimer already says past ≠ future. It's there to
show the tool producing a clean positive run, nothing more.)

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

> **Why only daily/weekly EODHD configs?** The `demo` token's **EOD endpoint
> serves full history** (decades for AAPL/VTI), so the five daily/weekly demos
> span their full multi-year windows (well over a year). But the token's
> **intraday endpoint only returns a rolling ~4-month window** — so the 1h
> Heikin-Ashi strategy *can't* be given a ≥1-year live demo on the free token,
> and the 4h MACD strategy isn't supported by the driver at all. Both intraday
> strategies are therefore demoed only on synthetic data (which does span ≥1
> year). With a **paid key** (full intraday history) you can point an EODHD
> config at `BTC-USD.CC` `1h` and get a multi-year intraday run.

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
