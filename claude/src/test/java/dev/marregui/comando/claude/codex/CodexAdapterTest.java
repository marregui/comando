package dev.marregui.comando.claude.codex;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.marregui.comando.core.files.Mapped;
import dev.marregui.comando.core.intent.Intent;
import dev.marregui.comando.core.json.Json;
import dev.marregui.comando.core.json.JsonNode;
import dev.marregui.comando.core.json.ObjectNode;
import dev.marregui.comando.core.model.SessionSpec;
import dev.marregui.comando.core.model.StartMode;
import dev.marregui.comando.core.port.McpGateway;
import dev.marregui.comando.core.transcript.TranscriptRegistry;
import dev.marregui.comando.testkit.RecordingSink;

import static org.assertj.core.api.Assertions.assertThat;

/// [CodexAdapter] runs each Codex session as one `codex app-server`, here `fake-codex.sh`, through a recording
/// [CodexEvents] in place of the mapper.
///
/// - Starts the thread with comando's permission profile, or resumes the session's thread.
/// - Sends prompts as turns, one at a time, with the session's model and effort.
/// - Answers the commands Codex lacks with a note, and declines what the server asks.
/// - Stops, revokes the ticket, and reports a crash with its stderr.
class CodexAdapterTest {

    private static final Duration WAIT = Duration.ofSeconds(15);

    private final RecordingSink sink = new RecordingSink();
    private final TranscriptRegistry transcripts = new TranscriptRegistry();
    private final List<String> revoked = new CopyOnWriteArrayList<>();
    private final McpGateway gateway = new McpGateway() {
        @Override
        public Ticket register(final String session) {
            return new Ticket("http://127.0.0.1:1/mcp", "token-" + session);
        }

        @Override
        public void unregister(final String session, final Ticket ticket) {
            if (ticket != null) {
                revoked.add(ticket.token());
            }
        }
    };
    private final Events events = new Events();
    @TempDir
    Path tmp;
    private CodexAdapter adapter;
    private Path work;
    private Path scratch;
    private Path log;

    @BeforeEach
    void setUp() throws IOException {
        work = Files.createDirectories(tmp.resolve("work"));
        scratch = Files.createDirectories(tmp.resolve("SCRATCHPAD"));
        log = tmp.resolve("received.jsonl");
    }

    @AfterEach
    void tearDown() {
        if (adapter != null) {
            adapter.close();
        }
    }

    /// The thread starts on the session's model with approval `never`, the scratch-only profile (the whole disk read,
    /// the scratchpad the one place written, no network), plugins and apps off, the user's MCP servers off and
    /// comando's on with the session's ticket; the thread's id reaches the session as its conversation id.
    @Test
    void threadStartCarriesTheScratchOnlyProfile() throws IOException {
        final Path config = tmp.resolve("config.toml");
        Files.writeString(config, """
                model = "gpt-6.2-sol"
                [mcp_servers.github]
                command = "gh-mcp"
                [mcp_servers.github.env]
                TOKEN = "x"
                [mcp_servers."local docs"]
                url = "http://localhost:9"
                [mcp_servers.comando]
                url = "http://old"
                [profiles.fast]
                """);
        start("", config);
        adapter.start(SessionSpec.fresh("s", work.toString()));

        final Intent.SessionInit init = sink.await(Intent.SessionInit.class, WAIT);
        assertThat(init.info().claudeSessionId()).isEqualTo("thr-1");
        assertThat(init.info().cliVersion()).isEqualTo("0.172.0");
        assertThat(events.lines()).contains("thread thr-1 gpt-6.2-sol medium");
        final ObjectNode params = received("thread/start").getFirst();
        assertThat(params.path("model").asString()).isEqualTo("gpt-6.2-sol");
        assertThat(params.path("cwd").asString()).isEqualTo(work.toString());
        assertThat(params.path("approvalPolicy").asString()).isEqualTo("never");
        assertThat(params.path("developerInstructions").asString()).isEqualTo("Write only the scratchpad.");
        final JsonNode cfg = params.path("config");
        assertThat(cfg.path("default_permissions").asString()).isEqualTo("scratch-only");
        final JsonNode profile = cfg.path("permissions").path("scratch-only");
        final List<String> writable = new ArrayList<>();
        for (final Map.Entry<String, JsonNode> e : profile.path("filesystem").properties()) {
            if ("write".equals(e.getValue().asString())) {
                writable.add(e.getKey());
            }
        }
        assertThat(writable).containsExactly(scratch.toString());
        assertThat(profile.path("filesystem").path(":root").asString()).isEqualTo("read");
        assertThat(profile.path("network").path("enabled").asBoolean()).isFalse();
        assertThat(profile.path("network").path("enabled").isBoolean()).isTrue();
        assertThat(cfg.path("features").path("plugins").asBoolean(true)).isFalse();
        assertThat(cfg.path("features").path("apps").asBoolean(true)).isFalse();
        final JsonNode servers = cfg.path("mcp_servers");
        assertThat(servers.path("github").path("enabled").asBoolean(true)).isFalse();
        assertThat(servers.path("local docs").path("enabled").asBoolean(true)).isFalse();
        assertThat(servers.path("comando").path("url").asString()).isEqualTo("http://127.0.0.1:1/mcp");
        assertThat(servers.path("comando").path("enabled").isMissingNode()).isTrue();
        assertThat(servers.path("comando").path("http_headers").path("Authorization").asString())
                .isEqualTo("Bearer token-s");
        assertThat(servers.size()).isEqualTo(3);
        assertThat(received("initialize").getFirst().path("clientInfo").path("name").asString()).isEqualTo("comando");
        assertThat(sink.of(Intent.SessionStarted.class)).hasSize(1);
    }

