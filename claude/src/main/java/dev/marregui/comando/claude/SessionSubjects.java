package dev.marregui.comando.claude;

import java.io.IOException;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import dev.marregui.comando.core.json.Json;
import dev.marregui.comando.core.json.JsonException;
import dev.marregui.comando.core.json.JsonNode;
import dev.marregui.comando.core.state.SessionSubject;
import dev.marregui.comando.core.transcript.Block;
import dev.marregui.comando.core.transcript.Transcript;

/**
 * What one session works on, read from its own writes (record 138): the repository of the file its latest
 * {@code Write}, {@code Edit}, {@code MultiEdit} or {@code NotebookEdit} changed, and the others it changed files in.
 * A shell command is not read, a call that failed wrote nothing, and each pass reads only the blocks added since the
 * last one.
 * <p>
 * A path under the working folder or Claude Code's folder is dropped by its text, before any file check: the kit and
 * the brain inside it say nothing of the work, and no {@code .git} under them is read. Confined to the session's
 * strand.
 */
final class SessionSubjects {

    private static final List<String> PATH_FIELDS = List.of("file_path", "notebook_path");
    private static final Pattern TICKET = Pattern.compile("[A-Za-z]{2,}-\\d+");
    private static final Set<String> WRITES = Set.of("Edit", "MultiEdit", "NotebookEdit", "Write");

    private final Path claudeDir;
    /** The repositories written to, the latest last. */
    private final LinkedHashSet<Path> repositories = new LinkedHashSet<>();
    /** How many of the transcript's blocks the passes have read. */
    private int scanned;

    /** A session of the Claude Code whose stored conversations are in {@code projectsDir}, inside its folder. */
    SessionSubjects(final Path projectsDir) {
        this.claudeDir = projectsDir.toAbsolutePath().normalize().getParent();
    }

    /** The ticket {@code branch} names, upper case, empty when it names none. */
    static String ticket(final String branch) {
        final Matcher m = TICKET.matcher(branch);
        return m.find() ? m.group().toUpperCase(Locale.ROOT) : "";
    }

    /** Reads the blocks added since the last pass; the branch is read each time, as a session may change it. */
    SessionSubject pass(final Transcript transcript, final Path workDir) {
        final int size = transcript.size();
        if (scanned < size) {
            final List<Path> dropped = new ArrayList<>(forms(workDir));
            dropped.addAll(forms(claudeDir));
            for (; scanned < size; scanned++) {
                if (transcript.get(scanned) instanceof Block.ToolCall call && WRITES.contains(call.name())) {
                    if (call.status() == Block.ToolStatus.RUNNING) {
                        break; // a background sub-agent's write, not in yet: the next pass starts here
                    }
                    if (call.status() == Block.ToolStatus.DONE) {
                        repositoryOf(call.inputJson(), dropped).ifPresent(repositories::addLast);
                    }
                }
            }
        }
        if (repositories.isEmpty()) {
            return SessionSubject.NONE;
        }
        final Path latest = repositories.getLast();
        final String branch = GitBranch.of(latest);
        return new SessionSubject(latest.toString(), branch, ticket(branch),
                repositories.reversed().stream().skip(1L).map(Path::toString).toList());
    }

    /** The transcript was emptied and its conversation goes on: what was read stands, the next pass starts over. */
    void rewind() {
        scanned = 0;
    }

    /** The transcript now holds another conversation. */
    void forget() {
        repositories.clear();
        scanned = 0;
    }

    /** Whether {@code workDir} is Claude Code's folder or lies inside it. */
    boolean inKit(final Path workDir) {
        final List<Path> kit = forms(claudeDir);
        return forms(workDir).stream().anyMatch(dir -> kit.stream().anyMatch(dir::startsWith));
    }

    /**
     * The repository of the file {@code value} names; a path through a link is dropped by the real folder it reaches.
     */
    private static Optional<Path> repositoryOfFile(final String value, final List<Path> dropped) {
        try {
            final Path file = Path.of(value).normalize();
            if (!file.isAbsolute() || forms(file.getParent()).stream()
                    .anyMatch(form -> dropped.stream().anyMatch(form::startsWith))) {
                return Optional.empty();
            }
            return GitBranch.top(file.getParent())
                    .filter(top -> forms(top).stream().noneMatch(form -> dropped.stream().anyMatch(form::startsWith)));
        } catch (final InvalidPathException _) {
            return Optional.empty(); // a value that is no path names no file
        }
    }

    /** {@code folder} as written and as it really lies: a link's target is what a session's paths name. */
    private static List<Path> forms(final Path folder) {
        final Path written = folder.toAbsolutePath().normalize();
        try {
            return List.of(written, written.toRealPath());
        } catch (final IOException _) {
            return List.of(written);
        }
    }

    /** The repository of the file a call's input names, unless the path is dropped or lies in no repository. */
    private Optional<Path> repositoryOf(final String inputJson, final List<Path> dropped) {
        final JsonNode input;
        try {
            input = Json.readTree(inputJson);
        } catch (final JsonException _) {
            return Optional.empty();
        }
        for (final String field : PATH_FIELDS) {
            final String value = input.path(field).asString("");
            if (!value.isEmpty()) {
                return repositoryOfFile(value, dropped);
            }
        }
        return Optional.empty();
    }
}
