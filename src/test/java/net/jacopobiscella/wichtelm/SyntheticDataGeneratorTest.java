package net.jacopobiscella.wichtelm;

import net.jacopobiscella.wichtelm.demo.SyntheticDataGenerator;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Sanity tests for {@link SyntheticDataGenerator}. */
class SyntheticDataGeneratorTest {

    private static final SyntheticDataGenerator.Calibration SAMPLE =
            new SyntheticDataGenerator.Calibration("TEST", 2024, 100.0, 42L, List.of(
                    new SyntheticDataGenerator.Regime(LocalDate.of(2024, 1, 1),
                            LocalDate.of(2024, 6, 1), 0.10, 0.20),
                    new SyntheticDataGenerator.Regime(LocalDate.of(2024, 6, 1),
                            LocalDate.of(2025, 1, 1), -0.30, 0.45)));

    @Test
    void csvHeaderAndOhlcInvariantsAreCorrect() {
        List<String> lines = SyntheticDataGenerator.generate(SAMPLE).lines().toList();
        assertEquals("time,open,high,low,close,volume", lines.getFirst());
        // 2024 is a leap year: 8784 hourly bars plus the header row.
        assertEquals(8785, lines.size());

        Instant previousTime = null;
        for (int i = 1; i < lines.size(); i++) {
            String[] cells = lines.get(i).split(",");
            Instant time = Instant.parse(cells[0]);
            BigDecimal open = new BigDecimal(cells[1]);
            BigDecimal high = new BigDecimal(cells[2]);
            BigDecimal low = new BigDecimal(cells[3]);
            BigDecimal close = new BigDecimal(cells[4]);
            long volume = Long.parseLong(cells[5]);

            assertTrue(high.compareTo(open.max(close)) >= 0,
                    () -> "high must cover open and close at " + cells[0]);
            assertTrue(low.compareTo(open.min(close)) <= 0,
                    () -> "low must fall under open and close at " + cells[0]);
            assertTrue(open.signum() > 0 && high.signum() > 0
                            && low.signum() > 0 && close.signum() > 0,
                    () -> "every price must be strictly positive at " + cells[0]);
            assertTrue(volume >= 0, () -> "volume must be non-negative at " + cells[0]);
            if (previousTime != null) {
                assertTrue(time.isAfter(previousTime),
                        () -> "timestamps must be strictly increasing at " + cells[0]);
            }
            previousTime = time;
        }
    }

    @Test
    void outputIsDeterministicGivenTheSeed() {
        String first = SyntheticDataGenerator.generate(SAMPLE);
        String second = SyntheticDataGenerator.generate(SAMPLE);
        assertEquals(first, second);
    }
}
