package dev.marregui.comando.claude;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.marregui.comando.core.intent.Intent;
import dev.marregui.comando.core.model.SessionSpec;
import dev.marregui.comando.core.port.McpGateway;
import dev.marregui.comando.core.transcript.Block;
import dev.marregui.comando.core.transcript.Transcript;
import dev.marregui.comando.core.transcript.TranscriptRegistry;
import dev.marregui.comando.testkit.RecordingSink;

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
    private final List<String> revoked = new CopyOnWriteArrayList<>();
    private final McpGateway gateway = new McpGateway() {
        private int issued;

        @Override
        public synchronized Ticket register(final String session) {
            registered.add("+" + session);
            issued++;
            return new Ticket("http://127.0.0.1:1/mcp", "token-" + session + (issued == 1 ? "" : "-" + issued));
        }

        @Override
        public synchronized void unregister(final String session, final Ticket ticket) {
            registered.add("-" + session);
            if (ticket != null) {
                revoked.add(ticket.token());
            }
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

    private ClaudeAdapter adapter(final String script) throws IOException {
        final Path exe = tmp.resolve(script);
        try (final InputStream in = getClass().getResourceAsStream("/" + script)) {
            Files.write(exe, in.readAllBytes());
        }
        exe.toFile().setExecutable(true);
        adapter = new ClaudeAdapter(sink, transcripts, gateway,
                new ClaudeSettings(exe.toString(), runDir, tmp.resolve("projects"), UiInstructions.TEXT));
        return adapter;
    }

    private List<Block> blocks(final String session) {
        final Transcript t = transcripts.of(session);
        final List<Block> out = new ArrayList<>();
        for (int i = 0; i < t.size(); i++) {
            out.add(t.get(i));
        }
        return out;
    }

    /// A started `work` session writes an owner-only configuration with its bearer token, answers two prompts on one
    /// process (`pong 1`, `pong 2`), and after a clean stop leaves no configuration and no registration.
    @Test
    void startSendAnswerStop() throws Exception {
        final ClaudeAdapter a = adapter("fake-claude.sh");
        final SessionSpec spec = SessionSpec.fresh("work", tmp.toString());
        a.start(spec);
        sink.await(Intent.SessionStarted.class, WAIT);
        try (final Stream<Path> files = Files.list(runDir)) {
            final Path config = files.findFirst().orElseThrow();
            assertThat(Files.readString(config)).contains("Bearer token-work", "\"timeout\":3600000");
            assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(config))).isEqualTo("rw-------");
        }

        a.send("work", "ping", "panel one");
        final Intent.SessionInit init = sink.await(Intent.SessionInit.class, WAIT);
        assertThat(init.info().claudeSessionId()).isEqualTo("fake-1");
        final Intent.SessionTurnEnded end = sink.await(Intent.SessionTurnEnded.class, WAIT);
        assertThat(end.contextTokens()).isEqualTo(100L);

        a.send("work", "ping again", "panel one");
        sink.await(Intent.SessionTurnEnded.class, e -> sink.of(Intent.SessionTurnEnded.class).size() == 2, WAIT);
        assertThat(blocks("work")).filteredOn(Block.AssistantText.class::isInstance)
                .extracting(b -> ((Block.AssistantText) b).text()).containsExactly("pong 1", "pong 2");
        assertThat(blocks("work")).filteredOn(Block.UserPrompt.class::isInstance)
                .containsExactly(new Block.UserPrompt("ping", "panel one", ""), new Block.UserPrompt("ping again", "panel one", ""));

        a.stop("work");
        final Intent.SessionExited exit = sink.await(Intent.SessionExited.class, WAIT);
        assertThat(exit.exitCode()).isZero();
        try (final Stream<Path> files = Files.list(runDir)) {
            assertThat(files).isEmpty();
        }
        assertThat(registered).containsExactly("+work", "-work");
    }

    /// Once comando runs the checkpoints (the window's threshold, always on), a session launched gets
    /// `CONTEXT_CHECKPOINT_BY=comando`; one launched by an adapter that does not (a test's, a tool's) has the variable
    /// taken out, whatever the environment comando started with holds.
    @Test
    void aSessionLaunchedWhileComandoCheckpointsLeavesTheCheckpointToComando() throws Exception {
        final Path exe = tmp.resolve("env-claude.sh");
        Files.writeString(exe, """
                #!/bin/sh
                # stands in for claude -p: writes who checkpoints it, then reads its input until it closes
                printf '%s' "${CONTEXT_CHECKPOINT_BY-absent}" > "$PWD/checkpoint-by.txt"
                cat > /dev/null
                """);
        exe.toFile().setExecutable(true);
        adapter = new ClaudeAdapter(sink, transcripts, gateway, new ClaudeSettings(exe.toString(), runDir, tmp.resolve("projects"), UiInstructions.TEXT));
        final Path off = Files.createDirectories(tmp.resolve("off"));
        adapter.start(SessionSpec.fresh("off", off.toString()));
        Awaitility.await("the session off").atMost(WAIT).until(() -> Files.exists(off.resolve("checkpoint-by.txt"))
                && !Files.readString(off.resolve("checkpoint-by.txt")).isEmpty());
        assertThat(Files.readString(off.resolve("checkpoint-by.txt"))).isEqualTo("absent");

        adapter.checkpointByComando();
        final Path on = Files.createDirectories(tmp.resolve("on"));
        adapter.start(SessionSpec.fresh("on", on.toString()));
        Awaitility.await("the session on").atMost(WAIT).until(() -> Files.exists(on.resolve("checkpoint-by.txt"))
                && !Files.readString(on.resolve("checkpoint-by.txt")).isEmpty());
        assertThat(Files.readString(on.resolve("checkpoint-by.txt"))).isEqualTo("comando");

        adapter.stop("on");
        adapter.stop("off");
        sink.await(Intent.SessionExited.class, e -> sink.of(Intent.SessionExited.class).size() == 2, WAIT);
    }

    /// Esc and Stop turn reach the live process: an interrupt is a control request on its input, sent while the turn
    /// runs, before the session is stopped; an interrupt for a session comando does not know sends nothing.
    @Test
    void anInterruptReachesTheLiveProcessAsAControlRequest() throws Exception {
        final Path exe = tmp.resolve("stdin-claude.sh");
        Files.writeString(exe, """
                #!/bin/sh
                # stands in for claude -p: keeps every input line until the input closes
                cat >> "$PWD/stdin.txt"
                """);
        exe.toFile().setExecutable(true);
        adapter = new ClaudeAdapter(sink, transcripts, gateway, new ClaudeSettings(exe.toString(), runDir, tmp.resolve("projects"), UiInstructions.TEXT));
        final Path work = Files.createDirectories(tmp.resolve("work"));
        final Path stdin = work.resolve("stdin.txt");
        adapter.start(SessionSpec.fresh("work", work.toString()));
        sink.await(Intent.SessionStarted.class, WAIT);

        adapter.interrupt("unknown");
        adapter.interrupt("work");

        Awaitility.await("the interrupt").atMost(WAIT).until(() -> Files.exists(stdin)
                && Files.readString(stdin).contains("\"type\":\"control_request\""));
        assertThat(Files.readAllLines(stdin)).singleElement().asString().contains("\"subtype\":\"interrupt\"");
        adapter.stop("work");
        sink.await(Intent.SessionExited.class, WAIT);
    }

    /// Clearing a transcript empties it and leaves the process running: the next prompt's answer is the only one shown.
    @Test
    void aClearedTranscriptShowsOnlyWhatCameAfter() throws Exception {
        final ClaudeAdapter a = adapter("fake-claude.sh");
        a.start(SessionSpec.fresh("work", tmp.toString()));
        sink.await(Intent.SessionStarted.class, WAIT);
        a.send("work", "ping", "panel one");
        sink.await(Intent.SessionTurnEnded.class, WAIT);
        assertThat(blocks("work")).filteredOn(Block.AssistantText.class::isInstance).hasSize(1);

        a.clearTranscript("work");
        Awaitility.await("the clear").atMost(WAIT).until(() -> transcripts.of("work").size() == 0);
        a.send("work", "ping again", "panel one");
        sink.await(Intent.SessionTurnEnded.class, e -> sink.of(Intent.SessionTurnEnded.class).size() == 2, WAIT);

        assertThat(blocks("work")).filteredOn(Block.AssistantText.class::isInstance)
                .extracting(b -> ((Block.AssistantText) b).text()).containsExactly("pong 2");
        a.stop("work");
        sink.await(Intent.SessionExited.class, WAIT);
    }

    /// A preface, the summary an earlier checkpoint carried (record 86), goes to Claude in front of the prompt; the
    /// prompt's block shows the prompt as typed.
    @Test
    void aPrefaceGoesWithThePromptAndIsNotShown() throws Exception {
        final ClaudeAdapter a = adapter("fake-claude.sh");
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
        final ClaudeAdapter a = adapter("fake-claude.sh");
        assertThat(runDir.resolve("old-crash.mcp.json")).doesNotExist();
        a.start(SessionSpec.fresh("work", tmp.toString()));
        sink.await(Intent.SessionStarted.class, WAIT);
        try (final Stream<Path> files = Files.list(runDir)) {
            assertThat(files).hasSize(1);
        }
        a.close();
        try (final Stream<Path> files = Files.list(runDir)) {
            assertThat(files).isEmpty();
        }
        assertThat(registered).containsExactly("+work", "-work");

        a.start(SessionSpec.fresh("late", tmp.toString()));
        a.send("late", "hello", "scratch");
        assertThat(registered).as("a session asked for after close never starts").containsExactly("+work", "-work");
        try (final Stream<Path> files = Files.list(runDir)) {
            assertThat(files).isEmpty();
        }
    }

    /// A prompt routed to a running session while the adapter closes (the reducer routes effects until the state loop
    /// closes, after the adapter) leaves no `claude` process, configuration file or registration behind once close
    /// returns. Close is held in its first executor stop, as a sub-agent read that does not answer an interrupt holds
    /// it, so the prompt lands after close began.
    @Test
    void aPromptRoutedWhileTheAdapterClosesLeavesNoClaudeProcessBehind() throws Exception {
        final Path exe = tmp.resolve("pid-claude.sh");
        Files.writeString(exe, "#!/bin/sh\necho $$ > claude.pid.tmp && mv claude.pid.tmp claude.pid\n"
                + "while IFS= read -r line; do :; done\nexit 0\n");
        assertThat(exe.toFile().setExecutable(true)).isTrue();
        adapter = new ClaudeAdapter(sink, transcripts, gateway, new ClaudeSettings(exe.toString(), runDir, tmp.resolve("projects"), UiInstructions.TEXT));
        adapter.start(SessionSpec.fresh("work", tmp.toString()));
        sink.await(Intent.SessionStarted.class, WAIT);
        final Path pidFile = tmp.resolve("claude.pid");
        Awaitility.await().atMost(WAIT).until(() -> Files.isRegularFile(pidFile));
        final long pid = Long.parseLong(Files.readString(pidFile).strip());
        try {
            final ScheduledExecutorService subagents = (ScheduledExecutorService) field("subagents").get(adapter);
            final CountDownLatch held = new CountDownLatch(1);
            subagents.execute(() -> {
                held.countDown();
                final long end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(1_500);
                while (end - System.nanoTime() > 0) {
                    try {
                        Thread.sleep(20);
                    } catch (final InterruptedException e) {
                        // a directory scan does not answer an interrupt either
                    }
                }
            });
            assertThat(held.await(5, TimeUnit.SECONDS)).isTrue();
            final Thread closer = Thread.ofVirtual().start(adapter::close);
            final Field closed = field("closed");
            Awaitility.await().atMost(WAIT).until(() -> closed.getBoolean(adapter));
            adapter.send("work", "late prompt", "panel one");
            assertThat(closer.join(Duration.ofSeconds(20))).isTrue();

            assertThat(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)).as("claude process alive after close returned").isFalse();
            assertThat(registered).containsExactly("+work", "-work");
            try (final Stream<Path> files = Files.list(runDir)) {
                assertThat(files).as("no configuration with its bearer token").isEmpty();
            }
        } finally {
            ProcessHandle.of(pid).ifPresent(ProcessHandle::destroyForcibly);
        }
    }

    private static Field field(final String name) throws NoSuchFieldException {
        final Field f = ClaudeAdapter.class.getDeclaredField(name);
        f.setAccessible(true);
        return f;
    }

    /// A process that exits while a child still holds its output open has its exit handled only once the output closes
    /// (the drain bound, here a minute, does not end it first); a start in between replaces it. The replaced process's
    /// token is revoked when its exit is handled, and the newer process keeps its own: a replaced run leaves no live
    /// bearer token behind. The child holds the output until the test creates `release`, so no step races a clock.
    @Test
    void aReplacedProcessHasItsTokenRevokedAtItsExit() throws Exception {
        final Path exe = tmp.resolve("replaced-claude.sh");
        Files.writeString(exe, """
                #!/bin/sh
                # stands in for claude -p: the first run exits at once while a child keeps its stdout open until the
                # test creates release, so its exit is handled only then; a later run reads its input until it closes
                if [ ! -f "$PWD/first.pid" ]; then
                  ( while [ ! -f "$PWD/release" ]; do sleep 0.05; done ) &
                  echo $$ > "$PWD/first.pid"
                  exit 0
                fi
                cat > /dev/null
                """);
        exe.toFile().setExecutable(true);
        adapter = new ClaudeAdapter(sink, transcripts, gateway,
                new ClaudeSettings(exe.toString(), runDir, tmp.resolve("projects"), UiInstructions.TEXT), Duration.ofMinutes(1));
        final Path release = tmp.resolve("release");
        try {
            final SessionSpec spec = SessionSpec.fresh("work", tmp.toString());
            adapter.start(spec);
            sink.await(Intent.SessionStarted.class, WAIT);
            final Path pidFile = tmp.resolve("first.pid");
            Awaitility.await().atMost(WAIT).until(() -> Files.exists(pidFile) && !Files.readString(pidFile).isBlank());
            final long pid = Long.parseLong(Files.readString(pidFile).strip());
            Awaitility.await().atMost(WAIT).until(() -> ProcessHandle.of(pid).map(h -> !h.isAlive()).orElse(true));

            adapter.start(spec); // the child still holds the first process's output: its exit is not handled yet
            sink.await(Intent.SessionStarted.class, e -> sink.of(Intent.SessionStarted.class).size() == 2, WAIT);
            assertThat(revoked).as("the first exit is still pending").doesNotContain("token-work");

            Files.writeString(release, "");
            Awaitility.await().atMost(WAIT).untilAsserted(() -> assertThat(revoked).contains("token-work"));
            assertThat(revoked).as("the newer process keeps its token").doesNotContain("token-work-2");
            assertThat(sink.of(Intent.SessionExited.class)).as("the replaced process says nothing to the window").isEmpty();
        } finally {
            Files.writeString(release, ""); // the child never outlives the test
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
        final ClaudeAdapter a = adapter("fake-claude-crash.sh");
        a.start(SessionSpec.fresh("work", tmp.toString()));
        final Intent.SessionExited exit = sink.await(Intent.SessionExited.class, WAIT);
        assertThat(exit.exitCode()).isEqualTo(3);
        assertThat(exit.error()).contains("unknown option");
        assertThat(blocks("work")).anySatisfy(b -> assertThat(b).isInstanceOf(Block.Note.class)
                .extracting(n -> ((Block.Note) n).kind()).isEqualTo(Block.NoteKind.ERROR));
    }

    /// A working-directory string containing a NUL cannot become a path, so startup ends with code `-1` and names that
    /// path conversion failure instead of attempting a process launch.
    @Test
    void aWorkingDirectoryThatIsNoPathFailsTheStart() throws Exception {
        final ClaudeAdapter a = adapter("fake-claude.sh");
        a.start(SessionSpec.fresh("work", "/tmp/bad\0dir"));
        final Intent.SessionExited exit = sink.await(Intent.SessionExited.class, WAIT);
        assertThat(exit.exitCode()).isEqualTo(-1);
        assertThat(exit.error()).startsWith("working directory is not a path");
    }

    /// A nonexistent working directory ends startup with code `-1` and never registers an MCP ticket, because no
    /// process can be started there.
    @Test
    void aMissingWorkingDirectoryFailsTheStart() throws Exception {
        final ClaudeAdapter a = adapter("fake-claude.sh");
        a.start(SessionSpec.fresh("work", tmp.resolve("nope").toString()));
        final Intent.SessionExited exit = sink.await(Intent.SessionExited.class, WAIT);
        assertThat(exit.exitCode()).isEqualTo(-1);
        assertThat(exit.error()).contains("does not exist");
        assertThat(registered).noneMatch(r -> r.startsWith("+"));
    }

    /// Sending to an unseen, stopped session finishes the turn as an error rather than silently dropping the prompt.
    @Test
    void sendingToAStoppedSessionEndsTheTurnWithAnError() throws Exception {
        final ClaudeAdapter a = adapter("fake-claude.sh");
        a.send("work", "hello?", "one");
        final Intent.SessionTurnEnded end = sink.await(Intent.SessionTurnEnded.class, WAIT);
        assertThat(end.error()).isTrue();
    }

    /// Loading `abc.jsonl` replays its user and assistant blocks into the imported session without emitting a start
    /// intent, because stored history needs no running process.
    @Test
    void storedConversationLoadsWithoutStartingAProcess() throws Exception {
        final ClaudeAdapter a = adapter("fake-claude.sh");
        final Path project = Files.createDirectories(tmp.resolve("projects/-w"));
        Files.writeString(project.resolve("abc.jsonl"), """
                {"type":"user","cwd":"/w","message":{"role":"user","content":"earlier question"}}
                {"type":"assistant","message":{"id":"m0","content":[{"type":"text","text":"earlier answer"}]}}
                """);
        a.loadHistory(SessionSpec.imported("old", tmp.toString(), "abc"));
        final long deadline = System.nanoTime() + WAIT.toNanos();
        while (transcripts.of("old").size() < 3 && deadline - System.nanoTime() > 0) {
            Thread.sleep(20);
        }
        assertThat(blocks("old")).startsWith(new Block.UserPrompt("earlier question", "", ""),
                new Block.AssistantText("m0", 0, "earlier answer", false, ""));
        assertThat(sink.of(Intent.SessionStarted.class)).isEmpty();
    }

    private ClaudeAdapter adapterAt(final String executable, final Path run) {
        adapter = new ClaudeAdapter(sink, transcripts, gateway, new ClaudeSettings(executable, run, tmp.resolve("projects"), UiInstructions.TEXT));
        return adapter;
    }

    private List<Block.Note> errorNotes(final String session) {
        return blocks(session).stream().filter(Block.Note.class::isInstance).map(Block.Note.class::cast)
                .filter(n -> n.kind() == Block.NoteKind.ERROR).toList();
    }

    /// A prompt queued after an executable-not-found startup failure gets the single `session could not start` turn
    /// error and an informational transcript note, without adding another error note.
    @Test
    void aPromptAfterAFailedStartAddsNoSecondError() {
        final ClaudeAdapter a = adapterAt(tmp.resolve("no-such-claude").toString(), runDir);
        a.start(SessionSpec.fresh("work", tmp.toString()));
        a.send("work", "hello", "one");

        final Intent.SessionExited exit = sink.await(Intent.SessionExited.class, WAIT);
        assertThat(exit.exitCode()).isEqualTo(-1);
        assertThat(exit.error()).contains("COMANDO_CLAUDE");
        final Intent.SessionTurnEnded end = sink.await(Intent.SessionTurnEnded.class, WAIT);
        assertThat(end.error()).isTrue();
        assertThat(end.message()).isEqualTo("session could not start");
        assertThat(errorNotes("work")).hasSize(1);
        final Block last = blocks("work").getLast();
        assertThat(last).as("the prompt says it went nowhere").isInstanceOfSatisfying(Block.Note.class,
                n -> assertThat(n.text()).startsWith("Not sent: the session could not start"));
    }

    /// A run path occupied by a regular file reports MCP-configuration failure before executable advice, preserving the
    /// earlier filesystem cause even when the executable is also absent.
    @Test
    void aRunDirectoryThatCannotBeWrittenFailsWithoutTheExecutableAdvice() throws Exception {
        final Path notADirectory = Files.writeString(tmp.resolve("run-file"), "x");
        final ClaudeAdapter a = adapterAt(tmp.resolve("no-such-claude").toString(), notADirectory);
        a.start(SessionSpec.fresh("work", tmp.toString()));

        final Intent.SessionExited exit = sink.await(Intent.SessionExited.class, WAIT);
        assertThat(exit.exitCode()).isEqualTo(-1);
        assertThat(exit.error()).startsWith("cannot write the MCP configuration").doesNotContain("COMANDO_CLAUDE");
        assertThat(registered).containsExactly("+work", "-work");
    }

    /// When a process exits after opening tool call `t1`, the unfinished call is sealed as `ERROR` with `NO_RESULT` so
    /// the transcript does not leave an indefinitely running tool.
    @Test
    void aToolCallLeftRunningWhenTheProcessEndsGetsAnErrorResult() throws Exception {
        final Path exe = tmp.resolve("fake-claude-dies-mid-tool.sh");
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
        final ClaudeAdapter a = adapterAt(exe.toString(), runDir);
        a.start(SessionSpec.fresh("work", tmp.toString()));
        sink.await(Intent.SessionStarted.class, WAIT);
        a.send("work", "run it", "one");

        sink.await(Intent.SessionExited.class, WAIT);

        assertThat(blocks("work")).filteredOn(Block.ToolCall.class::isInstance).singleElement()
                .satisfies(b -> {
                    final Block.ToolCall call = (Block.ToolCall) b;
                    assertThat(call.status()).isEqualTo(Block.ToolStatus.ERROR);
                    assertThat(call.resultText()).isEqualTo(StreamMapper.NO_RESULT);
                });
    }

    /// Replaying stored history that ends immediately after tool call `t1` seals that call as `ERROR`, because no
    /// matching result exists in the saved stream.
    @Test
    void aStoredConversationThatStopsMidToolEndsTheCall() throws Exception {
        final ClaudeAdapter a = adapter("fake-claude.sh");
        final Path project = Files.createDirectories(tmp.resolve("projects/-w"));
        Files.writeString(project.resolve("abd.jsonl"), """
                {"type":"user","cwd":"/w","message":{"role":"user","content":"build it"}}
                {"type":"assistant","message":{"id":"m0","content":[{"type":"tool_use","id":"t1","name":"Bash","input":{"command":"./gradlew build"}}]}}
                """);
        a.loadHistory(SessionSpec.imported("old", tmp.toString(), "abd"));
        final long deadline = System.nanoTime() + WAIT.toNanos();
        while (transcripts.of("old").size() < 3 && deadline - System.nanoTime() > 0) {
            Thread.sleep(20);
        }

        assertThat(blocks("old")).filteredOn(Block.ToolCall.class::isInstance).singleElement()
                .satisfies(b -> assertThat(((Block.ToolCall) b).status()).isEqualTo(Block.ToolStatus.ERROR));
    }
}
