package dev.marregui.comando.mcp;

import dev.marregui.comando.core.intent.Intent;
import dev.marregui.comando.core.model.PanelId;
import dev.marregui.comando.core.model.SessionSpec;
import dev.marregui.comando.core.model.Status;
import dev.marregui.comando.core.port.IntentSink;
import dev.marregui.comando.core.state.AppState;
import dev.marregui.comando.core.state.InstancesState;
import dev.marregui.comando.core.state.PanelState;
import dev.marregui.comando.core.state.SessionState;
import dev.marregui.comando.core.transcript.Block;
import dev.marregui.comando.core.transcript.Transcript;
import dev.marregui.comando.core.transcript.TranscriptRegistry;
import dev.marregui.comando.testkit.FakeClock;
import dev.marregui.comando.testkit.RecordingSink;
import dev.marregui.comando.testkit.States;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/// Instances consulting each other through a broker (record 123), with a broker in memory between fake instances: each
/// with its own state, transcripts and data root, and a window that applies the intents a test needs.
class PeersTest {

    private static final Duration WAIT = Duration.ofSeconds(10);
    private static final Pattern ID = Pattern.compile("\"id\": \"([^\"]+)\"");

    private final ObjectMapper json = new ObjectMapper();
    private final Bus bus = new Bus();
    private final List<Node> nodes = new ArrayList<>();
    private final ExecutorService callers = Executors.newVirtualThreadPerTaskExecutor();
    @TempDir
    Path dir;

    @AfterEach
    void close() {
        nodes.forEach(n -> n.peers.close());
        callers.shutdownNow();
    }

    /// Each instance lists the others with their sessions, itself marked; a new session shows at the next tick, and the
    /// presence of an instance that closes, or whose link dies and fires the last will, is cleared.
    @Test
    void presenceIsSeenFollowsTheSessionsAndIsClearedOnCloseAndByTheLastWill() throws Exception {
        Node main = node("", Peers.ANSWER_WITHIN);
        Node b = node("b", Peers.ANSWER_WITHIN);
        Node c = node("c", Peers.ANSWER_WITHIN);
        String listed = call(main, "instances_list", "{}").text();
        assertThat(listed).contains("comando · b (instance b)", "comando · c (instance c)", "sessions: work", "this one is comando (instance main)");
        assertThat(call(b, "instances_list", "{}").text()).contains("comando (instance main)");

        b.window.state.updateAndGet(s -> withSession(s, SessionSpec.fresh("review", "/tmp")));
        b.peers.tick();
        assertThat(call(main, "instances_list", "{}").text()).contains("sessions: work, review");

        b.peers.close();
        assertThat(call(main, "instances_list", "{}").text()).doesNotContain("instance b").contains("instance c");
        assertThat(bus.retained).containsOnlyKeys(Peers.presenceTopic("main"), Peers.presenceTopic("c"));

        c.link.die();
        assertThat(call(main, "instances_list", "{}").text()).contains("no other instance is connected");
    }

