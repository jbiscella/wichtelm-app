package net.jacopobiscella.wichtelm.runtime;

import java.time.Instant;
import java.util.Objects;

/**
 * Records an entry signal that matched all its conditions but was NOT fired
 * because its declared protective stop / take could not be evaluated at the
 * prospective fill — in v1 this means an {@code atr_value(period)} stop whose
 * ATR was not yet warm (fewer than {@code period} bars precede the fill).
 *
 * <p>Suppressing the entry (rather than opening a position without its declared
 * protection, or aborting the backtest) keeps the stop part of the entry
 * contract and is lookahead-honest: a trader could not place an ATR stop before
 * the ATR exists. The suppressed entry naturally fires on a later bar once the
 * indicator warms. These records surface in the HTML report so the author can
 * see why a strategy produced fewer trades than expected.
 *
 * @param barTime      the bar at which the entry matched and was suppressed
 * @param scenarioName the entry Scenario whose conditions held
 * @param reason       human-readable cause (e.g. "ATR not warm: needs 14
 *                     pre-fill bars, only 6 available")
 */
public record SuppressedEntry(Instant barTime, String scenarioName, String reason) {

    public SuppressedEntry {
        Objects.requireNonNull(barTime, "barTime");
        Objects.requireNonNull(scenarioName, "scenarioName");
        Objects.requireNonNull(reason, "reason");
    }
}
