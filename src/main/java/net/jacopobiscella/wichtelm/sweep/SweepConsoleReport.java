package net.jacopobiscella.wichtelm.sweep;

import org.hatrack.frauholle.result.BacktestMetrics;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Renders a sweep's ranked results as a monospace console table (CLAUDE.md
 * section 18): {@code rank}, one column per swept parameter, then the objective
 * value, total return, Sharpe and trade count. Only the leading {@code top} rows
 * are tabulated; a trailing footer states how many combinations ran and points
 * at the winner with a paste-ready {@code [parameters]} block.
 */
public final class SweepConsoleReport {

    private SweepConsoleReport() {
    }

    /**
     * @param baseParameters the resolved non-swept parameters (strategy defaults
     *                        overlaid with the fixed {@code [parameters]}); the
     *                        winner's paste-ready block prints the full effective
     *                        set (these overlaid with its swept values) so copying
     *                        it into a plain config reproduces the ranked row
     *                        rather than silently reverting fixed parameters to
     *                        their defaults
     */
    public static String render(List<SweepResult> rows, List<String> axisNames,
                                SweepObjective objective, int top,
                                Map<String, BigDecimal> baseParameters) {
        if (rows.isEmpty()) {
            return "Sweep produced no combinations.";
        }
        // The ranked-by column is labeled generically "objective" so it never
        // duplicates a context column header (e.g. when --objective is sharpe).
        List<String> headers = new ArrayList<>();
        headers.add("rank");
        headers.addAll(axisNames);
        headers.add("objective");
        headers.add("total_return");
        headers.add("sharpe");
        headers.add("trades");

        int shown = Math.min(top, rows.size());
        List<List<String>> table = new ArrayList<>();
        for (int i = 0; i < shown; i++) {
            SweepResult row = rows.get(i);
            List<String> cells = new ArrayList<>();
            cells.add(Integer.toString(i + 1));
            for (String name : axisNames) {
                cells.add(num(row.combination().get(name)));
            }
            cells.add(row.objectiveValue(objective).map(SweepConsoleReport::num).orElse("-"));
            if (row.metrics().isPresent()) {
                BacktestMetrics m = row.metrics().get();
                cells.add(num(m.totalReturn()));
                cells.add(num(m.sharpeRatio()));
                cells.add(Integer.toString(m.numTrades()));
            } else {
                cells.add("FAILED");
                cells.add("-");
                cells.add("-");
            }
            table.add(cells);
        }

        int[] widths = new int[headers.size()];
        for (int c = 0; c < headers.size(); c++) {
            widths[c] = headers.get(c).length();
        }
        for (List<String> cells : table) {
            for (int c = 0; c < cells.size(); c++) {
                widths[c] = Math.max(widths[c], cells.get(c).length());
            }
        }

        StringBuilder sb = new StringBuilder();
        appendRow(sb, headers, widths);
        StringBuilder rule = new StringBuilder();
        for (int c = 0; c < widths.length; c++) {
            if (c > 0) {
                rule.append("  ");
            }
            rule.append("-".repeat(widths[c]));
        }
        sb.append(rule).append('\n');
        for (List<String> cells : table) {
            appendRow(sb, cells, widths);
        }

        long ran = rows.stream().filter(SweepResult::ran).count();
        sb.append('\n').append(rows.size()).append(" combinations (")
                .append(ran).append(" ran, ").append(rows.size() - ran)
                .append(" failed), ranked by ").append(objective.label()).append('.');

        SweepResult winner = rows.getFirst();
        if (winner.ran()) {
            sb.append("\nBest combination - paste into [parameters] for a full report:\n");
            sb.append("[parameters]\n");
            // Full effective set: base (defaults + fixed [parameters]) overlaid
            // with the winner's swept values, so the block reproduces this row
            // standalone instead of dropping fixed parameters back to defaults.
            Map<String, BigDecimal> effective = new LinkedHashMap<>(baseParameters);
            effective.putAll(winner.combination());
            for (Map.Entry<String, BigDecimal> entry : effective.entrySet()) {
                // Exact, unrounded: this block is meant to be pasted back into a
                // config to reproduce the row, so it must not lose precision the
                // way the display-rounded table columns do.
                sb.append(entry.getKey()).append(" = ").append(exact(entry.getValue())).append('\n');
            }
        }
        return sb.toString();
    }

    private static void appendRow(StringBuilder sb, List<String> cells, int[] widths) {
        for (int c = 0; c < cells.size(); c++) {
            if (c > 0) {
                sb.append("  ");
            }
            String cell = cells.get(c);
            sb.append(cell);
            sb.append(" ".repeat(widths[c] - cell.length()));
        }
        sb.append('\n');
    }

    private static String num(BigDecimal value) {
        if (value == null) {
            return "-";
        }
        return value.setScale(Math.min(value.scale(), 4), RoundingMode.HALF_UP)
                .stripTrailingZeros().toPlainString();
    }

    /** Exact, unrounded plain decimal — for the paste-ready winner block. */
    private static String exact(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }
}
