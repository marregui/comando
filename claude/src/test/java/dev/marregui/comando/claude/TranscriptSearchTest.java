package dev.marregui.comando.claude;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.marregui.comando.core.json.Json;
import dev.marregui.comando.core.model.SearchHit;
import dev.marregui.comando.core.model.SearchQuery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/// [TranscriptSearch] finds text in the messages of the conversations Claude Code stored.
///
/// - Only a prompt a person typed and the text of an answer count; keys, metadata, thinking and tools do not.
/// - The text is found as JSON writes it, in either case for ASCII letters.
/// - A snippet is one line of at most 200 chars around the match.
/// - A hit names its conversation and the title it goes by.
/// - Conversations are read newest first, top level only.
/// - A scan stops when told; a file cut while mapped counts as failed.
class TranscriptSearchTest {

    private static final String ID = "0b5e7a1c-2d3e-4f50-8a9b-0c1d2e3f4a5b";
    private static final BooleanSupplier GOES_ON = () -> false;

    @TempDir
    Path projects;
    private final List<SearchHit> hits = new ArrayList<>();
    private Path project;

    @BeforeEach
    void setUp() throws Exception {
        project = Files.createDirectories(projects.resolve("-w"));
    }

    /// A hit keeps the message around its match for the preview, and says where the match is in it.
    @Test
    void aHitCarriesTheMessageAroundItsMatch() throws Exception {
        final String before = "a line before the word\n\n".repeat(20);
        store(ID, line("assistant", before + "zebra and the rest of the message"));
        search("zebra");
        assertThat(hits).singleElement().satisfies(h -> {
            assertThat(h.context()).contains("a line before the word\n\n", "and the rest of the message");
            assertThat(h.context().substring(h.contextStart())).startsWith("zebra");
        });
    }

    /// A hit's heading says who wrote its message and when, to the minute on the reader's clock; a line with no time,
    /// or one that does not read, names the writer alone.
    @Test
    void aHitsHeadingSaysWhoWroteItAndWhen() throws Exception {
        assertThat(TranscriptSearch.heading(Json.readTree(
                "{\"type\":\"assistant\",\"timestamp\":\"2026-11-07T22:15:30.123Z\"}"), ZoneOffset.UTC))
                .isEqualTo("Claude · 7 Nov 2026 22:15");
        assertThat(TranscriptSearch.heading(Json.readTree("{\"type\":\"user\"}"), ZoneOffset.UTC)).isEqualTo("You");
        assertThat(TranscriptSearch.heading(Json.readTree("{\"type\":\"user\",\"timestamp\":\"soon\"}"),
                ZoneOffset.UTC)).isEqualTo("You");
        store(ID, line("user", "zebra"));
        search("zebra");
        assertThat(hits).singleElement().satisfies(h -> {
            assertThat(h.heading()).startsWith("You");
            assertThat(h.root()).isEmpty();
        });
    }

    /// A prompt that opens with a tag of the user's is a prompt; the tags Claude Code writes for a command are not.
    @Test
    void aTypedPromptThatOpensWithATagCounts() throws Exception {
        store(ID, line("user", "<div> zebra </div>") + line("user", "<command-name>/zebra</command-name>"));
        assertThat(search("zebra")).isZero();
        assertThat(hits).singleElement().satisfies(h -> assertThat(h.snippet()).contains("<div>"));
    }

    /// A line holding bytes that are not UTF-8, as a file cut at a character can, still gives its hit.
    @Test
    void aLineOfInvalidTextStillGivesItsHit() throws Exception {
        final byte[] start = "{\"type\":\"user\",\"message\":{\"role\":\"user\",\"content\":\"zebra "
                .getBytes(StandardCharsets.UTF_8);
        final byte[] end = "\"}}\n".getBytes(StandardCharsets.UTF_8);
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        bytes.write(start);
        bytes.write(0xC3); // the start of a two-byte character whose second byte is missing
        bytes.write(end);
        Files.write(project.resolve(ID + ".jsonl"), bytes.toByteArray());
        assertThat(search("zebra")).isZero();
        assertThat(hits).hasSize(1);
    }

    /// A word that is a key, a value of a line's metadata and a tool's name appears in every line's bytes and in no
    /// message, so it gives no hit.
    @Test
    void aKeywordInMetadataGivesNoHit() throws Exception {
        store(ID, """
                {"type":"user","gitBranch":"zebra","cwd":"/zebra","zebra":1,"message":{"role":"user","content":"hello"}}
                {"type":"assistant","message":{"id":"zebra","model":"zebra","content":[{"type":"text","text":"hi"}]}}
                {"type":"queue-operation","operation":"enqueue","content":"zebra"}
                {"type":"last-prompt","lastPrompt":"zebra"}
                """);
        assertThat(search("zebra")).isZero();
        assertThat(hits).isEmpty();
    }

