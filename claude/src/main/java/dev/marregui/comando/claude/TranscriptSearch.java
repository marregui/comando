package dev.marregui.comando.claude;

import java.io.IOException;
import java.io.InputStream;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

import dev.marregui.comando.core.files.ByteScan;
import dev.marregui.comando.core.files.Mapped;
import dev.marregui.comando.core.json.Json;
import dev.marregui.comando.core.json.JsonException;
import dev.marregui.comando.core.json.JsonNode;
import dev.marregui.comando.core.model.SearchHit;
import dev.marregui.comando.core.model.SearchQuery;
import dev.marregui.comando.core.port.SearchPort;

/**
 * Looks in the conversations Claude Code stored, {@code <projects>/<project>/<session id>.jsonl}, newest first, with no
 * index (record 139). Each file is mapped and its bytes scanned for the text as JSON writes it; only a line that holds
 * those bytes is parsed, and it counts only if the text is in a message: a prompt a person typed or the text of an
 * answer. Keys, metadata, thinking, tool calls and their results do not. A message gives one hit, at its first match,
 * and a conversation is listed under its custom title, else its agent's name, else the start of its id.
 * <p>
 * A file cut while it was read counts as not read, as does one that cannot be opened; a line that does not parse, as
 * the last of a file still being written may not, is passed over.
 */
public final class TranscriptSearch implements SearchPort.Source {

    /** A snippet's length at most, in chars. */
    static final int SNIPPET_CHARS = 200;

    private static final System.Logger LOG = System.getLogger(TranscriptSearch.class.getName());
    /** The chars of a message a hit keeps each side of its match, for the preview. Unmeasured: a screenful of text. */
    private static final int CONTEXT_CHARS = 1_500;
    /** Bytes scanned between two looks at {@code ended}, as in the search of the folders. */
    private static final long CHUNK_BYTES = 1L << 20;
    /** Chars kept before a match in a long message, so what precedes it reads while most of the snippet follows it. */
    private static final int CHARS_BEFORE = 60;
    /** How much of an id names a conversation with no title, as the card's Conversation fact shows it. */
    private static final int ID_CHARS = 8;
    private static final byte NEWLINE = '\n';
    private static final String SUFFIX = ".jsonl";
    /** How a hit's heading names when its message was written: the reader's own clock, to the minute. */
    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("d MMM yyyy HH:mm", Locale.ROOT);
    /** The quotes cannot lie in a JSON string unescaped, so these bytes are found only where a line says its type. */
    private static final byte[] CUSTOM_TITLE = ByteScan.needle("\"type\":\"custom-title\"");
    private static final byte[] AGENT_NAME = ByteScan.needle("\"type\":\"agent-name\"");
    /** How the text Claude Code adds to a conversation in the user's place starts: tags, interrupts, the summary. */
    private static final List<String> NOT_TYPED = List.of(
            "<command-name>", "<command-message>", "<local-command-stdout>", "[Request interrupted",
            "This session is being continued from a previous conversation");

    private final Path projectsDir;

    public TranscriptSearch(final Path projectsDir) {
        this.projectsDir = projectsDir;
    }

    @Override
    public int scan(final SearchQuery query, final Predicate<SearchHit> found, final BooleanSupplier ended) {
        if (!query.conversations()) {
            return 0;
        }
        final char[] encoded = Json.quote(query.text());
        final byte[] needle = ByteScan.needle(new String(encoded));
        final String word = lowerCase(query.text());
        final List<Stored> files = new ArrayList<>();
        int failed = list(files);
        files.sort(Comparator.comparingLong(Stored::modifiedMillis).reversed());
        for (final Stored stored : files) {
            if (ended.getAsBoolean()) {
                return failed;
            }
            try {
                if (!Mapped.read(stored.file(), m -> new Reading(m, stored, needle, word, found, ended).read())) {
                    return failed;
                }
            } catch (final IOException e) {
                failed++;
                LOG.log(System.Logger.Level.DEBUG, "cannot search [file=" + stored.file() + ']', e);
            }
        }
        return failed;
    }

    /** {@code text} with its ASCII letters in lower case; every other char stays, as in a scan of the bytes. */
    private static String lowerCase(final String text) {
        final StringBuilder out = new StringBuilder(text);
        for (int i = 0; i < out.length(); i++) {
            out.setCharAt(i, lowerCase(out.charAt(i)));
        }
        return out.toString();
    }

    private static char lowerCase(final char c) {
        return c >= 'A' && c <= 'Z' ? Character.toLowerCase(c) : c;
    }

    /** Where {@code lowerCaseWord} first occurs in {@code text}, ASCII letters in either case; -1 when it does not. */
    private static int indexOf(final String text, final String lowerCaseWord) {
        final int last = text.length() - lowerCaseWord.length();
        for (int at = 0; at <= last; at++) {
            int i = 0;
            while (i < lowerCaseWord.length() && lowerCase(text.charAt(at + i)) == lowerCaseWord.charAt(i)) {
                i++;
            }
            if (i == lowerCaseWord.length()) {
                return at;
            }
        }
        return -1;
    }

