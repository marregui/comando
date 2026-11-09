package dev.marregui.comando.mcp;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.IntFunction;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import dev.marregui.comando.core.intent.Intent;
import dev.marregui.comando.core.model.Approval;
import dev.marregui.comando.core.model.ApprovalDecision;
import dev.marregui.comando.core.model.ApprovalKind;
import dev.marregui.comando.core.model.PanelId;
import dev.marregui.comando.core.port.QueryPort;
import dev.marregui.comando.core.port.TerminalPort;
import dev.marregui.comando.core.state.AppState;
import dev.marregui.comando.core.transcript.TranscriptRegistry;
import dev.marregui.comando.testkit.FakeClock;
import dev.marregui.comando.testkit.RecordingSink;
import dev.marregui.comando.testkit.States;

import static org.assertj.core.api.Assertions.assertThat;

/// The terminal tools (record 148) over a fake port: a send waits for the user's card unless the caller holds the
/// `"terminal"` grant, only the user answers that card, a stop needs the destructive grant, a read is cut at its
/// budget and its wait ends on a match or on quiet output, and nothing runs before the application sets the port.
class TerminalToolsTest {

    private final ObjectMapper json = new ObjectMapper();
    private final RecordingSink sink = new RecordingSink();
    private final AtomicReference<AppState> state = new AtomicReference<>(States.twoPanels());
    private FakeTerminals terminals;
    private McpTools tools;
    @TempDir
    Path home;

    @BeforeEach
    void start() {
        tools = tools();
        terminals = new FakeTerminals();
        tools.terminals(terminals);
    }

    /// A send puts a TERMINAL card before the user, titled with the panel and carrying the text, and nothing reaches
    /// the shell while it waits; once the user allows it, the text and Enter do.
    @Test
    void aSendRaisesATerminalCardAndTypesNothingBeforeAllow() throws Exception {
        final CompletableFuture<McpTools.Outcome> sent =
                callAsync("foreman", "terminal_send", "{\"panel\":\"one\",\"text\":\"make test\"}");
        final Approval card = sink.await(Intent.ApprovalRequested.class, Duration.ofSeconds(10)).approval();
        assertThat(card.kind()).isEqualTo(ApprovalKind.TERMINAL);
        assertThat(card.toolName()).isEqualTo("terminal_send");
        assertThat(card.summary()).isEqualTo("one");
        final JsonNode input = json.readTree(card.inputJson());
        assertThat(input.path("panel").asString()).isEqualTo("one");
        assertThat(input.path("text").asString()).isEqualTo("make test");
        assertThat(input.path("enter").asBoolean()).isTrue();
        assertThat(terminals.typed).as("nothing typed while the card waits").isEmpty();
        tools.resolve(card, ApprovalDecision.allowOnce());
        final McpTools.Outcome out = sent.get(10, TimeUnit.SECONDS);
        assertThat(out.error()).as(out.text()).isFalse();
        assertThat(terminals.typed).containsExactly("make test\r");
    }

    /// A card the user denies is a refusal carrying the user's message, and nothing reaches the shell.
    @Test
    void aDeniedCardIsARefusal() throws Exception {
        final CompletableFuture<McpTools.Outcome> sent =
                callAsync("foreman", "terminal_send", "{\"panel\":\"one\",\"text\":\"rm -rf build\"}");
        final Approval card = sink.await(Intent.ApprovalRequested.class, Duration.ofSeconds(10)).approval();
        tools.resolve(card, new ApprovalDecision.Deny("not now"));
        final McpTools.Outcome out = sent.get(10, TimeUnit.SECONDS);
        assertThat(out.error()).isTrue();
        assertThat(out.text()).startsWith("refused").contains("not now");
        assertThat(terminals.typed).isEmpty();
    }

    /// A caller the user granted `"terminal"` in `agents.json` types without a card.
    @Test
    void theTerminalGrantTypesWithoutACard() throws Exception {
        Files.writeString(home.resolve("agents.json"), "{\"grants\": {\"foreman\": [\"terminal\"]}}");
        final McpTools.Outcome out =
                callAsync("foreman", "terminal_send", "{\"panel\":\"one\",\"text\":\"ls\",\"enter\":false}")
                        .get(10, TimeUnit.SECONDS);
        assertThat(out.error()).as(out.text()).isFalse();
        assertThat(sink.of(Intent.ApprovalRequested.class)).as("no card").isEmpty();
        assertThat(terminals.typed).containsExactly("ls");
    }

