package dev.marregui.comando.mcp;

import java.nio.charset.StandardCharsets;
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

import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.marregui.comando.core.intent.Intent;
import dev.marregui.comando.core.json.Json;
import dev.marregui.comando.core.json.JsonNode;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/// Instances consulting each other through a broker (record 123), with a broker in memory between fake instances: each
/// with its own state, transcripts and data root, and a window that applies the intents a test needs.
class PeersTest {

    private static final Duration WAIT = Duration.ofSeconds(10);
    private static final Pattern ID = Pattern.compile("\"id\": \"([^\"]+)\"");

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
        final Node main = node("", Peers.ANSWER_WITHIN);
        final Node b = node("b", Peers.ANSWER_WITHIN);
        final Node c = node("c", Peers.ANSWER_WITHIN);
        final String listed = call(main, "instances_list", "{}").text();
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
    /// headed with who asks, main marked the overseer; a second question, addressed by the instance's display name,
    /// reuses it, and the asker prompts nothing of its own.
    @Test
    void anAskMakesTheConsultSessionOnceAndPromptsItThroughItsPanelHeadedWithWhoAsks() throws Exception {
        final Node main = node("", Peers.ANSWER_WITHIN);
        final Node b = node("b", Peers.ANSWER_WITHIN);
        final McpTools.Outcome first = call(main, "foreman", "instance_ask", "{\"instance\":\"b\",\"text\":\"what is X?\",\"wait_seconds\":0}");
        assertThat(first.error()).as(first.text()).isFalse();
        assertThat(first.text()).contains("instance_reply", "consult-main");
        b.window.recorded.await(Intent.PromptSession.class, WAIT);
        final McpTools.Outcome second = call(main, "foreman", "instance_ask", "{\"instance\":\"comando · b\",\"text\":\"and Y?\",\"wait_seconds\":0}");
        assertThat(second.error()).as(second.text()).isFalse();
        b.window.recorded.await(Intent.PromptSession.class, p -> p.text().endsWith("and Y?"), WAIT);

        final List<Intent.SaveSession> made = b.window.recorded.of(Intent.SaveSession.class);
        assertThat(made).hasSize(1);
        assertThat(made.getFirst().spec().name()).isEqualTo("consult-main");
        assertThat(made.getFirst().spec().createdBy()).isEqualTo("peer:main");
        assertThat(made.getFirst().spec().workDir()).isEqualTo(dir.resolve("b").toString());
        assertThat(b.window.recorded.of(Intent.PromptSession.class)).extracting(Intent.PromptSession::session, Intent.PromptSession::text,
                Intent.PromptSession::from).containsExactly(
                tuple("consult-main", "[from instance comando · overseer · session foreman]\nwhat is X?",
                        "peer:main"),
                tuple("consult-main", "[from instance comando · overseer · session foreman]\nand Y?", "peer:main"));
        assertThat(main.window.recorded.of(Intent.PromptSession.class)).as("the asker prompts nothing of its own").isEmpty();
    }

    /// The asker gets no reply while the consult turn runs; when the turn ends it gets Claude's text of that turn,
    /// without the tool output.
    @Test
    void theReplyIsClaudesTextOfTheTurnOnceItEndsWithoutItsToolOutput() throws Exception {
        final Node main = node("", Peers.ANSWER_WITHIN);
        final Node b = node("b", Peers.ANSWER_WITHIN);
        final CompletableFuture<McpTools.Outcome> asking = async(main, "instance_ask", "{\"instance\":\"b\",\"text\":\"what is X?\",\"wait_seconds\":30}");
        b.window.recorded.await(Intent.PromptSession.class, WAIT);
        Thread.sleep(400);
        assertThat(asking).as("no reply before the turn ends").isNotDone();

        b.window.turn("consult-main", "X is a broker.", "Done.");
        final McpTools.Outcome reply = asking.get(WAIT.toSeconds(), TimeUnit.SECONDS);
        assertThat(reply.error()).as(reply.text()).isFalse();
        assertThat(reply.text()).startsWith("reply from comando · b (its session consult-main):").contains("X is a broker.\n\nDone.")
                .doesNotContain("tool output");
    }

    /// When the consult session is still in an earlier turn, the answer is the text of the turn that took the new
    /// question, and nothing of the earlier turn.
    @Test
    void aQuestionToABusySessionIsAnsweredByItsOwnTurnNotTheOneStillRunning() throws Exception {
        final Node main = node("", Peers.ANSWER_WITHIN);
        final Node b = node("b", Peers.ANSWER_WITHIN);
        // the consult session is mid-turn on an earlier question
        b.window.state.updateAndGet(s -> withSession(s, SessionSpec.fresh("consult-main", "/tmp").withCreatedBy("peer:main")));
        b.window.dispatch(new Intent.PromptSession("consult-main", "earlier", "peer:main"));
        b.window.transcripts.of("consult-main").append(new Block.AssistantText("m0", 0, "earlier, first half", false, ""));
        final CompletableFuture<McpTools.Outcome> asking = async(main, "instance_ask", "{\"instance\":\"b\",\"text\":\"now?\",\"wait_seconds\":30}");
        b.window.recorded.await(Intent.PromptSession.class, p -> p.text().endsWith("now?"), WAIT);

        b.window.turn("consult-main", "earlier, second half");
        Thread.sleep(400);
        assertThat(asking).as("its own turn has not ended").isNotDone();
        b.window.turn("consult-main", "now is the answer");
        final McpTools.Outcome reply = asking.get(WAIT.toSeconds(), TimeUnit.SECONDS);
        assertThat(reply.text()).contains("now is the answer").doesNotContain("earlier");
    }

