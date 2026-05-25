package net.jacopobiscella.wichtelm.report;

import org.hatrack.commons.OHLCBar;
import org.hatrack.commons.OHLCSeries;
import org.hatrack.commons.PivotPointVariant;
import org.hatrack.commons.PriceSource;
import org.hatrack.heerwisch.api.error.ChartRenderException;
import org.hatrack.heerwisch.api.port.ChartRenderer;
import org.hatrack.heerwisch.api.spec.Annotation;
import org.hatrack.heerwisch.api.spec.ChartImage;
import org.hatrack.heerwisch.api.spec.ChartSpec;
import org.hatrack.heerwisch.api.spec.ChartSpecBuilder;
import org.hatrack.heerwisch.api.spec.ImageFormat;
import org.hatrack.heerwisch.api.spec.Indicator;
import org.hatrack.heerwisch.api.spec.LayoutSpec;
import org.hatrack.heerwisch.api.spec.Pane;
import org.hatrack.heerwisch.jfreechart.JFreeChartRenderer;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Golden-image regression for the chart-rendering boundary: a representative
 * spec — HA candles + an SMA overlay + RSI and σ(StdDev) sub-panes + STANDARD
 * pivot levels — is rendered through the real {@link JFreeChartRenderer} (the same
 * renderer the report uses) and compared pixel-wise, within a tolerance, against a
 * committed baseline. It catches a future change that silently alters how charts
 * look — the failure mode that shipped the BB(20) / missing-pivot defects green.
 *
 * <p>Deliberately a local/build check, not a CI pipeline (per {@code AGENTS.md}).
 * The baseline is environment-sensitive (fonts / Java2D anti-aliasing); regenerate
 * it intentionally with {@code -Dwichtelm.golden.update=true} and commit the result.
 */
class GoldenChartImageTest {

    private static final Path GOLDEN =
            Path.of("src/test/resources/golden/representative-chart.png");
    /** Mean absolute per-channel pixel difference allowed (0-255 scale). */
    private static final double TOLERANCE = 5.0;

    @Test
    void representativeChartMatchesTheCommittedGolden() throws IOException, ChartRenderException {
        System.setProperty("java.awt.headless", "true");
        byte[] rendered = renderRepresentativeChart();

        if (System.getProperty("wichtelm.golden.update") != null || Files.notExists(GOLDEN)) {
            Files.createDirectories(GOLDEN.getParent());
            Files.write(GOLDEN, rendered);
            throw new org.opentest4j.TestAbortedException(
                    "golden (re)generated at " + GOLDEN + " — commit it and re-run to compare");
        }

        BufferedImage actual = ImageIO.read(new ByteArrayInputStream(rendered));
        BufferedImage expected = ImageIO.read(GOLDEN.toFile());

        assertEquals(expected.getWidth(), actual.getWidth(), "chart width drifted");
        assertEquals(expected.getHeight(), actual.getHeight(), "chart height drifted");
        assertTrue(meanAbsDiff(expected, actual) <= TOLERANCE,
                "rendered chart diverged from the golden beyond tolerance (" + TOLERANCE
                        + "); if the change is intentional, re-bless with "
                        + "-Dwichtelm.golden.update=true and commit "
                        + GOLDEN);
    }

    private static byte[] renderRepresentativeChart() throws ChartRenderException {
        OHLCSeries series = syntheticDaily(60);
        ChartSpecBuilder builder = ChartSpec.builder().withSeries(series);
        builder.addIndicator(new Indicator.SMA(10, PriceSource.CLOSE));
        builder.addIndicator(new Indicator.RSI(14, BigDecimal.valueOf(70), BigDecimal.valueOf(30),
                PriceSource.CLOSE, Optional.of(Indicator.RsiVisualization.DANGER_ZONES_ON)),
                Pane.SUBPLOT_1);
        builder.addIndicator(new Indicator.StdDev(20, PriceSource.CLOSE), Pane.SUBPLOT_2);
        // STANDARD pivots from a fixed prior-period bar — exercises the annotation
        // overlay + its legend entry.
        builder.addAnnotation(new Annotation.PivotPointLevels(
                PivotPointVariant.STANDARD, series.bars().get(40)));
        builder.withLayout(LayoutSpec.builder()
                .withSize(900, 600).withFormat(ImageFormat.PNG).build());

        ChartRenderer renderer = new JFreeChartRenderer();
        ChartImage image = renderer.render(builder.build());
        return image.bytes();
    }

    /** A deterministic daily OHLC path: a drifting sine, gap-less candle bodies. */
    private static OHLCSeries syntheticDaily(int n) {
        Instant start = Instant.parse("2024-01-01T00:00:00Z");
        List<OHLCBar> bars = new ArrayList<>(n);
        BigDecimal prevClose = null;
        for (int i = 0; i < n; i++) {
            double v = 100.0 + 10.0 * Math.sin(i / 6.0) + i * 0.1;
            BigDecimal close = new BigDecimal(String.format(java.util.Locale.ROOT, "%.2f", v));
            BigDecimal open = prevClose != null ? prevClose : close;
            BigDecimal high = open.max(close).add(BigDecimal.ONE);
            BigDecimal low = open.min(close).subtract(BigDecimal.ONE);
            bars.add(new OHLCBar(start.plus(Duration.ofDays(i)), open, high, low, close,
                    Optional.empty()));
            prevClose = close;
        }
        return new OHLCSeries(bars);
    }

    private static double meanAbsDiff(BufferedImage a, BufferedImage b) {
        long sum = 0;
        int w = a.getWidth();
        int h = a.getHeight();
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int pa = a.getRGB(x, y);
                int pb = b.getRGB(x, y);
                sum += Math.abs(((pa >> 16) & 0xFF) - ((pb >> 16) & 0xFF));
                sum += Math.abs(((pa >> 8) & 0xFF) - ((pb >> 8) & 0xFF));
                sum += Math.abs((pa & 0xFF) - (pb & 0xFF));
            }
        }
        return (double) sum / ((long) w * h * 3);
    }
}