    /// No agent answers a TERMINAL card through `approval_answer`, not even one of another family: the user does, in
    /// the window.
    @Test
    void noAgentAnswersATerminalCard() throws Exception {
        final Approval card = new Approval(61, "users", ApprovalKind.TERMINAL, "terminal_send",
                "{\"panel\":\"one\",\"text\":\"ls\"}", "one", 0L);
        state.set(state.get().withApprovals(List.of(card)));
        final McpTools.Outcome out = call("foreman", "approval_answer", "{\"id\":61,\"decision\":\"allow\"}");
        assertThat(out.error()).isTrue();
        assertThat(out.text()).contains("only the user answers it");
        assertThat(sink.of(Intent.ApprovalAnswered.class)).isEmpty();
    }

    /// A stop is destructive: without the user's `"destructive"` grant it is refused and the shell keeps running.
    @Test
    void aStopWithoutTheDestructiveGrantIsRefused() throws Exception {
        final McpTools.Outcome out = call("foreman", "terminal_stop", "{\"panel\":\"one\"}");
        assertThat(out.error()).isTrue();
        assertThat(out.text()).contains("is destructive");
        assertThat(terminals.stops).hasValue(0);
    }

    /// A page of 70,000 characters is cut at 65,536, marked `more`, and its `next` is the line the cut left
    /// incomplete, so the following read starts with it.
    @Test
    void aPageOverTheBudgetIsCutWithMore() throws Exception {
        // 700 lines of 100 characters: the cut falls inside line 656
        final String line = "x".repeat(99) + "\n";
        terminals.pages = n -> line.repeat(700);
        final McpTools.Outcome out = call("foreman", "terminal_read", "{\"panel\":\"one\",\"since\":1}");
        assertThat(out.error()).as(out.text()).isFalse();
        final String text = out.text().substring(0, out.text().lastIndexOf('\n'));
        assertThat(text).hasSize(TerminalTools.MAX_PAGE_CHARS);
        assertThat(closing(out)).contains("more=true").contains("first=1,").contains("next=656,");
    }

    /// A read with `until` ends as soon as a line matches, long before its `wait_seconds`.
    @Test
    void untilEndsTheWaitBeforeWaitSeconds() throws Exception {
        terminals.pages = n -> n < 3 ? "$ make\nbuilding\n" : "$ make\nbuilding\nPASS 42\n";
        terminals.writing = true;
        final long started = System.nanoTime();
        final McpTools.Outcome out =
                call("foreman", "terminal_read", "{\"panel\":\"one\",\"until\":\"PASS \\\\d+\",\"wait_seconds\":30}");
        final long tookMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        assertThat(out.error()).as(out.text()).isFalse();
        assertThat(closing(out)).contains("matched=true");
        assertThat(tookMillis).as("ended on the match").isLessThan(10_000);
        assertThat(terminals.reads.get()).as("read again until the line came").isGreaterThanOrEqualTo(4);
    }

    /// A wait reads the page once while the shell writes nothing: a read runs `ps` for the job, and an unchanged
    /// screen needs no second one.
    @Test
    void aWaitDoesNotReadAgainWithoutNewOutput() throws Exception {
        terminals.lastOutputNanos = Optional.of(System.nanoTime() - TimeUnit.SECONDS.toNanos(1));
        final McpTools.Outcome out =
                call("foreman", "terminal_read", "{\"panel\":\"one\",\"until\":\"never\",\"wait_seconds\":1}");
        assertThat(out.error()).as(out.text()).isFalse();
        assertThat(closing(out)).contains("matched=false");
        assertThat(terminals.reads.get()).as("one read, no output since").isEqualTo(1);
    }

    /// Output stamped just before a read may not be in the emulator's buffer yet: the wait reads again while the read
    /// is less than 200 ms after the last output, and finds the line the first read missed.
    @Test
    void aWaitReadsAgainOutputTheEmulatorHadNotShownYet() throws Exception {
        terminals.lastOutputNanos = Optional.of(System.nanoTime());
        terminals.pages = n -> n == 0 ? "$ make\n" : "$ make\nPASS 42\n";
        final McpTools.Outcome out =
                call("foreman", "terminal_read", "{\"panel\":\"one\",\"until\":\"PASS\",\"wait_seconds\":2}");
        assertThat(out.error()).as(out.text()).isFalse();
        assertThat(closing(out)).contains("matched=true");
    }

