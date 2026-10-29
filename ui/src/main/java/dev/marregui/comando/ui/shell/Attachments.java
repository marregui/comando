package dev.marregui.comando.ui.shell;

import dev.marregui.comando.core.result.ResultView;

import java.util.List;
import java.util.stream.IntStream;

/**
 * The text ⌘⇧I puts in the editor: a run's rows or its error, fenced. It never contains a blank line, because
 * a blank line ends a block and would separate the rows from the question written under them: an empty row
 * reads "(empty row)", a newline inside a cell reads "⏎", and blank lines of an error are dropped.
 */
final class Attachments {

    static final int DEFAULT_ROWS = 50;

    private Attachments() {
    }

    /** The error {@code sql} ended with, fenced. */
    static String error(String sql, String error) {
        List<String> lines = error.strip().lines().filter(l -> !l.isBlank()).toList();
        // an error without text would leave the fence empty: a blank line, which ends the block
        return "Error from `" + oneLine(sql) + "`:\n```\n" + (lines.isEmpty() ? "(no message)" : String.join("\n", lines)) + "\n```\n";
    }

    /** The selected rows, or the first {@link #DEFAULT_ROWS} when {@code selected} is empty. */
    static String rows(String sql, ResultView r, int[] selected) {
        String which = selected.length == 0 ? "first " + Math.min(r.rowCount(), DEFAULT_ROWS) : "selected " + selected.length;
        int[] rows = selected.length == 0 ? IntStream.range(0, Math.min(r.rowCount(), DEFAULT_ROWS)).toArray() : selected;
        StringBuilder sb = new StringBuilder();
        sb.append("Result of `").append(oneLine(sql)).append("` (").append(which).append(" of ").append(r.rowCount()).append(" rows):\n```\n");
        StringBuilder line = new StringBuilder();
        for (int c = 0; c < r.columnCount(); c++) {
            line.append(c == 0 ? "" : "\t").append(r.columnName(c));
        }
        sb.append(line).append('\n');
        StringBuilder cell = new StringBuilder();
        for (int i : rows) {
            line.setLength(0);
            for (int c = 0; c < r.columnCount(); c++) {
                if (c > 0) {
                    line.append('\t');
                }
                cell.setLength(0);
                r.format(i, c, cell);
                for (int k = 0; k < cell.length(); k++) {
                    char ch = cell.charAt(k);
                    line.append(ch == '\n' ? '⏎' : ch == '\r' ? ' ' : ch);
                }
            }
            sb.append(line.toString().isBlank() ? "(empty row)" : line).append('\n');
        }
        return sb.append("```\n").toString();
    }

    private static String oneLine(String sql) {
        return sql.replace('\n', ' ').strip();
    }
}
