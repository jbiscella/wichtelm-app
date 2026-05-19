package net.jacopobiscella.wichtelm;

import org.hatrack.commons.HABar;
import org.hatrack.commons.HASeries;
import org.hatrack.commons.HeikinAshiCalculator;
import org.hatrack.commons.OHLCBar;
import org.hatrack.commons.OHLCSeries;
import org.hatrack.commons.PriceSource;
import org.hatrack.commons.Series;
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
 * {@code mvn test -Dtest=DirectionProbe}. Discovers whether a nachtkrapp
 * {@code PatternMatch} lets a caller tell a bullish HA reversal / MACD cross
 * from a bearish one. Findings are printed to stdout.
 */
final class DirectionProbe {

    private static final Instant START = Instant.parse("2024-01-01T00:00:00Z");

    @Test
    void probeHaColorChangeDirection() {
        // Price path: fall, rise, fall. The HA colour flips bullish around the
        // start of the rise and bearish around the start of the second fall.
        double[] prices = new double[60];
        double p = 100.0;
        for (int i = 0; i < 20; i++)  { p -= 1.4; prices[i] = p; }
        for (int i = 20; i < 40; i++) { p += 1.6; prices[i] = p; }
        for (int i = 40; i < 60; i++) { p -= 1.4; prices[i] = p; }
        List<OHLCBar> ohlc = barsFromPrices(prices);
        // HAColorChangeRule requires an HASeries (OHLCSeries fails spec
        // validation V5 — see DetectionSpecBuilder.checkSeriesCompatibility).
        List<HABar> haBars = HeikinAshiCalculator.computeChain(Optional.empty(), ohlc);
        HASeries series = new HASeries(haBars);

        System.out.println("\n########## HA COLOR CHANGE PROBE ##########");
        System.out.println("Heikin-Ashi colour per bar (expected truth):");
        for (int i = 0; i < haBars.size(); i++) {
            HABar b = haBars.get(i);
            boolean bull = b.haClose().compareTo(b.haOpen()) > 0;
            boolean prevBull = i > 0
                    && haBars.get(i - 1).haClose().compareTo(haBars.get(i - 1).haOpen()) > 0;
            String flip = i > 0 && bull != prevBull ? "  <-- COLOUR FLIP" : "";
            System.out.printf("  bar %2d %s  haOpen=%s haClose=%s%s%n",
                    i, bull ? "BULLISH" : "BEARISH", b.haOpen(), b.haClose(), flip);
        }

        List<PatternMatch> matches = detect(series, new DetectionRule.HAColorChangeRule(2));
        dumpMatches("HAColorChangeRule", matches);
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
        OHLCSeries series = new OHLCSeries(barsFromPrices(prices));

        System.out.println("\n########## MACD SIGNAL CROSS PROBE ##########");
        List<PatternMatch> matches = detect(series,
                new DetectionRule.MACDSignalCrossRule(3, 6, 3, PriceSource.CLOSE));
        dumpMatches("MACDSignalCrossRule", matches);
    }

    // ----- detection plumbing --------------------------------------------------

    private static List<PatternMatch> detect(Series series, DetectionRule rule) {
        System.out.println("  [probe] rule=" + rule + " minBars=" + rule.minBars()
                + " seriesType=" + series.getClass().getSimpleName());
        try {
            DetectionSpec spec = DetectionSpec.builder()
                    .withSeries(series)
                    .withTimeframe(Timeframe.fromWire("1h"))
                    .addRule(rule)
                    .build();
            DetectionResult result = new RuleBasedPatternDetector().detect(spec);
            return result.matches();
        } catch (Exception e) {
            System.out.println("  [probe] FAILED: " + e);
            throw new RuntimeException(e);
        }
    }

    private static void dumpMatches(String label, List<PatternMatch> matches) {
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

    private static BigDecimal bd(double v) {
        return BigDecimal.valueOf(Math.round(v * 10000.0) / 10000.0);
    }
}
