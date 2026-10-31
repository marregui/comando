package dev.marregui.comando.ui.shell;

import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import tools.jackson.databind.JsonNode;

import dev.marregui.comando.core.transcript.Block;
import dev.marregui.comando.core.transcript.Transcript;
import dev.marregui.comando.ui.kit.format.Json;

/**
 * The folders a session has worked in, read from its own tool calls (record 111): the repository of each file a call
 * named by its full path ({@code file_path}, {@code notebook_path}, {@code path}), or its folder outside a repository,
 * the latest first. A path the session writes relative to one of them is looked for there.
 */
final class WorkedIn {

    /** How many folders a search reads, the latest first. */
    static final int MAX_ROOTS = 8;
    /** How many transcript blocks are read, from the end. */
    static final int MAX_BLOCKS = 4_000;
    /** How far up from a file a repository's top is looked for. */
    static final int MAX_UP = 12;
    private static final List<String> FIELDS = List.of("file_path", "notebook_path", "path");

    private WorkedIn() {
    }

    /** The folders {@code transcript}'s tool calls worked in, the latest first, at most {@code max}. Off the EDT. */
    static List<Path> roots(final Transcript transcript, final int max) {
        final Set<Path> out = new LinkedHashSet<>();
        final int size = transcript.size();
        for (int i = size - 1; i >= Math.max(0, size - MAX_BLOCKS) && out.size() < max; i--) {
            if (!(transcript.get(i) instanceof Block.ToolCall call)) {
                continue;
            }
            final JsonNode in = Json.parse(call.inputJson());
            for (final String field : FIELDS) {
                final String value = in.path(field).asString("");
                if (!value.startsWith("/")) {
                    continue;
                }
                try {
                    final Path named = Path.of(value).normalize();
                    final Path folder = Files.isDirectory(named) ? named : named.getParent();
                    if (folder != null) {
                        out.add(repositoryOf(folder));
                    }
                } catch (final InvalidPathException e) {
                    // a value that is no path names no folder
                }
            }
        }
        return new ArrayList<>(out);
    }

    /** The top of the git repository holding {@code folder}, else {@code folder} itself. */
    static Path repositoryOf(final Path folder) {
        Path at = folder;
        for (int i = 0; at != null && i < MAX_UP; i++, at = at.getParent()) {
            if (Files.exists(at.resolve(".git"))) {
                return at;
            }
        }
        return folder;
    }
}
