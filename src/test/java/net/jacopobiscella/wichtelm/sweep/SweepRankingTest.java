package net.jacopobiscella.wichtelm.sweep;

import org.hatrack.frauholle.result.BacktestMetrics;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Ranking must keep a failed combination from landing at {@code rows.getFirst()}
 * when it ties a successful tradeless run on an undefined objective — otherwise
 * the console would suppress the paste-ready winner block even though a real run
 * exists (CLAUDE.md section 18.4).
 */
class SweepRankingTest {

    private static BacktestMetrics metrics(int trades, String sharpe) {
        BigDecimal z = BigDecimal.ZERO;
        return new BacktestMetrics(z, z, trades, z, new BigDecimal(sharpe), z, z, z, z, z);
    }

    @Test
    void successfulTradelessRunOutranksFailedRowForUndefinedObjective() {
        // profit_factor is undefined for both: the failed row has no metrics, and
        // the tradeless run extracts null. The ran() tie-break must still win.
        SweepResult failed = SweepResult.failed(Map.of("p", BigDecimal.ONE), "Boom: bad combo");
        SweepResult tradeless = SweepResult.success(Map.of("p", new BigDecimal("2")), metrics(0, "0"));

        List<SweepResult> rows = new ArrayList<>(List.of(failed, tradeless)); // failed first on input
        rows.sort(SweepRunner.ranking(SweepObjective.PROFIT_FACTOR));

        assertTrue(rows.getFirst().ran(),
                "a successful tradeless run must outrank a failed row, so the winner block shows");
    }

    @Test
    void tradedRunsStillOutrankTradelessOnes() {
        SweepResult traded = SweepResult.success(Map.of("p", new BigDecimal("1")), metrics(3, "1.5"));
        SweepResult tradeless = SweepResult.success(Map.of("p", new BigDecimal("2")), metrics(0, "9"));

        List<SweepResult> rows = new ArrayList<>(List.of(tradeless, traded));
        rows.sort(SweepRunner.ranking(SweepObjective.SHARPE));

        assertEquals(0, new BigDecimal("1").compareTo(rows.getFirst().combination().get("p")),
                "the traded run ranks first despite the tradeless run's higher Sharpe");
    }
}
