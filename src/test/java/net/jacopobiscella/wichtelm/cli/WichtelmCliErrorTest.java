package net.jacopobiscella.wichtelm.cli;

import net.jacopobiscella.wichtelm.error.DataSourceUnavailableException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The CLI error renderer must surface the CAUSE chain. A data-source failure is
 * reported as a generic "failed to load ... data for symbol ..." wrapper, but
 * the actionable detail (EODHD 403 / 404 / 429 rate-limit / parse error) lives
 * in the wrapped cause — dropping it leaves the user unable to tell a token
 * problem from a rate limit from a bad window.
 */
class WichtelmCliErrorTest {

    @Test
    void describeSurfacesTheWrappedCause() {
        // ARRANGE: the runtime's wrapper around a driver-level EODHD error.
        Throwable driver = new IllegalStateException(
                "EODHD returned 429 for symbol 'AAPL.US' (rate limit exceeded)");
        DataSourceUnavailableException wrapped = new DataSourceUnavailableException(
                "failed to load 1h data for symbol AAPL.US", driver);

        // ACT
        String rendered = WichtelmCli.describe(wrapped);

        // ASSERT: both the wrapper and the underlying reason are shown.
        assertTrue(rendered.contains("failed to load 1h data for symbol AAPL.US"),
                "wrapper message present");
        assertTrue(rendered.contains("caused by"), "cause chain is rendered");
        assertTrue(rendered.contains("EODHD returned 429"),
                "the actionable driver reason is surfaced, not hidden");
    }
}
