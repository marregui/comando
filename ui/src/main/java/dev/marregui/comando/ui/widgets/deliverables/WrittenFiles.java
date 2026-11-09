package dev.marregui.comando.ui.widgets.deliverables;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

import dev.marregui.comando.core.model.HomeDir;
import dev.marregui.comando.core.transcript.Block;
import dev.marregui.comando.core.transcript.Transcript;
import dev.marregui.comando.ui.kit.format.Json;
import dev.marregui.comando.ui.kit.vcs.RepositoryTop;

/**
 * The files one session's transcript wrote (record 143): the full paths its successful Write, Edit, MultiEdit and
 * NotebookEdit calls named, in the order the session first wrote each, each once.
 *
 * <p>Each {@link #read()} reads only the blocks added since the last one, and again the calls that were still
 * running then; a transcript cleared and filled again is read from its start. Not thread-safe: one reader at a time,
 * each read handed to the next by a happens-before edge (the widget starts a read only after the last one's result
 * reached the EDT).</p>
 */
final class WrittenFiles {

    /** The tools that write a file, each with the input field naming it. */
    private static final Map<String, String> TOOLS = Map.of(
            "Write", "file_path",
            "Edit", "file_path",
            "MultiEdit", "file_path",
            "NotebookEdit", "notebook_path");

    private final TreeMap<Integer, Path> calls = new TreeMap<>();
    private final Map<Path, String> folders = new HashMap<>();
    private final List<Integer> running = new ArrayList<>();
    private final Transcript transcript;
    private Block first;
    private int next;

    WrittenFiles(final Transcript transcript) {
        this.transcript = transcript;
    }

    /**
     * The folder of {@code file} as a row shows it: the repository's name and the folder inside it
     * ({@code comando/ui/src}), else the folder with the home folder as {@code ~}.
     */
    static String folderOf(final Path file) {
        final Path folder = file.getParent();
        if (folder == null) {
            return "";
        }
        final Optional<Path> repository = RepositoryTop.above(folder);
        if (repository.isEmpty()) {
            return HomeDir.shorten(folder.toString());
        }
        final Path at = repository.get();
        final Path name = at.getFileName();
        final String top = name == null ? at.toString() : name.toString();
        final String inside = at.relativize(folder).toString();
        return inside.isEmpty() ? top : top + "/" + inside;
    }

    /** The files written so far, in first-write order. Off the EDT: a folder's label reads the file system. */
    List<Written> read() {
        final int size = transcript.size();
        if (size < next || next > 0 && transcript.get(0) != first) {
            calls.clear();
            running.clear();
            next = 0;
        }
        if (next == 0 && size > 0) {
            first = transcript.get(0);
        }
        final List<Integer> again = List.copyOf(running);
        running.clear();
        for (final int index : again) {
            look(index);
        }
        for (; next < size; next++) {
            look(next);
        }
        final Set<Path> seen = new HashSet<>();
        final List<Written> out = new ArrayList<>();
        for (final Path file : calls.values()) {
            if (seen.add(file)) {
                out.add(new Written(file, folders.computeIfAbsent(file, WrittenFiles::folderOf)));
            }
        }
        return out;
    }

    private static Path fileOf(final String input, final String field) {
        final String value = Json.parse(input).path(field).asString("");
        if (value.isBlank()) {
            return null;
        }
        try {
            final Path file = Path.of(value);
            return file.isAbsolute() ? file.normalize() : null;
        } catch (final InvalidPathException _) {
            // a value that is no path names no file
            return null;
        }
    }

    private void look(final int index) {
        if (!(transcript.get(index) instanceof Block.ToolCall call)) {
            return;
        }
        final String field = TOOLS.get(call.name());
        if (field == null) {
            return;
        }
        switch (call.status()) {
            case RUNNING -> running.add(index);
            case DONE -> {
                final Path file = fileOf(call.inputJson(), field);
                if (file != null) {
                    calls.put(index, file);
                }
            }
            case ERROR -> {
                // a failed call wrote nothing
            }
        }
    }

    /** A file written, with its folder as the row shows it. */
    record Written(Path file, String folder) {
    }
}