    /// A tool's input and result, the model's thinking and a line Claude Code wrote for itself hold the word and give
    /// no hit.
    @Test
    void toolsThinkingAndOwnLinesDoNotCount() throws Exception {
        store(ID, """
                {"type":"assistant","message":{"content":[{"type":"tool_use","id":"t","name":"Write","input":{"content":"zebra"}}]}}
                {"type":"user","message":{"role":"user","content":[{"type":"tool_result","tool_use_id":"t","content":"zebra"}]}}
                {"type":"assistant","message":{"content":[{"type":"thinking","thinking":"zebra"}]}}
                {"type":"user","isMeta":true,"message":{"role":"user","content":"zebra"}}
                {"type":"user","isSidechain":true,"message":{"role":"user","content":"zebra"}}
                {"type":"user","isCompactSummary":true,"message":{"role":"user","content":"zebra"}}
                {"type":"user","origin":{"kind":"task"},"message":{"role":"user","content":"zebra"}}
                {"type":"user","message":{"role":"user","content":"<local-command-stdout>zebra</local-command-stdout>"}}
                {"type":"user","message":{"role":"user","content":"[Request interrupted by user zebra]"}}
                """);
        search("zebra");
        assertThat(hits).isEmpty();
    }

    /// A prompt as a string, a prompt in a text block and an answer's text block each give a hit.
    @Test
    void promptsAndAnswersCount() throws Exception {
        store(ID, """
                {"type":"user","message":{"role":"user","content":"one zebra"}}
                {"type":"user","message":{"role":"user","content":[{"type":"text","text":"two zebra"}]}}
                {"type":"assistant","message":{"content":[{"type":"thinking","thinking":"no"},{"type":"text","text":"three zebra"}]}}
                """);
        search("zebra");
        assertThat(hits).extracting(SearchHit::snippet).containsExactly("one zebra", "two zebra", "three zebra");
        assertThat(hits).allSatisfy(h -> {
            assertThat(h.kind()).isEqualTo(SearchHit.Kind.CONVERSATION);
            assertThat(h.line()).isZero();
            assertThat(h.conversation()).isEqualTo(ID);
            assertThat(h.source()).isEqualTo(project.resolve(ID + ".jsonl").toString());
        });
    }

    /// `say "hi"` lies in the file as `say \"hi\"`: the bytes scanned are the JSON's, the match the message's.
    @Test
    void aWordWithAJsonEscapedQuoteIsFound() throws Exception {
        store(ID, line("user", "they say \"hi\" here"));
        search("say \"hi\"");
        assertThat(hits).singleElement().satisfies(h -> {
            assertThat(h.snippet()).isEqualTo("they say \"hi\" here");
            assertThat(h.snippet().substring(h.matchStart(), h.matchStart() + h.matchLength())).isEqualTo("say \"hi\"");
        });
    }

    /// A backslash and a line break are escaped in the file as well, and non-ASCII text lies there as UTF-8.
    @Test
    void escapesAndUtf8AreFound() throws Exception {
        store(ID, line("user", "path c:\\temp and\nnext ñandú"));
        search("c:\\temp");
        search("and\nnext");
        search("ñandú");
        assertThat(hits).hasSize(3);
        assertThat(hits.get(1).snippet()).isEqualTo("path c:\\temp and next ñandú");
    }

    /// ASCII letters match in either case, in the bytes and in the message; other letters match only themselves.
    @Test
    void asciiLettersMatchInEitherCase() throws Exception {
        store(ID, line("user", "Zebra CROSSING Ñu"));
        search("zEBRA");
        search("crossing");
        search("ñu");
        assertThat(hits).extracting(SearchHit::matchStart).containsExactly(0, 6);
    }

    /// A message of 10000 chars gives a snippet of 200 around a match in its middle, with the offsets on the match,
    /// and the line's breaks shown as spaces.
    @Test
    void theSnippetIsAWindowOnOneLine() throws Exception {
        final String around = "ab\n".repeat(2000);
        store(ID, line("user", around + "zebra" + around));
        search("zebra");
        assertThat(hits).singleElement().satisfies(h -> {
            assertThat(h.snippet()).hasSize(TranscriptSearch.SNIPPET_CHARS).doesNotContain("\n");
            assertThat(h.snippet().substring(h.matchStart(), h.matchStart() + h.matchLength())).isEqualTo("zebra");
            assertThat(h.matchStart()).isEqualTo(60);
        });
    }

