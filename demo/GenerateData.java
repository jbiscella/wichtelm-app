// Generates the synthetic OHLC data used by the wichtelm-app demo backtest.
//
// Single-file Java program — no build step, no dependency beyond the JDK the
// project already requires. Run from the repository root:
//
//   java demo/GenerateData.java
//
// The output is fully deterministic, so re-running this reproduces the exact
// CSV files committed under demo/data/.
//
// The price path is built from:
//
//   * a slow trend regime (half a sine wave over the whole window): the market
//     rises for the first ~60 days and falls for the last ~60, so the daily
//     trend filter admits long entries early and short entries late;
//   * three oscillations with incommensurate periods. They beat against each
//     other, so the swing the strategy trades into varies in size from one
//     cycle to the next — which gives the backtest a realistic mix of winning
//     and losing trades rather than a perfectly periodic outcome;
//   * a small irregular wobble so bars are not perfectly smooth.
//
// The 1d series is aggregated from the 1h series, so the two timeframes are
// always mutually consistent (the daily bar spans its 24 hourly bars).

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class GenerateData {

    static final int DAYS = 120;
    static final int HOURS = DAYS * 24;
    static final Instant START = Instant.parse("2024-01-01T00:00:00Z");

    static final double BASE = 118.0;
    static final double TREND_AMP = 52.0;
    static final double TREND_PERIOD_DAYS = 240.0;   // half a cycle over the 120-day window

    record Bar(Instant time, double open, double high, double low, double close) {}

    /** Hourly close for hour index i. */
    static double closePrice(int i) {
        double day = i / 24.0;
        double trend = TREND_AMP * Math.sin(2.0 * Math.PI * day / TREND_PERIOD_DAYS);
        double osc = 7.0 * Math.sin(2.0 * Math.PI * i / 54.0)
                + 4.0 * Math.sin(2.0 * Math.PI * i / 89.0 + 0.7)
                + 2.5 * Math.sin(2.0 * Math.PI * i / 31.0 + 1.9);
        double wobble = 1.1 * Math.sin(i * 0.37) + 0.7 * Math.sin(i * 0.13);
        return BASE + trend + osc + wobble;
    }

    static List<Bar> hourlyBars() {
        List<Bar> bars = new ArrayList<>(HOURS);
        double prevClose = closePrice(0);
        for (int i = 0; i < HOURS; i++) {
            double close = closePrice(i);
            double open = i > 0 ? prevClose : close;
            double rng = 0.5 + 0.35 * Math.abs(Math.sin(i * 0.9));
            double high = Math.max(open, close) + rng;
            double low = Math.min(open, close) - rng;
            bars.add(new Bar(START.plus(Duration.ofHours(i)), open, high, low, close));
            prevClose = close;
        }
        return bars;
    }

    static List<Bar> dailyBars(List<Bar> hourly) {
        List<Bar> bars = new ArrayList<>(DAYS);
        for (int d = 0; d < DAYS; d++) {
            List<Bar> window = hourly.subList(d * 24, (d + 1) * 24);
            double high = Double.NEGATIVE_INFINITY;
            double low = Double.POSITIVE_INFINITY;
            for (Bar bar : window) {
                high = Math.max(high, bar.high());
                low = Math.min(low, bar.low());
            }
            bars.add(new Bar(START.plus(Duration.ofDays(d)),
                    window.get(0).open(), high, low, window.get(23).close()));
        }
        return bars;
    }

    static void writeCsv(String path, List<Bar> bars) throws IOException {
        StringBuilder sb = new StringBuilder("time,open,high,low,close\n");
        for (Bar bar : bars) {
            sb.append(String.format(Locale.ROOT, "%s,%.2f,%.2f,%.2f,%.2f\n",
                    bar.time(), bar.open(), bar.high(), bar.low(), bar.close()));
        }
        Files.writeString(Path.of(path), sb.toString());
    }

    public static void main(String[] args) throws IOException {
        List<Bar> hourly = hourlyBars();
        List<Bar> daily = dailyBars(hourly);
        writeCsv("demo/data/DEMO_1h.csv", hourly);
        writeCsv("demo/data/DEMO_1d.csv", daily);
        System.out.println("wrote demo/data/DEMO_1h.csv (" + hourly.size() + " bars)");
        System.out.println("wrote demo/data/DEMO_1d.csv (" + daily.size() + " bars)");
    }
}
