# TOML config, CLI, and reading the report

A `.strat` file describes *what* to trade; a **TOML config** describes *which* symbol, date
range, and data source to run it against. `wichtelm run <config>` produces an HTML report.

## TOML config schema

```toml
# Required — bare keys MUST come before any [table] header
strategy    = "strategies/mean-reversion.strat"   # path to the .strat file
symbol      = "AAPL"
data_source = "csv"            # "csv" or "eodhd"

# Required: date range
[date_range]
from = 2024-01-01              # ISO-8601 date, from < to
to   = 2024-12-31

# Required: position sizing
[sizing]
position_size_pct = 50         # 0 < value <= 100
pyramiding        = false       # default false; true lets a same-direction entry add to the position

# Optional: override strategy parameter defaults (names must match Parameter declarations)
[parameters]
rsi_period = 21
oversold   = 25

# Optional: sweep parameter ranges/lists for `wichtelm sweep` (CLAUDE.md §18).
# A parameter is either fixed (in [parameters]) or swept (here), never both.
[sweep]
overbought = { from = 65, to = 75, step = 5 }   # range -> 65, 70, 75
take_profit = [8, 12, 16]                         # explicit list

# Optional: output
[output]
directory = "./reports"
format    = "html"             # only "html" in v1

# Required when data_source = "csv"
[csv]
file = "data/{symbol}_{timeframe}.csv"   # {symbol} is MANDATORY; {timeframe} optional

# Required when data_source = "eodhd"
[eodhd]
api_token_env = "EODHD_API_TOKEN"        # NAME of an env var; never the token itself
```

> TOML gotcha: every bare key (`strategy`, `symbol`, `data_source`) must appear **before** the
> first `[table]` header, or TOML reads it as a key of that table.

### Config validation rules (C1–C11)

| Rule | Summary |
|---|---|
| C1 | `strategy` required, a readable file path |
| C2 | `symbol` required, non-empty |
| C3 | `date_range.from` / `to` valid ISO-8601 dates with `from < to` |
| C4 | `data_source` is `"csv"` or `"eodhd"` |
| C5 | `sizing.position_size_pct` in `(0, 100]` |
| C6 | `sizing.pyramiding` defaults to `false` |
| C7 | `[parameters]` keys must match `Parameter` names in the strategy (unknown key → error; missing → uses the default) |
| C8 | csv: `[csv].file` required and **must contain `{symbol}`** (a literal path without it is rejected); placeholders only in the file name, parent dir must exist |
| C9 | eodhd: `[eodhd].api_token_env` is required (a missing/blank key is a parse error); whether the *named env var* is actually set is checked later, at run time (see below) |
| C10 | `output.format` must be `"html"` in v1 |
| C11 | unknown top-level keys → warning, not an error |

The optional `[sweep]` section (used only by `wichtelm sweep`, see CLI below) adds four more:

