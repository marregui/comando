package dev.marregui.comando.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.marregui.comando.core.intent.Intent;
import dev.marregui.comando.core.model.PanelId;
import dev.marregui.comando.core.model.SessionSpec;
import dev.marregui.comando.core.model.Status;
import dev.marregui.comando.core.port.IntentSink;
import dev.marregui.comando.core.state.AppState;
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

/**
 * Instances consulting each other through a broker (record 123), with a broker in memory between fake instances: each
 * with its own state, transcripts and data root, and a window that applies the intents a test needs.
 */
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

    @Test
    void aConsultTurnThatOutlastsItsBoundRepliesTimeout() throws Exception {
        Node main = node("", Peers.ANSWER_WITHIN);
        node("b", Duration.ofSeconds(1));
        McpTools.Outcome out = call(main, "instance_ask", "{\"instance\":\"b\",\"text\":\"what is X?\",\"wait_seconds\":20}");
        assertThat(out.error()).isTrue();
        assertThat(out.text()).startsWith("timeout:").contains("consult-main", "did not finish its turn within 1 s");
    }

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

    @Test
    void anUnknownInstanceAndThisOneAreRefusedWithTheInstancesNamed() throws Exception {
        Node main = node("", Peers.ANSWER_WITHIN);
        node("b", Peers.ANSWER_WITHIN);
        assertThat(call(main, "instance_ask", "{\"instance\":\"nobody\",\"text\":\"hi\"}").text()).contains("no instance 'nobody'", "comando · b (b)");
        assertThat(call(main, "instance_ask", "{\"instance\":\"main\",\"text\":\"hi\"}").text()).contains("is this instance");
        assertThat(call(main, "instance_ask", "{\"instance\":\"b\"}").text()).contains("instance and text are required");
        assertThat(Peers.slug("Comando Expert")).isEqualTo("comando-expert");
        assertThat(Peers.slug("")).isEqualTo("main");
    }

    // ---- the fixture

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
        Path home = Files.createDirectories(dir.resolve(name.isEmpty() ? "main" : name));
        Window window = new Window();
        Link link = new Link(bus);
        Peers peers = new Peers(link, Peers.Me.of(name, "2.0.0"), window, window.state::get, window.transcripts, home.toString(),
                home.resolve("agents.json"), answerWithin);
        McpTools tools = new McpTools(window, window.state::get, window.transcripts, null, AuditLog.none(), new FakeClock(), Duration.ofMinutes(1));
        tools.peers(peers);
        peers.start();
        link.connect();
        Node n = new Node(window, link, peers, tools, home.resolve("agents.json"));
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

    private record Node(Window window, Link link, Peers peers, McpTools tools, Path agents) {
    }

    /** The window and the reducer as far as the tests need them: a new session with its panel, a prompt shown and counted. */
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

    /** A broker in memory: retained messages, last wills, and delivery to every matching subscription of a connected link. */
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
