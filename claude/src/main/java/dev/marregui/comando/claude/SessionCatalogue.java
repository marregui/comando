package dev.marregui.comando.claude;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Lists the conversations Claude Code stored on disk, {@code <projects>/<project>/<session id>.jsonl}, for
 * the import dialog. Reads only the head (working directory, branch) and the tail (title, last prompt)
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

    public SessionCatalogue(Path projectsDir) {
        this.projectsDir = projectsDir;
    }

    /** Conversations newest first, at most {@code limit}. */
    public List<Entry> scan(int limit) {
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
        } catch (IOException e) {
            LOG.log(System.Logger.Level.WARNING, "cannot list " + projectsDir + ": " + e.getMessage());
            return List.of();
        }
        List<Entry> entries = new ArrayList<>(files.size());
        for (Path f : files) {
            Entry e = read(f);
            if (e != null) {
                entries.add(e);
            }
        }
        return entries;
    }

    private static Stream<Path> transcripts(Path project) {
        try (Stream<Path> s = Files.list(project)) {
            return s.filter(p -> p.getFileName().toString().endsWith(".jsonl") && Files.isRegularFile(p)).toList().stream();
        } catch (IOException e) {
            return Stream.empty();
        }
    }

    private static long modified(Path p) {
        try {
            return Files.getLastModifiedTime(p).toMillis();
        } catch (IOException e) {
            return 0L;
        }
    }

    Entry read(Path file) {
        String name = file.getFileName().toString();
        String id = name.substring(0, name.length() - ".jsonl".length());
        String cwd = "";
        String branch = "";
        String title = "";
        String lastPrompt = "";
        long size;
        try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "r")) {
            size = raf.length();
            for (String line : lines(raf, 0, (int) Math.min(size, HEAD_BYTES), false)) {
                JsonNode n = parse(line);
                if (n != null && cwd.isEmpty() && n.hasNonNull("cwd")) {
                    cwd = n.path("cwd").asText();
                    branch = n.path("gitBranch").asText("");
                }
            }
            long tailStart = Math.max(0, size - TAIL_BYTES);
            for (String line : lines(raf, tailStart, (int) (size - tailStart), tailStart > 0)) {
                JsonNode n = parse(line);
                if (n == null) {
                    continue;
                }
                switch (n.path("type").asText()) {
                    case "custom-title" -> title = n.path("customTitle").asText(title);
                    case "ai-title" -> {
                        if (title.isEmpty() || !n.path("aiTitle").asText().isEmpty()) {
                            title = n.path("aiTitle").asText(title);
                        }
                    }
                    case "user" -> {
                        JsonNode c = n.path("message").path("content");
                        if (c.isTextual() && "human".equals(n.path("origin").path("kind").asText("human"))
                                && !c.asText().startsWith("<")) {
                            lastPrompt = c.asText();
                        }
                        if (n.hasNonNull("gitBranch")) {
                            branch = n.path("gitBranch").asText(branch);
                        }
                    }
                    default -> {
                        // other entries carry nothing the catalogue shows
                    }
                }
            }
        } catch (IOException e) {
            LOG.log(System.Logger.Level.DEBUG, "cannot read " + file + ": " + e.getMessage());
            return null;
        }
        if (cwd.isEmpty()) {
            return null; // not a conversation Claude Code can resume from a known directory
        }
        return new Entry(id, file, cwd, branch, title, StreamMapper.clip(lastPrompt.strip().replace('\n', ' '), 200), modified(file), size);
    }

    private JsonNode parse(String line) {
        try {
            return json.readTree(line);
        } catch (IOException e) {
            return null;
        }
    }

    private static List<String> lines(RandomAccessFile raf, long from, int length, boolean skipFirst) throws IOException {
        byte[] buf = new byte[length];
        raf.seek(from);
        raf.readFully(buf);
        List<String> lines = new ArrayList<>(new String(buf, StandardCharsets.UTF_8).lines().toList());
        if (skipFirst && !lines.isEmpty()) {
            lines.removeFirst(); // the read started mid-line
        }
        if (!lines.isEmpty() && from + length < raf.length()) {
            lines.removeLast(); // the read ended mid-line
        }
        return lines;
    }
}