| Rule | Summary |
|---|---|
| C12 | a parameter must not appear in both `[parameters]` (fixed) and `[sweep]` (varied) |
| C13 | every `[sweep]` key must name a `Parameter` declared in the strategy |
| C14 | a range `{ from, to, step }` needs finite numeric values (TOML `nan`/`inf` rejected) with `step > 0` and `from <= to`; a value list `[ … ]` must be non-empty and finite-numeric. For an **integer** parameter, `from`/`to`/`step` must *all* be whole (checked once the strategy's parameter type is known, like C13) |
| C15 | the grid (product of axis sizes) must not exceed `--max-combos` (default 500) — checked before any backtest runs |

A C12–C15 violation throws a `SweepConfigException` (a sibling of `ConfigParseException`).

A parse-time violation throws a `ConfigParseException` naming the offending key and rule.
One thing to keep separate: for C9, only a missing/blank `api_token_env` *key* is a parse
error. If the key is present but the **environment variable it names is unset/empty**, that is
not caught at parse time — it surfaces at run time as a `DataSourceUnavailableException` (the
error still names the missing variable). So a config can parse cleanly and still fail the run
because `EODHD_API_TOKEN` isn't exported.

### Data sources

- **csv** — offline, reproducible. `{symbol}`/`{timeframe}` in `[csv].file` are substituted at
  load time (e.g. `data/{symbol}_{timeframe}.csv` → `data/AAPL_1h.csv`). The driver loads the
  primary timeframe plus any higher timeframes the strategy's Background series need.
- **eodhd** — live EODHD HTTPS API. The token is read at run time from the env var named by
  `api_token_env`; it is never written in the config. `export EODHD_API_TOKEN=...` before running.

> Note: the spec (CLAUDE.md §5.3) defines a global preferences file at
> `~/.config/wichtelm/config.toml` (XDG standard) holding defaults that a per-backtest config
> overrides. Be aware that the v1 CLI does **not yet read** this file (as documented in the
> project README) — in v1, settings come from the per-backtest config and CLI flags, so don't
> rely on global preferences for a working run until that lands.

### EODHD data availability (and the "insufficient market data" error)

If a run fails with `DataSourceUnavailableException: insufficient market data: only <n> <TF>
bar(s) loaded for <symbol> over <from> → <to>; a backtest needs at least 2 bars`, it means the
data source returned only `<n>` bars for the primary timeframe and the backtester needs **at
least 2**. (The message includes a CSV- or EODHD-specific hint; the underlying frau-holle check
is `[V6]`.) Common causes with `data_source = "eodhd"`:

- **Token history limits (the usual culprit).** EODHD's free/registered plan provides only **~1
  year** of EOD history (and ~20 API calls/day); paid plans go back 30+ years (see the
  [EODHD pricing page](https://eodhd.com/pricing)). So a multi-year request on a free token returns
  almost nothing — in one observed case a single recent bar, dated ~1 year before "today" — which
  trips this error.
- **The public `demo` token** serves full EOD history, but **only** for six tickers: `AAPL.US`,
  `TSLA.US`, `VTI.US`, `AMZN.US`, `BTC-USD.CC`, `EURUSD.FOREX`. For those, a daily/weekly strategy
  works out of the box (`export EODHD_API_TOKEN=demo`); any other symbol returns almost nothing.
- **Intraday is short-lived.** EODHD intraday history is limited, and the `demo` token's intraday is
  only a rolling ~4-month window, so a multi-year intraday range returns ~0 bars. Keep intraday
  `[date_range]`s recent.
- **Endpoint by timeframe.** `1d`/`1w` use the EOD endpoint (years of history); `1m`/`5m`/`1h` use
  intraday. **`4h` is not supported** by the EODHD driver (only `1m`, `5m`, `1h`).

Verify what the API actually returns before blaming the strategy — count the bars:

```sh
curl -s "https://eodhd.com/api/eod/AAPL.US?api_token=$EODHD_API_TOKEN&fmt=json&from=2020-01-01&to=2024-12-31&period=d" | grep -o '"date"' | wc -l
```

If that prints ~1, it's the token/plan (try `demo` for the six tickers above, upgrade the plan, or
switch to `data_source = "csv"`); if it prints hundreds, the strategy's timeframe or range is the
issue. The same `[V6]` error also fires for a too-narrow `[date_range]`, or a `{timeframe}` that
doesn't match the CSV file's bar interval.

## CLI

| Command | Effect |
|---|---|
| `wichtelm validate <file>.strat` | parse-check a strategy (all P-rules), no backtest |
| `wichtelm run <config>.toml` | run the backtest and write an HTML report |
| `wichtelm run <config>.toml --no-report` | run but skip report generation |
| `wichtelm run <config>.toml --output-dir <path>` | override the output directory |
| `wichtelm sweep <config>.toml` | run every combination in the config's `[sweep]` table; print a ranked table and write a `{config}_sweep_{timestamp}.csv` |
| `wichtelm sweep … --objective <metric>` | rank by `sharpe` (default), `total_return`, `sortino`, `calmar`, or `profit_factor` |
| `wichtelm sweep … --top <N>` / `--max-combos <N>` | rows tabulated in the console (default 10) / grid cap (default 500) |
| `wichtelm sweep … --no-report` / `--output-dir <path>` | console table only (no CSV) / override the output directory |
| `wichtelm --version` / `wichtelm --help` | version / usage |

`sweep` is the optimization entry point (CLAUDE.md §18): it loads the market data once and
re-runs the backtest per combination. The default objective is `sharpe` because ranking on raw
return alone tends to crown an overfit lucky run. To turn a winning row into a full per-trade HTML
report, copy its printed `[parameters]` block into a plain config and `wichtelm run` it.

Output directory precedence: `--output-dir` > `[output].directory` > current directory.

### Exit codes

| Code | Meaning |
|---|---|
| 0 | success |
| 1 | application error (parse failure, data source unavailable, report/backtest error) |
| 2 | usage error (bad CLI arguments) |

Report files are named `<config-basename>_<timestamp>.html` (the ISO time's `:` replaced by
`-`) and are **never** overwritten — each run writes a new file.

## Reading the HTML report

A self-contained HTML page (zero JavaScript). It contains, top to bottom:

1. **Header** — strategy name, symbol, date window, timeframe, generation timestamp, and a
   "not financial advice" disclaimer.
2. **Aggregate metrics** — `totalReturn`, `numTrades`, `winRate`, `maxDrawdown`, `sharpeRatio`,
   `sortinoRatio`, `calmarRatio`, `profitFactor`, `avgWin`, `avgLoss`. (Profit factor renders
   as `—`/undefined when there are no trades.) Total return and max drawdown are
   semantic-coloured.
3. **Equity curve & drawdown** — indexed equity (base 100) with a reference line, and the
   drawdown curve (% from peak).
4. **Per-trade breakdown** — each trade as a collapsible row: direction (long/short),
   entry→exit times and prices, P/L %, and a conditions line showing which entry/exit steps
   fired. Expanding a trade shows Entry/Exit/Hold/P-L/MFE/MAE stats and one or two chart frames
   — a primary-timeframe **Heikin-Ashi candle** chart with entry/exit markers and any overlays
   the scenario referenced (SMA/EMA, pivots, channels, RSI/ATR/MACD sub-panes), plus a
   higher-timeframe context chart when the strategy uses a multi-TF Background series.
5. **Suppressed entries** (diagnostics) — only present if some entries were suppressed for
   indicator warmup (see §"Warmup suppression" in `dsl-grammar.md`): a table of
   `bar time · scenario · reason`. Omitted entirely on a clean run.

### Quick read

- **Did it make money?** → totalReturn, and the shape of the equity curve.
- **Is the edge real or lucky?** → numTrades (tiny counts are noise), winRate + profitFactor,
  and risk-adjusted Sharpe/Sortino/Calmar.
- **How painful was the ride?** → maxDrawdown and the drawdown panel.
- **Why are early trades missing?** → the Suppressed entries section (warmup), not a bug.