    /// A first question creates the consult session on the asked instance, created by the asker's peer, and prompts it
    /// headed with who asks; a second question, addressed by the instance's display name, reuses it, and the asker
    /// prompts nothing of its own.
    @Test
    void anAskMakesTheConsultSessionOnceAndPromptsItThroughItsPanelHeadedWithWhoAsks() throws Exception {
        Node main = node("", Peers.ANSWER_WITHIN);
        Node b = node("b", Peers.ANSWER_WITHIN);
        McpTools.Outcome first = call(main, "foreman", "instance_ask", "{\"instance\":\"b\",\"text\":\"what is X?\",\"wait_seconds\":0}");
        assertThat(first.error()).as(first.text()).isFalse();
        assertThat(first.text()).contains("instance_reply", "consult-main");
        b.window.recorded.await(Intent.PromptSession.class, WAIT);
        McpTools.Outcome second = call(main, "foreman", "instance_ask", "{\"instance\":\"comando · b\",\"text\":\"and Y?\",\"wait_seconds\":0}");
        assertThat(second.error()).as(second.text()).isFalse();
        b.window.recorded.await(Intent.PromptSession.class, p -> p.text().endsWith("and Y?"), WAIT);

        List<Intent.SaveSession> made = b.window.recorded.of(Intent.SaveSession.class);
        assertThat(made).hasSize(1);
        assertThat(made.getFirst().spec().name()).isEqualTo("consult-main");
        assertThat(made.getFirst().spec().createdBy()).isEqualTo("peer:main");
        assertThat(made.getFirst().spec().workDir()).isEqualTo(dir.resolve("b").toString());
        assertThat(b.window.recorded.of(Intent.PromptSession.class)).extracting(Intent.PromptSession::session, Intent.PromptSession::text,
                Intent.PromptSession::from).containsExactly(
                tuple("consult-main", "[from instance comando · session foreman]\nwhat is X?", "peer:main"),
                tuple("consult-main", "[from instance comando · session foreman]\nand Y?", "peer:main"));
        assertThat(main.window.recorded.of(Intent.PromptSession.class)).as("the asker prompts nothing of its own").isEmpty();
    }

    /// The asker gets no reply while the consult turn runs; when the turn ends it gets Claude's text of that turn,
    /// without the tool output.
    @Test
    void theReplyIsClaudesTextOfTheTurnOnceItEndsWithoutItsToolOutput() throws Exception {
        Node main = node("", Peers.ANSWER_WITHIN);
        Node b = node("b", Peers.ANSWER_WITHIN);
        CompletableFuture<McpTools.Outcome> asking = async(main, "instance_ask", "{\"instance\":\"b\",\"text\":\"what is X?\",\"wait_seconds\":30}");
        b.window.recorded.await(Intent.PromptSession.class, WAIT);
        Thread.sleep(400);
        assertThat(asking).as("no reply before the turn ends").isNotDone();

        b.window.turn("consult-main", "X is a broker.", "Done.");
        McpTools.Outcome reply = asking.get(WAIT.toSeconds(), TimeUnit.SECONDS);
        assertThat(reply.error()).as(reply.text()).isFalse();
        assertThat(reply.text()).startsWith("reply from comando · b (its session consult-main):").contains("X is a broker.\n\nDone.")
                .doesNotContain("tool output");
    }

    /// When the consult session is still in an earlier turn, the answer is the text of the turn that took the new
    /// question, and nothing of the earlier turn.
    @Test
    void aQuestionToABusySessionIsAnsweredByItsOwnTurnNotTheOneStillRunning() throws Exception {
        Node main = node("", Peers.ANSWER_WITHIN);
        Node b = node("b", Peers.ANSWER_WITHIN);
        // the consult session is mid-turn on an earlier question
        b.window.state.updateAndGet(s -> withSession(s, SessionSpec.fresh("consult-main", "/tmp").withCreatedBy("peer:main")));
        b.window.dispatch(new Intent.PromptSession("consult-main", "earlier", "peer:main"));
        b.window.transcripts.of("consult-main").append(new Block.AssistantText("m0", 0, "earlier, first half", false, ""));
        CompletableFuture<McpTools.Outcome> asking = async(main, "instance_ask", "{\"instance\":\"b\",\"text\":\"now?\",\"wait_seconds\":30}");
        b.window.recorded.await(Intent.PromptSession.class, p -> p.text().endsWith("now?"), WAIT);

        b.window.turn("consult-main", "earlier, second half");
        Thread.sleep(400);
        assertThat(asking).as("its own turn has not ended").isNotDone();
        b.window.turn("consult-main", "now is the answer");
        McpTools.Outcome reply = asking.get(WAIT.toSeconds(), TimeUnit.SECONDS);
        assertThat(reply.text()).contains("now is the answer").doesNotContain("earlier");
    }

