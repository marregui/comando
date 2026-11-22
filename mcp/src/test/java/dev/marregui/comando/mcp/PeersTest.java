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
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.marregui.comando.core.intent.Intent;
import dev.marregui.comando.core.json.Json;
import dev.marregui.comando.core.json.JsonNode;
import dev.marregui.comando.core.model.InstanceName;
import dev.marregui.comando.core.model.PanelId;
import dev.marregui.comando.core.model.SessionSpec;
import dev.marregui.comando.core.model.Status;
import dev.marregui.comando.core.port.IntentSink;
import dev.marregui.comando.core.port.MachinesPort;
import dev.marregui.comando.core.state.AppState;
import dev.marregui.comando.core.state.InstancesState;
import dev.marregui.comando.core.state.PanelState;
import dev.marregui.comando.core.state.SessionInfo;
import dev.marregui.comando.core.state.SessionState;
import dev.marregui.comando.core.transcript.Block;
import dev.marregui.comando.core.transcript.Transcript;
import dev.marregui.comando.core.transcript.TranscriptRegistry;
import dev.marregui.comando.testkit.FakeClock;
import dev.marregui.comando.testkit.RecordingSink;
import dev.marregui.comando.testkit.States;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.awaitility.Awaitility.await;

/// Instances consulting each other through a gateway (record 123), with a gateway in memory between fake instances:
/// each with its own state, transcripts and data root, and a window that applies the intents a test needs.
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

        b.window.turn("consult-main", "X is a link.", "Done.");
        final McpTools.Outcome reply = asking.get(WAIT.toSeconds(), TimeUnit.SECONDS);
        assertThat(reply.error()).as(reply.text()).isFalse();
        assertThat(reply.text()).startsWith("reply from comando · b (its session consult-main):")
                .contains("X is a link.\n\nDone.")
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
        b.window.turn("consult-main", "X is a link.");
        final McpTools.Outcome reply = call(main, "instance_reply", "{\"id\":\"" + id.group(1) + "\",\"wait_seconds\":10}");
        assertThat(reply.error()).as(reply.text()).isFalse();
        assertThat(reply.text()).contains("X is a link.");
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
        b.window.transcripts.of("work").append(new Block.AssistantText("m1", 0, "X is a link.\nDetails.", false, ""));
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
        assertThat(work.reply()).isEqualTo("X is a link.");
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

    /// A peer whose process is gone, its presence left retained on a node, is dropped when that presence is of the
    /// same process, and remembered as last seen; one of another start stays, and so does this instance itself. Its
    /// next tick, unchanged, sends nothing; a resend sends it again, and it is present once more.
    @Test
    void aGonePeerIsDroppedOnlyWhenItsStartMatchesAndAResendBringsItBack() throws Exception {
        final Node main = node("", Peers.ANSWER_WITHIN);
        final Node b = node("b", Peers.ANSWER_WITHIN);
        assertThat(present(main)).containsExactly("b");

        main.peers.gone("b", "2000-01-01T00:00:00Z");
        main.peers.gone("main", Peers.PROCESS_STARTED);

        assertThat(present(main)).as("another start's presence stays").containsExactly("b");
        assertThat(call(main, "instances_list", "{}").text()).contains("this one is comando (instance main)");

        main.peers.gone("b", Peers.PROCESS_STARTED);

        assertThat(present(main)).as("the same start's presence goes").isEmpty();
        assertThat(main.peers.instances().others()).singleElement().satisfies(o -> {
            assertThat(o.slug()).isEqualTo("b");
            assertThat(o.lastSeen()).isPositive();
        });
        b.peers.tick();
        assertThat(present(main)).as("an unchanged presence is not sent again").isEmpty();

        b.peers.resendPresence();

        Awaitility.await().atMost(WAIT).until(() -> present(main).equals(List.of("b")));
    }

    /// Forgetting an instance that left tells the octopus, so its link and registry entry go too; an instance present
    /// is not forgotten, and the octopus is not told.
    @Test
    void forgettingAnInstanceTellsTheOctopus() throws Exception {
        final Node main = node("", Peers.ANSWER_WITHIN);
        final Node b = node("b", Peers.ANSWER_WITHIN);
        final List<String> forgotten = new CopyOnWriteArrayList<>();
        main.peers.onForget(forgotten::add);

        main.peers.forget("b");
        assertThat(forgotten).as("b is present").isEmpty();

        b.peers.close();
        Awaitility.await().atMost(WAIT).until(() -> present(main).isEmpty());
        main.peers.forget("b");

        assertThat(forgotten).containsExactly("b");
        assertThat(main.peers.instances().others()).isEmpty();
    }

    /// An instance whose presence first comes has its link put at once, on an errand, not at the octopus's next look; a
    /// presence of it again puts nothing, and this instance's own never does. A question to it puts the link first, on
    /// the asker's thread, before the question leaves.
    @Test
    void aPeerFirstSeenAndEveryQuestionToItHaveTheLinkPutFirst() throws Exception {
        final Node main = node("", Peers.ANSWER_WITHIN);
        final List<String> linked = new CopyOnWriteArrayList<>();
        final List<Boolean> beforeTheQuestion = new CopyOnWriteArrayList<>();
        final AtomicReference<Node> b = new AtomicReference<>();
        main.peers.linkTo(slug -> {
            final Node asked = b.get();
            if (asked != null) {
                beforeTheQuestion.add(asked.window.recorded.of(Intent.PromptSession.class).isEmpty());
            }
            linked.add(slug);
        });
        b.set(node("b", Peers.ANSWER_WITHIN));
        Awaitility.await().atMost(WAIT).until(() -> linked.equals(List.of("b")));

        b.get().peers.resendPresence();
        b.get().peers.tick();
        main.peers.resendPresence();
        main.peers.tick();
        Awaitility.await().during(Duration.ofMillis(300)).atMost(WAIT).until(() -> linked.equals(List.of("b")));

        beforeTheQuestion.clear();
        final McpTools.Outcome asked = call(main, "foreman", "instance_ask",
                "{\"instance\":\"b\",\"text\":\"what is X?\",\"wait_seconds\":0}");
        assertThat(asked.error()).as(asked.text()).isFalse();
        assertThat(linked).containsExactly("b", "b");
        assertThat(beforeTheQuestion).as("put before the question reached b").containsExactly(true);
    }

    /// The slugs of the instances `n` holds present, in its order.
    private static List<String> present(final Node n) {
        return n.peers.instances().others().stream().filter(InstancesState.Instance::present)
                .map(InstancesState.Instance::slug).toList();
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

        b.window.turn("consult-main", "X is a link.");
        final InstancesState.Consult asked = main.window.recorded.await(Intent.InstancesChanged.class,
                i -> i.instances().consults().stream().anyMatch(c -> c.status() == InstancesState.Status.OK), WAIT).instances().consults().getFirst();
        assertThat(asked.reply()).isEqualTo("X is a link.");
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

        main.window.turn("consult-b", "X is a link.");
        assertThat(bAsks.get(WAIT.toSeconds(), TimeUnit.SECONDS).text()).contains("X is a link.");
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

    /// A presence on another machine's topic is taken only with that machine's origin: one stamped with a third
    /// machine's, or with none, lists no instance.
    @Test
    void aPresenceFromAnotherMachineThanItsTopicsIsDropped() throws Exception {
        final Bus a = new Bus("a");
        final Node mainA = node(a, "", _ -> null);
        final byte[] p = "{\"schema\":1,\"name\":\"comando · z\",\"slug\":\"z\",\"version\":\"2.0.0\",\"sessions\":[]}"
                .getBytes(StandardCharsets.UTF_8);
        a.deliver(new PeerGateway.Message(Peers.presenceTopic("b", "z"), p, null, null, "@c"), false);
        a.deliver(new PeerGateway.Message(Peers.presenceTopic("b", "z"), p, null, null), false);
        assertThat(call(mainA, "instances_list", "{}").text()).doesNotContain("z@b");
        a.deliver(new PeerGateway.Message(Peers.presenceTopic("b", "z"), p, null, null, "@b"), false);
        assertThat(call(mainA, "instances_list", "{}").text()).contains("(instance z@b)");
    }

    /// A question from a machine paired as a peer (record 189) is headed with its asker's id, `<slug>@<machine>`, and
    /// the peer's mark, its main's too, and the consult session it makes runs in plan mode; the reply, stamped with
    /// the asked machine's origin, reaches the asker.
    @Test
    void aPeerMachinesQuestionIsHeadedAsAPeerAndAnsweredInPlanMode() throws Exception {
        final Bus a = new Bus("a");
        final Bus b = new Bus("b");
        Bus.cross(a, b);
        final Node x = node(a, "x", _ -> null);
        final Node mainA = node(a, "", _ -> null);
        final Node w = node(b, "w", m -> m.equals("a") ? MachinesPort.Grant.PEER : null);
        final CompletableFuture<McpTools.Outcome> asked = async(x, "instance_ask",
                "{\"instance\":\"w@b\",\"text\":\"what is X?\",\"wait_seconds\":30}");
        final Intent.PromptSession p = w.window.recorded.await(Intent.PromptSession.class, WAIT);
        assertThat(p.session()).isEqualTo("consult-x@a");
        assertThat(p.text()).isEqualTo("[from instance x@a · peer · session foreman]\nwhat is X?");
        assertThat(made(w, "consult-x@a").planMode()).isTrue();
        w.window.turn("consult-x@a", "X is a link.");
        assertThat(asked.get(WAIT.toSeconds(), TimeUnit.SECONDS).text()).contains("X is a link.");

        final McpTools.Outcome fromMain = call(mainA, "instance_ask",
                "{\"instance\":\"w@b\",\"text\":\"and Y?\",\"wait_seconds\":0}");
        assertThat(fromMain.error()).as(fromMain.text()).isFalse();
        assertThat(w.window.recorded.await(Intent.PromptSession.class, q -> q.text().endsWith("and Y?"), WAIT).text())
                .isEqualTo("[from instance main@a · peer · session foreman]\nand Y?");
        assertThat(made(w, "consult-main@a").planMode()).as("a peer machine's main plans too").isTrue();
    }

    /// A machine granted the overseer's authority has its main's question headed with the overseer's mark and
    /// answered in a session of the normal mode; its other instances stay peers that plan.
    @Test
    void anOverseerMachinesMainIsHeadedAsTheOverseerAndAnsweredInTheNormalMode() throws Exception {
        final Bus a = new Bus("a");
        final Bus b = new Bus("b");
        Bus.cross(a, b);
        final Node mainA = node(a, "", _ -> null);
        final Node x = node(a, "x", _ -> null);
        final Node w = node(b, "w", m -> m.equals("a") ? MachinesPort.Grant.OVERSEER : null);
        assertThat(call(mainA, "instance_ask", "{\"instance\":\"w@b\",\"text\":\"do X\",\"wait_seconds\":0}").error())
                .isFalse();
        assertThat(w.window.recorded.await(Intent.PromptSession.class, WAIT).text())
                .isEqualTo("[from instance main@a · overseer · session foreman]\ndo X");
        assertThat(made(w, "consult-main@a").planMode()).isFalse();

        assertThat(call(x, "instance_ask", "{\"instance\":\"w@b\",\"text\":\"do Y\",\"wait_seconds\":0}").error())
                .isFalse();
        assertThat(w.window.recorded.await(Intent.PromptSession.class, q -> q.text().endsWith("do Y"), WAIT).text())
                .isEqualTo("[from instance x@a · peer · session foreman]\ndo Y");
        assertThat(made(w, "consult-x@a").planMode()).isTrue();
    }

    /// A question from another machine is dropped, counted and never prompted when its `from` names an instance of
    /// another machine than its origin, its response topic is not its asker's, its machine is not paired, or it came
    /// on this machine's local inbox; the same question, well formed, is prompted.
    @Test
    void aSpoofedAskerAForeignResponseTopicAndNoGrantAreEachDropped() throws Exception {
        final Bus a = new Bus("a");
        final Bus b = new Bus("b");
        Bus.cross(a, b);
        final Node w = node(b, "w", m -> m.equals("a") ? MachinesPort.Grant.OVERSEER : null);
        final String inbox = Peers.inboxTopic("b", "w");
        question(b, inbox, "@a", "main@c", Peers.replyTopic("a", "main"), "q-1");
        assertThat(w.peers.dropped()).as("a from of another machine").isEqualTo(1);
        question(b, inbox, "@a", "main@a", Peers.replyTopic("a", "x"), "q-2");
        assertThat(w.peers.dropped()).as("another instance's response topic").isEqualTo(2);
        question(b, inbox, "@c", "main@c", Peers.replyTopic("c", "main"), "q-3");
        assertThat(w.peers.dropped()).as("a machine not paired").isEqualTo(3);
        question(b, Peers.inboxTopic("w"), "@a", "main@a", Peers.replyTopic("a", "main"), "q-4");
        assertThat(w.peers.dropped()).as("on the local inbox").isEqualTo(4);

        question(b, inbox, "@a", "main@a", Peers.replyTopic("a", "main"), "q-5");
        assertThat(w.window.recorded.await(Intent.PromptSession.class, WAIT).text())
                .startsWith("[from instance main@a · overseer");
        assertThat(w.window.recorded.of(Intent.PromptSession.class)).hasSize(1);
        assertThat(w.peers.dropped()).isEqualTo(4);
    }

    /// A reply to a question asked of another machine is taken only with that machine's origin: one stamped with a
    /// third machine's, or with none, is dropped, and the real reply is the one read.
    @Test
    void aReplyFromAnotherMachineThanTheOneAskedIsDropped() throws Exception {
        final Bus a = new Bus("a");
        final Bus b = new Bus("b");
        Bus.cross(a, b);
        final Node x = node(a, "x", _ -> null);
        final Node w = node(b, "w", m -> m.equals("a") ? MachinesPort.Grant.PEER : null);
        final McpTools.Outcome asked = call(x, "instance_ask",
                "{\"instance\":\"w@b\",\"text\":\"what is X?\",\"wait_seconds\":0}");
        final Matcher id = ID.matcher(asked.text());
        assertThat(id.find()).as(asked.text()).isTrue();
        final byte[] correlation = id.group(1).getBytes(StandardCharsets.UTF_8);
        final byte[] forged = ("{\"schema\":1,\"status\":\"ok\",\"text\":\"forged\",\"instance\":\"w@b\","
                + "\"session\":\"\"}").getBytes(StandardCharsets.UTF_8);
        a.deliver(new PeerGateway.Message(Peers.replyTopic("a", "x"), forged, null, correlation, "@c"), false);
        a.deliver(new PeerGateway.Message(Peers.replyTopic("a", "x"), forged, null, correlation), false);

        w.window.recorded.await(Intent.PromptSession.class, WAIT);
        w.window.turn("consult-x@a", "X is a link.");
        final McpTools.Outcome read = call(x, "instance_reply", "{\"id\":\"" + id.group(1) + "\",\"wait_seconds\":10}");
        assertThat(read.text()).contains("X is a link.").doesNotContain("forged");
    }

    /// A question of a machine with an id to an instance of the same machine goes as before: on the local topics,
    /// main headed as the overseer, its session in the normal mode; it expires with its asker's wait, as every question
    /// does, and its reply, with no origin, is taken.
    @Test
    void aLocalQuestionOfAMachineWithAnIdGoesAsBefore() throws Exception {
        final Bus a = new Bus("a");
        final Node mainA = node(a, "", _ -> null);
        final Node y = node(a, "y", _ -> null);
        final CompletableFuture<McpTools.Outcome> asked = async(mainA, "instance_ask",
                "{\"instance\":\"y\",\"text\":\"what is X?\",\"wait_seconds\":30}");
        assertThat(y.window.recorded.await(Intent.PromptSession.class, WAIT).text())
                .isEqualTo("[from instance comando · overseer · session foreman]\nwhat is X?");
        assertThat(made(y, "consult-main").planMode()).isFalse();
        final Sent q = mainA.link.sent.stream().filter(s -> s.message().topic().equals(Peers.inboxTopic("y")))
                .findFirst().orElseThrow();
        assertThat(q.message().responseTopic()).isEqualTo(Peers.replyTopic("main"));
        assertThat(q.expirySeconds()).isEqualTo(Peers.ANSWER_WITHIN.plusSeconds(40).toSeconds());
        y.window.turn("consult-main", "X is a link.");
        assertThat(asked.get(WAIT.toSeconds(), TimeUnit.SECONDS).text()).contains("X is a link.");
    }

    /// The presence another machine sees carries the name, slug, version, role, whether it is busy and the sessions'
    /// names only: no folder, branch, prompt, reply, task or kit, which this machine's presence carries; it expires
    /// with the gateway after 30 s, and this instance's close clears it on both machines.
    @Test
    void theCutPresenceCarriesNoFolderBranchPromptOrReplyAndIsClearedOnClose() throws Exception {
        final Bus a = new Bus("a");
        final Bus b = new Bus("b");
        Bus.cross(a, b);
        final Node x = node(a, "x", _ -> null);
        x.window.dispatch(new Intent.PromptSession("work", "a secret prompt", ""));
        x.window.turn("work", "a secret reply");
        x.peers.resendPresence();
        x.peers.tick();
        final String cut = Peers.presenceTopic("a", "x");
        final JsonNode full = Json.readTree(a.retained.get(Peers.presenceTopic("x")));
        assertThat(full.path("sessions").get(0).propertyNames()).as("this machine's")
                .contains("folder", "branch", "prompt", "reply", "tasks");
        final JsonNode p = Json.readTree(b.retained.get(cut));
        assertThat(p.propertyNames())
                .containsExactlyInAnyOrder("schema", "name", "slug", "version", "role", "busy", "sessions");
        assertThat(p.path("sessions").size()).isEqualTo(1);
        assertThat(p.path("sessions").get(0).propertyNames()).containsExactly("name");
        assertThat(p.path("sessions").get(0).path("name").asString()).isEqualTo("work");
        assertThat(b.retainedOrigins.get(cut)).isEqualTo("@a");
        assertThat(x.link.sent.stream().filter(s -> s.message().topic().equals(cut)).toList().getLast()
                .expirySeconds()).isEqualTo(Peers.REMOTE_EXPIRY.toSeconds());

        x.peers.close();
        assertThat(a.retained).doesNotContainKey(cut);
        assertThat(b.retained).doesNotContainKey(cut);
    }

    /// The cut presence goes out again unchanged once its resend interval less a tick has passed, so the machines
    /// it reaches see the instance present for as long as it runs; within that interval an unchanged one is not sent.
    @Test
    void anUnchangedCutPresenceIsSentAgainOnlyOnceItsIntervalHasPassed() throws Exception {
        final Bus a = new Bus("a");
        final Bus b = new Bus("b");
        Bus.cross(a, b);
        final String cut = Peers.presenceTopic("a", "x");
        final Node often = node(a, "x", "", Peers.ANSWER_WITHIN, Peers.ANSWER_WITHIN.plusSeconds(40),
                Peers.REMOTE_EXPIRY, Peers.PRESENCE_EVERY, _ -> null);
        // the connect queues a tick of its own on the ticks strand; the count is read once it has run
        final long before = settled(often, cut);
        often.peers.tick();
        often.peers.tick();
        assertThat(sent(often, cut)).as("each tick, at an interval of one tick").isEqualTo(before + 2);

        final Bus c = new Bus("c");
        final Bus d = new Bus("d");
        Bus.cross(c, d);
        final String seldom = Peers.presenceTopic("c", "y");
        final Node y = node(c, "y", _ -> null);
        final long first = settled(y, seldom);
        y.peers.tick();
        y.peers.tick();
        assertThat(sent(y, seldom)).as("none within the default interval").isEqualTo(first);
        assertThat(first).as("the connect's").isPositive();
    }

    /// How many messages `n` has published to `topic`, once the count has held for 300 ms: the ticks the connect
    /// queued have run by then (a tick takes well under a millisecond in this fake).
    private static long settled(final Node n, final String topic) {
        final AtomicLong last = new AtomicLong(-1L);
        await().atMost(Duration.ofSeconds(5)).pollInterval(Duration.ofMillis(300)).until(() -> {
            final long now = sent(n, topic);
            return now == last.getAndSet(now);
        });
        return last.get();
    }

    /// How many messages `n` has published to `topic`.
    private static long sent(final Node n, final String topic) {
        return n.link.sent.stream().filter(s -> s.message().topic().equals(topic)).count();
    }

    /// `instance_ask` names an instance of another machine by `<slug>@<machine>`, and by its bare slug when no instance
    /// of this machine has it and one machine's only does; a bare slug several machines have is refused with their ids,
    /// and one an instance of this machine has names that one.
    @Test
    void anAskResolvesAnInstanceOfAnotherMachineByIdOrBareSlugAndRefusesAnAmbiguousOne() throws Exception {
        final Bus a = new Bus("a");
        final Bus b = new Bus("b");
        final Bus c = new Bus("c");
        Bus.cross(a, b, c);
        final Function<String, MachinesPort.Grant> peer = m -> m.equals("a") ? MachinesPort.Grant.PEER : null;
        final Node mainA = node(a, "", _ -> null);
        final Node xb = node(b, "x", peer);
        final Node yb = node(b, "y", peer);
        final Node xc = node(c, "x", peer);
        assertThat(call(mainA, "instances_list", "{}").text())
                .contains("(instance x@b)", "(instance x@c)", "(instance y@b)", "<slug>@<machine>");

        assertThat(call(mainA, "instance_ask", "{\"instance\":\"y\",\"text\":\"one\",\"wait_seconds\":0}").error())
                .isFalse();
        assertThat(yb.window.recorded.await(Intent.PromptSession.class, WAIT).text()).endsWith("one");
        assertThat(call(mainA, "instance_ask", "{\"instance\":\"x@b\",\"text\":\"two\",\"wait_seconds\":0}").error())
                .isFalse();
        assertThat(xb.window.recorded.await(Intent.PromptSession.class, WAIT).text()).endsWith("two");
        final McpTools.Outcome ambiguous = call(mainA, "instance_ask",
                "{\"instance\":\"x\",\"text\":\"three\",\"wait_seconds\":0}");
        assertThat(ambiguous.error()).isTrue();
        assertThat(ambiguous.text()).contains("names instances on several machines: x@b, x@c");
        assertThat(xc.window.recorded.of(Intent.PromptSession.class)).isEmpty();

        final Node xa = node(a, "x", _ -> null);
        assertThat(call(mainA, "instance_ask", "{\"instance\":\"x\",\"text\":\"four\",\"wait_seconds\":0}").error())
                .isFalse();
        assertThat(xa.window.recorded.await(Intent.PromptSession.class, WAIT).text())
                .isEqualTo("[from instance comando · overseer · session foreman]\nfour");
        assertThat(xb.window.recorded.of(Intent.PromptSession.class)).hasSize(1);
    }

    /// An instance of another machine is present for as long after its last presence came as the bound says: past it,
    /// it is neither listed nor asked, and a presence sent again brings it back.
    @Test
    void anInstanceOfAnotherMachineGoesStaleAndAResendBringsItBack() throws Exception {
        final Bus a = new Bus("a");
        final Bus b = new Bus("b");
        Bus.cross(a, b);
        final Node mainA = node(a, "", Duration.ofMillis(500), _ -> null);
        final Node w = node(b, "w", m -> m.equals("a") ? MachinesPort.Grant.PEER : null);
        assertThat(call(mainA, "instances_list", "{}").text()).contains("(instance w@b)");
        Awaitility.await().atMost(WAIT).until(() -> !call(mainA, "instances_list", "{}").text().contains("w@b"));
        assertThat(call(mainA, "instance_ask", "{\"instance\":\"w@b\",\"text\":\"q\",\"wait_seconds\":0}").text())
                .contains("no instance 'w@b' is present on frontear");

        w.peers.resendPresence();
        w.peers.tick();
        assertThat(call(mainA, "instances_list", "{}").text()).contains("(instance w@b)");
    }

    /// A question from another machine goes to its asker's consult session whatever session it names: one naming
    /// another machine's consult session, or a session that may act, is prompted in `consult-<asker>` in plan mode,
    /// and the named session gets nothing.
    @Test
    void aPeerMachinesQuestionGoesToItsConsultSessionWhateverItNames() throws Exception {
        final Bus a = new Bus("a");
        final Bus b = new Bus("b");
        Bus.cross(a, b);
        final Node x = node(a, "x", _ -> null);
        final Node w = node(b, "w", m -> m.equals("a") || m.equals("c") ? MachinesPort.Grant.PEER : null);
        w.window.state.updateAndGet(s -> withSession(s, SessionSpec.fresh("consult-y@c", "/tmp").withPlanMode(true)));
        for (final String named : List.of("consult-y@c", "work")) {
            assertThat(call(x, "instance_ask", "{\"instance\":\"w@b\",\"session\":\"" + named
                    + "\",\"text\":\"repeat " + named + "\",\"wait_seconds\":0}").error()).isFalse();
            final Intent.PromptSession p = w.window.recorded.await(Intent.PromptSession.class,
                    q -> q.text().endsWith("repeat " + named), WAIT);
            assertThat(p.session()).as(named).isEqualTo("consult-x@a");
        }
        assertThat(made(w, "consult-x@a").planMode()).isTrue();
        assertThat(w.window.recorded.of(Intent.PromptSession.class)).extracting(Intent.PromptSession::session)
                .containsOnly("consult-x@a");
    }

    /// An instance keeps at most 64 instances of one other machine: a presence of a new one past them is dropped and
    /// counted, while one of an instance kept is taken, another machine has room of its own, and an instance that
    /// left makes room.
    @Test
    void anotherMachineHasAtMost64InstancesKeptAndAPresencePastThemIsDroppedAndCounted() throws Exception {
        final Bus a = new Bus("a");
        final Node mainA = node(a, "", _ -> null);
        for (int i = 0; i < Peers.MAX_REMOTES; i++) {
            remotePresence(a, "b", "s" + i);
        }
        assertThat(call(mainA, "instances_list", "{}").text()).contains("(instance s0@b)", "(instance s63@b)");
        assertThat(mainA.peers.droppedPresences()).isZero();

        remotePresence(a, "b", "s64");
        assertThat(call(mainA, "instances_list", "{}").text()).doesNotContain("s64@b");
        assertThat(mainA.peers.droppedPresences()).isEqualTo(1);
        remotePresence(a, "b", "s0");
        remotePresence(a, "c", "t");
        assertThat(call(mainA, "instances_list", "{}").text()).as("a kept one, another machine's")
                .contains("(instance s0@b)", "(instance t@c)");
        assertThat(mainA.peers.droppedPresences()).isEqualTo(1);

        a.deliver(new PeerGateway.Message(Peers.presenceTopic("b", "s0"), new byte[0], null, null, "@b"), false);
        remotePresence(a, "b", "s64");
        assertThat(call(mainA, "instances_list", "{}").text()).as("room once one left").contains("(instance s64@b)")
                .doesNotContain("(instance s0@b)");
        assertThat(mainA.peers.droppedPresences()).isEqualTo(1);
    }

    /// A machine with 64 instances kept, none of them present any more, has them make room for a new one.
    @Test
    void theInstancesOfAFullMachineNoLongerPresentMakeRoomForANewOne() throws Exception {
        final Bus a = new Bus("a");
        final Node mainA = node(a, "", Duration.ofMillis(300), _ -> null);
        for (int i = 0; i < Peers.MAX_REMOTES; i++) {
            remotePresence(a, "b", "s" + i);
        }
        Awaitility.await().atMost(WAIT).until(() -> !call(mainA, "instances_list", "{}").text().contains("@b"));
        remotePresence(a, "b", "s64");
        assertThat(call(mainA, "instances_list", "{}").text()).contains("(instance s64@b)");
        assertThat(mainA.peers.droppedPresences()).isZero();
    }

    /// An instance makes at most 8 consult sessions for the instances of one other machine: the question of a ninth
    /// asker of that machine is refused with a reply that says so and makes nothing, while those of another machine
    /// and of this one do not count, and an asker that has its session is still answered.
    @Test
    void anotherMachineHasAtMost8ConsultSessionsAndTheQuestionOfANinthAskerIsRefused() throws Exception {
        final Bus a = new Bus("a");
        final Bus b = new Bus("b");
        Bus.cross(a, b);
        final Node x = node(a, "x", _ -> null);
        final Node z = node(a, "z", _ -> null);
        final Node w = node(b, "w", m -> m.equals("a") ? MachinesPort.Grant.PEER : null);
        w.window.state.updateAndGet(s -> {
            AppState with = withSession(withSession(s, SessionSpec.fresh("consult-y@c", "/tmp").withPlanMode(true)),
                    SessionSpec.fresh("consult-v", "/tmp"));
            for (int i = 0; i < Peers.MAX_CONSULTS - 1; i++) {
                with = withSession(with, SessionSpec.fresh("consult-s" + i + "@a", "/tmp").withPlanMode(true));
            }
            return with;
        });
        assertThat(call(x, "instance_ask", "{\"instance\":\"w@b\",\"text\":\"eighth\",\"wait_seconds\":0}").error())
                .isFalse();
        assertThat(w.window.recorded.await(Intent.PromptSession.class, WAIT).session()).isEqualTo("consult-x@a");

        final McpTools.Outcome ninth = call(z, "instance_ask",
                "{\"instance\":\"w@b\",\"text\":\"ninth\",\"wait_seconds\":10}");
        assertThat(ninth.error()).isTrue();
        assertThat(ninth.text())
                .contains("comando · w has 8 consult sessions for the instances of machine a, its most");
        assertThat(w.window.recorded.of(Intent.SaveSession.class)).extracting(save -> save.spec().name())
                .containsExactly("consult-x@a");

        question(b, Peers.inboxTopic("b", "w"), "@a", "s0@a", Peers.replyTopic("a", "s0"), "q-1");
        assertThat(w.window.recorded.await(Intent.PromptSession.class, p -> p.session().equals("consult-s0@a"), WAIT)
                .text()).endsWith("\nq");
    }

    /// Delivers to `on` the presence of instance `slug` of machine `machine`, stamped with that machine's origin.
    private static void remotePresence(final Bus on, final String machine, final String slug) {
        final byte[] p = ("{\"schema\":1,\"name\":\"comando · " + slug + "\",\"slug\":\"" + slug + "\","
                + "\"version\":\"2.0.0\",\"sessions\":[]}").getBytes(StandardCharsets.UTF_8);
        on.deliver(new PeerGateway.Message(Peers.presenceTopic(machine, slug), p, null, null,
                InstanceName.origin(machine)), false);
    }

    /// A consult session for another machine's asker that its user set out of plan mode is refused that asker's
    /// question: it could act for an asker whose questions only plan; nothing is prompted there.
    @Test
    void aPeerMachinesQuestionToAConsultSessionThatDoesNotPlanIsRefused() throws Exception {
        final Bus a = new Bus("a");
        final Bus b = new Bus("b");
        Bus.cross(a, b);
        final Node x = node(a, "x", _ -> null);
        final Node w = node(b, "w", m -> m.equals("a") ? MachinesPort.Grant.PEER : null);
        w.window.state.updateAndGet(s -> withSession(s, SessionSpec.fresh("consult-x@a", "/tmp")));
        final McpTools.Outcome out = call(x, "instance_ask",
                "{\"instance\":\"w@b\",\"text\":\"rm it\",\"wait_seconds\":10}");
        assertThat(out.error()).isTrue();
        assertThat(out.text()).contains("session consult-x@a of comando · w does not run in plan mode");
        assertThat(w.window.recorded.of(Intent.PromptSession.class)).isEmpty();
    }

    /// A session made to only plan whose process reports another mode takes no question, from this machine or
    /// another, until it is started again in plan mode; one that reports plan, or no mode yet, takes it.
    @Test
    void aSessionMadeToPlanThatReportsAnotherModeIsRefusedEachQuestion() throws Exception {
        final Bus a = new Bus("a");
        final Bus b = new Bus("b");
        Bus.cross(a, b);
        final Node x = node(a, "x", _ -> null);
        final Node w = node(b, "w", m -> m.equals("a") ? MachinesPort.Grant.PEER : null);
        final Node v = node(b, "v", _ -> null);
        w.window.state.updateAndGet(s -> withSession(withSession(s,
                SessionSpec.fresh("consult-x@a", "/tmp").withPlanMode(true)),
                SessionSpec.fresh("consult-v", "/tmp").withPlanMode(true)));
        reports(w, "consult-x@a", "bypassPermissions");
        reports(w, "consult-v", "acceptEdits");
        final McpTools.Outcome remote = call(x, "instance_ask",
                "{\"instance\":\"w@b\",\"text\":\"q\",\"wait_seconds\":10}");
        assertThat(remote.error()).isTrue();
        assertThat(remote.text()).contains("session consult-x@a of comando · w was made to only plan but reports mode "
                + "bypassPermissions");
        final McpTools.Outcome local = call(v, "instance_ask",
                "{\"instance\":\"w\",\"text\":\"q\",\"wait_seconds\":10}");
        assertThat(local.error()).isTrue();
        assertThat(local.text()).contains("was made to only plan but reports mode acceptEdits");
        assertThat(w.window.recorded.of(Intent.PromptSession.class)).isEmpty();

        reports(w, "consult-x@a", "plan");
        assertThat(call(x, "instance_ask", "{\"instance\":\"w@b\",\"text\":\"again\",\"wait_seconds\":0}").error())
                .isFalse();
        assertThat(w.window.recorded.await(Intent.PromptSession.class, WAIT).session()).isEqualTo("consult-x@a");
    }

    /// Has `n`'s session `session` report permission mode `mode`, as Claude Code's init does.
    private static void reports(final Node n, final String session, final String mode) {
        final SessionInfo info = new SessionInfo("conversation", "opus", "/tmp", "2.1", mode, List.of());
        n.window.state.updateAndGet(s -> s.withSession(session, x -> x.withInfo(info)));
    }

    /// The spec of the session `n`'s window was asked to make under `name`.
    private static SessionSpec made(final Node n, final String name) {
        return n.window.recorded.of(Intent.SaveSession.class).stream().map(Intent.SaveSession::spec)
                .filter(s -> s.name().equals(name)).findFirst().orElseThrow();
    }

    /// Delivers to `on` a question on `topic`, stamped with `origin`, from `from`, whose reply goes to `replyTo`.
    private static void question(
            final Bus on,
            final String topic,
            final String origin,
            final String from,
            final String replyTo,
            final String correlation
    ) {
        final byte[] q = ("{\"schema\":1,\"from\":\"" + from + "\",\"name\":\"comando\",\"session\":\"work\","
                + "\"text\":\"q\"}").getBytes(StandardCharsets.UTF_8);
        on.deliver(new PeerGateway.Message(topic, q, replyTo, correlation.getBytes(StandardCharsets.UTF_8), origin),
                false);
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
        return node(bus, name, role, answerWithin, expireWithin, Peers.REMOTE_EXPIRY, _ -> null);
    }

    /// Instance `name` on `on`'s machine, which grants each machine what `grants` says, at the defaults' bounds.
    private Node node(final Bus on, final String name, final Function<String, MachinesPort.Grant> grants)
            throws Exception {
        return node(on, name, Peers.REMOTE_EXPIRY, grants);
    }

    /// As above, with how long a presence of another machine shows its instance present given.
    private Node node(
            final Bus on,
            final String name,
            final Duration presentFor,
            final Function<String, MachinesPort.Grant> grants
    ) throws Exception {
        return node(on, name, "", Peers.ANSWER_WITHIN, Peers.ANSWER_WITHIN.plusSeconds(40), presentFor, grants);
    }

    private Node node(
            final Bus on,
            final String name,
            final String role,
            final Duration answerWithin,
            final Duration expireWithin,
            final Duration presentFor,
            final Function<String, MachinesPort.Grant> grants
    ) throws Exception {
        return node(on, name, role, answerWithin, expireWithin, presentFor, Peers.REMOTE_EVERY, grants);
    }

    /// As above, with how long at most between two cut presences given too.
    private Node node(
            final Bus on,
            final String name,
            final String role,
            final Duration answerWithin,
            final Duration expireWithin,
            final Duration presentFor,
            final Duration resendEvery,
            final Function<String, MachinesPort.Grant> grants
    ) throws Exception {
        final Path machineDir = on.machine.isEmpty() ? dir : dir.resolve(on.machine);
        final Path home = Files.createDirectories(machineDir.resolve(name.isEmpty() ? "main" : name));
        final Window window = new Window();
        final Link link = new Link(on);
        final Peers peers = new Peers(link, Peers.Me.of(name, "2.0.0", role), window, window.state::get, window.transcripts, home.toString(),
                home.resolve("agents.json"), answerWithin, expireWithin, presentFor, resendEvery);
        peers.machine(on.machine);
        peers.grants(grants);
        peers.dataRoot(home);
        final McpTools tools = new McpTools(window, window.state::get, window.transcripts, null, AuditLog.none(), new FakeClock(), Duration.ofMinutes(1));
        tools.peers(peers);
        peers.start();
        link.connect();
        // the connect's own presence goes out on the ticks' strand; the test's sends it now, so the others see it at
        // once
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

    /** A message a link was asked to publish, and its expiry in seconds, 0 for none. */
    private record Sent(PeerGateway.Message message, long expirySeconds) {
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

    /**
     * A gateway in memory: retained messages, last wills, delivery to each matching subscription of a live link. A bus
     * of a machine is one machine's gateway: its topics between machines reach the buses it is crossed with, stamped
     * with its origin as the border gateway stamps them, and its {@code local} topics stay on it.
     */
    private static final class Bus {
        final Map<String, byte[]> retained = new LinkedHashMap<>();
        /** The origin each retained message came with, by topic; null for one of this bus's machine. */
        final Map<String, String> retainedOrigins = new LinkedHashMap<>();
        final List<Link> links = new CopyOnWriteArrayList<>();
        /** This bus's machine, empty for a bus crossed with none. */
        final String machine;
        final List<Bus> others = new CopyOnWriteArrayList<>();

        Bus() {
            this("");
        }

        Bus(final String machine) {
            this.machine = machine;
        }

        /** Crosses each of {@code buses} with every other, as paired machines' border gateways do. */
        static void cross(final Bus... buses) {
            for (final Bus a : buses) {
                for (final Bus b : buses) {
                    if (a != b) {
                        a.others.add(b);
                    }
                }
            }
        }

        void publish(final String topic, final byte[] payload, final boolean retain, final String responseTopic, final byte[] correlation) {
            deliver(new PeerGateway.Message(topic, payload, responseTopic, correlation), retain);
            if (!machine.isEmpty() && topic.startsWith(Peers.MACHINES)
                    && !topic.startsWith(Peers.MACHINES + Peers.LOCAL + '/')) {
                for (final Bus o : others) {
                    o.deliver(new PeerGateway.Message(topic, payload, responseTopic, correlation, '@' + machine),
                            retain);
                }
            }
        }

        /** Keeps {@code m} when retained and delivers it to each link of this bus, as it is, its origin included. */
        void deliver(final PeerGateway.Message m, final boolean retain) {
            if (retain) {
                synchronized (retained) {
                    if (m.payload().length == 0) {
                        retained.remove(m.topic());
                        retainedOrigins.remove(m.topic());
                    } else {
                        retained.put(m.topic(), m.payload());
                        retainedOrigins.put(m.topic(), m.origin());
                    }
                }
            }
            for (final Link l : links) {
                l.deliver(m);
            }
        }

        List<PeerGateway.Message> retained(final String filter) {
            synchronized (retained) {
                return retained.entrySet().stream().filter(e -> matches(filter, e.getKey()))
                        .map(e -> new PeerGateway.Message(e.getKey(), e.getValue(), null, null,
                                retainedOrigins.get(e.getKey()))).toList();
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

    private static final class Link implements PeerGateway {
        private final Bus bus;
        private final List<Runnable> listeners = new CopyOnWriteArrayList<>();
        private final Map<String, Consumer<Message>> subscriptions = new LinkedHashMap<>();
        volatile boolean up;
        /** Set by a test, read by the asking thread: a publish then fails while the link stays connected. */
        volatile boolean failing;
        /** What this link was asked to publish, in order, sent or not, with each message's expiry. */
        final List<Sent> sent = new CopyOnWriteArrayList<>();
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

        /** Gone without a goodbye: the gateway publishes the last will. */
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
        public CompletableFuture<Void> publish(
                final String topic,
                final byte[] payload,
                final boolean retained,
                final String responseTopic,
                final byte[] correlation,
                final long expirySeconds
        ) {
            sent.add(new Sent(new PeerGateway.Message(topic, payload, responseTopic, correlation), expirySeconds));
            if (!up) {
                return CompletableFuture.failedFuture(new IllegalStateException("not connected"));
            }
            if (failing) {
                return CompletableFuture.failedFuture(new IllegalStateException("the gateway refused it"));
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
