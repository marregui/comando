package dev.marregui.comando.ui.kit.text;

import java.util.ArrayList;
import java.util.List;

/**
 * The unified diff text of a list of differences: two file headers, then hunks of the changed lines with
 * {@code context} unchanged lines around them. Differences at most twice the context apart share a hunk. A hunk's
 * header counts the lines it shows of each side; an empty side names the line before it, as {@code diff -u} does.
 * No line says a text lacks a newline at its end: the texts are lists of lines.
 */
final class UnifiedDiff {

    private UnifiedDiff() {
    }

    /**
     * The lines of the unified diff that turns {@code a} into {@code b} by {@code chunks} (from {@link Myers}), its
     * headers naming {@code aName} and {@code bName}; no lines, not even the headers, when there is no difference.
     */
    static List<String> of(
            final String aName,
            final String bName,
            final List<String> a,
            final List<String> b,
            final List<SplitDiff.Chunk> chunks,
            final int context) {
        final List<String> out = new ArrayList<>();
        if (chunks.isEmpty()) {
            return out;
        }
        out.add("--- " + aName);
        out.add("+++ " + bName);
        int first = 0;
        for (int i = 1; i < chunks.size(); i++) {
            if (chunks.get(i - 1).leftEnd() + context < chunks.get(i).leftStart() - context) {
                hunk(a, b, chunks.subList(first, i), context, out);
                first = i;
            }
        }
        hunk(a, b, chunks.subList(first, chunks.size()), context, out);
        return out;
    }

    /** Adds to {@code out} the hunk of {@code chunks}: its header, then its lines. */
    private static void hunk(
            final List<String> a,
            final List<String> b,
            final List<SplitDiff.Chunk> chunks,
            final int context,
            final List<String> out) {
        final List<String> lines = new ArrayList<>();
        final SplitDiff.Chunk head = chunks.getFirst();
        int aLines = 0;
        int bLines = 0;
        for (int line = Math.max(0, head.leftStart() - context); line < head.leftStart(); line++) {
            lines.add(" " + a.get(line));
            aLines++;
            bLines++;
        }
        int kept = head.leftStart();
        for (final SplitDiff.Chunk c : chunks) {
            for (int line = kept; line < c.leftStart(); line++) {
                lines.add(" " + a.get(line));
                aLines++;
                bLines++;
            }
            for (int line = c.leftStart(); line < c.leftEnd(); line++) {
                lines.add("-" + a.get(line));
            }
            for (int line = c.rightStart(); line < c.rightEnd(); line++) {
                lines.add("+" + b.get(line));
            }
            aLines += c.leftEnd() - c.leftStart();
            bLines += c.rightEnd() - c.rightStart();
            kept = c.leftEnd();
        }
        for (int line = kept; line < kept + context && line < a.size(); line++) {
            lines.add(" " + a.get(line));
            aLines++;
            bLines++;
        }
        final int aStart = Math.max(1, head.leftStart() + 1 - context) - (aLines == 0 ? 1 : 0);
        final int bStart = Math.max(1, head.rightStart() + 1 - context) - (bLines == 0 ? 1 : 0);
        out.add("@@ -" + aStart + "," + aLines + " +" + bStart + "," + bLines + " @@");
        out.addAll(lines);
    }
}
