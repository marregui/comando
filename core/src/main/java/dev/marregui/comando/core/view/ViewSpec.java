package dev.marregui.comando.core.view;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Data for one view, validated before it reaches the reducer. Each record is one view type; the UI's
 * widget registry maps the type to the widget that renders it.
 */
public sealed interface ViewSpec {

    /** The view type name used by the MCP tools and the widget registry. */
    String type();

    /** Rows Claude holds, as text. */
    record Table(List<String> columns, List<List<String>> rows) implements ViewSpec {
        public Table {
            columns = List.copyOf(columns);
            rows = rows.stream().map(List::copyOf).toList();
            for (final List<String> row : rows) {
                if (row.size() != columns.size()) {
                    throw new IllegalArgumentException("row width " + row.size() + " differs from column count " + columns.size());
                }
            }
        }

        @Override
        public String type() {
            return "table";
        }
    }

    /** One plotted series: points sorted or not, x and y of equal length. */
    record Series(String name, double[] x, double[] y) {
        public Series {
            Objects.requireNonNull(name, "name");
            if (x.length != y.length) {
                throw new IllegalArgumentException("series '" + name + "': x has " + x.length + " points, y has " + y.length);
            }
            x = x.clone();
            y = y.clone();
        }

        @Override
        public boolean equals(final Object o) {
            return o instanceof Series s && name.equals(s.name) && Arrays.equals(x, s.x) && Arrays.equals(y, s.y);
        }

        @Override
        public int hashCode() {
            return 31 * name.hashCode() + Arrays.hashCode(x) * 17 + Arrays.hashCode(y);
        }

        @Override
        public String toString() {
            return "Series[" + name + ", points=" + x.length + ']';
        }
    }

    /** Series over shared axes; {@code timeX} formats x as epoch milliseconds. */
    record Plot(String xLabel, String yLabel, boolean timeX, List<Series> series) implements ViewSpec {
        public Plot {
            Objects.requireNonNull(xLabel, "xLabel");
            Objects.requireNonNull(yLabel, "yLabel");
            series = List.copyOf(series);
            if (series.isEmpty()) {
                throw new IllegalArgumentException("plot has no series");
            }
        }

        @Override
        public String type() {
            return "plot";
        }
    }

    /** Two versions of a text, rendered as a diff. */
    record Diff(String path, String before, String after) implements ViewSpec {
        public Diff {
            Objects.requireNonNull(path, "path");
            Objects.requireNonNull(before, "before");
            Objects.requireNonNull(after, "after");
        }

        @Override
        public String type() {
            return "diff";
        }
    }

    record Markdown(String markdown) implements ViewSpec {
        public Markdown {
            Objects.requireNonNull(markdown, "markdown");
        }

        @Override
        public String type() {
            return "markdown";
        }
    }

    /** A text or file shown read-only, scrolled to {@code line} (1-based, 0 for none). */
    record Text(String path, String text, String syntax, int line) implements ViewSpec {
        public Text {
            Objects.requireNonNull(path, "path");
            Objects.requireNonNull(text, "text");
            Objects.requireNonNull(syntax, "syntax");
        }

        @Override
        public String type() {
            return "text";
        }
    }

    /**
     * A tree node; {@code link} is what activating the node opens ({@link #fileLink} path and line), empty for none.
     */
    record TreeNode(String label, String detail, String link, List<TreeNode> children) {

        /** The scheme of a link that opens a file at a line, read by every view that opens files. */
        public static final String FILE_SCHEME = "comando-file:";
        /** The label of the entry that ends a folder read only in part. */
        public static final String MORE = "…";

        public TreeNode {
            Objects.requireNonNull(label, "label");
            Objects.requireNonNull(detail, "detail");
            Objects.requireNonNull(link, "link");
            children = List.copyOf(children);
        }

        public TreeNode(final String label, final String detail, final List<TreeNode> children) {
            this(label, detail, "", children);
        }

        public static final TreeNode NONE = new TreeNode("", "", "", List.of());

        /** The link that opens {@code path} at {@code line} (0 for none). */
        public static String fileLink(final String path, final int line) {
            return FILE_SCHEME + path + "#" + line;
        }
    }

    /** A labelled tree: test results, a schema, a call tree. */
    record Tree(TreeNode root) implements ViewSpec {
        public Tree {
            Objects.requireNonNull(root, "root");
        }

        @Override
        public String type() {
            return "tree";
        }
    }

    /**
     * What the git repositories of the Files tab's folders hold that their last commits do not. The view carries no
     * data: the repositories are read while it is open, and its widget shows them as they arrive.
     *
     * @param root   the top folder of the one repository shown; empty for every repository
     * @param folder a folder inside {@code root}, relative to it, whose changes alone are shown; empty for all of them
     * @param name   what the repository's row is called; empty for its folder's name
     */
    record Changes(String root, String folder, String name) implements ViewSpec {

        /** Every Changes view's title, as IntelliJ's commit view: one view, whichever repository or folder it shows. */
        public static final String TITLE = "Uncommitted changes";

        public Changes {
            root = root == null ? "" : root;
            folder = folder == null ? "" : folder;
            name = name == null ? "" : name;
        }

        /** Every repository, whole. */
        public Changes() {
            this("", "", "");
        }

        /** The repository at {@code root}, whole, its row called {@code name}. */
        public static Changes of(final String root, final String name) {
            return new Changes(root, "", name);
        }

        @Override
        public String type() {
            return "changes";
        }
    }

    enum RunState { RUNNING, PASSED, FAILED, CANCELLED }

    /**
     * A command run: its output lives in the console store under {@code runId}. When it ends, {@code tests}
     * holds the test results found (root {@link TreeNode#NONE} when none) and {@code failures} the text of
     * each failed test by its name.
     *
     * @param panel the id of the panel the run was started from, "" when none; failures go to its session
     */
    record Console(long runId, String command, RunState state, int exitCode, long millis, TreeNode tests,
                   Map<String, String> failures, String panel) implements ViewSpec {
        public Console {
            Objects.requireNonNull(command, "command");
            panel = panel == null ? "" : panel;
            Objects.requireNonNull(state, "state");
            Objects.requireNonNull(tests, "tests");
            failures = Map.copyOf(failures);
        }

        public static Console running(final long runId, final String command) {
            return running(runId, command, "");
        }

        public static Console running(final long runId, final String command, final String panel) {
            return new Console(runId, command, RunState.RUNNING, -1, 0L, TreeNode.NONE, Map.of(), panel);
        }

        @Override
        public String type() {
            return "console";
        }
    }

    /**
     * A sub-agent's conversation as a session of its own (record 109): its blocks live in the transcript store under
     * {@link dev.marregui.comando.core.transcript.TranscriptRegistry#subagentKey}; {@code session} is the session that
     * started it, whose folder its links are read from; {@code state} says whether it is followed, or why no longer.
     */
    record Agent(String session, String toolUseId, String description, String state) implements ViewSpec {
        public Agent {
            Objects.requireNonNull(session, "session");
            Objects.requireNonNull(toolUseId, "toolUseId");
            description = description == null ? "" : description;
            state = state == null ? "" : state;
        }

        @Override
        public String type() {
            return "agent";
        }
    }
}
