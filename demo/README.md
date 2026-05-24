# wichtelm-app demo

A complete, runnable end-to-end example of `wichtelm-app`: a multi-timeframe
strategy, synthetic CSV market data, a config file, and the **HTML report the
tool actually produced** from them.

It doubles as an end-to-end smoke test — `run_demo.sh` exercises the whole
pipeline (build → parse → load CSV → backtest → render report).

## Contents

| Path | What it is |
|---|---|
| `strategies/mean-reversion-trend.strat` | The demo strategy (5 scenarios, multi-timeframe, stop-loss) |
| `data/DEMO_1h.csv` | Hourly OHLC bars — the primary timeframe (2880 bars, 120 days) |
| `data/DEMO_1d.csv` | Daily OHLC bars — the higher timeframe used by the trend filter (120 bars) |
| `data/TST2020_1h.csv` / `data/TST2020_1d.csv` | Hourly + daily OHLCV bars, full calendar year 2020 — synthetic `TST2020` instrument, calibrated to the *shape* of the S&P 500 COVID year (see below) |
| `data/TST2022_1h.csv` / `data/TST2022_1d.csv` | Hourly + daily OHLCV bars, full calendar year 2022 — synthetic `TST2022` instrument, calibrated to the *shape* of the S&P 500 bear market (see below) |
| `demo-backtest.toml` | The original per-backtest config (`DEMO` symbol, 120 days) |
| `tst2020-backtest.toml` / `tst2022-backtest.toml` | Per-backtest configs that run the same strategy against the TST2020 / TST2022 datasets |
| `GenerateData.java` | Deterministic generator for the `DEMO` CSV files (single-file Java program) |
| `run_demo.sh` | One-shot end-to-end run (build, generate, validate, backtest) for the `DEMO` example |
| `reports/demo-backtest-report.html` | The committed HTML report for the `DEMO` backtest |
| `reports/tst2020-backtest-report.html` / `reports/tst2022-backtest-report.html` | The committed HTML reports for the TST2020 / TST2022 backtests |

## Running it

From the repository root:

```sh
./demo/run_demo.sh
```

Or step by step:

```sh
mvn clean package -DskipTests          # build target/wichtelm.jar
java demo/GenerateData.java            # (re)create the CSV data
java -jar target/wichtelm.jar validate demo/strategies/mean-reversion-trend.strat
java -jar target/wichtelm.jar run demo/demo-backtest.toml
```

Each run writes a new timestamped HTML file under `demo/reports/`
(reports are never overwritten). The committed
`reports/demo-backtest-report.html` is one such run, renamed to a stable
path so it can be linked to.

## Running a demo on real data (EODHD)

**Quickstart — a full real-data backtest in two commands.** Nine ready-to-run
configs are committed (one per strategy), all using EODHD's free public `demo`
token, so there's no signup, key, or download step:

```sh
export EODHD_API_TOKEN=demo
java -jar target/wichtelm.jar run demo/eodhd-aapl-macd-boolean.toml
```

That fetches real AAPL.US data live and writes a full HTML report to
`reports/` — same pipeline as the CSV demos, just real prices. Pick any of:

| Config | Strategy | Ticker |
|---|---|---|
| `eodhd-aapl-intro.toml` | mean reversion + daily trend | AAPL.US |
| `eodhd-vti-mean-reversion.toml` | mean reversion + daily trend | VTI.US |
| `eodhd-aapl-indicator-showcase.toml` | indicator showcase | AAPL.US |
| `eodhd-tsla-macd-breakout.toml` | MACD breakout | TSLA.US |
| `eodhd-tsla-ha-pattern.toml` | HA pattern reversal at RSI extremes | TSLA.US |
| `eodhd-aapl-macd-boolean.toml` | MACD boolean cross | AAPL.US |
| `eodhd-tsla-ha-streak.toml` | pure HA pattern reversal after streak | TSLA.US |
| `eodhd-vti-ma-atr-trend.toml` | MA trend filter + ATR stop (0.52) | VTI.US |
| `eodhd-aapl-pivot-bias.toml` | daily pivot bias (0.52) | AAPL.US |

The free `demo` token serves `AAPL.US`, `TSLA.US`, `VTI.US`, `AMZN.US`,
`BTC-USD.CC`, `EURUSD.FOREX`.

> **Do NOT commit the generated EODHD reports (or any EODHD-derived CSV).** EODHD
> market data is licensed for your own use, not redistribution, and the free
> `demo` token is for evaluation only. A backtest report embeds the real prices
> (the charts are the data; the trade tables list exact OHLC/entry/exit values),
> so publishing one redistributes their data. This is why the committed reference
> reports use **synthetic** `TST`/`DEMO` data (CLAUDE.md §17) — run the EODHD demos
> locally and view them there. `reports/.gitignore` excludes `eodhd-*-report.html`
> so they can't be committed by accident.