    /// MQTT delivers a question at least once: the same question delivered twice, with the same asker and correlation
    /// data, is answered once; another correlation is another question.
    @Test
    void aQuestionDeliveredTwiceIsAnsweredOnce() throws Exception {
        node("", Peers.ANSWER_WITHIN);
        final Node b = node("b", Peers.ANSWER_WITHIN);
        final byte[] question = "{\"from\":\"main\",\"name\":\"comando\",\"session\":\"foreman\",\"text\":\"what is X?\"}".getBytes(StandardCharsets.UTF_8);
        bus.publish(Peers.inboxTopic("b"), question, false, Peers.replyTopic("main"), "q-1".getBytes(StandardCharsets.UTF_8));
        bus.publish(Peers.inboxTopic("b"), question, false, Peers.replyTopic("main"), "q-1".getBytes(StandardCharsets.UTF_8));
        b.window.recorded.await(Intent.PromptSession.class, WAIT);
        bus.publish(Peers.inboxTopic("b"), question, false, Peers.replyTopic("main"), "q-2".getBytes(StandardCharsets.UTF_8));
        b.window.recorded.await(Intent.PromptSession.class, p -> b.window.recorded.of(Intent.PromptSession.class).size() == 2, WAIT);
        Thread.sleep(300);
        assertThat(b.window.recorded.of(Intent.PromptSession.class)).as("q-1 once, q-2 once").hasSize(2);
    }

    /// Messages between instances name their schema; a question of a later schema is refused with a reply that says
    /// so, and no session is prompted.
    @Test
    void aQuestionOfALaterSchemaIsRefused() throws Exception {
        node("", Peers.ANSWER_WITHIN);
        final Node b = node("b", Peers.ANSWER_WITHIN);
        final List<String> replies = new CopyOnWriteArrayList<>();
        final Link listener = new Link(bus);
        listener.subscribe(Peers.replyTopic("main"), m -> replies.add(new String(m.payload(), StandardCharsets.UTF_8)));
        listener.connect();
        bus.publish(Peers.inboxTopic("b"), "{\"schema\":2,\"from\":\"main\",\"name\":\"comando\",\"text\":\"q\"}".getBytes(StandardCharsets.UTF_8), false,
                Peers.replyTopic("main"), "q-9".getBytes(StandardCharsets.UTF_8));
        assertThat(replies).singleElement().asString().contains("\"status\":\"refused\"").contains("schema 1");
        assertThat(b.window.recorded.of(Intent.PromptSession.class)).isEmpty();
        assertThat(new String(bus.retained.get(Peers.presenceTopic("b")), StandardCharsets.UTF_8)).contains("\"schema\":1");
    }

    /// A question whose reply never comes, its peer gone while this instance was off frontear so no presence clears it,
    /// expires past the answer's bound and margins: `instance_reply` then answers that no reply came, and the
    /// question waits no longer.
    @Test
    void aQuestionWhoseReplyCannotComeExpires() throws Exception {
        final Node main = node("", "", Peers.ANSWER_WITHIN, Duration.ofMillis(200));
        node("b", Peers.ANSWER_WITHIN);
        final McpTools.Outcome asked = call(main, "instance_ask", "{\"instance\":\"b\",\"text\":\"what is X?\",\"wait_seconds\":0}");
        final Matcher id = ID.matcher(asked.text());
        assertThat(id.find()).as(asked.text()).isTrue();
        Thread.sleep(300);
        main.peers.tick();
        final McpTools.Outcome reply = call(main, "instance_reply", "{\"id\":\"" + id.group(1) + "\",\"wait_seconds\":0}");
        assertThat(reply.error()).isTrue();
        assertThat(reply.text()).contains("sent no reply within");
        assertThat(call(main, "instance_reply", "{\"id\":\"" + id.group(1) + "\",\"wait_seconds\":0}").text()).as("read once").contains("no question");
    }

    /// A peer denied in the receiving instance's `agents.json` gets a refusal naming the file; no session is made or
    /// prompted there, and a notice tells the user.
    @Test
    void aPeerTheUserDeniedIsRefusedAndNothingIsPrompted() throws Exception {
        final Node main = node("", Peers.ANSWER_WITHIN);
        final Node b = node("b", Peers.ANSWER_WITHIN);
        Files.writeString(b.agents, "{\"peers\": {\"deny\": [\"main\"]}}");
        final McpTools.Outcome out = call(main, "instance_ask", "{\"instance\":\"b\",\"text\":\"what is X?\",\"wait_seconds\":10}");
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
        final Node main = node("", Peers.ANSWER_WITHIN);
        final Node b = node("b", Peers.ANSWER_WITHIN);
        final McpTools.Outcome asked = call(main, "instance_ask", "{\"instance\":\"b\",\"text\":\"what is X?\",\"wait_seconds\":0}");
        final Matcher id = ID.matcher(asked.text());
        assertThat(id.find()).as(asked.text()).isTrue();
        assertThat(call(main, "instance_reply", "{\"id\":\"" + id.group(1) + "\",\"wait_seconds\":0}").text()).contains("no reply from comando · b yet");

        b.window.recorded.await(Intent.PromptSession.class, WAIT);
        b.window.turn("consult-main", "X is a broker.");
        final McpTools.Outcome reply = call(main, "instance_reply", "{\"id\":\"" + id.group(1) + "\",\"wait_seconds\":10}");
        assertThat(reply.error()).as(reply.text()).isFalse();
        assertThat(reply.text()).contains("X is a broker.");
        assertThat(call(main, "instance_reply", "{\"id\":\"" + id.group(1) + "\"}").text()).contains("waits for a reply", "read once");
    }

