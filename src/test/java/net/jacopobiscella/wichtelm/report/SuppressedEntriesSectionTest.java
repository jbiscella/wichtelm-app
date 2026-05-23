package net.jacopobiscella.wichtelm.report;

import net.jacopobiscella.wichtelm.runtime.SuppressedEntry;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The "Suppressed entries" diagnostics section. A clean run (no suppressions)
 * must render nothing; when entries were suppressed for indicator warmup, the
 * section lists each with its time, scenario and reason so the author can see
 * why the strategy produced fewer trades than expected.
 */
class SuppressedEntriesSectionTest {

    private final HtmlReportGenerator generator = new HtmlReportGenerator();

    @Test
    void aCleanRunRendersNoSection() {
        // ARRANGE / ACT: no suppressed entries.
        String html = generator.suppressedEntriesSection(List.of());

        // ASSERT: nothing rendered — the section never appears for a clean run.
        assertEquals("", html);
    }

    @Test
    void suppressedEntriesAreListedWithTimeScenarioAndReason() {
        // ARRANGE: one entry suppressed for ATR warmup.
        SuppressedEntry entry = new SuppressedEntry(
                Instant.parse("2024-03-01T05:00:00Z"),
                "Enter long on EMA reclaim",
                "ATR not warm: needs 14 pre-fill bars, only 6 available");

        // ACT
        String html = generator.suppressedEntriesSection(List.of(entry));

        // ASSERT: a labelled section carrying every field of the record.
        assertTrue(html.contains("Suppressed entries"), "section heading present");
        assertTrue(html.contains("Enter long on EMA reclaim"), "scenario name present");
        assertTrue(html.contains("ATR not warm: needs 14 pre-fill bars, only 6 available"),
                "reason present");
        assertTrue(html.contains("2024-03-01T05:00Z"), "suppression time present");
    }
}