> **The `demo` token only serves a ROLLING recent intraday window (~4 months).**
> Every config's `[date_range]` is set to that recent slice on purpose — a
> historical window returns no bars and the run fails with
> `DataSourceUnavailableException: ... insufficient ... [V5]`. The dates rot as
> the window rolls forward, so refresh them when a run starts failing. Probe the
> currently-served range with:
>
> ```sh
> curl -s 'https://eodhd.com/api/intraday/AAPL.US?api_token=demo&interval=1h&fmt=json' \
>   | python3 -c 'import sys,json;d=json.load(sys.stdin);print(d[0]["datetime"],"→",d[-1]["datetime"])'
> ```
>
> then set every `[date_range]` inside that span. (A paid key has full history
> and is not subject to this limit — point `api_token_env` at it.)

To regenerate **every** EODHD report in one pass, run
`./demo/run_eodhd_demos.sh` (after `export EODHD_API_TOKEN=demo`). It builds
the JAR, runs each `eodhd-*.toml`, and promotes each result to a stable
`reports/<config>-report.html` ready to commit; set `WICHTELM_SKIP_BUILD=1`
to reuse an existing `target/wichtelm.jar`.

### How it works / using your own ticker

Each demo runs two ways, both straight from the `wichtelm` CLI:

- `data_source = "csv"` — offline, against the committed `data/*.csv` fixtures
  (zero network, zero credentials). The reports committed under `reports/` are
  these CSV runs.
- `data_source = "eodhd"` — live, through the `frau-holle-eodhd` driver, which
  fetches the bars at backtest time. The token is read from the env var named
  by `[eodhd].api_token_env`; it never lives in the config.