    /// A read with `quiet_ms` ends once the shell has been quiet that long, unmatched, long before its `wait_seconds`.
    @Test
    void quietMsEndsTheWait() throws Exception {
        terminals.lastOutputNanos = Optional.of(System.nanoTime());
        final long started = System.nanoTime();
        final McpTools.Outcome out =
                call("foreman", "terminal_read", "{\"panel\":\"one\",\"quiet_ms\":200,\"wait_seconds\":30}");
        final long tookMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        assertThat(out.error()).as(out.text()).isFalse();
        assertThat(closing(out)).contains("matched=false");
        assertThat(tookMillis).as("ended on quiet output").isBetween(200L, 10_000L);
    }

    /// Before the application sets the port, every terminal tool answers that terminals are not ready.
    @Test
    void everyToolAnswersNotReadyBeforeThePortIsSet() throws Exception {
        Files.writeString(home.resolve("agents.json"), "{\"grants\": {\"foreman\": [\"destructive\", \"terminal\"]}}");
        tools = tools();
        final String one = "{\"panel\":\"one\"}";
        for (final List<String> c : List.of(List.of("terminal_list", "{}"), List.of("terminal_start", one),
                List.of("terminal_send", "{\"panel\":\"one\",\"text\":\"ls\"}"), List.of("terminal_read", one),
                List.of("terminal_interrupt", one), List.of("terminal_stop", one))) {
            final McpTools.Outcome out = call("foreman", c.getFirst(), c.getLast());
            assertThat(out.error()).as(c.getFirst()).isTrue();
            assertThat(out.text()).as(c.getFirst()).isEqualTo("refused: terminals are not ready");
        }
    }

    /// A control character other than tab and newline in a send's text is refused before any card, and nothing is
    /// typed.
    @Test
    void aControlCharacterInTextIsRefused() throws Exception {
        final McpTools.Outcome out =
                call("foreman", "terminal_send", "{\"panel\":\"one\",\"text\":\"clear\\u001b[2J\"}");
        assertThat(out.error()).isTrue();
        assertThat(out.text()).contains("control character").contains("U+001B");
        assertThat(sink.of(Intent.ApprovalRequested.class)).as("no card").isEmpty();
        assertThat(terminals.typed).isEmpty();
    }

    /// The tools without a terminal port, reading the grants from this test's `agents.json`.
    private McpTools tools() {
        final QueryPort db = null;
        final McpTools t = new McpTools(sink, state::get, new TranscriptRegistry(), db, AuditLog.none(),
                new FakeClock(), Duration.ofMinutes(1));
        t.agentsFile(home.resolve("agents.json"));
        return t;
    }

    private McpTools.Outcome call(final String caller, final String tool, final String args) throws Exception {
        return tools.call(caller, tool, json.readTree(args));
    }

    private CompletableFuture<McpTools.Outcome> callAsync(final String caller, final String tool, final String args) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return call(caller, tool, args);
            } catch (final Exception e) {
                throw new IllegalStateException(e);
            }
        });
    }

    private static String closing(final McpTools.Outcome out) {
        return out.text().substring(out.text().lastIndexOf('\n') + 1);
    }

    /// A port with one running terminal, panel one's: it records what reaches the shell and answers the page its script
    /// gives for the read's ordinal, whatever `maxChars` asks.
    static final class FakeTerminals implements TerminalPort {
        final List<String> typed = new CopyOnWriteArrayList<>();
        final AtomicInteger reads = new AtomicInteger();
        final AtomicInteger stops = new AtomicInteger();
        volatile IntFunction<String> pages = n -> "$ ls\nREADME.md\n";
        volatile Optional<Long> lastOutputNanos = Optional.empty();
        /// While set, the shell writes all the time: each look at its last output is a new one.
        volatile boolean writing;

        @Override
        public List<Terminal> list() {
            return List.of(new Terminal(States.P1, "one", true, true, 4242L, "", Path.of("/tmp"), 3L, false));
        }

        @Override
        public String start(final PanelId panel, final Optional<Path> directory) {
            return "started the shell of " + panel.value();
        }

        @Override
        public String send(final PanelId panel, final String text, final boolean enter) {
            typed.add(enter ? text + "\r" : text);
            return "typed " + text.length() + " characters";
        }

        @Override
        public String interrupt(final PanelId panel) {
            return "interrupted";
        }

        @Override
        public String stop(final PanelId panel) {
            stops.incrementAndGet();
            return "stopped";
        }

        @Override
        public Optional<Page> read(final PanelId panel, final long since, final int lines, final int maxChars) {
            final String text = pages.apply(reads.getAndIncrement());
            return Optional.of(new Page(text, 1L, 1L + text.lines().count(), 1L, 0L, false, "", false));
        }

        @Override
        public Optional<Long> lastOutputNanos(final PanelId panel) {
            return writing ? Optional.of(System.nanoTime()) : lastOutputNanos;
        }
    }
}
