// Generates the synthetic OHLC data for the wichtelm-app demos.
//
// Single-file Java program — no build step, JDK-only. Run from the repo root:
//
//   java demo/GenerateData.java
//
// Deterministic (no RNG; everything is closed-form), so re-running reproduces
// the committed demo/data/*.csv exactly. Both instruments are deliberately fake
// names (CLAUDE.md §17), never real tickers.
//
// Two synthetic instruments, each modelling its asset class REALISTICALLY:
//
//   * TSTX — a synthetic EQUITY at 1d / 1w. Bars exist on WEEKDAYS only
//     (Mon–Fri), exactly like a real stock: no Saturday/Sunday bars. Daily and
//     weekly charts plotted on a time axis therefore show the normal small
//     weekend gaps — realistic, and still clean (unlike gappy intraday equity).
//
//   * TSTC — a synthetic CRYPTO asset at 1h / 4h. Crypto trades 24/7, so a
//     continuous bar every hour is the realistic shape here — and it keeps the
//     intraday charts gap-free.
//
// Each instrument's price path layers a multi-year regime cycle (bull → bear →
// bull), medium swings, a short swing and a small wobble, so every strategy gets
// a realistic mix of winning and losing trades. Volume is synthetic but always
// positive (the volume-gated demo needs it).

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class GenerateData {

    record Bar(Instant time, double open, double high, double low, double close, long volume) {}

    static long volume(int i) {
        return (long) (40_000_000.0
                + 25_000_000.0 * Math.abs(Math.sin(2 * Math.PI * i / 37.0))
                + 10_000_000.0 * Math.abs(Math.sin(i * 0.07 + 1.0)));
    }

    // ─── TSTX: synthetic EQUITY, weekday-only 1d + 1w ─────────────────────────

    static final Instant EQ_START = Instant.parse("2021-01-04T00:00:00Z"); // a Monday
    static final int EQ_CAL_DAYS = 1092;   // ~3 calendar years
    static final double EQ_BASE = 140.0;

    static double eqClose(int d) {         // d = calendar-day index
        double trend  = 45.0 * Math.sin(2 * Math.PI * d / 730.0);          // ~2yr regime
        double swingA = 14.0 * Math.sin(2 * Math.PI * d / 26.0);           // ~26-day swing
        double swingB = 8.0  * Math.sin(2 * Math.PI * d / 41.0 + 0.8);     // ~41-day swing
        double micro  = 3.0  * Math.sin(2 * Math.PI * d / 5.3 + 1.1);      // ~5-day swing
        double wobble = 0.8  * Math.sin(d * 0.7) + 0.5 * Math.sin(d * 0.27 + 2.0);
        return EQ_BASE + trend + swingA + swingB + micro + wobble;
    }

    static List<Bar> equityDaily() {
        List<Bar> bars = new ArrayList<>();
        double prevClose = eqClose(0);
        for (int d = 0; d < EQ_CAL_DAYS; d++) {
            Instant t = EQ_START.plus(Duration.ofDays(d));
            DayOfWeek dow = t.atOffset(ZoneOffset.UTC).getDayOfWeek();
            if (dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY) {
                continue;                                                  // markets closed
            }
            double close = eqClose(d);
            double open = prevClose;
            double rng = close * (0.006 + 0.004 * Math.abs(Math.sin(d * 0.9)));
            bars.add(new Bar(t, open, Math.max(open, close) + rng,
                    Math.min(open, close) - rng, close, volume(d)));
            prevClose = close;
        }
        return bars;
    }

    /** Group weekday daily bars into calendar-week (Monday-anchored) weekly bars. */
    static List<Bar> equityWeekly(List<Bar> daily) {
        Map<Long, List<Bar>> byWeek = new LinkedHashMap<>();
        for (Bar b : daily) {
            long wk = Duration.between(EQ_START, b.time()).toDays() / 7;
            byWeek.computeIfAbsent(wk, k -> new ArrayList<>()).add(b);
        }
        List<Bar> out = new ArrayList<>();
        for (Map.Entry<Long, List<Bar>> e : byWeek.entrySet()) {
            List<Bar> w = e.getValue();
            double high = Double.NEGATIVE_INFINITY, low = Double.POSITIVE_INFINITY;
            long vol = 0;
            for (Bar b : w) {
                high = Math.max(high, b.high());
                low = Math.min(low, b.low());
                vol += b.volume();
            }
            out.add(new Bar(EQ_START.plus(Duration.ofDays(e.getKey() * 7)),
                    w.get(0).open(), high, low, w.get(w.size() - 1).close(), vol));
        }
        return out;
    }

    // ─── TSTC: synthetic CRYPTO, continuous 24/7 1h + 4h ──────────────────────

    static final Instant CR_START = Instant.parse("2022-01-01T00:00:00Z");
    static final int CR_HOURS = 730 * 24;  // two years, every hour
    static final double CR_BASE = 120.0;

    static double crClose(int i) {          // i = hour index
        double day = i / 24.0;
        double trend  = 30.0 * Math.sin(2 * Math.PI * day / 240.0);        // ~8-month regime
        double swingA = 10.0 * Math.sin(2 * Math.PI * day / 19.0);         // ~19-day swing
        double swingB = 5.0  * Math.sin(2 * Math.PI * day / 7.0 + 0.6);    // ~weekly swing
        double intra  = 2.4  * Math.sin(2 * Math.PI * i / 24.0)
                      + 1.3  * Math.sin(2 * Math.PI * i / 8.0 + 0.5);      // intraday cycle
        double wobble = 0.8  * Math.sin(i * 0.37) + 0.5 * Math.sin(i * 0.131 + 2.0);
        return CR_BASE + trend + swingA + swingB + intra + wobble;
    }

    static List<Bar> cryptoHourly() {
        List<Bar> bars = new ArrayList<>(CR_HOURS);
        double prevClose = crClose(0);
        for (int i = 0; i < CR_HOURS; i++) {
            double close = crClose(i);
            double open = i > 0 ? prevClose : close;
            double rng = close * (0.0035 + 0.0025 * Math.abs(Math.sin(i * 0.9)));
            bars.add(new Bar(CR_START.plus(Duration.ofHours(i)), open,
                    Math.max(open, close) + rng, Math.min(open, close) - rng, close, volume(i)));
            prevClose = close;
        }
        return bars;
    }

    /** Aggregate the 1h crypto base into {@code spanHours}-wide bars (4h). */
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
        List<Bar> eqD = equityDaily();
        List<Bar> eqW = equityWeekly(eqD);
        writeCsv("demo/data/TSTX_1d.csv", eqD);
        writeCsv("demo/data/TSTX_1w.csv", eqW);

        List<Bar> crH = cryptoHourly();
        List<Bar> crH4 = aggregate(crH, 4);
        writeCsv("demo/data/TSTC_1h.csv", crH);
        writeCsv("demo/data/TSTC_4h.csv", crH4);

        System.out.printf(Locale.ROOT,
                "TSTX (equity, weekdays): 1d=%d 1w=%d%nTSTC (crypto, 24/7): 1h=%d 4h=%d%n",
                eqD.size(), eqW.size(), crH.size(), crH4.size());
    }
}
