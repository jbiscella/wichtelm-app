package net.jacopobiscella.wichtelm;

import org.hatrack.commons.OHLCBar;
import org.hatrack.commons.OHLCSeries;
import org.hatrack.commons.PriceSource;
import org.hatrack.commons.Timeframe;
import org.hatrack.nachtkrapp.detector.DetectionResult;
import org.hatrack.nachtkrapp.detector.RuleBasedPatternDetector;
import org.hatrack.nachtkrapp.match.PatternMatch;
import org.hatrack.nachtkrapp.rule.DetectionRule;
import org.hatrack.nachtkrapp.spec.DetectionSpec;
import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Block 7 direction-encoding probe (NOT a behavioural test). Run explicitly:
 * {@code mvn test -Dtest=DirectionProbe}. Its job is to discover whether a
 * nachtkrapp {@code PatternMatch} lets a caller tell a bullish HA reversal /
 * MACD cross from a bearish one. Findings are printed to stdout.
 */
final class DirectionProbe {

    private static final Instant START = Instant.parse("2024-01-01T00:00:00Z");

    @Test
    void probeHaColorChangeDirection() {
        // Price path: fall (bars 0-7), rise (8-15), fall (16-23). The HA color
        // therefore flips bullish around the start of the rise and bearish
        // around the start of the second fall.
        double[] prices = new double[60];
        double p = 100.0;
        for (int i = 0; i < 20; i++)  { p -= 1.4; prices[i] = p; }
        for (int i = 20; i < 40; i++) { p += 1.6; prices[i] = p; }
        for (int i = 40; i < 60; i++) { p -= 1.4; prices[i] = p; }
        List<OHLCBar> bars = barsFromPrices(prices);
        OHLCSeries series = new OHLCSeries(bars);

        List<PatternMatch> matches = detect(series,
                new DetectionRule.HAColorChangeRule(2));

        System.out.println("\n########## HA COLOR CHANGE PROBE ##########");
        double[][] ha = heikinAshi(bars);  // [i] = {haOpen, haClose}
        System.out.println("Heikin-Ashi colour per bar (expected truth):");
        for (int i = 0; i < bars.size(); i++) {
            String colour = ha[i][1] > ha[i][0] ? "BULLISH" : "BEARISH";
            String flip = i > 0 && (ha[i][1] > ha[i][0]) != (ha[i - 1][1] > ha[i - 1][0])
                    ? "  <-- COLOUR FLIP" : "";
            System.out.printf("  bar %2d %s  haOpen=%.3f haClose=%.3f%s%n",
                    i, colour, ha[i][0], ha[i][1], flip);
        }
        dumpMatches("HAColorChangeRule", matches, bars, ha);
    }

    @Test
    void probeMacdSignalCrossDirection() {
        // Long enough for MACD(3,6,3); down then up then down so the signal
        // line is crossed in both directions.
        double[] prices = new double[42];
        double p = 100.0;
        for (int i = 0; i < 15; i++) { p -= 0.9; prices[i] = p; }
        for (int i = 15; i < 30; i++){ p += 1.1; prices[i] = p; }
        for (int i = 30; i < 42; i++){ p -= 0.9; prices[i] = p; }
        List<OHLCBar> bars = barsFromPrices(prices);
        OHLCSeries series = new OHLCSeries(bars);

        List<PatternMatch> matches = detect(series,
                new DetectionRule.MACDSignalCrossRule(3, 6, 3, PriceSource.CLOSE));

        System.out.println("\n########## MACD SIGNAL CROSS PROBE ##########");
        dumpMatches("MACDSignalCrossRule", matches, bars, null);
    }

    // ----- detection plumbing --------------------------------------------------

    private static List<PatternMatch> detect(OHLCSeries series, DetectionRule rule) {
        System.out.println("  [probe] rule=" + rule + " minBars=" + rule.minBars());
        DetectionSpec spec;
        try {
            var b = DetectionSpec.builder().withSeries(series).addRule(rule);
            if (!(rule instanceof DetectionRule.HAColorChangeRule)) {
                b = b.withTimeframe(Timeframe.fromWire("1h"));
            }
            spec = b.build();
        } catch (Exception e) {
            System.out.println("spec build failed: " + e);
            throw new RuntimeException(e);
        }
        DetectionResult result;
        try {
            result = new RuleBasedPatternDetector().detect(spec);
        } catch (Exception e) {
            System.out.println("detect failed: " + e);
            throw new RuntimeException(e);
        }
        return result.matches();
    }

