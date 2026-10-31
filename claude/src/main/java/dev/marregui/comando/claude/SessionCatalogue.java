package dev.marregui.comando.claude;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Lists the conversations Claude Code stored on disk, {@code <projects>/<project>/<session id>.jsonl}, for
 * the import dialog. Reads only the head (working directory, branch) and the tail (title, last prompt, latest branch)
 * of each file, so a large transcript costs two bounded reads.
 */
public final class SessionCatalogue {

    private static final System.Logger LOG = System.getLogger(SessionCatalogue.class.getName());
    private static final int HEAD_BYTES = 64 * 1024;
    private static final int TAIL_BYTES = 256 * 1024;

    /**
     * One stored conversation.
     *
     * @param title      the custom or generated title, empty when none
     * @param lastPrompt the last prompt a person typed, clipped
     */
    public record Entry(String sessionId, Path file, String cwd, String branch, String title, String lastPrompt, long modifiedMillis,
                        long sizeBytes) {
    }

    private final Path projectsDir;
    private final ObjectMapper json = new ObjectMapper();

    public SessionCatalogue(final Path projectsDir) {
        this.projectsDir = projectsDir;
    }

    /** Conversations newest first, at most {@code limit}. */
    public List<Entry> scan(final int limit) {
        if (!Files.isDirectory(projectsDir)) {
            return List.of();
        }
        List<Path> files;
        try (Stream<Path> projects = Files.list(projectsDir)) {
            files = projects.filter(Files::isDirectory)
                    .flatMap(SessionCatalogue::transcripts)
                    .sorted(Comparator.comparingLong(SessionCatalogue::modified).reversed())
                    .limit(limit)
                    .toList();
        } catch (final IOException e) {
            LOG.log(System.Logger.Level.WARNING, "cannot list [dir=" + projectsDir + ']', e);
            return List.of();
        }
        final List<Entry> entries = new ArrayList<>(files.size());
        for (final Path f : files) {
            final Entry e = read(f);
            if (e != null) {
                entries.add(e);
            }
        }
        return entries;
    }

    private static Stream<Path> transcripts(final Path project) {
        try (Stream<Path> s = Files.list(project)) {
            return s.filter(p -> p.getFileName().toString().endsWith(".jsonl") && Files.isRegularFile(p)).toList().stream();
        } catch (final IOException e) {
            return Stream.empty();
        }
    }

    private static long modified(final Path p) {
        try {
            return Files.getLastModifiedTime(p).toMillis();
        } catch (final IOException e) {
            return 0L;
        }
    }

    Entry read(final Path file) {
        final String name = file.getFileName().toString();
        final String id = name.substring(0, name.length() - ".jsonl".length());
        String cwd = "";
        String branch = "";
        String title = "";
        String lastPrompt = "";
        long size;
        try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "r")) {
            size = raf.length();
            for (final String line : lines(raf, 0, (int) Math.min(size, HEAD_BYTES), false)) {
                final JsonNode n = parse(line);
                if (n != null && cwd.isEmpty() && n.hasNonNull("cwd")) {
                    cwd = n.path("cwd").asString("");
                    branch = n.path("gitBranch").asString("");
                }
            }
            final long tailStart = Math.max(0, size - TAIL_BYTES);
            for (final String line : lines(raf, tailStart, (int) (size - tailStart), tailStart > 0)) {
                final JsonNode n = parse(line);
                if (n == null) {
                    continue;
                }
                switch (n.path("type").asString("")) {
                    case "custom-title" -> title = n.path("customTitle").asString(title);
                    case "ai-title" -> {
                        if (title.isEmpty() || !n.path("aiTitle").asString("").isEmpty()) {
                            title = n.path("aiTitle").asString(title);
                        }
                    }
                    case "user" -> {
                        final JsonNode c = n.path("message").path("content");
                        if (c.isString() && "human".equals(n.path("origin").path("kind").asString("human"))
                                && !c.asString("").startsWith("<")) {
                            lastPrompt = c.asString("");
                        }
                        if (n.hasNonNull("gitBranch")) {
                            branch = n.path("gitBranch").asString(branch);
                        }
                    }
                    default -> {
                        // other entries carry nothing the catalogue shows
                    }
                }
            }
        } catch (final IOException e) {
            LOG.log(System.Logger.Level.DEBUG, "cannot read [file=" + file + ']', e);
            return null;
        }
        if (cwd.isEmpty()) {
            return null; // not a conversation Claude Code can resume from a known directory
        }
        return new Entry(id, file, cwd, branch, title, StreamMapper.clip(lastPrompt.strip().replace('\n', ' '), 200), modified(file), size);
    }

    private JsonNode parse(final String line) {
        try {
            return json.readTree(line);
        } catch (final JacksonException e) {
            return null;
        }
    }

    private static List<String> lines(final RandomAccessFile raf, final long from, final int length, final boolean skipFirst) throws IOException {
        final byte[] buf = new byte[length];
        raf.seek(from);
        raf.readFully(buf);
        final List<String> lines = new ArrayList<>(new String(buf, StandardCharsets.UTF_8).lines().toList());
        if (skipFirst && !lines.isEmpty()) {
            lines.removeFirst(); // the read started mid-line
        }
        if (!lines.isEmpty() && from + length < raf.length()) {
            lines.removeLast(); // the read ended mid-line
        }
        return lines;
    }
}