    /**
     * Who wrote the messages of {@code line} and when, as a hit's heading says it: "You" or "Claude", then the time
     * when the line has one that reads.
     */
    static String heading(final JsonNode line, final ZoneId zone) {
        final String who = "assistant".equals(line.path("type").asString("")) ? "Claude" : "You";
        final String stamp = line.path("timestamp").asString("");
        if (stamp.isEmpty()) {
            return who;
        }
        try {
            return who + " · " + WHEN.format(Instant.parse(stamp).atZone(zone));
        } catch (final DateTimeException _) {
            return who;
        }
    }

    /** The messages a line holds; none when Claude Code wrote it for itself or a sub-agent's run. */
    private static List<String> messages(final JsonNode line) {
        if (line.path("isMeta").asBoolean(false) || line.path("isSidechain").asBoolean(false)
                || line.path("isCompactSummary").asBoolean(false)) {
            return List.of();
        }
        final JsonNode content = line.path("message").path("content");
        return switch (line.path("type").asString("")) {
            case "assistant" -> textBlocks(content);
            case "user" -> "human".equals(line.path("origin").path("kind").asString("human"))
                    ? prompts(content)
                    : List.of();
            default -> List.of();
        };
    }

    private static List<String> prompts(final JsonNode content) {
        final List<String> texts = content.isString() ? List.of(content.asString("")) : textBlocks(content);
        return texts.stream().filter(TranscriptSearch::isTyped).toList();
    }

    private static boolean isTyped(final String text) {
        final String start = text.stripLeading();
        return NOT_TYPED.stream().noneMatch(start::startsWith);
    }

    private static List<String> textBlocks(final JsonNode content) {
        final List<String> out = new ArrayList<>();
        if (content.isArray()) {
            for (final JsonNode block : content) {
                if ("text".equals(block.path("type").asString(""))) {
                    out.add(block.path("text").asString(""));
                }
            }
        }
        return out;
    }

    /** {@code text} with a tab or any other control char as a space, which keeps every offset. */
    private static String oneLine(final String text) {
        final StringBuilder out = new StringBuilder(text);
        for (int i = 0; i < out.length(); i++) {
            if (Character.isISOControl(out.charAt(i))) {
                out.setCharAt(i, ' ');
            }
        }
        return out.toString();
    }

    /** Fills {@code out} with the stored conversations; returns how many folders or files could not be listed. */
    private int list(final List<Stored> out) {
        if (!Files.isDirectory(projectsDir)) {
            return 0;
        }
        int failed = 0;
        try (final DirectoryStream<Path> projects = Files.newDirectoryStream(projectsDir, Files::isDirectory)) {
            for (final Path project : projects) {
                failed += list(project, out);
            }
        } catch (final IOException | DirectoryIteratorException e) {
            failed++;
            LOG.log(System.Logger.Level.DEBUG, "cannot list [dir=" + projectsDir + ']', e);
        }
        return failed;
    }

    /** The time is taken here, once: a file still being written would otherwise change under the sort. */
    private int list(final Path project, final List<Stored> out) {
        int failed = 0;
        try (final DirectoryStream<Path> files = Files.newDirectoryStream(project, "*" + SUFFIX)) {
            for (final Path file : files) {
                try {
                    out.add(new Stored(file, Files.getLastModifiedTime(file).toMillis()));
                } catch (final IOException e) {
                    failed++;
                    LOG.log(System.Logger.Level.DEBUG, "cannot stat [file=" + file + ']', e);
                }
            }
        } catch (final IOException | DirectoryIteratorException e) {
            failed++;
            LOG.log(System.Logger.Level.DEBUG, "cannot list [dir=" + project + ']', e);
        }
        return failed;
    }

    private record Stored(Path file, long modifiedMillis) {
    }

    /** The scan of one mapped file, on the thread that mapped it. */
    private final class Reading {

        private final Mapped mapped;
        private final MemorySegment bytes;
        private final long size;
        private final Stored stored;
        private final byte[] needle;
        private final String word;
        private final Predicate<SearchHit> found;
        private final BooleanSupplier ended;
        private final String id;
        /** The conversation's name, read at its first hit; a file with no hit never pays for it. */
        private String group = "";

        Reading(
                final Mapped mapped,
                final Stored stored,
                final byte[] needle,
                final String word,
                final Predicate<SearchHit> found,
                final BooleanSupplier ended
        ) {
            this.mapped = mapped;
            this.bytes = mapped.segment();
            this.size = mapped.size();
            this.stored = stored;
            this.needle = needle;
            this.word = word;
            this.found = found;
            this.ended = ended;
            final String name = stored.file().getFileName().toString();
            this.id = name.substring(0, name.length() - SUFFIX.length());
        }

