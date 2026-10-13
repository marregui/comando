package dev.marregui.comando.ui.panels;

import dev.marregui.comando.core.view.ViewSpec;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;

/**
 * A query plan, one node per line of {@code EXPLAIN} output, nested by indentation: a line belongs to the
 * nearest line above it that is indented less. Postgres's {@code ->} arrows are dropped from labels.
 */
final class PlanTree {

    /** A node while its children are still being read. */
    private static final class Open {
        final int indent;
        final String label;
        final List<ViewSpec.TreeNode> children = new ArrayList<>();

        Open(int indent, String label) {
            this.indent = indent;
            this.label = label;
        }
    }

    private PlanTree() {
    }

    static boolean isExplain(String sql) {
        return sql.strip().toUpperCase(Locale.ROOT).startsWith("EXPLAIN");
    }

    static ViewSpec.TreeNode parse(List<String> lines) {
        Deque<Open> stack = new ArrayDeque<>();
        stack.push(new Open(-1, "plan"));
        int count = 0;
        for (String raw : lines) {
            for (String line : raw.split("\n")) {
                if (line.isBlank()) {
                    continue;
                }
                int indent = 0;
                while (indent < line.length() && line.charAt(indent) == ' ') {
                    indent++;
                }
                String label = line.strip();
                if (label.startsWith("->")) {
                    label = label.substring(2).strip();
                }
                while (stack.peek().indent >= indent) {
                    close(stack);
                }
                stack.push(new Open(indent, label));
                count++;
            }
        }
        while (stack.size() > 1) {
            close(stack);
        }
        return new ViewSpec.TreeNode("plan", count + " lines", List.copyOf(stack.pop().children));
    }

    private static void close(Deque<Open> stack) {
        Open done = stack.pop();
        stack.peek().children.add(new ViewSpec.TreeNode(done.label, "", List.copyOf(done.children)));
    }
}
