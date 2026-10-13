package dev.marregui.comando.claude;

import dev.marregui.comando.core.intent.Intent;
import dev.marregui.comando.core.model.SessionSpec;
import dev.marregui.comando.core.port.McpGateway;
import dev.marregui.comando.core.transcript.Block;
import dev.marregui.comando.core.transcript.Transcript;
import dev.marregui.comando.core.transcript.TranscriptRegistry;
import dev.marregui.comando.testkit.RecordingSink;
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
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

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
    }

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

    @Test
    void aMissingWorkingDirectoryFailsTheStart() throws Exception {
        ClaudeAdapter a = adapter("fake-claude.sh");
        a.start(SessionSpec.fresh("work", tmp.resolve("nope").toString()));
        Intent.SessionExited exit = sink.await(Intent.SessionExited.class, WAIT);
        assertThat(exit.exitCode()).isEqualTo(-1);
        assertThat(exit.error()).contains("does not exist");
        assertThat(registered).noneMatch(r -> r.startsWith("+"));
    }

    @Test
    void sendingToAStoppedSessionEndsTheTurnWithAnError() throws Exception {
        ClaudeAdapter a = adapter("fake-claude.sh");
        a.send("work", "hello?", "one");
        Intent.SessionTurnEnded end = sink.await(Intent.SessionTurnEnded.class, WAIT);
        assertThat(end.error()).isTrue();
    }

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
}