    private static void dumpMatches(String label, List<PatternMatch> matches,
                                    List<OHLCBar> bars, double[][] ha) {
        System.out.println(label + " produced " + matches.size() + " match(es).");
        for (int m = 0; m < matches.size(); m++) {
            PatternMatch match = matches.get(m);
            System.out.println("  --- match #" + m + " ---");
            System.out.println("    interface view : time=" + match.time()
                    + " flavor=" + match.flavor() + " timeframe=" + match.timeframe());
            Class<?> cls = match.getClass();
            System.out.println("    concrete class : " + cls.getName()
                    + " (isRecord=" + cls.isRecord() + ")");
            if (cls.isRecord()) {
                for (RecordComponent rc : cls.getRecordComponents()) {
                    Object value;
                    try {
                        value = rc.getAccessor().invoke(match);
                    } catch (ReflectiveOperationException e) {
                        value = "<inaccessible: " + e + ">";
                    }
                    System.out.println("      record component: " + rc.getName()
                            + " (" + rc.getType().getSimpleName() + ") = " + value);
                }
            }
            // All no-arg public methods, to surface any non-record direction getter.
            for (var method : cls.getMethods()) {
                if (method.getParameterCount() == 0
                        && !method.getName().equals("toString")
                        && !method.getName().equals("hashCode")
                        && !method.getDeclaringClass().equals(Object.class)) {
                    try {
                        System.out.println("      method " + method.getName()
                                + "() -> " + method.invoke(match));
                    } catch (ReflectiveOperationException ignored) {
                        // ignore
                    }
                }
            }
            // Ground-truth direction independently derived from the bar.
            int idx = indexOfTime(bars, match.time());
            if (idx >= 0 && ha != null) {
                String colour = ha[idx][1] > ha[idx][0] ? "BULLISH" : "BEARISH";
                System.out.println("    bar HA colour at match time: " + colour
                        + " (bar index " + idx + ")");
            } else if (idx >= 0) {
                System.out.println("    bar index at match time: " + idx
                        + " close=" + bars.get(idx).close());
            }
        }
    }

    // ----- helpers -------------------------------------------------------------

    private static List<OHLCBar> barsFromPrices(double[] prices) {
        List<OHLCBar> bars = new ArrayList<>(prices.length);
        for (int i = 0; i < prices.length; i++) {
            double open = i == 0 ? prices[0] : prices[i - 1];
            double close = prices[i];
            double high = Math.max(open, close) + 0.5;
            double low = Math.min(open, close) - 0.5;
            bars.add(new OHLCBar(START.plus(Duration.ofHours(i)),
                    bd(open), bd(high), bd(low), bd(close),
                    Optional.of(BigDecimal.valueOf(1000))));
        }
        return bars;
    }

    private static double[][] heikinAshi(List<OHLCBar> bars) {
        double[][] ha = new double[bars.size()][2];
        for (int i = 0; i < bars.size(); i++) {
            OHLCBar b = bars.get(i);
            double haClose = (b.open().doubleValue() + b.high().doubleValue()
                    + b.low().doubleValue() + b.close().doubleValue()) / 4.0;
            double haOpen = i == 0
                    ? (b.open().doubleValue() + b.close().doubleValue()) / 2.0
                    : (ha[i - 1][0] + ha[i - 1][1]) / 2.0;
            ha[i][0] = haOpen;
            ha[i][1] = haClose;
        }
        return ha;
    }

    private static int indexOfTime(List<OHLCBar> bars, Instant time) {
        for (int i = 0; i < bars.size(); i++) {
            if (bars.get(i).time().equals(time)) {
                return i;
            }
        }
        return -1;
    }

    private static BigDecimal bd(double v) {
        return BigDecimal.valueOf(Math.round(v * 10000.0) / 10000.0);
    }
}