        /** Hands over the file's hits; false when {@code found} ended the scan. */
        boolean read() throws IOException {
            long from = 0L;
            for (long at = next(needle, from); at >= 0L; at = next(needle, from)) {
                final long end = lineEnd(at);
                final Optional<JsonNode> line = parsed(lineStart(at), end);
                if (line.isPresent()) {
                    final String heading = heading(line.get(), ZoneId.systemDefault());
                    for (final String message : messages(line.get())) {
                        final int match = indexOf(message, word);
                        if (match >= 0 && !found.test(hit(message, match, heading))) {
                            return false;
                        }
                    }
                }
                from = end + 1L;
            }
            return true;
        }

        /** The next {@code what} at or after {@code from}, by chunks; -1 when there is none or the search ended. */
        private long next(final byte[] what, final long from) {
            for (long start = from; start < size; start += CHUNK_BYTES) {
                if (ended.getAsBoolean()) {
                    return -1L;
                }
                final long length = Math.min(CHUNK_BYTES + what.length - 1L, size - start);
                final long at = ByteScan.indexOf(bytes.asSlice(start, length), what, 0L);
                if (at >= 0L) {
                    return start + at;
                }
            }
            return -1L;
        }

        /**
         * The start of the line holding {@code at}; a chunk's look at {@code ended} on the way, so a line is no wait.
         */
        private long lineStart(final long at) {
            long start = at;
            while (start > 0L && bytes.get(ValueLayout.JAVA_BYTE, start - 1L) != NEWLINE) {
                start--;
                if ((start & CHUNK_BYTES - 1L) == 0L && ended.getAsBoolean()) {
                    break;
                }
            }
            return start;
        }

        /** The end of the line holding {@code at}; a chunk's look at {@code ended} on the way, so a line is no wait. */
        private long lineEnd(final long at) {
            long end = at;
            while (end < size && bytes.get(ValueLayout.JAVA_BYTE, end) != NEWLINE) {
                end++;
                if ((end & CHUNK_BYTES - 1L) == 0L && ended.getAsBoolean()) {
                    break;
                }
            }
            return end;
        }

        /**
         * The line in bytes {@code from} to {@code to}; empty when it does not parse. A line of invalid UTF-8 is read
         * as the conversation's view reads it, so what is shown can be found.
         */
        private Optional<JsonNode> parsed(final long from, final long to) throws IOException {
            try (final InputStream in = mapped.stream(from, to)) {
                return Optional.of(Json.readTree(in));
            } catch (final JsonException _) {
                try {
                    return Optional.of(Json.readTree(mapped.text(from, to)));
                } catch (final JsonException _) {
                    return Optional.empty();
                }
            }
        }

        /**
         * The hit at {@code at} in {@code message}, written as {@code heading} says: {@link #CHARS_BEFORE} before it,
         * the rest of a snippet after.
         */
        private SearchHit hit(final String message, final int at, final String heading) throws IOException {
            int from = Math.max(0, at - CHARS_BEFORE);
            if (from > 0 && Character.isLowSurrogate(message.charAt(from))) {
                from++;
            }
            int to = Math.min(message.length(), from + SNIPPET_CHARS);
            if (to < message.length() && Character.isHighSurrogate(message.charAt(to - 1))) {
                to--;
            }
            final String snippet = oneLine(message.substring(from, to));
            final int start = within(message, Math.max(0, at - CONTEXT_CHARS));
            final int stop = within(message, Math.min(message.length(), at + CONTEXT_CHARS));
            return new SearchHit(SearchHit.Kind.CONVERSATION, stored.file().toString(), id, group(), 0, snippet,
                    at - from, Math.min(word.length(), to - at), stored.modifiedMillis(),
                    message.substring(start, stop), at - start, "", heading);
        }

        /** {@code at}, moved off the middle of a surrogate pair. */
        private static int within(final String message, final int at) {
            return at > 0 && at < message.length() && Character.isLowSurrogate(message.charAt(at)) ? at - 1 : at;
        }

        private String group() throws IOException {
            if (group.isEmpty()) {
                final String title = latest(CUSTOM_TITLE, "custom-title", "customTitle");
                final String name = title.isEmpty() ? latest(AGENT_NAME, "agent-name", "agentName") : title;
                group = name.isEmpty() ? id.substring(0, Math.min(ID_CHARS, id.length())) : name;
            }
            return group;
        }

        /** The {@code field} of the file's last line of type {@code type} that has one, found by {@code mark}. */
        private String latest(final byte[] mark, final String type, final String field) throws IOException {
            String value = "";
            long from = 0L;
            for (long at = next(mark, from); at >= 0L; at = next(mark, from)) {
                final long end = lineEnd(at);
                final Optional<JsonNode> line = parsed(lineStart(at), end)
                        .filter(n -> type.equals(n.path("type").asString("")));
                if (line.isPresent() && !line.get().path(field).asString("").isBlank()) {
                    value = line.get().path(field).asString("");
                }
                from = end + 1L;
            }
            return value;
        }
    }
}