    /// A peer denied in the receiving instance's `agents.json` gets a refusal naming the file; no session is made or
    /// prompted there, and a notice tells the user.
    @Test
    void aPeerTheUserDeniedIsRefusedAndNothingIsPrompted() throws Exception {
        Node main = node("", Peers.ANSWER_WITHIN);
        Node b = node("b", Peers.ANSWER_WITHIN);
        Files.writeString(b.agents, "{\"peers\": {\"deny\": [\"main\"]}}");
        McpTools.Outcome out = call(main, "instance_ask", "{\"instance\":\"b\",\"text\":\"what is X?\",\"wait_seconds\":10}");
        assertThat(out.error()).isTrue();
        assertThat(out.text()).startsWith("refused:").contains("agents.json");
        assertThat(b.window.recorded.of(Intent.SaveSession.class)).isEmpty();
        assertThat(b.window.recorded.of(Intent.PromptSession.class)).isEmpty();
        assertThat(b.window.recorded.of(Intent.Notify.class)).anyMatch(n -> n.text().startsWith("Refused a question from instance comando"));
    }

    /// A question not answered within its wait returns an id; `instance_reply` says there is no reply yet, then returns
    /// the answer once the turn ends, and a later read says it was read once.
    @Test
    void anAskThatOutlastsItsWaitReturnsAnIdAndInstanceReplyReadsTheAnswerOnce() throws Exception {
        Node main = node("", Peers.ANSWER_WITHIN);
        Node b = node("b", Peers.ANSWER_WITHIN);
        McpTools.Outcome asked = call(main, "instance_ask", "{\"instance\":\"b\",\"text\":\"what is X?\",\"wait_seconds\":0}");
        Matcher id = ID.matcher(asked.text());
        assertThat(id.find()).as(asked.text()).isTrue();
        assertThat(call(main, "instance_reply", "{\"id\":\"" + id.group(1) + "\",\"wait_seconds\":0}").text()).contains("no reply from comando · b yet");

        b.window.recorded.await(Intent.PromptSession.class, WAIT);
        b.window.turn("consult-main", "X is a broker.");
        McpTools.Outcome reply = call(main, "instance_reply", "{\"id\":\"" + id.group(1) + "\",\"wait_seconds\":10}");
        assertThat(reply.error()).as(reply.text()).isFalse();
        assertThat(reply.text()).contains("X is a broker.");
        assertThat(call(main, "instance_reply", "{\"id\":\"" + id.group(1) + "\"}").text()).contains("waits for a reply", "read once");
    }

    /// A consult turn that outlasts the receiving instance's bound is answered with a timeout naming the consult
    /// session and the bound.
    @Test
    void aConsultTurnThatOutlastsItsBoundRepliesTimeout() throws Exception {
        Node main = node("", Peers.ANSWER_WITHIN);
        node("b", Duration.ofSeconds(1));
        McpTools.Outcome out = call(main, "instance_ask", "{\"instance\":\"b\",\"text\":\"what is X?\",\"wait_seconds\":20}");
        assertThat(out.error()).isTrue();
        assertThat(out.text()).startsWith("timeout:").contains("consult-main", "did not finish its turn within 1 s");
    }

    /// With the instance not connected to the comando Edge, the instance tools say so and point to the Edge toggle.
    @Test
    void withTheEdgeDownTheToolsSaySoAndHowToStartIt() throws Exception {
        Node main = node("", Peers.ANSWER_WITHIN);
        node("b", Peers.ANSWER_WITHIN);
        main.link.up = false;
        for (String tool : List.of("instances_list", "instance_ask")) {
            McpTools.Outcome out = call(main, tool, "{\"instance\":\"b\",\"text\":\"what is X?\"}");
            assertThat(out.error()).isTrue();
            assertThat(out.text()).contains("not connected to the comando Edge", "toggle at the top of the workspace");
        }
    }