    /// A word longer than a snippet is cut to it, and its length says what is shown.
    @Test
    void aMatchLongerThanASnippetIsCut() throws Exception {
        final String word = "z".repeat(300);
        store(ID, line("user", word));
        search(word);
        assertThat(hits).singleElement().satisfies(h -> {
            assertThat(h.snippet()).hasSize(TranscriptSearch.SNIPPET_CHARS);
            assertThat(h.matchLength()).isEqualTo(TranscriptSearch.SNIPPET_CHARS);
        });
    }

    /// A snippet's edges never split a pair of surrogates: an emoji at either edge is left out whole.
    @Test
    void aSnippetSplitsNoSurrogatePair() throws Exception {
        store(ID, line("user", "😀".repeat(100) + "x" + "zebra" + "y" + "😀".repeat(100)));
        search("zebra");
        assertThat(hits).singleElement().satisfies(h -> {
            assertThat(Character.isLowSurrogate(h.snippet().charAt(0))).isFalse();
            assertThat(Character.isHighSurrogate(h.snippet().charAt(h.snippet().length() - 1))).isFalse();
            assertThat(h.snippet().substring(h.matchStart(), h.matchStart() + h.matchLength())).isEqualTo("zebra");
        });
    }

    /// A conversation goes by its latest custom title, even with an agent's name; with only that, by the name; with
    /// neither, by the start of its id. A word in a message quoting the type is not a title.
    @Test
    void aHitNamesItsGroup() throws Exception {
        store("titled", """
                {"type":"custom-title","customTitle":"old title","sessionId":"s"}
                {"type":"agent-name","agentName":"agent","sessionId":"s"}
                {"type":"user","message":{"role":"user","content":"zebra"}}
                {"type":"custom-title","customTitle":"new title","sessionId":"s"}
                """);
        store("named", """
                {"type":"agent-name","agentName":"agent","sessionId":"s"}
                {"type":"user","message":{"role":"user","content":"zebra"}}
                """);
        store(ID, line("user", "zebra \"type\":\"custom-title\",\"customTitle\":\"fake\""));
        search("zebra");
        assertThat(hits).extracting(SearchHit::conversation, SearchHit::group).containsExactlyInAnyOrder(
                tuple("titled", "new title"),
                tuple("named", "agent"),
                tuple(ID, ID.substring(0, 8)));
    }

    /// The newest file comes first, whatever its name, with the time it last changed on its hits.
    @Test
    void conversationsAreReadNewestFirst() throws Exception {
        final Path other = Files.createDirectories(projects.resolve("-x"));
        final Path older = store("older", line("user", "zebra"));
        final Path newer = Files.writeString(other.resolve("newer.jsonl"), line("user", "zebra"));
        final Path oldest = store("oldest", line("user", "zebra"));
        Files.setLastModifiedTime(older, FileTime.fromMillis(2_000L));
        Files.setLastModifiedTime(newer, FileTime.fromMillis(3_000L));
        Files.setLastModifiedTime(oldest, FileTime.fromMillis(1_000L));
        search("zebra");
        assertThat(hits).extracting(SearchHit::conversation).containsExactly("newer", "older", "oldest");
        assertThat(hits).extracting(SearchHit::modifiedMillis).containsExactly(3_000L, 2_000L, 1_000L);
    }

    /// A sub-agent's transcript one folder down, a file of another kind and one straight in the projects folder are
    /// not conversations.
    @Test
    void onlyTopLevelConversationsAreRead() throws Exception {
        final Path deep = Files.createDirectories(project.resolve(ID + "/subagents"));
        Files.writeString(deep.resolve("agent.jsonl"), line("user", "zebra"));
        Files.writeString(project.resolve("notes.txt"), line("user", "zebra"));
        Files.writeString(projects.resolve("stray.jsonl"), line("user", "zebra"));
        search("zebra");
        assertThat(hits).isEmpty();
    }

    /// `found` answering false ends the scan at that hit, in the file and across files.
    @Test
    void aRefusedHitEndsTheScan() throws Exception {
        store("a", line("user", "zebra") + line("user", "zebra"));
        store("b", line("user", "zebra"));
        final AtomicInteger seen = new AtomicInteger();
        new TranscriptSearch(projects).scan(query("zebra"), h -> seen.incrementAndGet() < 1, GOES_ON);
        assertThat(seen).hasValue(1);
    }

