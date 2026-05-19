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
| `data/SPX2020_1h.csv` | Hourly OHLCV bars, full calendar year 2020 — calibrated to the real S&P 500 COVID year (see below) |
| `data/SPX2022_1h.csv` | Hourly OHLCV bars, full calendar year 2022 — calibrated to the real S&P 500 bear market (see below) |
| `demo-backtest.toml` | The per-backtest config that ties strategy + data + date range together |
| `GenerateData.java` | Deterministic generator for the `DEMO` CSV files (single-file Java program) |
| [`SyntheticDataGenerator`](../src/main/java/net/jacopobiscella/wichtelm/demo/SyntheticDataGenerator.java) | Regime-switching GBM + GARCH generator for the SPX 2020 / 2022 datasets |
| `run_demo.sh` | One-shot end-to-end run (build, generate, validate, backtest) |
| `reports/demo-backtest-report.html` | The committed HTML report produced by the run below |

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
byte-identical CSVs. To regenerate:

```sh
mvn -q compile
java -cp target/classes net.jacopobiscella.wichtelm.demo.SyntheticDataGenerator
```

(Optional first arg is the output directory; defaults to `demo/data`.)
