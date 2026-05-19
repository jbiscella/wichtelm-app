package net.jacopobiscella.wichtelm.demo;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Random;

/**
 * Generates deterministic synthetic OHLCV CSV datasets calibrated to real
 * historical market regimes — the COVID year (2020) and the bear market (2022)
 * on the S&P 500 — for the Block 7 demo backtests.
 *
 * <p>Each dataset is produced by a regime-switching geometric Brownian motion
 * with GARCH(1,1) volatility clustering on the log returns:
 *
 * <pre>{@code
 *   sigma^2_t = omega * sigma_lr^2 + alpha * r_{t-1}^2 + beta * sigma^2_{t-1}
 *   r_t       = (mu - 0.5 * sigma^2_t) * dt + sigma_t * sqrt(dt) * Z_t,  Z_t ~ N(0,1)
 *   S_t       = S_{t-1} * exp(r_t)
 * }</pre>
 *
 * <p>Within each calendar regime, {@code mu} (annual drift) and the long-run
 * variance {@code sigma_lr^2} are piecewise constant and calibrated to the
 * shape of the real episode the regime represents. Volatility clusters
 * naturally via the GARCH update (alpha+beta close to 1). The intra-bar high
 * and low are sampled around max(open, close) and min(open, close) with a
 * range proportional to the current volatility, and the volume is a base
 * level scaled by the absolute log-return so big moves carry heavier volume,
 * matching how real markets behave.
 *
 * <p>Output is one CSV per dataset, written under the directory supplied as
 * the first command-line argument (default {@code demo/data}), one bar per
 * hour over the whole calendar year (8760 bars). The CSV schema is the one
 * the {@code frau-holle-csv} driver expects:
 * {@code time,open,high,low,close,volume} with UTC ISO-8601 timestamps.
 *
 * <p>Determinism: each calibration carries a fixed seed, so re-running the
 * generator produces byte-identical CSVs.
 */
public final class SyntheticDataGenerator {

    /** A calendar window with the per-bar log-return statistics that hold inside it. */
    public record Regime(LocalDate from, LocalDate to, double annualDrift, double annualVol) {
    }

    /** A complete, self-contained dataset specification. */
    public record Calibration(String symbol, int year, double startPrice,
                              long seed, List<Regime> regimes) {
    }

    /**
     * 2020 — calibrated to the SPX calendar year. Calm bull (Jan-Feb 19),
     * COVID crash (Feb 19 - Mar 23, ~-34%), V-recovery (Mar 23 - Sep 1,
     * ~+60%), autumn chop (Sep-Oct), vaccine rally (Nov-Dec, ~+14%).
     */
    private static final Calibration SPX2020 = new Calibration(
            "SPX2020", 2020, 3231.0, 20200101L, List.of(
            new Regime(LocalDate.of(2020, 1, 1),  LocalDate.of(2020, 2, 19), 0.35, 0.12),
            new Regime(LocalDate.of(2020, 2, 19), LocalDate.of(2020, 3, 23), -4.30, 0.85),
            new Regime(LocalDate.of(2020, 3, 23), LocalDate.of(2020, 9, 1),  0.95, 0.30),
            new Regime(LocalDate.of(2020, 9, 1),  LocalDate.of(2020, 11, 1), -0.05, 0.30),
            new Regime(LocalDate.of(2020, 11, 1), LocalDate.of(2021, 1, 1),  0.75, 0.18)));

    /**
     * 2022 — calibrated to the SPX bear market. Early decline (Jan, -10%),
     * relief rally to late March (+13%), bear leg to mid-June (-22%),
     * bear-market rally through August (+18%), another leg lower to
     * mid-October (-18%), modest year-end relief (+7%).
     */
    private static final Calibration SPX2022 = new Calibration(
            "SPX2022", 2022, 4766.0, 20220101L, List.of(
            new Regime(LocalDate.of(2022, 1, 1),   LocalDate.of(2022, 1, 24),  -1.40, 0.30),
            new Regime(LocalDate.of(2022, 1, 24),  LocalDate.of(2022, 3, 29),  0.75, 0.25),
            new Regime(LocalDate.of(2022, 3, 29),  LocalDate.of(2022, 6, 17),  -1.00, 0.25),
            new Regime(LocalDate.of(2022, 6, 17),  LocalDate.of(2022, 8, 16),  1.10, 0.22),
            new Regime(LocalDate.of(2022, 8, 16),  LocalDate.of(2022, 10, 13), -1.00, 0.30),
            new Regime(LocalDate.of(2022, 10, 13), LocalDate.of(2023, 1, 1),   0.30, 0.22)));

