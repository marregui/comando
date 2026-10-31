package dev.marregui.comando.claude;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The model and effort a session's message ran at, read from the transcript Claude Code writes beside its stream: the
 * stream's {@code assistant} event names the model but not the effort, while each assistant line of the transcript
 * carries both ({@code message.model}, {@code perTurnEffort}, else {@code effort}). A skill that sets {@code effort} or
 * {@code model}, a {@code /effort} or a {@code /model} shows there from the next message on. Reads only the file's last
 * {@value #TAIL} bytes, where the latest message's lines are; one reader per session, on the session's strand.
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

    RunSettings(final Path projectsDir, final ObjectMapper json) {
        this.projectsDir = projectsDir;
        this.json = json;
    }

    /**
     * What message {@code messageId} of conversation {@code conversationId} ran at; empty while its line is not yet in
     * the transcript, or the transcript cannot be found or read.
     */
    Optional<Ran> of(final String conversationId, final String messageId) {
        if (conversationId.isEmpty() || messageId.isEmpty()) {
            return Optional.empty();
        }
        final Path f = file(conversationId);
        if (f == null) {
            return Optional.empty();
        }
        try {
            final String tail = tail(f);
            final String needle = "\"" + messageId + "\"";
            for (int end = tail.length(); end > 0; ) {
                final int start = tail.lastIndexOf('\n', end - 1) + 1;
                final String line = tail.substring(start, end);
                end = start - 1;
                if (!line.contains(needle) || !line.contains("\"assistant\"")) {
                    continue;
                }
                final JsonNode e = json.readTree(line);
                if (e.path("type").asString("").equals("assistant") && e.path("message").path("id").asString("").equals(messageId)) {
                    final String effort = e.path("perTurnEffort").asString(e.path("effort").asString(""));
                    return Optional.of(new Ran(e.path("message").path("model").asString(""), effort));
                }
            }
        } catch (IOException | JacksonException e) {
            // a line cut by the tail's start, or a file being rewritten: the next message reads again
        }
        return Optional.empty();
    }

    /** The conversation's transcript, {@code <projects>/<project>/<id>.jsonl}, found once per conversation. */
    private Path file(final String conversationId) {
        if (conversationId.equals(conversation) && file != null) {
            return file;
        }
        conversation = conversationId;
        file = null;
        if (!Files.isDirectory(projectsDir)) {
            return null;
        }
        try (var projects = Files.newDirectoryStream(projectsDir)) {
            for (final Path project : projects) {
                final Path candidate = project.resolve(conversationId + ".jsonl");
                if (Files.isRegularFile(candidate)) {
                    file = candidate;
                    break;
                }
            }
        } catch (final IOException e) {
            // the projects folder could not be listed: found on a later message
        }
        return file;
    }

    private static String tail(final Path f) throws IOException {
        try (RandomAccessFile raf = new RandomAccessFile(f.toFile(), "r")) {
            final long length = raf.length();
            final int n = (int) Math.min(length, TAIL);
            final byte[] bytes = new byte[n];
            raf.seek(length - n);
            raf.readFully(bytes);
            return new String(bytes, StandardCharsets.UTF_8);
        }
    }
}
