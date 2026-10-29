package dev.marregui.comando.ui.kit.text;

import com.github.difflib.DiffUtils;
import com.github.difflib.UnifiedDiffUtils;
import com.github.difflib.patch.Patch;
import tools.jackson.databind.JsonNode;

import java.util.Arrays;
import java.util.List;

/** Unified diffs of two texts, three lines of context. */
public final class Diffs {

    /** The file an edit tool changes, the text it replaces and the text it writes. */
    public record Edit(String path, String before, String after) {
    }

    private Diffs() {
    }

    /**
     * What the edit tool {@code tool} changes with input {@code in}; null for a tool that edits nothing. A
     * multi-edit's replaced texts and written texts each follow one another, an ellipsis line between them.
     */
    public static Edit edit(String tool, JsonNode in) {
        String path = in.path("file_path").asString("");
        return switch (tool) {
            case "Edit" -> new Edit(path, in.path("old_string").asString(""), in.path("new_string").asString(""));
            case "MultiEdit" -> {
                StringBuilder before = new StringBuilder();
                StringBuilder after = new StringBuilder();
                for (JsonNode e : in.path("edits")) {
                    before.append(e.path("old_string").asString("")).append("\n…\n");
                    after.append(e.path("new_string").asString("")).append("\n…\n");
                }
                yield new Edit(path, before.toString(), after.toString());
            }
            case "Write" -> new Edit(path, "", in.path("content").asString(""));
            default -> null;
        };
    }

    public static List<String> unified(String path, String before, String after) {
        List<String> a = before.isEmpty() ? List.of() : Arrays.asList(before.split("\n", -1));
        List<String> b = after.isEmpty() ? List.of() : Arrays.asList(after.split("\n", -1));
        Patch<String> patch = DiffUtils.diff(a, b);
        return UnifiedDiffUtils.generateUnifiedDiff(path.isEmpty() ? "before" : path, path.isEmpty() ? "after" : path, a, patch, 3);
    }

    /**
     * Whether line {@code index} of {@link #unified}'s output is a file header. Only the first two lines are:
     * a removed SQL comment ({@code "--- x"} in the diff) or an added {@code "++"} line is content.
     */
    public static boolean isHeader(int index, String line) {
        return index < 2 && (line.startsWith("---") || line.startsWith("+++"));
    }

    public static boolean isAdded(int index, String line) {
        return line.startsWith("+") && !isHeader(index, line);
    }

    public static boolean isRemoved(int index, String line) {
        return line.startsWith("-") && !isHeader(index, line);
    }
}
