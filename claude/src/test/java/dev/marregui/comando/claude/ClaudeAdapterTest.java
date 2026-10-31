package dev.marregui.comando.claude;

import dev.marregui.comando.core.intent.Intent;
import dev.marregui.comando.core.model.SessionSpec;
import dev.marregui.comando.core.port.McpGateway;
import dev.marregui.comando.core.transcript.Block;
import dev.marregui.comando.core.transcript.Transcript;
import dev.marregui.comando.core.transcript.TranscriptRegistry;
import dev.marregui.comando.testkit.RecordingSink;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/// [ClaudeAdapter] owns each session process, its transcript, and its temporary MCP configuration.
///
/// - Starts, sends to, and stops a session with one configuration file.
/// - Surfaces startup and process failures in the session transcript.
/// - Restores stored conversations without launching a process.
/// - Closes unfinished tool calls when a stream or history ends.
class ClaudeAdapterTest {

    private static final Duration WAIT = Duration.ofSeconds(15);

    @TempDir
    Path tmp;
    private final RecordingSink sink = new RecordingSink();
    private final TranscriptRegistry transcripts = new TranscriptRegistry();
    private final List<String> registered = new ArrayList<>();
    private final McpGateway gateway = new McpGateway() {
        @Override
        public synchronized Ticket register(String session) {
            registered.add("+" + session);
            return new Ticket("http://127.0.0.1:1/mcp", "token-" + session);
        }

        @Override
        public synchronized void unregister(String session, Ticket ticket) {
            registered.add("-" + session);
        }
    };
    private ClaudeAdapter adapter;
    private Path runDir;

    @BeforeEach
    void setUp() {
        runDir = tmp.resolve("run");
    }

    @AfterEach
    void tearDown() {
        if (adapter != null) {
            adapter.close();
        }
    }

    private ClaudeAdapter adapter(String script) throws IOException {
        Path exe = tmp.resolve(script);
        try (InputStream in = getClass().getResourceAsStream("/" + script)) {
            Files.write(exe, in.readAllBytes());
        }
        exe.toFile().setExecutable(true);
        adapter = new ClaudeAdapter(sink, transcripts, gateway,
                new ClaudeSettings(exe.toString(), runDir, tmp.resolve("projects"), UiInstructions.TEXT));
        return adapter;
    }

    private List<Block> blocks(String session) {
        Transcript t = transcripts.of(session);
        List<Block> out = new ArrayList<>();
        for (int i = 0; i < t.size(); i++) {
            out.add(t.get(i));
        }
        return out;
    }