    /// A consult turn that outlasts the receiving instance's bound is answered with a timeout naming the consult
    /// session and the bound.
    @Test
    void aConsultTurnThatOutlastsItsBoundRepliesTimeout() throws Exception {
        final Node main = node("", Peers.ANSWER_WITHIN);
        node("b", Duration.ofSeconds(1));
        final McpTools.Outcome out = call(main, "instance_ask", "{\"instance\":\"b\",\"text\":\"what is X?\",\"wait_seconds\":20}");
        assertThat(out.error()).isTrue();
        assertThat(out.text()).startsWith("timeout:").contains("consult-main", "did not finish its turn within 1 s");
    }

    /// With the instance not connected to frontear, the instance tools say so and point to frontear's toggle.
    @Test
    void withFrontearDownTheToolsSaySoAndHowToStartIt() throws Exception {
        final Node main = node("", Peers.ANSWER_WITHIN);
        node("b", Peers.ANSWER_WITHIN);
        main.link.up = false;
        for (final String tool : List.of("instances_list", "instance_ask")) {
            final McpTools.Outcome out = call(main, tool, "{\"instance\":\"b\",\"text\":\"what is X?\"}");
            assertThat(out.error()).isTrue();
            assertThat(out.text()).contains("not connected to frontear", "toggle at the top of the workspace");
        }
    }