    /// An unknown instance, the asking instance itself and a question with a missing field are each refused with a
    /// message naming what is wrong or the instances there are; names turn into slugs and client ids without a doubled
    /// prefix.
    @Test
    void anUnknownInstanceAndThisOneAreRefusedWithTheInstancesNamed() throws Exception {
        Node main = node("", Peers.ANSWER_WITHIN);
        node("b", Peers.ANSWER_WITHIN);
        assertThat(call(main, "instance_ask", "{\"instance\":\"nobody\",\"text\":\"hi\"}").text()).contains("no instance 'nobody'", "comando · b (b)");
        assertThat(call(main, "instance_ask", "{\"instance\":\"main\",\"text\":\"hi\"}").text()).contains("is this instance");
        assertThat(call(main, "instance_ask", "{\"instance\":\"b\"}").text()).contains("instance and text are required");
        assertThat(Peers.slug("Comando Expert")).isEqualTo("comando-expert");
        assertThat(Peers.slug("")).isEqualTo("main");
        assertThat(Peers.Me.of("Comando Expert", "v").clientId()).as("no doubled prefix").isEqualTo("comando-expert");
        assertThat(Peers.Me.of("b", "v").clientId()).isEqualTo("comando-b");
        assertThat(Peers.Me.of("", "v").clientId()).isEqualTo("comando-main");
    }

    /// The window learns the instances from their presences (record 124): each other instance with its role and kit,
    /// this one's own, and whether it is connected; one that leaves is gone from the next state.
    @Test
    void theWindowSeesTheInstancesWithTheirRoleAndKitAndThisOnesLink() throws Exception {
        Node main = node("", Peers.ANSWER_WITHIN);
        Node b = node("b", "comando specialist", Peers.ANSWER_WITHIN);
        b.peers.kit("a710bbf");
        b.peers.tick();
        InstancesState seen = main.window.recorded.await(Intent.InstancesChanged.class,
                i -> i.instances().others().stream().anyMatch(o -> o.kit().equals("a710bbf")), WAIT).instances();
        assertThat(seen.connected()).isTrue();
        assertThat(seen.self().slug()).isEqualTo("main");
        assertThat(seen.others()).singleElement().satisfies(o -> {
            assertThat(o.slug()).isEqualTo("b");
            assertThat(o.role()).isEqualTo("comando specialist");
            assertThat(o.version()).isEqualTo("2.0.0");
            assertThat(o.sessions()).extracting(InstancesState.Member::name).containsExactly("work");
        });

        b.peers.close();
        main.window.recorded.await(Intent.InstancesChanged.class, i -> i.instances().others().stream().noneMatch(InstancesState.Instance::present), WAIT);
        main.link.die();
        main.peers.tick();
        main.window.recorded.await(Intent.InstancesChanged.class, i -> !i.instances().connected(), WAIT);
    }

    /// A presence says what each session is doing, so another instance knows without asking: its state, what its turn
    /// does and since when, its model, effort, context, folder and branch, its background tasks, and the first line of
    /// its last prompt and reply (a consult's header skipped); and whether the instance started the comando Edge.
    @Test
    void thePresenceSaysWhatEachSessionIsDoingAndWhoStartedTheEdge() throws Exception {
        Node main = node("", Peers.ANSWER_WITHIN);
        Node b = node("b", Peers.ANSWER_WITHIN);
        b.peers.edge(() -> true);
        b.window.state.updateAndGet(s -> turns(s, "work", 1));
        b.window.state.updateAndGet(s -> s.withSession("work", x -> x.withActivity("running Bash", 0L).withContext(42_000, 100_000)));
        b.window.transcripts.of("work").append(new Block.UserPrompt("[from instance comando]\nwhat is X?\nmore", "work", ""));
        b.window.transcripts.of("work").append(new Block.AssistantText("m1", 0, "X is a broker.\nDetails.", false, ""));
        b.window.transcripts.of("work").append(new Block.UserPrompt("a subagent's prompt", "work", "tool-1"));
        b.peers.tick();
        InstancesState seen = main.window.recorded.await(Intent.InstancesChanged.class,
                i -> i.instances().others().stream().anyMatch(o -> o.edge() && o.sessions().stream().anyMatch(InstancesState.Member::busy)), WAIT)
                .instances();
        InstancesState.Member work = seen.others().getFirst().sessions().getFirst();
        assertThat(work.activity()).isEqualTo("running Bash");
        assertThat(work.status()).isEqualTo("OPEN");
        assertThat(work.context()).isEqualTo(42);
        assertThat(work.prompt()).as("the consult header skipped, the subagent's prompt not the session's").isEqualTo("what is X?");
        assertThat(work.reply()).isEqualTo("X is a broker.");
        assertThat(call(main, "instances_list", "{}").text()).contains("work (busy: running Bash) last asked: what is X?");
    }

