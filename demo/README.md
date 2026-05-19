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
| `demo-backtest.toml` | The per-backtest config that ties strategy + data + date range together |
| `GenerateData.java` | Deterministic generator for the two CSV files (single-file Java program) |
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
