package dev.marregui.comando.ui.kit.text;

import com.github.difflib.DiffUtils;
import com.github.difflib.UnifiedDiffUtils;
import com.github.difflib.patch.Patch;

import java.util.Arrays;
import java.util.List;

/** Unified diffs of two texts, three lines of context. */
public final class Diffs {

    private Diffs() {
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