    /// A session's folder goes out with the home folder written `~`: `/Users/ann/repo` as `~/repo`, while
    /// `/Users/anna/repo`, beside home rather than under it, goes out as it is. The home is set for the test and put
    /// back after.
    @Test
    void thePresenceWritesAFolderUnderHomeAsTildeAndLeavesOneBesideItAsItIs() throws Exception {
        final String userHome = System.getProperty("user.home");
        System.setProperty("user.home", "/Users/ann");
        try {
            final Node main = node("", Peers.ANSWER_WITHIN);
            final Node b = node("b", Peers.ANSWER_WITHIN);
            b.window.state.updateAndGet(s -> withSession(withSession(s, SessionSpec.fresh("beside", "/Users/anna/repo")),
                    SessionSpec.fresh("under", "/Users/ann/repo")));
            b.peers.tick();
            final InstancesState seen = main.window.recorded.await(Intent.InstancesChanged.class,
                    i -> i.instances().others().stream().anyMatch(o -> o.sessions().size() == 3), WAIT).instances();
            final Map<String, String> folders = new LinkedHashMap<>();
            seen.others().getFirst().sessions().forEach(m -> folders.put(m.name(), m.folder()));
            assertThat(folders).containsEntry("beside", "/Users/anna/repo").containsEntry("under", "~/repo");
        } finally {
            System.setProperty("user.home", userHome);
        }
    }

    /// An instance that leaves stays listed, not present, with when it was last seen; the data root remembers it across
    /// a restart, until the user forgets it; one present cannot be forgotten. The role set from the tab is written to
    /// `instance.json`, its other keys kept, and goes out in the next presence.
    @Test
    void anInstanceThatLeftIsRememberedUntilForgottenAndTheRoleIsWrittenAndSent() throws Exception {
        Node main = node("", Peers.ANSWER_WITHIN);
        Node b = node("b", Peers.ANSWER_WITHIN);
        main.window.recorded.await(Intent.InstancesChanged.class, i -> i.instances().others().stream().anyMatch(o -> o.slug().equals("b")), WAIT);
        main.peers.forget("b");
        assertThat(main.window.recorded.of(Intent.Notify.class)).anyMatch(n -> n.text().contains("only an instance that left can be forgotten"));

        b.peers.close();
        InstancesState.Instance left = main.window.recorded.await(Intent.InstancesChanged.class,
                i -> i.instances().others().stream().anyMatch(o -> !o.present()), WAIT).instances().others().getFirst();
        assertThat(left.slug()).isEqualTo("b");
        assertThat(left.lastSeen()).isPositive();
        assertThat(left.sessions()).isEmpty();
        Path file = main.home.resolve(Peers.SEEN_FILE);
        assertThat(json.readTree(Files.readString(file)).path("instances").get(0).path("slug").asString()).isEqualTo("b");

        main.peers.close();
        Node again = node("", Peers.ANSWER_WITHIN);
        assertThat(again.peers.instances().others()).singleElement().satisfies(o -> {
            assertThat(o.slug()).isEqualTo("b");
            assertThat(o.present()).isFalse();
        });
        again.peers.forget("b");
        assertThat(again.peers.instances().others()).isEmpty();
        assertThat(json.readTree(Files.readString(file)).path("instances").isEmpty()).isTrue();

        Files.writeString(again.home.resolve("instance.json"), "{\"name\": \"x\"}");
        Node c = node("c", Peers.ANSWER_WITHIN);
        again.peers.role("comando specialist");
        JsonNode written = json.readTree(Files.readString(again.home.resolve("instance.json")));
        assertThat(written.path("name").asString()).isEqualTo("x");
        assertThat(written.path("role").asString()).isEqualTo("comando specialist");
        c.window.recorded.await(Intent.InstancesChanged.class,
                i -> i.instances().others().stream().anyMatch(o -> o.slug().equals("main") && o.role().equals("comando specialist")), WAIT);
    }

