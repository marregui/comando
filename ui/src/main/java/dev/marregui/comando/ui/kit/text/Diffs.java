package dev.marregui.comando.ui.kit.text;

import java.util.Arrays;
import java.util.List;

import dev.marregui.comando.core.json.JsonNode;

/** Unified diffs of two texts, three lines of context, and what an edit or write tool's card shows of its input. */
public final class Diffs {

    /** The lines of a written text a card shows: the transcript's limit for what a call is given. */
    private static final int WRITTEN_LINES = 300;

    /** The file an edit tool changes, the text it replaces and the text it writes. */
    public record Edit(String path, String before, String after) {
    }

    private Diffs() {
    }

    /**
     * What the edit tool {@code tool} changes with input {@code in}; null for a tool that edits nothing and for Write:
     * it gives a whole text and not what it replaces, so every line would read as added. A multi-edit's replaced texts
     * and written texts each follow one another, an ellipsis line between them.
     */
    public static Edit edit(final String tool, final JsonNode in) {
        final String path = in.path("file_path").asString("");
        return switch (tool) {
            case "Edit" -> new Edit(path, in.path("old_string").asString(""), in.path("new_string").asString(""));
            case "MultiEdit" -> {
                final StringBuilder before = new StringBuilder();
                final StringBuilder after = new StringBuilder();
                for (final JsonNode e : in.path("edits")) {
                    before.append(e.path("old_string").asString("")).append("\n…\n");
                    after.append(e.path("new_string").asString("")).append("\n…\n");
                }
                yield new Edit(path, before.toString(), after.toString());
            }
            default -> null;
        };
    }

    /** The text a Write gives, as a card shows it: its first lines, then how many more it has. */
    public static String writtenText(final JsonNode in) {
        final String text = in.path("content").asString("");
        int cut = -1;
        for (int i = 0; i < WRITTEN_LINES; i++) {
            cut = text.indexOf('\n', cut + 1);
            if (cut < 0) {
                return text;
            }
        }
        final long rest = text.substring(cut + 1).lines().count();
        return rest == 0 ? text : text.substring(0, cut) + "\n… " + rest + " more lines";
    }

    /** How long the text a Write gives is: "48 lines". */
    public static String writtenLines(final JsonNode in) {
        final long lines = in.path("content").asString("").lines().count();
        return lines + (lines == 1 ? " line" : " lines");
    }

    /** The unified diff of {@code before} and {@code after}, its headers naming {@code path}, or before and after. */
    public static List<String> unified(final String path, final String before, final String after) {
        final List<String> a = before.isEmpty() ? List.of() : Arrays.asList(before.split("\n", -1));
        final List<String> b = after.isEmpty() ? List.of() : Arrays.asList(after.split("\n", -1));
        final String aName = path.isEmpty() ? "before" : path;
        final String bName = path.isEmpty() ? "after" : path;
        return UnifiedDiff.of(aName, bName, a, b, Myers.diff(a, b), 3);
    }

    /**
     * Whether line {@code index} of {@link #unified}'s output is a file header. Only the first two lines are:
     * a removed SQL comment ({@code "--- x"} in the diff) or an added {@code "++"} line is content.
     */
    public static boolean isHeader(final int index, final String line) {
        return index < 2 && (line.startsWith("---") || line.startsWith("+++"));
    }

    public static boolean isAdded(final int index, final String line) {
        return line.startsWith("+") && !isHeader(index, line);
    }

    public static boolean isRemoved(final int index, final String line) {
        return line.startsWith("-") && !isHeader(index, line);
    }
}
