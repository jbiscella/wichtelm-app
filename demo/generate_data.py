#!/usr/bin/env python3
"""Generate the synthetic OHLC data used by the wichtelm-app demo backtest.

The output is fully deterministic, so re-running this script reproduces the
exact CSV files committed under demo/data/.

The price path is built from:

  * a slow trend regime (half a sine wave over the whole window): the market
    rises for the first ~60 days and falls for the last ~60, so the daily
    trend filter admits long entries early and short entries late;
  * three oscillations with incommensurate periods. They beat against each
    other, so the swing the strategy trades into varies in size from one
    cycle to the next - which is what gives the backtest a realistic mix of
    winning and losing trades rather than a perfectly periodic outcome;
  * a small irregular wobble so bars are not perfectly smooth.

The 1d series is aggregated from the 1h series, so the two timeframes are
always mutually consistent (the daily bar spans its 24 hourly bars).
"""

import math
from datetime import datetime, timedelta, timezone

DAYS = 120
HOURS = DAYS * 24
START = datetime(2024, 1, 1, tzinfo=timezone.utc)

BASE = 118.0
TREND_AMP = 52.0
TREND_PERIOD_DAYS = 240.0   # half a cycle over the 120-day window


def close_price(i: int) -> float:
    """Hourly close for hour index i."""
    day = i / 24.0
    trend = TREND_AMP * math.sin(2.0 * math.pi * day / TREND_PERIOD_DAYS)
    osc = (7.0 * math.sin(2.0 * math.pi * i / 54.0)
           + 4.0 * math.sin(2.0 * math.pi * i / 89.0 + 0.7)
           + 2.5 * math.sin(2.0 * math.pi * i / 31.0 + 1.9))
    wobble = 1.1 * math.sin(i * 0.37) + 0.7 * math.sin(i * 0.13)
    return BASE + trend + osc + wobble


def hourly_bars():
    bars = []
    prev_close = close_price(0)
    for i in range(HOURS):
        close = close_price(i)
        open_ = prev_close if i > 0 else close
        rng = 0.5 + 0.35 * abs(math.sin(i * 0.9))
        high = max(open_, close) + rng
        low = min(open_, close) - rng
        bars.append({
            "time": START + timedelta(hours=i),
            "open": open_,
            "high": high,
            "low": low,
            "close": close,
        })
        prev_close = close
    return bars


def daily_bars(hourly):
    bars = []
    for d in range(DAYS):
        window = hourly[d * 24:(d + 1) * 24]
        bars.append({
            "time": START + timedelta(days=d),
            "open": window[0]["open"],
            "high": max(b["high"] for b in window),
            "low": min(b["low"] for b in window),
            "close": window[-1]["close"],
        })
    return bars


def iso(dt: datetime) -> str:
    return dt.strftime("%Y-%m-%dT%H:%M:%SZ")


def write_csv(path: str, bars):
    with open(path, "w", encoding="utf-8") as handle:
        handle.write("time,open,high,low,close\n")
        for bar in bars:
            handle.write("{},{:.2f},{:.2f},{:.2f},{:.2f}\n".format(
                iso(bar["time"]), bar["open"], bar["high"], bar["low"], bar["close"]))


def main():
    hourly = hourly_bars()
    daily = daily_bars(hourly)
    write_csv("demo/data/DEMO_1h.csv", hourly)
    write_csv("demo/data/DEMO_1d.csv", daily)
    print(f"wrote demo/data/DEMO_1h.csv ({len(hourly)} bars)")
    print(f"wrote demo/data/DEMO_1d.csv ({len(daily)} bars)")


if __name__ == "__main__":
    main()