    /// A session whose thread exists resumes it by id, with the same profile.
    @Test
    void aStartedSessionResumesItsThread() throws IOException {
        start("", null);
        adapter.start(new SessionSpec("s", work.toString(), "sol", "", 0.0, "thr-old", StartMode.RESUME,
                SessionSpec.DEFAULT_CHECKPOINT, ""));

        sink.await(Intent.SessionInit.class, WAIT);
        assertThat(received("thread/start")).isEmpty();
        final ObjectNode params = received("thread/resume").getFirst();
        assertThat(params.path("threadId").asString()).isEqualTo("thr-old");
        assertThat(params.path("config").path("default_permissions").asString()).isEqualTo("scratch-only");
    }

    /// An imported or copied session forks the thread, so the original's stays as it was, with the same profile.
    @Test
    void anImportedSessionForksItsThread() throws IOException {
        start("", null);
        adapter.start(SessionSpec.imported("s", work.toString(), "thr-old"));

        sink.await(Intent.SessionInit.class, WAIT);
        assertThat(received("thread/resume")).isEmpty();
        final ObjectNode params = received("thread/fork").getFirst();
        assertThat(params.path("threadId").asString()).isEqualTo("thr-old");
        assertThat(params.path("config").path("default_permissions").asString()).isEqualTo("scratch-only");
    }

    /// An execpolicy rule would run a command outside the sandbox, and app-server cannot skip it: no server starts.
    @Test
    void anExecpolicyRuleRefusesTheStart() throws IOException {
        final Path home = Files.createDirectories(tmp.resolve("codex-home"));
        Files.createDirectories(home.resolve("rules"));
        Files.writeString(home.resolve("rules").resolve("default.rules"),
                "prefix_rule(pattern=[\"rm\", \"-rf\"], decision=\"allow\")\n");
        start("", home.resolve("config.toml"));
        adapter.start(SessionSpec.fresh("s", work.toString()));

        final Intent.SessionExited exited = sink.await(Intent.SessionExited.class, WAIT);
        assertThat(exited.exitCode()).isEqualTo(-1);
        assertThat(exited.error()).contains("execpolicy rules", "default.rules");
        assertThat(received("thread/start")).isEmpty();
        assertThat(CodexAdapter.unsafe(home.resolve("config.toml"), work)).contains("execpolicy");
        Files.writeString(home.resolve("rules").resolve("default.rules"), "");
        assertThat(CodexAdapter.unsafe(home.resolve("config.toml"), work)).isEmpty();
    }

    /// A project's `.codex/config.toml` servers are turned off too.
    @Test
    void aProjectsServersAreTurnedOff() throws IOException {
        Files.createDirectories(work.resolve(".codex"));
        Files.writeString(work.resolve(".codex").resolve("config.toml"),
                "[mcp_servers.project_one]\ncommand = \"p\"\n");
        start("", null);
        adapter.start(SessionSpec.fresh("s", work.toString()));

        sink.await(Intent.SessionInit.class, WAIT);
        final JsonNode servers = received("thread/start").getFirst().path("config").path("mcp_servers");
        assertThat(servers.path("project_one").path("enabled").asBoolean(true)).isFalse();
    }

