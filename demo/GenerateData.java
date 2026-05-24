// Generates the synthetic OHLC data for the wichtelm-app demos.
//
// Single-file Java program — no build step, JDK-only. Run from the repo root:
//
//   java demo/GenerateData.java
//
// Output is fully deterministic (no RNG; everything is closed-form), so
// re-running reproduces the committed demo/data/TSTX_*.csv exactly.
//
// One synthetic instrument, "TSTX" (a deliberately fake name per CLAUDE.md §17 —
// never a real ticker), is emitted at four timeframes: 1h, 4h, 1d, 1w. The 4h /
// 1d / 1w series are aggregated from the 1h base, so every timeframe is mutually
// consistent. The window is 156 whole weeks (1092 days ≈ 3 years) starting on a
// Monday, so weekly bars align to week boundaries.
//
// The price path layers, so the SAME data yields tradeable structure at every
// timeframe (no perfectly periodic outcomes):
//   * a ~2-year regime cycle (bull → bear → bull) — the long trend filters key on;
//   * ~26-day and ~41-day swings — what the daily/weekly strategies trade;
//   * a ~5-day swing and an intraday cycle — for the 4h / 1h strategies;
//   * a small irregular wobble so bars are not perfectly smooth.
// Volume is synthetic but always positive (the volume-gated demo needs it).
//
// The data is continuous (a bar every period, weekends included) — that is what
// makes the charts clean; it is explicitly synthetic, not a model of real hours.

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class GenerateData {

    static final int WEEKS = 156;                 // 156 whole weeks
    static final int DAYS = WEEKS * 7;            // 1092 days ≈ 3 years
    static final int HOURS = DAYS * 24;           // 26208 hours
    static final Instant START = Instant.parse("2021-01-04T00:00:00Z"); // a Monday
    static final double BASE = 140.0;

    record Bar(Instant time, double open, double high, double low, double close, long volume) {}

    /** Hourly close for hour index i. */
    static double closePrice(int i) {
        double day = i / 24.0;
        double trend   = 45.0 * Math.sin(2 * Math.PI * day / 730.0);            // ~2yr: bull→bear→bull
        double swingA  = 14.0 * Math.sin(2 * Math.PI * day / 26.0);             // ~26-day swing
        double swingB  = 8.0  * Math.sin(2 * Math.PI * day / 41.0 + 0.8);       // ~41-day swing
        double daily   = 4.0  * Math.sin(2 * Math.PI * day / 5.3 + 1.1);        // ~5-day swing
        double intra   = 2.2  * Math.sin(2 * Math.PI * i / 24.0)
                       + 1.3  * Math.sin(2 * Math.PI * i / 8.0 + 0.5);          // intraday structure
        double wobble  = 0.8  * Math.sin(i * 0.37) + 0.5 * Math.sin(i * 0.131 + 2.0);
        return BASE + trend + swingA + swingB + daily + intra + wobble;
    }

    /** Always-positive synthetic hourly volume. */
    static long volume(int i) {
        double v = 40_000_000.0
                + 25_000_000.0 * Math.abs(Math.sin(2 * Math.PI * i / 37.0))
                + 10_000_000.0 * Math.abs(Math.sin(i * 0.07 + 1.0));
        return (long) v;
    }

    static List<Bar> hourlyBars() {
        List<Bar> bars = new ArrayList<>(HOURS);
        double prevClose = closePrice(0);
        for (int i = 0; i < HOURS; i++) {
            double close = closePrice(i);
            double open = i > 0 ? prevClose : close;
            double rng = close * (0.0035 + 0.0025 * Math.abs(Math.sin(i * 0.9)));
            double high = Math.max(open, close) + rng;
            double low = Math.min(open, close) - rng;
            bars.add(new Bar(START.plus(Duration.ofHours(i)), open, high, low, close, volume(i)));
            prevClose = close;
        }
        return bars;
    }

    /** Aggregate the 1h base into {@code spanHours}-wide bars (4h / 1d / 1w). */
    static List<Bar> aggregate(List<Bar> hourly, int spanHours) {
        List<Bar> out = new ArrayList<>();
        for (int s = 0; s + spanHours <= hourly.size(); s += spanHours) {
            List<Bar> w = hourly.subList(s, s + spanHours);
            double high = Double.NEGATIVE_INFINITY, low = Double.POSITIVE_INFINITY;
            long vol = 0;
            for (Bar b : w) {
                high = Math.max(high, b.high());
                low = Math.min(low, b.low());
                vol += b.volume();
            }
            out.add(new Bar(w.get(0).time(), w.get(0).open(), high, low,
                    w.get(w.size() - 1).close(), vol));
        }
        return out;
    }

    static void writeCsv(String path, List<Bar> bars) throws IOException {
        StringBuilder sb = new StringBuilder("time,open,high,low,close,volume\n");
        for (Bar b : bars) {
            sb.append(String.format(Locale.ROOT, "%s,%.2f,%.2f,%.2f,%.2f,%d\n",
                    b.time(), b.open(), b.high(), b.low(), b.close(), b.volume()));
        }
        Files.writeString(Path.of(path), sb.toString());
    }

    public static void main(String[] args) throws IOException {
        List<Bar> h1 = hourlyBars();
        List<Bar> h4 = aggregate(h1, 4);
        List<Bar> d1 = aggregate(h1, 24);
        List<Bar> w1 = aggregate(h1, 168);
        writeCsv("demo/data/TSTX_1h.csv", h1);
        writeCsv("demo/data/TSTX_4h.csv", h4);
        writeCsv("demo/data/TSTX_1d.csv", d1);
        writeCsv("demo/data/TSTX_1w.csv", w1);
        System.out.printf(Locale.ROOT, "TSTX: 1h=%d 4h=%d 1d=%d 1w=%d%n",
                h1.size(), h4.size(), d1.size(), w1.size());
    }
}
