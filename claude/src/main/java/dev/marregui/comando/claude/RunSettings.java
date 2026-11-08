package dev.marregui.comando.claude;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import dev.marregui.comando.core.files.Mapped;

/**
 * The model and effort a session's message ran at, read from the transcript Claude Code writes beside its stream: the
 * stream's {@code assistant} event names the model but not the effort, while each assistant line of the transcript
 * carries both ({@code message.model}, {@code perTurnEffort}, else {@code effort}). A skill that sets {@code effort} or
 * {@code model}, a {@code /effort} or a {@code /model} shows there from the next message on. Looks only at the file's
 * last {@value #TAIL} bytes, where the latest message's lines are, mapped and scanned in place from the end: only the
 * line found is copied out and parsed, so a read that finds nothing (the retries while the line is not yet written)
 * allocates next to nothing. One reader per session, on the session's strand.
 */
final class RunSettings {

    /** What a message ran at; either part empty when the transcript does not say. */
    record Ran(String model, String effort) {
    }

    /** The bytes read from the transcript's end: the latest message's lines are well within them. */
    static final int TAIL = 256 * 1024;

    private static final byte[] NEWLINE = {'\n'};
    private static final byte[] ASSISTANT = "\"assistant\"".getBytes(StandardCharsets.UTF_8);

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
        final byte[] needle = ('"' + messageId + '"').getBytes(StandardCharsets.UTF_8);
        try (final Mapped mapped = Mapped.open(f)) {
            final long length = mapped.size();
            if (length == 0L) {
                return Optional.empty();
            }
            final long from = Math.max(0L, length - TAIL);
            final MemorySegment tail = mapped.segment().asSlice(from);
            for (long at = lastIndexOf(tail, needle, tail.byteSize()); at >= 0L; ) {
                final long lineStart = lastIndexOf(tail, NEWLINE, at) + 1L;
                final long newline = indexOf(tail, NEWLINE[0], at);
                final long lineEnd = newline < 0L ? tail.byteSize() : newline;
                // a line cut by the tail's start is not whole; nothing before it is in the tail either
                if (lineStart == 0L && from > 0L) {
                    break;
                }
                final MemorySegment line = tail.asSlice(lineStart, lineEnd - lineStart);
                if (lastIndexOf(line, ASSISTANT, line.byteSize()) >= 0L) {
                    final JsonNode e = json.readTree(Mapped.bytes(line));
                    if (e.path("type").asString("").equals("assistant")
                            && e.path("message").path("id").asString("").equals(messageId)) {
                        final String effort = e.path("perTurnEffort").asString(e.path("effort").asString(""));
                        return Optional.of(new Ran(e.path("message").path("model").asString(""), effort));
                    }
                }
                at = lineStart == 0L ? -1L : lastIndexOf(tail, needle, lineStart - 1L);
            }
        } catch (final IOException | JacksonException e) {
            // a line cut by the end of a write, or a file being rewritten: the next message reads again
        } catch (final InternalError e) {
            // the file was cut shorter while mapped: the JDK reports the fault on the mapped page as an InternalError;
            // this reader holds no state the read could have left half-done, and the next message reads again
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
        try (final var projects = Files.newDirectoryStream(projectsDir)) {
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

    /**
     * Where the last {@code needle} starting before {@code before} begins in {@code s}, or -1. A byte loop: the line is
     * near the end, so a read stops after a few kilobytes.
     */
    private static long lastIndexOf(final MemorySegment s, final byte[] needle, final long before) {
        final byte first = needle[0];
        for (long i = Math.min(before, s.byteSize() - needle.length + 1L) - 1L; i >= 0L; i--) {
            if (s.get(ValueLayout.JAVA_BYTE, i) == first && matches(s, i, needle)) {
                return i;
            }
        }
        return -1L;
    }

    /** Where the first {@code b} at or after {@code from} is in {@code s}, or -1. */
    private static long indexOf(final MemorySegment s, final byte b, final long from) {
        for (long i = from; i < s.byteSize(); i++) {
            if (s.get(ValueLayout.JAVA_BYTE, i) == b) {
                return i;
            }
        }
        return -1L;
    }

    private static boolean matches(final MemorySegment s, final long at, final byte[] needle) {
        for (int k = 1; k < needle.length; k++) {
            if (s.get(ValueLayout.JAVA_BYTE, at + k) != needle[k]) {
                return false;
            }
        }
        return true;
    }
}
