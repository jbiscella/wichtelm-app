package net.jacopobiscella.wichtelm.sweep;

import org.hatrack.frauholle.result.BacktestMetrics;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

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

    public static String render(List<SweepResult> rows, List<String> axisNames,
                                SweepObjective objective, int top) {
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
            for (String name : axisNames) {
                sb.append(name).append(" = ").append(num(winner.combination().get(name))).append('\n');
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
}