    /// A search ended before a file starts reads nothing, and one ended after the first hit reads no further.
    @Test
    void anEndedSearchStops() throws Exception {
        store("a", line("user", "zebra") + line("user", "zebra"));
        store("b", line("user", "zebra"));
        assertThat(new TranscriptSearch(projects).scan(query("zebra"), collect(), () -> true)).isZero();
        assertThat(hits).isEmpty();
        new TranscriptSearch(projects).scan(query("zebra"), collect(), () -> !hits.isEmpty());
        assertThat(hits).hasSize(1);
    }

    /// A word past the first chunk, and one that straddles a chunk's edge, are found; the chunks overlap by the needle.
    @Test
    void aMatchAcrossAChunkIsFound() throws Exception {
        final String head = "{\"type\":\"user\",\"message\":{\"role\":\"user\",\"content\":\"";
        final int chunk = 1 << 20;
        store("straddles", head + "q".repeat(chunk - head.length() - 2) + "zebra\"}}\n");
        store("past", line("user", "x".repeat(chunk)) + line("user", "zebra"));
        search("zebra");
        assertThat(hits).extracting(SearchHit::conversation).containsExactlyInAnyOrder("straddles", "past");
    }

    /// A file cut short by `found` while it is mapped counts as failed; the scan goes on to the next file and raises
    /// nothing, and the hit it had stands.
    @Test
    void aFileCutWhileMappedCountsAsFailed() throws Exception {
        final String tail = line("user", "zebra").repeat(200_000);
        final Path cut = store("cut", line("user", "zebra") + tail);
        final Path next = store("next", line("user", "zebra"));
        Files.setLastModifiedTime(cut, FileTime.fromMillis(2_000L));
        Files.setLastModifiedTime(next, FileTime.fromMillis(1_000L));
        final AtomicInteger seen = new AtomicInteger();
        final int failed = new TranscriptSearch(projects).scan(query("zebra"), h -> {
            hits.add(h);
            if (seen.getAndIncrement() == 0) {
                try (final FileChannel channel = FileChannel.open(cut, StandardOpenOption.WRITE)) {
                    channel.truncate(0L);
                } catch (final IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
            return true;
        }, GOES_ON);
        assertThat(failed).isEqualTo(1);
        assertThat(hits).extracting(SearchHit::conversation).startsWith("cut").endsWith("next");
    }

    /// A line that does not parse, as the last of a file being written, is passed over; an empty file holds nothing.
    @Test
    void aLineThatDoesNotParseIsPassedOver() throws Exception {
        final String cut = "{\"type\":\"user\",\"message\":{\"content\":\"zebra\n";
        store(ID, cut + line("user", "zebra") + "{\"type\":\"user\",\"mess zebra");
        store("empty", "");
        assertThat(search("zebra")).isZero();
        assertThat(hits).hasSize(1);
    }

    /// A query that leaves conversations out and a missing projects folder read nothing and fail nothing.
    @Test
    void nothingToSearchIsNoFailure() throws Exception {
        store(ID, line("user", "zebra"));
        final SearchQuery noConversations = new SearchQuery("zebra", List.of(), false);
        assertThat(new TranscriptSearch(projects).scan(noConversations, collect(), GOES_ON)).isZero();
        assertThat(new TranscriptSearch(projects.resolve("none")).scan(query("zebra"), collect(), GOES_ON)).isZero();
        assertThat(hits).isEmpty();
    }

    private Path store(final String id, final String lines) throws Exception {
        return Files.writeString(project.resolve(id + ".jsonl"), lines);
    }

    /** A line of {@code type} with {@code text} as its message, as Claude Code writes it. */
    private String line(final String type, final String text) {
        final String encoded = Json.write(Json.text(text));
        return "assistant".equals(type)
                ? "{\"type\":\"assistant\",\"message\":{\"content\":[{\"type\":\"text\",\"text\":" + encoded + "}]}}\n"
                : "{\"type\":\"user\",\"message\":{\"role\":\"user\",\"content\":" + encoded + "}}\n";
    }

    private int search(final String text) {
        return new TranscriptSearch(projects).scan(query(text), collect(), GOES_ON);
    }

    private static SearchQuery query(final String text) {
        return new SearchQuery(text, List.of(), true);
    }

    private Predicate<SearchHit> collect() {
        return hits::add;
    }
}