    /// A started `work` session writes an owner-only configuration with its bearer token, answers two prompts on one
    /// process (`pong 1`, `pong 2`), and after a clean stop leaves no configuration and no registration.
    @Test
    void startSendAnswerStop() throws Exception {
        ClaudeAdapter a = adapter("fake-claude.sh");
        SessionSpec spec = SessionSpec.fresh("work", tmp.toString());
        a.start(spec);
        sink.await(Intent.SessionStarted.class, WAIT);
        try (Stream<Path> files = Files.list(runDir)) {
            Path config = files.findFirst().orElseThrow();
            assertThat(Files.readString(config)).contains("Bearer token-work", "\"timeout\":3600000");
            assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(config))).isEqualTo("rw-------");
        }

        a.send("work", "ping", "panel one");
        Intent.SessionInit init = sink.await(Intent.SessionInit.class, WAIT);
        assertThat(init.info().claudeSessionId()).isEqualTo("fake-1");
        Intent.SessionTurnEnded end = sink.await(Intent.SessionTurnEnded.class, WAIT);
        assertThat(end.contextTokens()).isEqualTo(100L);

        a.send("work", "ping again", "panel one");
        sink.await(Intent.SessionTurnEnded.class, e -> sink.of(Intent.SessionTurnEnded.class).size() == 2, WAIT);
        assertThat(blocks("work")).filteredOn(Block.AssistantText.class::isInstance)
                .extracting(b -> ((Block.AssistantText) b).text()).containsExactly("pong 1", "pong 2");
        assertThat(blocks("work")).filteredOn(Block.UserPrompt.class::isInstance)
                .containsExactly(new Block.UserPrompt("ping", "panel one", ""), new Block.UserPrompt("ping again", "panel one", ""));

        a.stop("work");
        Intent.SessionExited exit = sink.await(Intent.SessionExited.class, WAIT);
        assertThat(exit.exitCode()).isZero();
        try (Stream<Path> files = Files.list(runDir)) {
            assertThat(files).isEmpty();
        }
        assertThat(registered).containsExactly("+work", "-work");
    }

    /// A session launched while the checkpoint threshold is above 0 gets `CONTEXT_CHECKPOINT_BY=comando`; one launched
    /// at 0 has the variable taken out, whatever the environment comando started with holds.
    @Test
    void aSessionLaunchedWhileTheThresholdIsOnLeavesTheCheckpointToComando() throws Exception {
        Path exe = tmp.resolve("env-claude.sh");
        Files.writeString(exe, """
                #!/bin/sh
                # stands in for claude -p: writes who checkpoints it, then reads its input until it closes
                printf '%s' "${CONTEXT_CHECKPOINT_BY-absent}" > "$PWD/checkpoint-by.txt"
                cat > /dev/null
                """);
        exe.toFile().setExecutable(true);
        AtomicLong threshold = new AtomicLong(400_000L);
        adapter = new ClaudeAdapter(sink, transcripts, gateway, new ClaudeSettings(exe.toString(), runDir, tmp.resolve("projects"), UiInstructions.TEXT));
        adapter.checkpointThreshold(threshold::get);

        Path on = Files.createDirectories(tmp.resolve("on"));
        adapter.start(SessionSpec.fresh("on", on.toString()));
        Awaitility.await("the session on").atMost(WAIT).until(() -> Files.exists(on.resolve("checkpoint-by.txt"))
                && !Files.readString(on.resolve("checkpoint-by.txt")).isEmpty());
        assertThat(Files.readString(on.resolve("checkpoint-by.txt"))).isEqualTo("comando");

        threshold.set(0L);
        Path off = Files.createDirectories(tmp.resolve("off"));
        adapter.start(SessionSpec.fresh("off", off.toString()));
        Awaitility.await("the session off").atMost(WAIT).until(() -> Files.exists(off.resolve("checkpoint-by.txt"))
                && !Files.readString(off.resolve("checkpoint-by.txt")).isEmpty());
        assertThat(Files.readString(off.resolve("checkpoint-by.txt"))).isEqualTo("absent");

        adapter.stop("on");
        adapter.stop("off");
        sink.await(Intent.SessionExited.class, e -> sink.of(Intent.SessionExited.class).size() == 2, WAIT);
    }

    /// A preface, the summary an earlier checkpoint carried (record 86), goes to Claude in front of the prompt; the
    /// prompt's block shows the prompt as typed.
    @Test
    void aPrefaceGoesWithThePromptAndIsNotShown() throws Exception {
        ClaudeAdapter a = adapter("fake-claude.sh");
        a.start(SessionSpec.fresh("work", tmp.toString()));
        sink.await(Intent.SessionStarted.class, WAIT);
        a.send("work", "ping", "panel one");
        sink.await(Intent.SessionTurnEnded.class, WAIT);

        a.send("work", "go on", "panel one", "Context carried over: the summary\n\n---\n\n");
        sink.await(Intent.SessionTurnEnded.class, e -> sink.of(Intent.SessionTurnEnded.class).size() == 2, WAIT);
        assertThat(blocks("work")).filteredOn(Block.UserPrompt.class::isInstance)
                .containsExactly(new Block.UserPrompt("ping", "panel one", ""), new Block.UserPrompt("go on", "panel one", ""));
        a.stop("work");
        sink.await(Intent.SessionExited.class, WAIT);
    }

    /// Closing removes stale and active configuration files, unregisters the running session, and rejects a session
    /// requested after close without recreating a token file.
    @Test
    void closeStopsRunningSessionsAndLeavesNoTokenFiles() throws Exception {
        Files.createDirectories(runDir);
        Files.writeString(runDir.resolve("old-crash.mcp.json"), "{}");
        ClaudeAdapter a = adapter("fake-claude.sh");
        assertThat(runDir.resolve("old-crash.mcp.json")).doesNotExist();
        a.start(SessionSpec.fresh("work", tmp.toString()));
        sink.await(Intent.SessionStarted.class, WAIT);
        try (Stream<Path> files = Files.list(runDir)) {
            assertThat(files).hasSize(1);
        }
        a.close();
        try (Stream<Path> files = Files.list(runDir)) {
            assertThat(files).isEmpty();
        }
        assertThat(registered).containsExactly("+work", "-work");

        a.start(SessionSpec.fresh("late", tmp.toString()));
        a.send("late", "hello", "scratch");
        assertThat(registered).as("a session asked for after close never starts").containsExactly("+work", "-work");
        try (Stream<Path> files = Files.list(runDir)) {
            assertThat(files).isEmpty();
        }
    }

    /// A child of Claude Code that ignores SIGTERM is killed at close although Claude Code itself exits on the
    /// SIGTERM within the grace: once Claude Code is gone the child is no longer its descendant, so only the kill of
    /// the signalled tree ends it.
    @Test
    void closeKillsAChildThatIgnoredTheTerminate() throws Exception {
        final ClaudeAdapter a = adapter("fake-claude-stubborn-child.sh");
        a.start(SessionSpec.fresh("work", tmp.toString()));
        sink.await(Intent.SessionStarted.class, WAIT);
        final Path pidFile = tmp.resolve("child.pid");
        Awaitility.await("the child wrote its pid").atMost(WAIT).until(() -> Files.exists(pidFile));
        final long child = Long.parseLong(Files.readString(pidFile).strip());
        try {
            a.close();
            Awaitility.await("the child ended").atMost(WAIT)
                    .until(() -> ProcessHandle.of(child).map(h -> !h.isAlive()).orElse(true));
        } finally {
            ProcessHandle.of(child).ifPresent(ProcessHandle::destroyForcibly);
        }
    }

    /// Stopping a session kills a child that ignores SIGTERM although Claude Code exits on its closed input before the
    /// grace ends: the tree was recorded before the input closed, so the child is still found once it is orphaned.
    @Test
    void stopKillsAChildThatOutlivesClaudeExitingOnItsInput() throws Exception {
        final ClaudeAdapter a = adapter("fake-claude-stubborn-child.sh");
        a.start(SessionSpec.fresh("work", tmp.toString()));
        sink.await(Intent.SessionStarted.class, WAIT);
        final Path pidFile = tmp.resolve("child.pid");
        Awaitility.await("the child wrote its pid").atMost(WAIT).until(() -> Files.exists(pidFile));
        final long child = Long.parseLong(Files.readString(pidFile).strip());
        try {
            a.stop("work");
            sink.await(Intent.SessionExited.class, WAIT);
            Awaitility.await("the child ended").atMost(WAIT)
                    .until(() -> ProcessHandle.of(child).map(h -> !h.isAlive()).orElse(true));
        } finally {
            ProcessHandle.of(child).ifPresent(ProcessHandle::destroyForcibly);
        }
    }

    /// A process that exits with code `3` reports its stderr text and appends an error note to the affected transcript.
    @Test
    void aProcessThatDiesReportsItsStderr() throws Exception {
        ClaudeAdapter a = adapter("fake-claude-crash.sh");
        a.start(SessionSpec.fresh("work", tmp.toString()));
        Intent.SessionExited exit = sink.await(Intent.SessionExited.class, WAIT);
        assertThat(exit.exitCode()).isEqualTo(3);
        assertThat(exit.error()).contains("unknown option");
        assertThat(blocks("work")).anySatisfy(b -> assertThat(b).isInstanceOf(Block.Note.class)
                .extracting(n -> ((Block.Note) n).kind()).isEqualTo(Block.NoteKind.ERROR));
    }

    /// A working-directory string containing a NUL cannot become a path, so startup ends with code `-1` and names that
    /// path conversion failure instead of attempting a process launch.
    @Test
    void aWorkingDirectoryThatIsNoPathFailsTheStart() throws Exception {
        ClaudeAdapter a = adapter("fake-claude.sh");
        a.start(SessionSpec.fresh("work", "/tmp/bad\0dir"));
        Intent.SessionExited exit = sink.await(Intent.SessionExited.class, WAIT);
        assertThat(exit.exitCode()).isEqualTo(-1);
        assertThat(exit.error()).startsWith("working directory is not a path");
    }

    /// A nonexistent working directory ends startup with code `-1` and never registers an MCP ticket, because no
    /// process can be started there.
    @Test
    void aMissingWorkingDirectoryFailsTheStart() throws Exception {
        ClaudeAdapter a = adapter("fake-claude.sh");
        a.start(SessionSpec.fresh("work", tmp.resolve("nope").toString()));
        Intent.SessionExited exit = sink.await(Intent.SessionExited.class, WAIT);
        assertThat(exit.exitCode()).isEqualTo(-1);
        assertThat(exit.error()).contains("does not exist");
        assertThat(registered).noneMatch(r -> r.startsWith("+"));
    }

    /// Sending to an unseen, stopped session finishes the turn as an error rather than silently dropping the prompt.
    @Test
    void sendingToAStoppedSessionEndsTheTurnWithAnError() throws Exception {
        ClaudeAdapter a = adapter("fake-claude.sh");
        a.send("work", "hello?", "one");
        Intent.SessionTurnEnded end = sink.await(Intent.SessionTurnEnded.class, WAIT);
        assertThat(end.error()).isTrue();
    }

    /// Loading `abc.jsonl` replays its user and assistant blocks into the imported session without emitting a start
    /// intent, because stored history needs no running process.
    @Test
    void storedConversationLoadsWithoutStartingAProcess() throws Exception {
        ClaudeAdapter a = adapter("fake-claude.sh");
        Path project = Files.createDirectories(tmp.resolve("projects/-w"));
        Files.writeString(project.resolve("abc.jsonl"), """
                {"type":"user","cwd":"/w","message":{"role":"user","content":"earlier question"}}
                {"type":"assistant","message":{"id":"m0","content":[{"type":"text","text":"earlier answer"}]}}
                """);
        a.loadHistory(SessionSpec.imported("old", tmp.toString(), "abc"));
        long deadline = System.nanoTime() + WAIT.toNanos();
        while (transcripts.of("old").size() < 3 && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertThat(blocks("old")).startsWith(new Block.UserPrompt("earlier question", "", ""),
                new Block.AssistantText("m0", 0, "earlier answer", false, ""));
        assertThat(sink.of(Intent.SessionStarted.class)).isEmpty();
    }

    private ClaudeAdapter adapterAt(String executable, Path run) {
        adapter = new ClaudeAdapter(sink, transcripts, gateway, new ClaudeSettings(executable, run, tmp.resolve("projects"), UiInstructions.TEXT));
        return adapter;
    }

    private List<Block.Note> errorNotes(String session) {
        return blocks(session).stream().filter(Block.Note.class::isInstance).map(Block.Note.class::cast)
                .filter(n -> n.kind() == Block.NoteKind.ERROR).toList();
    }

    /// A prompt queued after an executable-not-found startup failure gets the single `session could not start` turn
    /// error and an informational transcript note, without adding another error note.
    @Test
    void aPromptAfterAFailedStartAddsNoSecondError() {
        ClaudeAdapter a = adapterAt(tmp.resolve("no-such-claude").toString(), runDir);
        a.start(SessionSpec.fresh("work", tmp.toString()));
        a.send("work", "hello", "one");

        Intent.SessionExited exit = sink.await(Intent.SessionExited.class, WAIT);
        assertThat(exit.exitCode()).isEqualTo(-1);
        assertThat(exit.error()).contains("COMANDO_CLAUDE");
        Intent.SessionTurnEnded end = sink.await(Intent.SessionTurnEnded.class, WAIT);
        assertThat(end.error()).isTrue();
        assertThat(end.message()).isEqualTo("session could not start");
        assertThat(errorNotes("work")).hasSize(1);
        Block last = blocks("work").getLast();
        assertThat(last).as("the prompt says it went nowhere").isInstanceOfSatisfying(Block.Note.class,
                n -> assertThat(n.text()).startsWith("Not sent: the session could not start"));
    }

    /// A run path occupied by a regular file reports MCP-configuration failure before executable advice, preserving the
    /// earlier filesystem cause even when the executable is also absent.
    @Test
    void aRunDirectoryThatCannotBeWrittenFailsWithoutTheExecutableAdvice() throws Exception {
        Path notADirectory = Files.writeString(tmp.resolve("run-file"), "x");
        ClaudeAdapter a = adapterAt(tmp.resolve("no-such-claude").toString(), notADirectory);
        a.start(SessionSpec.fresh("work", tmp.toString()));

        Intent.SessionExited exit = sink.await(Intent.SessionExited.class, WAIT);
        assertThat(exit.exitCode()).isEqualTo(-1);
        assertThat(exit.error()).startsWith("cannot write the MCP configuration").doesNotContain("COMANDO_CLAUDE");
        assertThat(registered).containsExactly("+work", "-work");
    }

    /// When a process exits after opening tool call `t1`, the unfinished call is sealed as `ERROR` with `NO_RESULT` so
    /// the transcript does not leave an indefinitely running tool.
    @Test
    void aToolCallLeftRunningWhenTheProcessEndsGetsAnErrorResult() throws Exception {
        Path exe = tmp.resolve("fake-claude-dies-mid-tool.sh");
        Files.writeString(exe, """
                #!/bin/sh
                while IFS= read -r line; do
                  case "$line" in
                    *'"type":"user"'*)
                      printf '%s\\n' '{"type":"assistant","parent_tool_use_id":null,"message":{"id":"m1","content":[{"type":"tool_use","id":"t1","name":"Bash","input":{"command":"sleep 99"}}]}}'
                      exit 0 ;;
                  esac
                done
                """);
        exe.toFile().setExecutable(true);
        ClaudeAdapter a = adapterAt(exe.toString(), runDir);
        a.start(SessionSpec.fresh("work", tmp.toString()));
        sink.await(Intent.SessionStarted.class, WAIT);
        a.send("work", "run it", "one");

        sink.await(Intent.SessionExited.class, WAIT);

        assertThat(blocks("work")).filteredOn(Block.ToolCall.class::isInstance).singleElement()
                .satisfies(b -> {
                    Block.ToolCall call = (Block.ToolCall) b;
                    assertThat(call.status()).isEqualTo(Block.ToolStatus.ERROR);
                    assertThat(call.resultText()).isEqualTo(StreamMapper.NO_RESULT);
                });
    }

    /// Replaying stored history that ends immediately after tool call `t1` seals that call as `ERROR`, because no
    /// matching result exists in the saved stream.
    @Test
    void aStoredConversationThatStopsMidToolEndsTheCall() throws Exception {
        ClaudeAdapter a = adapter("fake-claude.sh");
        Path project = Files.createDirectories(tmp.resolve("projects/-w"));
        Files.writeString(project.resolve("abd.jsonl"), """
                {"type":"user","cwd":"/w","message":{"role":"user","content":"build it"}}
                {"type":"assistant","message":{"id":"m0","content":[{"type":"tool_use","id":"t1","name":"Bash","input":{"command":"./gradlew build"}}]}}
                """);
        a.loadHistory(SessionSpec.imported("old", tmp.toString(), "abd"));
        long deadline = System.nanoTime() + WAIT.toNanos();
        while (transcripts.of("old").size() < 3 && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }

        assertThat(blocks("old")).filteredOn(Block.ToolCall.class::isInstance).singleElement()
                .satisfies(b -> assertThat(((Block.ToolCall) b).status()).isEqualTo(Block.ToolStatus.ERROR));
    }
}