    /// A prompt is shown, then sent as a turn with its preface at the default effort; the server's notifications,
    /// the replayed items of a real transcript included, reach the events in order.
    @Test
    void aPromptRunsAsATurn() throws IOException {
        start("", null);
        adapter.start(SessionSpec.fresh("s", work.toString()));
        adapter.send("s", "hello", "user", "pre: ");

        awaitLine("notification turn/completed");
        final ObjectNode turn = received("turn/start").getFirst();
        assertThat(turn.path("threadId").asString()).isEqualTo("thr-1");
        assertThat(turn.path("input").get(0).path("type").asString()).isEqualTo("text");
        assertThat(turn.path("input").get(0).path("text").asString()).isEqualTo("pre: hello");
        assertThat(turn.path("model").asString()).isEqualTo("gpt-6.2-sol");
        assertThat(turn.path("effort").asString()).isEqualTo("medium");
        assertThat(events.lines()).containsSubsequence("prompt hello user false", "thread thr-1 gpt-6.2-sol medium",
                "notification turn/started", "notification item/started", "notification item/agentMessage/delta",
                "notification turn/completed");
    }

    /// A prompt sent while a turn runs waits for it; an interrupt names the running turn, and its end starts the
    /// waiting prompt.
    @Test
    void aPromptWaitsForTheRunningTurn() throws IOException {
        start("hold", null);
        adapter.start(SessionSpec.fresh("s", work.toString()));
        adapter.send("s", "first", "user", "");
        adapter.send("s", "second", "user", "");

        awaitLine("notification turn/started");
        assertThat(received("turn/start")).hasSize(1);
        adapter.interrupt("s");
        Awaitility.await().atMost(WAIT).until(() -> received("turn/start").size() == 2);
        final ObjectNode interrupt = received("turn/interrupt").getFirst();
        assertThat(interrupt.path("threadId").asString()).isEqualTo("thr-1");
        assertThat(interrupt.path("turnId").asString()).isEqualTo("turn-1");
        assertThat(received("turn/start").getLast().path("input").get(0).path("text").asString()).isEqualTo("second");
    }

    /// `/model` and `/effort` set the next turn's, `max` becoming Codex's `xhigh`; a Claude command gets a note and
    /// no turn; a path is a prompt, not a command.
    @Test
    void commandsChangeTheNextTurnOrGetANote() throws IOException {
        start("", null);
        adapter.start(SessionSpec.fresh("s", work.toString()));
        adapter.send("s", "/model astra", "user", "");
        adapter.send("s", "/effort max", "user", "");
        adapter.send("s", "/compact", "user", "");
        adapter.send("s", "/model opus", "user", "");
        adapter.send("s", "/Users/x/notes.md says hi", "user", "");

        awaitLine("notification turn/completed");
        final List<ObjectNode> turns = received("turn/start");
        assertThat(turns).hasSize(1);
        assertThat(turns.getFirst().path("model").asString()).isEqualTo("gpt-6.2-astra");
        assertThat(turns.getFirst().path("effort").asString()).isEqualTo("xhigh");
        assertThat(turns.getFirst().path("input").get(0).path("text").asString())
                .isEqualTo("/Users/x/notes.md says hi");
        assertThat(events.lines()).contains("note The next turn runs on gpt-6.2-astra",
                "note The next turn runs at effort xhigh",
                "note /compact is a Claude Code command; Codex sessions do not have it",
                "note opus is a Claude model; a Codex session runs Codex models (sol, astra, luna): make a Claude "
                        + "session for it");
        assertThat(sink.of(Intent.SessionTurnEnded.class)).hasSize(4).allMatch(t -> !t.error());
    }

    /// What the server asks reaches the events and is declined.
    @Test
    void aServerRequestIsDeclined() throws IOException {
        start("request", null);
        adapter.start(SessionSpec.fresh("s", work.toString()));
        adapter.send("s", "touch it", "user", "");

        awaitLine("notification turn/completed");
        assertThat(events.lines()).contains("notification item/commandExecution/requestApproval");
        final ObjectNode answer = receivedLines().stream()
                .filter(m -> "srv-1".equals(m.path("id").asString(""))).findFirst().orElseThrow();
        assertThat(answer.path("result").path("decision").asString()).isEqualTo("decline");
    }

    /// Stop ends the server, revokes its ticket and exits the session cleanly; a prompt after it is not sent.
    @Test
    void stopEndsTheServer() throws IOException {
        start("", null);
        adapter.start(SessionSpec.fresh("s", work.toString()));
        sink.await(Intent.SessionInit.class, WAIT);
        adapter.stop("s");

        final Intent.SessionExited exited = sink.await(Intent.SessionExited.class, WAIT);
        assertThat(exited.exitCode()).isZero();
        assertThat(revoked).containsExactly("token-s");
        assertThat(events.lines()).contains("exited ");
        adapter.send("s", "late", "user", "");
        sink.await(Intent.SessionTurnEnded.class, Intent.SessionTurnEnded::error, WAIT);
        assertThat(sink.of(Intent.SessionExited.class)).hasSize(1);
    }