To run a different ticker, window, or your own EODHD key, copy a config and
edit `symbol` / `[date_range]` (point `api_token_env` at your key's env var).
Minimal shape:

```toml
strategy    = "strategies/mean-reversion-trend.strat"
symbol      = "AAPL.US"
data_source = "eodhd"

[date_range]
from = 2024-01-01
to   = 2024-03-31

[sizing]
position_size_pct = 50
pyramiding        = false

[eodhd]
api_token_env = "EODHD_API_TOKEN"
```

The committed `reports/` are CSV runs only because this repo's CI has no
outbound network — real-data reports are something you generate locally with
the command above and can commit if you want them tracked.

### Snapshotting EODHD data to a committed CSV (optional)

To make a live dataset reproducible offline, capture it once with `curl`
against EODHD's CSV endpoint and reshape it to the loader's
`time,open,high,low,close,volume` schema — still just the command line, no app
code:

```sh
# 1h intraday, e.g. AAPL.US over Q1 2024 (Unix-second from/to)
curl -s "https://eodhd.com/api/intraday/AAPL.US?api_token=demo&interval=1h&from=1704067200&to=1711843200&fmt=csv" \
  | tail -n +2 \
  | awk -F, 'BEGIN{print "time,open,high,low,close,volume"} {gsub(" ","T",$3); print $3"Z,"$4","$5","$6","$7","$8}' \
  > demo/data/AAPL.US_1h.csv
```

(EODHD's intraday CSV columns are `Timestamp,Gmtoffset,Datetime,Open,High,Low,Close,Volume`;
the `awk` keeps `Datetime`→`time` plus OHLCV. For the daily `eod` endpoint the
date column is already `YYYY-MM-DD` — append `T00:00:00Z`.) Then a config with
`data_source = "csv"` and `file = "data/{symbol}_{timeframe}.csv"` reads it
unchanged.

### Notes on real data

- **Raw, not adjusted.** Intraday data from EODHD (and most providers) is not
  adjusted for splits or dividends — standard for intraday endpoints. Choose
  windows that don't cross a corporate action (AAPL's last split was Aug 2020
  4:1; TSLA's was Aug 2022 3:1), or use a broad ETF (`VTI`), forex, or crypto.
  A single-name backtest crossing a split would produce meaningless results.
- **Network.** A live EODHD run needs `eodhd.com` reachable. In a sandboxed
  environment with an outbound allowlist it must be on the list; otherwise the
  fetch fails with "Host not in allowlist" (HTTP 403). The CSV path needs no
  network.

## The strategy

`mean-reversion-trend.strat` is a mean-reversion strategy with a
higher-timeframe trend filter:

- **Primary timeframe:** 1h. **Trend filter:** an `ema` on the 1d series,
  resolved lookahead-safely.
- **Long:** when the hourly RSI crosses *below* the oversold threshold while
  price is *above* the daily trend, open a long with a stop-loss.
- **Short:** the mirror image — RSI crosses *above* overbought while price is
  *below* the daily trend.
- **Exits:** RSI reverting through the opposite threshold, plus a protective
  price floor on longs.

It uses only the DSL features the backtest runtime supports today
(`ema`, `rsi`, market variables, `entry_price`).

## What the report shows

`reports/demo-backtest-report.html` is fully self-contained — price charts
are embedded as base64 PNGs and the equity/drawdown curves as inline SVG, so
it opens in any browser with no external assets. It contains:

- the 10 aggregate metrics;
- one box per scenario (sorted alphabetically) with a price chart per
  timeframe, a trigger marker on every bar the scenario fired, and a
  per-trigger sub-report table;
- the equity curve, the drawdown curve, the full trade list, and the
  diagnostic counters.

This run produces 42 trades (22 long, 20 short). The sample strategy is
deliberately simple and **underperforms on this dataset** — that is exactly
the kind of verdict a backtest exists to deliver, and the report makes the
losing pattern visible per scenario. One scenario (`Exit long on protective
floor`) does not fire in this run; its box still renders, demonstrating how
the report handles a scenario with zero triggers.

## The data

`GenerateData.java` produces a deterministic synthetic price path: a slow
trend regime (a rise then a fall, so the trend filter admits longs early and
shorts late) plus three beating oscillations that drive the RSI across its
thresholds with varying swing sizes. The 1d series is aggregated from the 1h
series, so the two timeframes are always mutually consistent.

## Realistic regime datasets (TST2020, TST2022)

`data/TST2020_1h.csv` and `data/TST2022_1h.csv` feed the Block 7 demo
backtests. Each is one full calendar year of hourly bars (24/7, no session
gaps — macroscopic regimes still follow real calendar dates). They are
committed **synthetic fixtures** under a deliberately fake instrument name
(`TST`, not a real ticker): realistic-looking data for offline demo runs, not
real prices. For real market data, run the demos against EODHD (see *Running
against real data* above).

The fixtures were produced by a regime-switching GBM + GARCH model (the
generator no longer lives in the source tree — these are now plain committed
data files). The model is **regime-switching geometric Brownian motion** with
**GARCH(1,1) volatility clustering** on the log returns:

```
sigma^2_t = omega * sigma_lr^2 + alpha * r_{t-1}^2 + beta * sigma^2_{t-1}
r_t       = (mu - 0.5 * sigma^2_t) * dt + sigma_t * sqrt(dt) * Z_t,  Z_t ~ N(0,1)
S_t       = S_{t-1} * exp(r_t)
```

Within each calendar window the annual drift `mu` and the long-run
volatility are piecewise constant and calibrated to the shape of the real
episode the regime represents:

- **2020**: calm bull (Jan – Feb 19) → COVID crash (Feb 19 – Mar 23, ~-34%)
  → V-recovery → autumn chop → vaccine rally.
- **2022**: early decline → relief rally → bear leg → bear-market rally →
  another leg lower → year-end relief.

Macro statistics (drawdowns, regime volatilities, year-end levels) come in
within a handful of percentage points of the real S&P 500 figures;
individual bars are pure simulation. These are realistic-looking synthetic
datasets, not real prices — they exist for offline demo backtests, not for any
research that depends on the actual historical tape. To run a demo against real
prices instead, point its config at `data_source = "eodhd"` (see *Running
against real data* above).

## TST backtest reports

`tst2020-backtest.toml` and `tst2022-backtest.toml` run the same
`mean-reversion-trend.strat` strategy (RSI mean reversion with a daily
EMA trend filter) against the two TST datasets. To produce a fresh
timestamped report:

```sh
mvn -q clean package -DskipTests
java -jar target/wichtelm.jar run demo/tst2020-backtest.toml
java -jar target/wichtelm.jar run demo/tst2022-backtest.toml
```

The stable committed copies (`reports/tst2020-backtest-report.html`,
`reports/tst2022-backtest-report.html`) show how the same strategy
behaves under very different market regimes:

| Metric | TST2020 (COVID-year shape) | TST2022 (bear-market shape) |
|---|---:|---:|
| Total return | −4.0% | +2.6% |
| Trades | 17 | 20 |
| Win rate | 52.9% | 70.0% |
| Max drawdown | 7.5% | 3.8% |
| Sharpe ratio | −0.84 | +0.66 |
| Profit factor | 0.58 | 1.45 |

A clean illustration of a mean-reversion strategy's preferences — it
loses modestly in 2020's strong V-recovery (mean reversion is punished
by sustained trends) and wins modestly in 2022's choppier bear market
where the opposing-side reversions actually pay.
