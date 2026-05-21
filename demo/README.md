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
| `data/SPX2020_1h.csv` / `data/SPX2020_1d.csv` | Hourly + daily OHLCV bars, full calendar year 2020 — calibrated to the real S&P 500 COVID year (see below) |
| `data/SPX2022_1h.csv` / `data/SPX2022_1d.csv` | Hourly + daily OHLCV bars, full calendar year 2022 — calibrated to the real S&P 500 bear market (see below) |
| `demo-backtest.toml` | The original per-backtest config (`DEMO` symbol, 120 days) |
| `spx2020-backtest.toml` / `spx2022-backtest.toml` | Per-backtest configs that run the same strategy against the SPX 2020 / 2022 datasets |
| `GenerateData.java` | Deterministic generator for the `DEMO` CSV files (single-file Java program) |
| [`SyntheticDataGenerator`](../src/main/java/net/jacopobiscella/wichtelm/demo/SyntheticDataGenerator.java) | Regime-switching GBM + GARCH generator for the SPX 2020 / 2022 hourly datasets |
| [`DailyAggregator`](../src/main/java/net/jacopobiscella/wichtelm/demo/DailyAggregator.java) | Aggregates a 1h CSV into the matching 1d series (used to produce `SPX2020_1d.csv` / `SPX2022_1d.csv`) |
| `run_demo.sh` | One-shot end-to-end run (build, generate, validate, backtest) for the `DEMO` example |
| `reports/demo-backtest-report.html` | The committed HTML report for the `DEMO` backtest |
| `reports/spx2020-backtest-report.html` / `reports/spx2022-backtest-report.html` | The committed HTML reports for the SPX 2020 / 2022 backtests |

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

## Real market data via EODHD (`DemoDataDownloader`)

The committed `data/*.csv` files are currently **synthetic** (see
*Synthetic data* below). They are being migrated to **real intraday data
fetched from [EODHD](https://eodhd.com/)** via the
[`DemoDataDownloader`](../src/main/java/net/jacopobiscella/wichtelm/demo/DemoDataDownloader.java)
CLI, which downloads OHLC bars through the `frau-holle-eodhd` driver and
writes them into `data/` in the canonical
`time,open,high,low,close,volume` schema the loader already reads.

The dataset manifest is [`data-sources.toml`](data-sources.toml). Fetch all
entries with:

```sh
mvn -q exec:java \
  -Dexec.mainClass=net.jacopobiscella.wichtelm.demo.DemoDataDownloader \
  -Dexec.args="demo/data-sources.toml"
```

Each `[[dataset]]` writes `data/{symbol}_{timeframe}.csv`. An existing CSV is
left untouched unless its entry sets `refetch = true`. A failed fetch (network
error, unknown ticker) is logged and skipped; the batch continues.

**Token.** The downloader uses EODHD's public free token (`api_token=demo`),
hardcoded — no secret, no payment. The demo token covers a fixed set of
tickers: `AAPL.US`, `TSLA.US`, `VTI.US`, `AMZN.US`, `BTC-USD.CC`,
`EURUSD.FOREX`. The demos use `AAPL.US`, `TSLA.US`, and `VTI.US`.

**Network requirement.** EODHD must be reachable from wherever the downloader
runs. In a sandboxed environment with an outbound allowlist, `eodhd.com` has
to be on it; otherwise every fetch returns "Host not in allowlist" (surfaced
as HTTP 403) and no data is written. This is why the CSV migration is staged:
the downloader, manifest, and dependency bump land first, and the actual fetch
plus report regeneration happens in an environment with EODHD access.

**Raw-data limitation.** Intraday data from EODHD (and most providers) is
*raw* — not adjusted for stock splits or dividends. This is the
industry-standard pattern for intraday endpoints. The periods in
`data-sources.toml` are chosen to avoid corporate-action discontinuities
(AAPL's last split was Aug 2020, 4:1; TSLA's was Aug 2022, 3:1). A single-name
backtest crossing a split date would produce meaningless results. Workarounds:
use a broad ETF (`VTI`), forex/crypto, or windows that don't cross a split —
which is what the manifest does.

**Ticker selection criteria.** Tickers are limited to those the free demo
token serves. Within that set: `AAPL.US` (liquid large-cap, clean trends) and
`TSLA.US` (high volatility, frequent reversals — good for the HA/MACD pattern
demos) for single-name behaviour, and `VTI.US` (total-market ETF, naturally
split-adjusted and corporate-action-light) for the mean-reversion demos that
want a calmer, broad-market series.

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

## Realistic regime datasets (SPX 2020, SPX 2022)

`data/SPX2020_1h.csv` and `data/SPX2022_1h.csv` feed the Block 7 demo
backtests. Each is one full calendar year of hourly bars (24/7, no session
gaps — macroscopic regimes still follow real calendar dates), produced by
[`SyntheticDataGenerator`](../src/main/java/net/jacopobiscella/wichtelm/demo/SyntheticDataGenerator.java).

The model is **regime-switching geometric Brownian motion** with
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
datasets, not real prices — they exist for demo backtests, not for any
research that depends on the actual historical tape.

The generator is deterministic in its seed, so re-running produces
byte-identical CSVs. To regenerate the hourly files and derive the
matching 1d series:

```sh
mvn -q compile
java -cp target/classes net.jacopobiscella.wichtelm.demo.SyntheticDataGenerator
java -cp target/classes net.jacopobiscella.wichtelm.demo.DailyAggregator \
    demo/data/SPX2020_1h.csv demo/data/SPX2020_1d.csv
java -cp target/classes net.jacopobiscella.wichtelm.demo.DailyAggregator \
    demo/data/SPX2022_1h.csv demo/data/SPX2022_1d.csv
```

## SPX backtest reports

`spx2020-backtest.toml` and `spx2022-backtest.toml` run the same
`mean-reversion-trend.strat` strategy (RSI mean reversion with a daily
EMA trend filter) against the two SPX datasets. To produce a fresh
timestamped report:

```sh
mvn -q clean package -DskipTests
java -jar target/wichtelm.jar run demo/spx2020-backtest.toml
java -jar target/wichtelm.jar run demo/spx2022-backtest.toml
```

The stable committed copies (`reports/spx2020-backtest-report.html`,
`reports/spx2022-backtest-report.html`) show how the same strategy
behaves under very different market regimes:

| Metric | SPX 2020 (COVID year) | SPX 2022 (bear market) |
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
