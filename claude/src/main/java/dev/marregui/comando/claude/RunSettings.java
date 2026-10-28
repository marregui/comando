package dev.marregui.comando.claude;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * The model and effort a session's message ran at, read from the transcript Claude Code writes beside its stream: the
 * stream's {@code assistant} event names the model but not the effort, while each assistant line of the transcript
 * carries both ({@code message.model}, {@code perTurnEffort}, else {@code effort}). A skill that sets {@code effort} or
 * {@code model}, a {@code /effort} or a {@code /model} shows there from the next message on. Reads only the file's last
 * {@value #TAIL} bytes, where the latest message's lines are; one reader per session, on its stream's thread.
 */
final class RunSettings {

    /** What a message ran at; either part empty when the transcript does not say. */
    record Ran(String model, String effort) {
    }

    /** The bytes read from the transcript's end: the latest message's lines are well within them. */
    static final int TAIL = 256 * 1024;

    private final Path projectsDir;
    private final ObjectMapper json;
    private String conversation = "";
    private Path file;

    RunSettings(Path projectsDir, ObjectMapper json) {
        this.projectsDir = projectsDir;
        this.json = json;
    }

    /**
     * What message {@code messageId} of conversation {@code conversationId} ran at; empty while its line is not yet in the
     * transcript, or the transcript cannot be found or read.
     */
    Optional<Ran> of(String conversationId, String messageId) {
        if (conversationId.isEmpty() || messageId.isEmpty()) {
            return Optional.empty();
        }
        Path f = file(conversationId);
        if (f == null) {
            return Optional.empty();
        }
        try {
            String tail = tail(f);
            String needle = "\"" + messageId + "\"";
            for (int end = tail.length(); end > 0; ) {
                int start = tail.lastIndexOf('\n', end - 1) + 1;
                String line = tail.substring(start, end);
                end = start - 1;
                if (!line.contains(needle) || !line.contains("\"assistant\"")) {
                    continue;
                }
                JsonNode e = json.readTree(line);
                if (e.path("type").asText().equals("assistant") && e.path("message").path("id").asText().equals(messageId)) {
                    String effort = e.path("perTurnEffort").asText(e.path("effort").asText(""));
                    return Optional.of(new Ran(e.path("message").path("model").asText(""), effort));
                }
            }
        } catch (IOException e) {
            // a line cut by the tail's start, or a file being rewritten: the next message reads again
        }
        return Optional.empty();
    }

    /** The conversation's transcript, {@code <projects>/<project>/<id>.jsonl}, found once per conversation. */
    private Path file(String conversationId) {
        if (conversationId.equals(conversation) && file != null) {
            return file;
        }
        conversation = conversationId;
        file = null;
        if (!Files.isDirectory(projectsDir)) {
            return null;
        }
        try (var projects = Files.newDirectoryStream(projectsDir)) {
            for (Path project : projects) {
                Path candidate = project.resolve(conversationId + ".jsonl");
                if (Files.isRegularFile(candidate)) {
                    file = candidate;
                    break;
                }
            }
        } catch (IOException e) {
            // the projects folder could not be listed: found on a later message
        }
        return file;
    }

    private static String tail(Path f) throws IOException {
        try (RandomAccessFile raf = new RandomAccessFile(f.toFile(), "r")) {
            long length = raf.length();
            int n = (int) Math.min(length, TAIL);
            byte[] bytes = new byte[n];
            raf.seek(length - n);
            raf.readFully(bytes);
            return new String(bytes, StandardCharsets.UTF_8);
        }
    }
}
