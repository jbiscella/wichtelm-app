package net.jacopobiscella.wichtelm;

import net.jacopobiscella.wichtelm.report.EquityCsvWriter;
import org.hatrack.frauholle.model.EquityPoint;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit coverage for {@link EquityCsvWriter}'s CSV rendering — the row/column
 * shape and exact (unrounded) value emission that the {@code --dump-equity}
 * CLI scenario in {@code cli-behavior.feature} only checks for existence.
 */
class EquityCsvWriterTest {

    private static String render(List<EquityPoint> curve) throws Exception {
        Method m = EquityCsvWriter.class.getDeclaredMethod("render", List.class);
        m.setAccessible(true);
        return (String) m.invoke(null, curve);
    }

    @Test
    void rendersHeaderAndOneRowPerPoint() throws Exception {
        // Arrange
        List<EquityPoint> curve = List.of(
                new EquityPoint(Instant.parse("2024-01-01T00:00:00Z"),
                        new BigDecimal("10000"), new BigDecimal("10000"), BigDecimal.ZERO),
                new EquityPoint(Instant.parse("2024-01-01T01:00:00Z"),
                        new BigDecimal("10150.50"), new BigDecimal("0"),
                        new BigDecimal("10150.50")));

        // Act
        String csv = render(curve);

        // Assert
        String[] lines = csv.split("\n");
        assertEquals("time,equity,cash,position_value", lines[0]);
        assertEquals(3, lines.length, "header + 2 data rows");
        assertEquals("2024-01-01T00:00:00Z,10000,10000,0", lines[1]);
        // Exact, unrounded mark-to-market value (trailing zeros stripped).
        assertEquals("2024-01-01T01:00:00Z,10150.5,0,10150.5", lines[2]);
    }

    @Test
    void emptyCurveRendersHeaderOnly() throws Exception {
        // Arrange / Act
        String csv = render(List.of());

        // Assert
        assertEquals("time,equity,cash,position_value\n", csv);
        assertTrue(csv.endsWith("\n"));
    }
}