    /// Every question is in the state on both sides: the asker's, waiting, then answered with the reply's text; the
    /// answering instance's, naming the session that answered it. A question asked from the tab has no session.
    @Test
    void theQuestionsAskedAndAnsweredAreKeptWithTheirReplies() throws Exception {
        Node main = node("", Peers.ANSWER_WITHIN);
        Node b = node("b", Peers.ANSWER_WITHIN);
        main.peers.ask("b", "what is X?");
        b.window.recorded.await(Intent.PromptSession.class, WAIT);
        InstancesState waiting = main.window.recorded.await(Intent.InstancesChanged.class, i -> !i.instances().consults().isEmpty(), WAIT).instances();
        assertThat(waiting.consults()).singleElement().satisfies(c -> {
            assertThat(c.direction()).isEqualTo(InstancesState.Direction.ASKED);
            assertThat(c.status()).isEqualTo(InstancesState.Status.WAITING);
            assertThat(c.session()).as("asked from the tab").isEmpty();
            assertThat(c.question()).isEqualTo("what is X?");
        });
        assertThat(b.window.recorded.of(Intent.PromptSession.class).getFirst().text()).as("no session in the header")
                .isEqualTo("[from instance comando]\nwhat is X?");

        b.window.turn("consult-main", "X is a broker.");
        InstancesState.Consult asked = main.window.recorded.await(Intent.InstancesChanged.class,
                i -> i.instances().consults().stream().anyMatch(c -> c.status() == InstancesState.Status.OK), WAIT).instances().consults().getFirst();
        assertThat(asked.reply()).isEqualTo("X is a broker.");
        assertThat(asked.endedAt()).isGreaterThanOrEqualTo(asked.askedAt());
        InstancesState.Consult answered = b.window.recorded.await(Intent.InstancesChanged.class,
                i -> i.instances().consults().stream().anyMatch(c -> c.status() == InstancesState.Status.OK), WAIT).instances().consults().getFirst();
        assertThat(answered.direction()).isEqualTo(InstancesState.Direction.ANSWERED);
        assertThat(answered.session()).isEqualTo("consult-main");
        assertThat(main.window.recorded.of(Intent.Notify.class)).anyMatch(n -> n.text().startsWith("reply from comando · b"));
    }

    /// Refusing from the tab writes `agents.json`'s `peers.deny`, keeping its grants; the next question from that
    /// instance is refused, and the state lists it as refused until the user takes it back.
    @Test
    void refusingAnInstanceWritesAgentsJsonKeepsTheGrantsAndRefusesItsQuestions() throws Exception {
        Node main = node("", Peers.ANSWER_WITHIN);
        Node b = node("b", Peers.ANSWER_WITHIN);
        Files.writeString(b.agents, "{\"grants\": {\"foreman\": [\"destructive\"]}}");
        b.peers.deny("main", true);
        JsonNode written = json.readTree(Files.readString(b.agents));
        assertThat(written.path("grants").path("foreman").get(0).asString()).isEqualTo("destructive");
        assertThat(written.path("peers").path("deny").get(0).asString()).isEqualTo("main");
        b.window.recorded.await(Intent.InstancesChanged.class, i -> i.instances().denied().contains("main"), WAIT);
        assertThat(call(main, "instance_ask", "{\"instance\":\"b\",\"text\":\"what is X?\",\"wait_seconds\":10}").text()).startsWith("refused:");

        b.peers.deny("main", false);
        assertThat(json.readTree(Files.readString(b.agents)).path("peers").path("deny").isEmpty()).isTrue();
        b.window.recorded.await(Intent.InstancesChanged.class, i -> i.instances().denied().isEmpty(), WAIT);
    }