    private static final List<Calibration> CALIBRATIONS = List.of(SPX2020, SPX2022);

    // GARCH(1,1) coefficients — alpha + beta close to 1 yields persistent
    // volatility clusters that mean-revert to the regime long-run variance.
    private static final double GARCH_ALPHA = 0.06;
    private static final double GARCH_BETA = 0.90;
    private static final double GARCH_OMEGA = 1.0 - GARCH_ALPHA - GARCH_BETA;

    // 8760 bars per year — 24/7 hourly, no session gaps. The macroscopic
    // regimes still follow real calendar dates.
    private static final double HOURS_PER_YEAR = 8760.0;

    public static void main(String[] args) throws IOException {
        Path outDir = Path.of(args.length > 0 ? args[0] : "demo/data");
        Files.createDirectories(outDir);
        for (Calibration cal : CALIBRATIONS) {
            String csv = generate(cal);
            Path file = outDir.resolve(cal.symbol() + "_1h.csv");
            Files.writeString(file, csv);
            System.out.println("Wrote " + file + " (" + csv.lines().count() + " lines)");
        }
    }

    /** Generates the CSV body for one calibration. Deterministic in {@code cal.seed()}. */
    public static String generate(Calibration cal) {
        Random rng = new Random(cal.seed());
        Instant start = LocalDate.of(cal.year(), 1, 1)
                .atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant end = LocalDate.of(cal.year() + 1, 1, 1)
                .atStartOfDay(ZoneOffset.UTC).toInstant();
        long hours = Duration.between(start, end).toHours();
        double dt = 1.0 / HOURS_PER_YEAR;
        double sqrtDt = Math.sqrt(dt);

        // Seed the GARCH variance at the first regime's long-run variance so
        // the first bars are not artificially calm.
        double firstVol = cal.regimes().getFirst().annualVol();
        double sigmaSq = firstVol * firstVol;
        double prevReturn = 0.0;
        double prevClose = cal.startPrice();

        StringBuilder csv = new StringBuilder("time,open,high,low,close,volume\n");
        for (long h = 0; h < hours; h++) {
            Instant time = start.plus(Duration.ofHours(h));
            LocalDate date = time.atOffset(ZoneOffset.UTC).toLocalDate();
            Regime regime = regimeFor(cal.regimes(), date);
            double sigmaLrSq = regime.annualVol() * regime.annualVol();
            sigmaSq = GARCH_OMEGA * sigmaLrSq
                    + GARCH_ALPHA * prevReturn * prevReturn
                    + GARCH_BETA * sigmaSq;
            double sigma = Math.sqrt(sigmaSq);
            double z = rng.nextGaussian();
            double ret = (regime.annualDrift() - 0.5 * sigmaSq) * dt + sigma * sqrtDt * z;
            double close = prevClose * Math.exp(ret);

            double open = prevClose;
            // Intra-bar range scales with current volatility plus a small
            // floor so flat-vol bars still have a believable wick.
            double barRange = sigma * sqrtDt * 0.6 + 0.0003;
            double high = Math.max(open, close) * (1.0 + rng.nextDouble() * barRange);
            double low = Math.min(open, close) * (1.0 - rng.nextDouble() * barRange);

            // Volume: base level plus a contribution proportional to the
            // absolute log-return, so crash hours carry the heaviest tape.
            long volume = (long) (50_000_000
                    + Math.abs(ret) * 8_000_000_000.0
                    + rng.nextDouble() * 20_000_000);

            csv.append(time).append(',')
                    .append(fmt(open)).append(',')
                    .append(fmt(high)).append(',')
                    .append(fmt(low)).append(',')
                    .append(fmt(close)).append(',')
                    .append(volume).append('\n');

            prevReturn = ret;
            prevClose = close;
        }
        return csv.toString();
    }

    private static Regime regimeFor(List<Regime> regimes, LocalDate date) {
        for (Regime r : regimes) {
            if (!date.isBefore(r.from()) && date.isBefore(r.to())) {
                return r;
            }
        }
        return regimes.getLast();
    }

    private static String fmt(double value) {
        return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    private SyntheticDataGenerator() {
    }
}