    /// An unknown instance, the asking instance itself and a question with a missing field are each refused with a
    /// message naming what is wrong or the instances there are; names turn into slugs and client ids without a doubled
    /// prefix.
    @Test
    void anUnknownInstanceAndThisOneAreRefusedWithTheInstancesNamed() throws Exception {
        final Node main = node("", Peers.ANSWER_WITHIN);
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
        final Node main = node("", Peers.ANSWER_WITHIN);
        final Node b = node("b", "comando specialist", Peers.ANSWER_WITHIN);
        b.peers.kit("a710bbf");
        b.peers.tick();
        final InstancesState seen = main.window.recorded.await(Intent.InstancesChanged.class,
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
    /// its last prompt and reply (a consult's header skipped); and whether the instance hosts frontear.
    @Test
    void thePresenceSaysWhatEachSessionIsDoingAndWhoHostsFrontear() throws Exception {
        final Node main = node("", Peers.ANSWER_WITHIN);
        final Node b = node("b", Peers.ANSWER_WITHIN);
        b.peers.host(() -> true);
        b.window.state.updateAndGet(s -> turns(s, "work", 1));
        b.window.state.updateAndGet(s -> s.withSession("work", x -> x.withActivity("running Bash", 0L).withContext(42_000, 100_000)));
        b.window.transcripts.of("work").append(new Block.UserPrompt("[from instance comando]\nwhat is X?\nmore", "work", ""));
        b.window.transcripts.of("work").append(new Block.AssistantText("m1", 0, "X is a broker.\nDetails.", false, ""));
        b.window.transcripts.of("work").append(new Block.UserPrompt("a subagent's prompt", "work", "tool-1"));
        b.peers.tick();
        // the presence after the last change: the periodic tick or the connect may send one made between the updates
        // above, busy without its activity or before the transcript's lines
        final InstancesState seen = main.window.recorded.await(Intent.InstancesChanged.class,
                i -> i.instances().others().stream().anyMatch(o -> o.host() && o.sessions().stream()
                        .anyMatch(m -> m.busy() && "running Bash".equals(m.activity()) && !m.reply().isEmpty())), WAIT)
                .instances();
        final InstancesState.Member work = seen.others().getFirst().sessions().getFirst();
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
    /// An `instances-seen.json` of a later version is kept as it is, and the user is told once, naming the file, both
    /// versions and that nothing is saved to it (record 133); the instances seen before are not listed.
    @Test
    void aLaterInstancesSeenFileIsKeptAndTheUserIsTold() throws Exception {
        final Node first = node("", Peers.ANSWER_WITHIN);
        first.peers.close();
        final Path file = first.home.resolve(Peers.SEEN_FILE);
        Files.writeString(file, "{\"version\": 2, \"instances\": [{\"slug\": \"b\"}]}");
        final Node later = node("", Peers.ANSWER_WITHIN);
        assertThat(later.window.recorded.of(Intent.Notify.class))
                .anyMatch(n -> n.text().startsWith("instances-seen.json is of version 2, written by a later comando; this one reads version 1"));
        assertThat(later.peers.instances().others()).isEmpty();
        later.peers.close();
        assertThat(Files.readString(file)).as("kept as it is").isEqualTo("{\"version\": 2, \"instances\": [{\"slug\": \"b\"}]}");
    }

    /// `instance.json`, its other keys kept, and goes out in the next presence.
    @Test
    void anInstanceThatLeftIsRememberedUntilForgottenAndTheRoleIsWrittenAndSent() throws Exception {
        final Node main = node("", Peers.ANSWER_WITHIN);
        final Node b = node("b", Peers.ANSWER_WITHIN);
        main.window.recorded.await(Intent.InstancesChanged.class, i -> i.instances().others().stream().anyMatch(o -> o.slug().equals("b")), WAIT);
        main.peers.forget("b");
        assertThat(main.window.recorded.of(Intent.Notify.class)).anyMatch(n -> n.text().contains("only an instance that left can be forgotten"));

        b.peers.close();
        final InstancesState.Instance left = main.window.recorded.await(Intent.InstancesChanged.class,
                i -> i.instances().others().stream().anyMatch(o -> !o.present()), WAIT).instances().others().getFirst();
        assertThat(left.slug()).isEqualTo("b");
        assertThat(left.lastSeen()).isPositive();
        assertThat(left.sessions()).isEmpty();
        final Path file = main.home.resolve(Peers.SEEN_FILE);
        assertThat(Json.readTree(Files.readString(file)).path("instances").get(0).path("slug").asString()).isEqualTo("b");

        main.peers.close();
        final Node again = node("", Peers.ANSWER_WITHIN);
        assertThat(again.peers.instances().others()).singleElement().satisfies(o -> {
            assertThat(o.slug()).isEqualTo("b");
            assertThat(o.present()).isFalse();
        });
        again.peers.forget("b");
        assertThat(again.peers.instances().others()).isEmpty();
        assertThat(Json.readTree(Files.readString(file)).path("instances").isEmpty()).isTrue();

        Files.writeString(again.home.resolve("instance.json"), "{\"name\": \"x\"}");
        final Node c = node("c", Peers.ANSWER_WITHIN);
        again.peers.role("comando specialist");
        final JsonNode written = Json.readTree(Files.readString(again.home.resolve("instance.json")));
        assertThat(written.path("name").asString()).isEqualTo("x");
        assertThat(written.path("role").asString()).isEqualTo("comando specialist");
        c.window.recorded.await(Intent.InstancesChanged.class,
                i -> i.instances().others().stream().anyMatch(o -> o.slug().equals("main") && o.role().equals("comando specialist")), WAIT);
    }

    /// Every question is in the state on both sides: the asker's, waiting, then answered with the reply's text; the
    /// answering instance's, naming the session that answered it. A question asked from the tab has no session.
    @Test
    void theQuestionsAskedAndAnsweredAreKeptWithTheirReplies() throws Exception {
        final Node main = node("", Peers.ANSWER_WITHIN);
        final Node b = node("b", Peers.ANSWER_WITHIN);
        main.peers.ask("b", "what is X?");
        b.window.recorded.await(Intent.PromptSession.class, WAIT);
        final InstancesState waiting = main.window.recorded.await(Intent.InstancesChanged.class, i -> !i.instances().consults().isEmpty(), WAIT).instances();
        assertThat(waiting.consults()).singleElement().satisfies(c -> {
            assertThat(c.direction()).isEqualTo(InstancesState.Direction.ASKED);
            assertThat(c.status()).isEqualTo(InstancesState.Status.WAITING);
            assertThat(c.session()).as("asked from the tab").isEmpty();
            assertThat(c.question()).isEqualTo("what is X?");
        });
        assertThat(b.window.recorded.of(Intent.PromptSession.class).getFirst().text()).as("no session in the header")
                .isEqualTo("[from instance comando · overseer]\nwhat is X?");

        b.window.turn("consult-main", "X is a broker.");
        final InstancesState.Consult asked = main.window.recorded.await(Intent.InstancesChanged.class,
                i -> i.instances().consults().stream().anyMatch(c -> c.status() == InstancesState.Status.OK), WAIT).instances().consults().getFirst();
        assertThat(asked.reply()).isEqualTo("X is a broker.");
        assertThat(asked.endedAt()).isGreaterThanOrEqualTo(asked.askedAt());
        final InstancesState.Consult answered = b.window.recorded.await(Intent.InstancesChanged.class,
                i -> i.instances().consults().stream().anyMatch(c -> c.status() == InstancesState.Status.OK), WAIT).instances().consults().getFirst();
        assertThat(answered.direction()).isEqualTo(InstancesState.Direction.ANSWERED);
        assertThat(answered.session()).isEqualTo("consult-main");
        // the tab's notice comes from its errand after the ask returns, so after the state already says OK: waited for
        main.window.recorded.await(Intent.Notify.class, n -> n.text().startsWith("reply from comando · b"), WAIT);
    }

    /// Refusing from the tab writes `agents.json`'s `peers.deny`, keeping its grants; the next question from that
    /// instance is refused, and the state lists it as refused until the user takes it back.
    @Test
    void refusingAnInstanceWritesAgentsJsonKeepsTheGrantsAndRefusesItsQuestions() throws Exception {
        final Node main = node("", Peers.ANSWER_WITHIN);
        final Node b = node("b", Peers.ANSWER_WITHIN);
        Files.writeString(b.agents, "{\"grants\": {\"foreman\": [\"destructive\"]}}");
        b.peers.deny("main", true);
        final JsonNode written = Json.readTree(Files.readString(b.agents));
        assertThat(written.path("grants").path("foreman").get(0).asString()).isEqualTo("destructive");
        assertThat(written.path("peers").path("deny").get(0).asString()).isEqualTo("main");
        b.window.recorded.await(Intent.InstancesChanged.class, i -> i.instances().denied().contains("main"), WAIT);
        assertThat(call(main, "instance_ask", "{\"instance\":\"b\",\"text\":\"what is X?\",\"wait_seconds\":10}").text()).startsWith("refused:");

        b.peers.deny("main", false);
        assertThat(Json.readTree(Files.readString(b.agents)).path("peers").path("deny").isEmpty()).isTrue();
        b.window.recorded.await(Intent.InstancesChanged.class, i -> i.instances().denied().isEmpty(), WAIT);
    }

    /// A question naming a session the peer lacks makes it there, created by the asker's peer in the default folder,
    /// and prompts it headed with who asks; the reply is that session's turn, and the answering side keeps the
    /// question under that session.
    @Test
    void aNamedSessionThePeerLacksIsMadeThereAndItsTurnAnswers() throws Exception {
        final Node main = node("", Peers.ANSWER_WITHIN);
        final Node b = node("b", Peers.ANSWER_WITHIN);
        final CompletableFuture<McpTools.Outcome> asking = async(main, "foreman", "instance_ask",
                "{\"instance\":\"b\",\"session\":\"Comando G\",\"text\":\"say hello\",\"wait_seconds\":30}");
        final Intent.PromptSession prompt = b.window.recorded.await(Intent.PromptSession.class, WAIT);
        assertThat(prompt.session()).isEqualTo("Comando G");
        assertThat(prompt.text()).isEqualTo("[from instance comando · overseer · session foreman]\nsay hello");
        assertThat(prompt.from()).isEqualTo("peer:main");
        assertThat(b.window.recorded.of(Intent.SaveSession.class)).singleElement().satisfies(save -> {
            assertThat(save.spec().name()).isEqualTo("Comando G");
            assertThat(save.spec().createdBy()).isEqualTo("peer:main");
            assertThat(save.spec().workDir()).isEqualTo(dir.resolve("b").toString());
        });

        b.window.turn("Comando G", "hello");
        final McpTools.Outcome reply = asking.get(WAIT.toSeconds(), TimeUnit.SECONDS);
        assertThat(reply.error()).as(reply.text()).isFalse();
        assertThat(reply.text()).startsWith("reply from comando · b (its session Comando G):").contains("hello")
                .doesNotContain("does not take a session name");
        b.window.recorded.await(Intent.InstancesChanged.class, i -> i.instances().consults().stream()
                .anyMatch(c -> c.session().equals("Comando G") && c.status() == InstancesState.Status.OK), WAIT);
    }

    /// A question naming a session the peer has prompts that session, in its own panel, and makes none; a name the
    /// peer's reducer refuses for a new session (the title of another session's panel) is refused with its reason, and
    /// nothing is prompted.
    @Test
    void aNamedSessionThePeerHasIsPromptedAndARefusedNameIsSaid() throws Exception {
        final Node main = node("", Peers.ANSWER_WITHIN);
        final Node b = node("b", Peers.ANSWER_WITHIN);
        final CompletableFuture<McpTools.Outcome> asking = async(main, "foreman", "instance_ask",
                "{\"instance\":\"b\",\"session\":\"work\",\"text\":\"status?\",\"wait_seconds\":30}");
        final Intent.PromptSession prompt = b.window.recorded.await(Intent.PromptSession.class, WAIT);
        assertThat(prompt.session()).isEqualTo("work");
        b.window.turn("work", "all green");
        assertThat(asking.get(WAIT.toSeconds(), TimeUnit.SECONDS).text())
                .startsWith("reply from comando · b (its session work):").contains("all green");
        assertThat(b.window.recorded.of(Intent.SaveSession.class)).isEmpty();
        assertThat(b.window.recorded.of(Intent.OpenSession.class)).as("work has its panel").isEmpty();

        final McpTools.Outcome refused = call(main, "instance_ask",
                "{\"instance\":\"b\",\"session\":\"one\",\"text\":\"hi\",\"wait_seconds\":10}");
        assertThat(refused.error()).isTrue();
        assertThat(refused.text()).contains("session one cannot be made in comando · b", "A panel called one exists");
        assertThat(b.window.recorded.of(Intent.PromptSession.class)).hasSize(1);
    }

    /// A peer from before session names drops the question's `to` and answers from the asker's consult session: the
    /// answer says which session answered instead. Its presence names its link to frontear with the older key `edge`,
    /// still read; this instance's own presence writes `host`, and `edge` beside it with the same value for that peer.
    @Test
    void anOlderPeerAnswersFromTheConsultSessionAndTheAnswerSaysSo() throws Exception {
        final Node main = node("", Peers.ANSWER_WITHIN);
        final List<JsonNode> questions = new CopyOnWriteArrayList<>();
        final Link old = new Link(bus);
        old.subscribe(Peers.inboxTopic("old"), m -> {
            questions.add(Json.readTree(m.payload()));
            final String r = "{\"schema\":1,\"status\":\"ok\",\"text\":\"hello\",\"instance\":\"comando · old\","
                    + "\"session\":\"consult-main\"}";
            old.publish(m.responseTopic(), r.getBytes(StandardCharsets.UTF_8), false, null, m.correlation());
        });
        old.connect();
        final String presence = "{\"schema\":1,\"name\":\"comando · old\",\"slug\":\"old\",\"version\":\"1.9.0\","
                + "\"edge\":true,\"sessions\":[]}";
        bus.publish(Peers.presenceTopic("old"), presence.getBytes(StandardCharsets.UTF_8), true, null, null);
        main.window.recorded.await(Intent.InstancesChanged.class,
                i -> i.instances().others().stream().anyMatch(o -> o.slug().equals("old") && o.host()), WAIT);

        final McpTools.Outcome reply = call(main, "instance_ask",
                "{\"instance\":\"old\",\"session\":\"Comando G\",\"text\":\"say hello\",\"wait_seconds\":10}");
        assertThat(questions).singleElement().satisfies(q -> {
            assertThat(q.path("to").asString()).isEqualTo("Comando G");
            assertThat(q.path("session").asString()).as("the asker's session, as before").isEqualTo("foreman");
        });
        assertThat(reply.error()).as(reply.text()).isFalse();
        assertThat(reply.text()).startsWith("reply from comando · old (its session consult-main) (comando · old "
                + "runs a comando that does not take a session name, so its session consult-main answered, not "
                + "Comando G):")
                .contains("hello");

        assertThat(call(main, "instance_ask", "{\"instance\":\"old\",\"text\":\"and you?\",\"wait_seconds\":10}")
                .text())
                .as("no session named, nothing to say").doesNotContain("does not take a session name");
        assertThat(questions.getLast().has("to")).as("no session named, no to").isFalse();
        final JsonNode own = Json.readTree(bus.retained.get(Peers.presenceTopic("main")));
        assertThat(own.has("host")).isTrue();
        assertThat(own.has("edge")).as("for a peer from before host").isTrue();
        assertThat(own.path("edge").asBoolean()).isEqualTo(own.path("host").asBoolean());
    }

    /// A session waiting for its own `instance_ask` cannot take a question until that wait ends: a question to it is
    /// refused at once, not after both waits ran out, and nothing is prompted; once its reply came it takes questions
    /// again. A surrogate's header carries no overseer mark.
    @Test
    void aSessionWaitingOnItsOwnAskIsRefusedAtOnce() throws Exception {
        final Node main = node("", Peers.ANSWER_WITHIN);
        final Node b = node("b", Peers.ANSWER_WITHIN);
        final CompletableFuture<McpTools.Outcome> bAsks = async(b, "work", "instance_ask",
                "{\"instance\":\"main\",\"text\":\"what is X?\",\"wait_seconds\":30}");
        final Intent.PromptSession toMain = main.window.recorded.await(Intent.PromptSession.class, WAIT);
        assertThat(toMain.session()).isEqualTo("consult-b");
        assertThat(toMain.text()).isEqualTo("[from instance comando · b · session work]\nwhat is X?");

        final long before = System.nanoTime();
        final McpTools.Outcome refused = call(main, "instance_ask",
                "{\"instance\":\"b\",\"session\":\"work\",\"text\":\"and you?\",\"wait_seconds\":30}");
        assertThat(Duration.ofNanos(System.nanoTime() - before)).as("no wait").isLessThan(WAIT);
        assertThat(refused.error()).isTrue();
        assertThat(refused.text()).contains("session work of comando · b is waiting for a reply of its own");
        assertThat(b.window.recorded.of(Intent.PromptSession.class)).isEmpty();

        main.window.turn("consult-b", "X is a broker.");
        assertThat(bAsks.get(WAIT.toSeconds(), TimeUnit.SECONDS).text()).contains("X is a broker.");
        final CompletableFuture<McpTools.Outcome> again = async(main, "foreman", "instance_ask",
                "{\"instance\":\"b\",\"session\":\"work\",\"text\":\"and you?\",\"wait_seconds\":30}");
        b.window.recorded.await(Intent.PromptSession.class, p -> p.session().equals("work"), WAIT);
        b.window.turn("work", "fine");
        assertThat(again.get(WAIT.toSeconds(), TimeUnit.SECONDS).text()).contains("fine");
    }

    /// An instance whose name starts like the main instance's id is not main: its header names it, with no overseer
    /// mark.
    @Test
    void aNameLikeMainsIsHeadedWithoutTheOverseerMark() throws Exception {
        final Node surrogate = node("mainframe", Peers.ANSWER_WITHIN);
        final Node b = node("b", Peers.ANSWER_WITHIN);
        final McpTools.Outcome out = call(surrogate, "instance_ask",
                "{\"instance\":\"b\",\"text\":\"who?\",\"wait_seconds\":0}");
        assertThat(out.error()).as(out.text()).isFalse();
        assertThat(b.window.recorded.await(Intent.PromptSession.class, WAIT).text())
                .isEqualTo("[from instance comando · mainframe · session foreman]\nwho?");
    }

    /// The mark a session's own `instance_ask` sets is cleared however the ask ends: its wait running out, the
    /// question not sent, an interrupt while it waits, and a refusal; after each, a question to that session is
    /// prompted there.
    @Test
    void theWaitingMarkOfAnAskClearsAfterATimeoutAnErrorAndAnInterrupt() throws Exception {
        final Node main = node("", Peers.ANSWER_WITHIN);
        final Node b = node("b", Peers.ANSWER_WITHIN);
        final McpTools.Outcome waited = call(b, "work", "instance_ask",
                "{\"instance\":\"main\",\"text\":\"one?\",\"wait_seconds\":0}");
        assertThat(waited.error()).as(waited.text()).isFalse();
        assertThat(waited.text()).contains("no reply from comando yet");
        assertPrompted(main, b, "probe after a timeout");

        b.link.failing = true;
        final McpTools.Outcome unsent = call(b, "work", "instance_ask",
                "{\"instance\":\"main\",\"text\":\"two?\",\"wait_seconds\":10}");
        b.link.failing = false;
        assertThat(unsent.error()).isTrue();
        assertThat(unsent.text()).contains("the question did not reach comando");
        assertPrompted(main, b, "probe after an unsent question");

        final McpTools.Outcome interrupted = interrupted(b, "instance_ask",
                "{\"instance\":\"main\",\"text\":\"three?\",\"wait_seconds\":30}");
        assertThat(interrupted.error()).isTrue();
        assertThat(interrupted.text()).contains("interrupted");
        assertPrompted(main, b, "probe after an interrupt");

        Files.writeString(main.agents, "{\"peers\": {\"deny\": [\"b\"]}}");
        final McpTools.Outcome refused = call(b, "work", "instance_ask",
                "{\"instance\":\"main\",\"text\":\"four?\",\"wait_seconds\":10}");
        assertThat(refused.error()).isTrue();
        assertThat(refused.text()).startsWith("refused:");
        assertPrompted(main, b, "probe after a refusal");
    }

    /// The mark `instance_reply` sets while it waits is cleared however that wait ends: its wait running out, an
    /// interrupt, and an error reply; after each, a question to that session is prompted there.
    @Test
    void theWaitingMarkOfAnInstanceReplyClearsAfterATimeoutAnErrorAndAnInterrupt() throws Exception {
        final Node main = node("", Peers.ANSWER_WITHIN);
        final Node b = node("b", Peers.ANSWER_WITHIN);
        final McpTools.Outcome asked = call(b, "work", "instance_ask",
                "{\"instance\":\"main\",\"text\":\"one?\",\"wait_seconds\":0}");
        final Matcher id = ID.matcher(asked.text());
        assertThat(id.find()).as(asked.text()).isTrue();
        final String read = "{\"id\":\"" + id.group(1) + "\",\"wait_seconds\":";

        final McpTools.Outcome waited = call(b, "work", "instance_reply", read + "0}");
        assertThat(waited.error()).as(waited.text()).isFalse();
        assertThat(waited.text()).contains("no reply from comando yet");
        assertPrompted(main, b, "probe after a timeout");

        final McpTools.Outcome interrupted = interrupted(b, "instance_reply", read + "30}");
        assertThat(interrupted.error()).isTrue();
        assertThat(interrupted.text()).contains("interrupted");
        assertPrompted(main, b, "probe after an interrupt");

        main.window.recorded.await(Intent.PromptSession.class, x -> x.text().endsWith("one?"), WAIT);
        main.window.failTurn("consult-b");
        final McpTools.Outcome failed = call(b, "work", "instance_reply", read + "10}");
        assertThat(failed.error()).isTrue();
        assertThat(failed.text()).contains("could not answer", "the turn failed");
        assertPrompted(main, b, "probe after an error");
    }

    /// Asks `to`'s session `work` from `from` and waits until that session is prompted with `text`: a session still
    /// marked waiting for a reply of its own is refused and never prompted.
    private void assertPrompted(final Node from, final Node to, final String text) throws Exception {
        final McpTools.Outcome out = call(from, "instance_ask",
                "{\"instance\":\"b\",\"session\":\"work\",\"text\":\"" + text + "\",\"wait_seconds\":0}");
        assertThat(out.error()).as(out.text()).isFalse();
        to.window.recorded.await(Intent.PromptSession.class,
                x -> x.session().equals("work") && x.text().endsWith(text), WAIT);
    }

    /// Calls `tool` as `b`'s session `work` on a thread of its own, interrupts it once it waits, and returns what the
    /// call returned.
    private McpTools.Outcome interrupted(final Node n, final String tool, final String args) throws Exception {
        final AtomicReference<McpTools.Outcome> out = new AtomicReference<>();
        final Thread caller = Thread.ofVirtual().name("peers-test-caller").start(() -> {
            try {
                out.set(call(n, "work", tool, args));
            } catch (final Exception e) {
                throw new IllegalStateException(e);
            }
        });
        Awaitility.await().atMost(WAIT).until(() -> caller.getState() == Thread.State.TIMED_WAITING);
        caller.interrupt();
        assertThat(caller.join(WAIT)).as("the call returned").isTrue();
        assertThat(out.get()).as("the call's outcome").isNotNull();
        return out.get();
    }

    private McpTools.Outcome call(final Node n, final String tool, final String args) throws Exception {
        return call(n, "foreman", tool, args);
    }

    private McpTools.Outcome call(final Node n, final String session, final String tool, final String args) throws Exception {
        return n.tools.call(session, tool, Json.readTree(args));
    }

    private CompletableFuture<McpTools.Outcome> async(final Node n, final String tool, final String args) {
        return async(n, "foreman", tool, args);
    }

    private CompletableFuture<McpTools.Outcome> async(
            final Node n,
            final String session,
            final String tool,
            final String args
    ) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return call(n, session, tool, args);
            } catch (final Exception e) {
                throw new IllegalStateException(e);
            }
        }, callers);
    }

    private Node node(final String name, final Duration answerWithin) throws Exception {
        return node(name, "", answerWithin);
    }

    private Node node(final String name, final String role, final Duration answerWithin) throws Exception {
        return node(name, role, answerWithin, answerWithin.plusSeconds(40));
    }

    private Node node(final String name, final String role, final Duration answerWithin, final Duration expireWithin) throws Exception {
        final Path home = Files.createDirectories(dir.resolve(name.isEmpty() ? "main" : name));
        final Window window = new Window();
        final Link link = new Link(bus);
        final Peers peers = new Peers(link, Peers.Me.of(name, "2.0.0", role), window, window.state::get, window.transcripts, home.toString(),
                home.resolve("agents.json"), answerWithin, expireWithin);
        peers.dataRoot(home);
        final McpTools tools = new McpTools(window, window.state::get, window.transcripts, null, AuditLog.none(), new FakeClock(), Duration.ofMinutes(1));
        tools.peers(peers);
        peers.start();
        link.connect();
        // the connect's own presence goes out on the ticks' strand; the test's sends it now, so the others see it at once
        peers.tick();
        final Node n = new Node(window, link, peers, tools, home.resolve("agents.json"), home);
        nodes.add(n);
        return n;
    }

    private static AppState withSession(final AppState s, final SessionSpec spec) {
        final List<SessionState> sessions = new ArrayList<>(s.sessions());
        sessions.add(SessionState.closed(spec));
        final List<PanelState> panels = new ArrayList<>(s.panels());
        // a session comes with its own panel (record 88)
        panels.add(new PanelState(PanelId.random(), spec.name(), "", spec.name()));
        return s.withSessions(sessions).withPanels(panels, s.focused());
    }

    private static AppState turns(final AppState s, final String session, final int delta) {
        final List<SessionState> sessions = new ArrayList<>();
        for (final SessionState x : s.sessions()) {
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
        public void dispatch(final Intent intent) {
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
        void turn(final String session, final String... texts) {
            final Transcript t = transcripts.of(session);
            for (int i = 0; i < texts.length; i++) {
                t.append(new Block.AssistantText("m" + t.size(), 0, texts[i], false, ""));
                if (i == 0) {
                    t.append(new Block.ToolCall("t" + t.size(), "Bash", "{}", "tool output", Block.ToolStatus.DONE, ""));
                }
            }
            t.append(new Block.TurnEnd(0.0, 10, 1, false, ""));
            state.updateAndGet(s -> turns(s, session, -1));
        }

        /** Ends the oldest open turn of {@code session} as failed. */
        void failTurn(final String session) {
            final Transcript t = transcripts.of(session);
            t.append(new Block.AssistantText("m" + t.size(), 0, "it broke", false, ""));
            t.append(new Block.TurnEnd(0.0, 10, 1, true, ""));
            state.updateAndGet(s -> turns(s, session, -1));
        }
    }

    /** A broker in memory: retained messages, last wills, delivery to each matching subscription of a live link. */
    private static final class Bus {
        final Map<String, byte[]> retained = new LinkedHashMap<>();
        final List<Link> links = new CopyOnWriteArrayList<>();

        void publish(final String topic, final byte[] payload, final boolean retain, final String responseTopic, final byte[] correlation) {
            if (retain) {
                synchronized (retained) {
                    if (payload.length == 0) {
                        retained.remove(topic);
                    } else {
                        retained.put(topic, payload);
                    }
                }
            }
            for (final Link l : links) {
                l.deliver(new PeerBroker.Message(topic, payload, responseTopic, correlation));
            }
        }

        List<PeerBroker.Message> retained(final String filter) {
            synchronized (retained) {
                return retained.entrySet().stream().filter(e -> matches(filter, e.getKey()))
                        .map(e -> new PeerBroker.Message(e.getKey(), e.getValue(), null, null)).toList();
            }
        }

        static boolean matches(final String filter, final String topic) {
            final String[] f = filter.split("/");
            final String[] t = topic.split("/");
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
        /** Set by a test, read by the asking thread: a publish then fails while the link stays connected. */
        volatile boolean failing;
        private Message will;

        Link(final Bus bus) {
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

        void deliver(final Message m) {
            if (!up) {
                return;
            }
            final List<Consumer<Message>> handlers = new ArrayList<>();
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
        public void onConnected(final Runnable listener) {
            listeners.add(listener);
        }

        @Override
        public void will(final String topic, final byte[] payload, final boolean retained) {
            will = new Message(topic, payload, null, null);
        }

        @Override
        public CompletableFuture<Void> publish(final String topic, final byte[] payload, final boolean retained, final String responseTopic, final byte[] correlation) {
            if (!up) {
                return CompletableFuture.failedFuture(new IllegalStateException("not connected"));
            }
            if (failing) {
                return CompletableFuture.failedFuture(new IllegalStateException("the broker refused it"));
            }
            bus.publish(topic, payload, retained, responseTopic, correlation);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void subscribe(final String filter, final Consumer<Message> handler) {
            synchronized (subscriptions) {
                subscriptions.put(filter, handler);
            }
            if (up) {
                bus.retained(filter).forEach(handler);
            }
        }
    }
}