    private McpTools.Outcome call(Node n, String tool, String args) throws Exception {
        return call(n, "foreman", tool, args);
    }

    private McpTools.Outcome call(Node n, String session, String tool, String args) throws Exception {
        return n.tools.call(session, tool, json.readTree(args));
    }

    private CompletableFuture<McpTools.Outcome> async(Node n, String tool, String args) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return call(n, tool, args);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }, callers);
    }

    private Node node(String name, Duration answerWithin) throws Exception {
        return node(name, "", answerWithin);
    }

    private Node node(String name, String role, Duration answerWithin) throws Exception {
        Path home = Files.createDirectories(dir.resolve(name.isEmpty() ? "main" : name));
        Window window = new Window();
        Link link = new Link(bus);
        Peers peers = new Peers(link, Peers.Me.of(name, "2.0.0", role), window, window.state::get, window.transcripts, home.toString(),
                home.resolve("agents.json"), answerWithin);
        peers.dataRoot(home);
        McpTools tools = new McpTools(window, window.state::get, window.transcripts, null, AuditLog.none(), new FakeClock(), Duration.ofMinutes(1));
        tools.peers(peers);
        peers.start();
        link.connect();
        Node n = new Node(window, link, peers, tools, home.resolve("agents.json"), home);
        nodes.add(n);
        return n;
    }

    private static AppState withSession(AppState s, SessionSpec spec) {
        List<SessionState> sessions = new ArrayList<>(s.sessions());
        sessions.add(SessionState.closed(spec));
        List<PanelState> panels = new ArrayList<>(s.panels());
        // a session comes with its own panel (record 88)
        panels.add(new PanelState(PanelId.random(), spec.name(), "", spec.name()));
        return s.withSessions(sessions).withPanels(panels, s.focused());
    }

    private static AppState turns(AppState s, String session, int delta) {
        List<SessionState> sessions = new ArrayList<>();
        for (SessionState x : s.sessions()) {
            sessions.add(x.name().equals(session) ? x.withStatus(Status.OPEN, "").withTurns(Math.max(0, x.pendingTurns() + delta), 0L) : x);
        }
        return s.withSessions(sessions);
    }

    private record Node(Window window, Link link, Peers peers, McpTools tools, Path agents, Path home) {
    }

    /** The window and reducer as far as the tests need them: a session with its panel, a prompt shown and counted. */
    private static final class Window implements IntentSink {
        final RecordingSink recorded = new RecordingSink();
        final AtomicReference<AppState> state = new AtomicReference<>(States.twoPanels());
        final TranscriptRegistry transcripts = new TranscriptRegistry();

        @Override
        public void dispatch(Intent intent) {
            switch (intent) {
                case Intent.SaveSession save when save.previousName().isEmpty() -> state.updateAndGet(s -> withSession(s, save.spec()));
                case Intent.PromptSession p -> {
                    state.updateAndGet(s -> turns(s, p.session(), 1));
                    transcripts.of(p.session()).append(new Block.UserPrompt(p.text(), p.session(), ""));
                }
                default -> {
                }
            }
            recorded.dispatch(intent);
        }

        /** Ends the oldest open turn of {@code session} with Claude's texts and a tool call between them. */
        void turn(String session, String... texts) {
            Transcript t = transcripts.of(session);
            for (int i = 0; i < texts.length; i++) {
                t.append(new Block.AssistantText("m" + t.size(), 0, texts[i], false, ""));
                if (i == 0) {
                    t.append(new Block.ToolCall("t" + t.size(), "Bash", "{}", "tool output", Block.ToolStatus.DONE, ""));
                }
            }
            t.append(new Block.TurnEnd(0.0, 10, 1, false, ""));
            state.updateAndGet(s -> turns(s, session, -1));
        }
    }

    /** A broker in memory: retained messages, last wills, delivery to each matching subscription of a live link. */
    private static final class Bus {
        final Map<String, byte[]> retained = new LinkedHashMap<>();
        final List<Link> links = new CopyOnWriteArrayList<>();

        void publish(String topic, byte[] payload, boolean retain, String responseTopic, byte[] correlation) {
            if (retain) {
                synchronized (retained) {
                    if (payload.length == 0) {
                        retained.remove(topic);
                    } else {
                        retained.put(topic, payload);
                    }
                }
            }
            for (Link l : links) {
                l.deliver(new PeerBroker.Message(topic, payload, responseTopic, correlation));
            }
        }

        List<PeerBroker.Message> retained(String filter) {
            synchronized (retained) {
                return retained.entrySet().stream().filter(e -> matches(filter, e.getKey()))
                        .map(e -> new PeerBroker.Message(e.getKey(), e.getValue(), null, null)).toList();
            }
        }

        static boolean matches(String filter, String topic) {
            String[] f = filter.split("/");
            String[] t = topic.split("/");
            for (int i = 0; i < f.length; i++) {
                if (f[i].equals("#")) {
                    return true;
                }
                if (i >= t.length || !f[i].equals("+") && !f[i].equals(t[i])) {
                    return false;
                }
            }
            return f.length == t.length;
        }
    }

    private static final class Link implements PeerBroker {
        private final Bus bus;
        private final List<Runnable> listeners = new CopyOnWriteArrayList<>();
        private final Map<String, Consumer<Message>> subscriptions = new LinkedHashMap<>();
        volatile boolean up;
        private Message will;

        Link(Bus bus) {
            this.bus = bus;
            bus.links.add(this);
        }

        void connect() {
            up = true;
            listeners.forEach(Runnable::run);
            Map<String, Consumer<Message>> subs;
            synchronized (subscriptions) {
                subs = Map.copyOf(subscriptions);
            }
            subs.forEach((filter, handler) -> bus.retained(filter).forEach(handler));
        }

        /** Gone without a goodbye: the broker publishes the last will. */
        void die() {
            up = false;
            bus.links.remove(this);
            if (will != null) {
                bus.publish(will.topic(), will.payload(), true, null, null);
            }
        }

        void deliver(Message m) {
            if (!up) {
                return;
            }
            List<Consumer<Message>> handlers = new ArrayList<>();
            synchronized (subscriptions) {
                subscriptions.forEach((filter, handler) -> {
                    if (Bus.matches(filter, m.topic())) {
                        handlers.add(handler);
                    }
                });
            }
            handlers.forEach(h -> h.accept(m));
        }

        @Override
        public boolean connected() {
            return up;
        }

        @Override
        public void onConnected(Runnable listener) {
            listeners.add(listener);
        }

        @Override
        public void will(String topic, byte[] payload, boolean retained) {
            will = new Message(topic, payload, null, null);
        }

        @Override
        public CompletableFuture<Void> publish(String topic, byte[] payload, boolean retained, String responseTopic, byte[] correlation) {
            if (!up) {
                return CompletableFuture.failedFuture(new IllegalStateException("not connected"));
            }
            bus.publish(topic, payload, retained, responseTopic, correlation);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void subscribe(String filter, Consumer<Message> handler) {
            synchronized (subscriptions) {
                subscriptions.put(filter, handler);
            }
            if (up) {
                bus.retained(filter).forEach(handler);
            }
        }
    }
}