    /// A server that dies exits the session with its code and stderr, and the events hear why.
    @Test
    void aCrashReachesTheSession() throws IOException {
        start("crash", null);
        adapter.start(SessionSpec.fresh("s", work.toString()));
        adapter.send("s", "boom", "user", "");

        final Intent.SessionExited exited = sink.await(Intent.SessionExited.class, WAIT);
        assertThat(exited.exitCode()).isEqualTo(3);
        assertThat(exited.error()).isEqualTo("fake codex crashed");
        assertThat(events.lines()).contains("exited fake codex crashed");
        assertThat(revoked).containsExactly("token-s");
    }

    /// A thread the server refuses ends the session with an error, its ticket revoked.
    @Test
    void aRefusedThreadEndsTheSession() throws IOException {
        start("error", null);
        adapter.start(SessionSpec.imported("s", work.toString(), "thr-gone"));

        final Intent.SessionExited exited = sink.await(Intent.SessionExited.class, WAIT);
        assertThat(exited.exitCode()).isEqualTo(-1);
        assertThat(sink.of(Intent.SessionInit.class)).isEmpty();
        assertThat(revoked).containsExactly("token-s");
    }

    /// The adapter under test, its `codex` a wrapper that runs `fake-codex.sh` in {@code mode}.
    private void start(final String mode, final Path codexConfig) throws IOException {
        final Path fake = tmp.resolve("fake-codex.sh");
        try (final InputStream in = getClass().getResourceAsStream("/fake-codex.sh")) {
            Files.write(fake, in.readAllBytes());
        }
        final Path replay = tmp.resolve("turn.jsonl");
        try (final InputStream in = getClass().getResourceAsStream("/codex/turn.jsonl")) {
            Files.write(replay, in.readAllBytes());
        }
        final Path codex = tmp.resolve("codex");
        Files.writeString(codex, "#!/bin/sh\nFAKE_CODEX_LOG='" + log + "' FAKE_CODEX_MODE='" + mode
                + "' FAKE_CODEX_REPLAY='" + replay + "' exec '" + fake + "' \"$@\"\n");
        assertThat(fake.toFile().setExecutable(true)).isTrue();
        assertThat(codex.toFile().setExecutable(true)).isTrue();
        // never the user's ~/.codex/config.toml: an absent file when the test gives none
        final Path config = codexConfig == null ? tmp.resolve("no-config.toml") : codexConfig;
        adapter = new CodexAdapter(sink, transcripts, gateway,
                new CodexSettings(codex.toString(), scratch, "Write only the scratchpad."), _ -> events, config);
    }

    private void awaitLine(final String line) {
        Awaitility.await().atMost(WAIT).until(() -> events.lines().contains(line));
    }

    /// The params of every {@code method} the fake read, in order.
    private List<ObjectNode> received(final String method) throws IOException {
        final List<ObjectNode> params = new ArrayList<>();
        for (final ObjectNode m : receivedLines()) {
            if (method.equals(m.path("method").asString("")) && m.path("params") instanceof ObjectNode p) {
                params.add(p);
            }
        }
        return params;
    }

    private List<ObjectNode> receivedLines() throws IOException {
        final List<ObjectNode> messages = new ArrayList<>();
        if (!Files.exists(log)) {
            return messages;
        }
        for (final String line : Mapped.lines(log)) {
            if (Json.tryReadTree(line) instanceof ObjectNode m) {
                messages.add(m);
            }
        }
        return messages;
    }

    /// The calls the adapter made, one line each.
    private static final class Events implements CodexEvents {
        private final List<String> lines = new CopyOnWriteArrayList<>();

        @Override
        public void prompt(final String text, final String origin, final boolean delivered) {
            lines.add("prompt " + text + ' ' + origin + ' ' + delivered);
        }

        @Override
        public void threadStarted(final String threadId, final String model, final String effort) {
            lines.add("thread " + threadId + ' ' + model + ' ' + effort);
        }

        @Override
        public void notification(final String method, final ObjectNode params) {
            lines.add("notification " + method);
        }

        @Override
        public void note(final String text) {
            lines.add("note " + text);
        }

        @Override
        public void exited(final String error) {
            lines.add("exited " + error);
        }

        List<String> lines() {
            return List.copyOf(lines);
        }
    }
}
