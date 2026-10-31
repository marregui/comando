package dev.marregui.comando.mcp;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import dev.marregui.comando.core.action.Action;
import dev.marregui.comando.core.action.Actions;
import dev.marregui.comando.core.intent.Intent;
import dev.marregui.comando.core.model.Approval;
import dev.marregui.comando.core.model.ApprovalDecision;
import dev.marregui.comando.core.model.ApprovalKind;
import dev.marregui.comando.core.model.SessionSpec;
import dev.marregui.comando.core.model.Status;
import dev.marregui.comando.core.port.QueryPort;
import dev.marregui.comando.core.port.ServerSwitch;
import dev.marregui.comando.core.state.AppState;
import dev.marregui.comando.core.state.SessionState;
import dev.marregui.comando.core.transcript.TranscriptRegistry;
import dev.marregui.comando.testkit.FakeClock;
import dev.marregui.comando.testkit.RecordingSink;
import dev.marregui.comando.testkit.States;

import static org.assertj.core.api.Assertions.assertThat;

/// The registry's actions as MCP tools (record 121): creating and driving sessions, and the two rules.
class ActionToolsTest {

    private final ObjectMapper json = new ObjectMapper();
    private final RecordingSink sink = new RecordingSink();
    private final AtomicReference<AppState> state = new AtomicReference<>(States.twoPanels());
    private McpTools tools;
    @TempDir
    Path home;

    @BeforeEach
    void start() {
        final QueryPort db = null;
        tools = new McpTools(sink, state::get, new TranscriptRegistry(), db, AuditLog.none(), new FakeClock(), Duration.ofMinutes(1));
        tools.agentsFile(home.resolve("agents.json"));
        state.set(with(state.get(), session("foreman", ""), session("mine", "foreman"), session("grandchild", "mine"), session("users", "")));
    }

    /// `session_new` saves a session with the given name, folder and model, records the calling session as its creator,
    /// and with a prompt starts it, headed with who sent it, without writing to any panel; a notice names the caller.
    @Test
    void sessionNewCreatesOpensAndWithAPromptStartsASessionThatRecordsItsCreator() throws Exception {
        final McpTools.Outcome out = call("foreman", "session_new", "{\"name\":\"slug-writer\",\"work_dir\":\"" + home + "\",\"model\":\"sonnet\","
                + "\"effort\":\"high\",\"prompt\":\"write slugify\"}");
        assertThat(out.error()).as(out.text()).isFalse();
        final Intent.SaveSession save = sink.of(Intent.SaveSession.class).getFirst();
        assertThat(save.previousName()).isEmpty();
        assertThat(save.spec().name()).isEqualTo("slug-writer");
        assertThat(save.spec().workDir()).isEqualTo(home.toAbsolutePath().normalize().toString());
        assertThat(save.spec().model()).isEqualTo("sonnet");
        assertThat(save.spec().createdBy()).isEqualTo("foreman");
        final Intent.PromptSession prompt = sink.of(Intent.PromptSession.class).getFirst();
        assertThat(prompt.session()).isEqualTo("slug-writer");
        assertThat(prompt.from()).isEqualTo("foreman");
        assertThat(prompt.text()).isEqualTo("[from session foreman]\nwrite slugify");
        assertThat(sink.of(Intent.EditPanel.class)).as("no write to a panel id of its own making").isEmpty();
        assertThat(sink.of(Intent.Notify.class)).anyMatch(n -> n.text().startsWith("foreman: session new"));
    }

    /// `session_new` refuses a name already taken and a folder that does not exist, and saves nothing.
    @Test
    void sessionNewRefusesATakenNameAndAMissingFolder() throws Exception {
        assertThat(call("foreman", "session_new", "{\"name\":\"mine\",\"work_dir\":\"" + home + "\"}").text()).contains("exists");
        assertThat(call("foreman", "session_new", "{\"name\":\"x\",\"work_dir\":\"" + home.resolve("nope") + "\"}").text()).contains("is not a folder");
        assertThat(sink.of(Intent.SaveSession.class)).isEmpty();
    }

    /// A caller starts only sessions it created, directly or through others; starting a session of the user's is
    /// refused citing record 44, while stopping any session is always allowed.
    @Test
    void aStoppedSessionIsStartedOnlyByItsCreatorsRecord44() throws Exception {
        assertThat(call("foreman", "session_start", "{\"session\":\"mine\"}").error()).isFalse();
        assertThat(call("foreman", "session_start", "{\"session\":\"grandchild\"}").error()).as("through others").isFalse();
        final McpTools.Outcome users = call("foreman", "session_start", "{\"session\":\"users\"}");
        assertThat(users.error()).isTrue();
        assertThat(users.text()).contains("record 44");
        assertThat(sink.of(Intent.StartSession.class)).extracting(Intent.StartSession::name).containsExactly("mine", "grandchild");
        assertThat(call("foreman", "session_stop", "{\"session\":\"users\"}").error()).as("stopping is always allowed").isFalse();
    }

    /// Rule 1: an approval is not answered by the session that asks for it nor by a session that created the asker; any
    /// other caller answers it, allowing with a scope or denying with a message.
    @Test
    void anApprovalIsNeverAnsweredByItsAskerNorByTheAskersCreatorsRule1() throws Exception {
        state.set(state.get().withApprovals(List.of(card(1, "foreman"), card(2, "grandchild"), card(3, "users"))));
        assertThat(call("foreman", "approval_answer", "{\"id\":1,\"decision\":\"allow\"}").text()).contains("rule 1");
        assertThat(call("foreman", "approval_answer", "{\"id\":2,\"decision\":\"allow\"}").text()).as("created through mine").contains("rule 1");
        final McpTools.Outcome ok = call("foreman", "approval_answer", "{\"id\":3,\"decision\":\"deny\",\"message\":\"not now\"}");
        assertThat(ok.error()).as(ok.text()).isFalse();
        assertThat(call("users", "approval_answer", "{\"id\":2,\"decision\":\"allow\",\"scope\":\"session\"}").error()).isFalse();
        assertThat(sink.of(Intent.ApprovalAnswered.class)).extracting(Intent.ApprovalAnswered::id).containsExactly(3L, 2L);
        assertThat(sink.of(Intent.ApprovalAnswered.class).getFirst().decision()).isEqualTo(new ApprovalDecision.Deny("not now"));
        assertThat(sink.of(Intent.ApprovalAnswered.class).get(1).decision()).isEqualTo(new ApprovalDecision.Allow(ApprovalDecision.Scope.SESSION));
    }

    /// Rule 2: a destructive action such as `session_remove` on a session the caller did not create is refused until
    /// `agents.json` grants it to the caller, then runs for that caller only.
    @Test
    void aDestructiveActionRunsOnlyForACallerTheUserGrantedItRule2() throws Exception {
        final McpTools.Outcome refused = call("foreman", "session_remove", "{\"session\":\"users\"}");
        assertThat(refused.error()).isTrue();
        assertThat(refused.text()).contains("agents.json").contains("did not create");
        Files.writeString(home.resolve("agents.json"), "{\"grants\": {\"foreman\": [\"destructive\"]}}");
        assertThat(call("foreman", "session_remove", "{\"session\":\"users\"}").error()).isFalse();
        assertThat(call("mine", "session_remove", "{\"session\":\"users\"}").error()).as("another caller").isTrue();
        assertThat(sink.of(Intent.RemoveSession.class)).extracting(Intent.RemoveSession::name).containsExactly("users");
    }

    /// A session's creator, directly or through others, clears and removes it without a grant, so a caller cleans up
    /// the sessions it made; a caller that made neither the session nor its makers is still refused.
    @Test
    void aCreatorClearsAndRemovesTheSessionsItMadeWithoutAGrant() throws Exception {
        assertThat(call("foreman", "session_clear", "{\"session\":\"mine\"}").error()).isFalse();
        assertThat(call("foreman", "session_remove", "{\"session\":\"grandchild\"}").error()).as("through mine").isFalse();
        assertThat(call("mine", "session_remove", "{\"session\":\"foreman\"}").error()).as("not its maker").isTrue();
        assertThat(call("users", "session_remove", "{\"session\":\"mine\"}").error()).as("unrelated").isTrue();
        assertThat(call("foreman", "panel_remove", "{\"panel\":\"" + state.get().panels().getFirst().title() + "\"}").error())
                .as("not a session: the grant alone").isTrue();
        assertThat(sink.of(Intent.ClearTranscript.class)).extracting(Intent.ClearTranscript::name).containsExactly("mine");
        assertThat(sink.of(Intent.RemoveSession.class)).extracting(Intent.RemoveSession::name).containsExactly("grandchild");
    }

    /// `server_start` and `server_stop` reach the server the caller names, in the direction it names, and the state
    /// lists each server with whether it runs; an unknown server is answered with the ones there are.
    @Test
    void anAgentStartsAndStopsTheServersAndTheStateShowsThem() throws Exception {
        final FakeServer edge = new FakeServer("Edge", false);
        final FakeServer questDb = new FakeServer("QuestDB", true);
        tools.servers(List.of(edge, questDb));
        assertThat(call("foreman", "server_start", "{\"server\":\"edge\"}").text()).isEqualTo("Edge up");
        final McpTools.Outcome refused = call("foreman", "server_stop", "{\"server\":\"questdb\"}");
        assertThat(refused.error()).as("a refusal is a tool error").isTrue();
        assertThat(edge.asked).containsExactly(true);
        assertThat(questDb.asked).containsExactly(false);
        assertThat(call("foreman", "server_start", "{\"server\":\"kafka\"}").text()).contains("servers: [edge, questdb]");
        final JsonNode servers = json.readTree(tools.stateJson()).path("servers");
        assertThat(servers.findValuesAsString("name")).containsExactly("edge", "questdb");
        assertThat(servers.get(0).path("running").asBoolean()).isTrue();
        assertThat(sink.of(Intent.Notify.class)).anyMatch(n -> n.text().startsWith("foreman: server start"));
    }

    /// `session_edit` saves the changes asked for, here the name and effort, under the previous name, and keeps the
    /// creator and the settings it was not asked to change.
    @Test
    void anEditKeepsTheCreatorAndWhatItDoesNotChange() throws Exception {
        assertThat(call("foreman", "session_edit", "{\"session\":\"mine\",\"new_name\":\"mine2\",\"effort\":\"max\"}").error()).isFalse();
        final Intent.SaveSession save = sink.of(Intent.SaveSession.class).getFirst();
        assertThat(save.previousName()).isEqualTo("mine");
        assertThat(save.spec().name()).isEqualTo("mine2");
        assertThat(save.spec().effort()).isEqualTo("max");
        assertThat(save.spec().workDir()).isEqualTo("/tmp");
        assertThat(save.spec().createdBy()).isEqualTo("foreman");
    }

    /// Panels are addressed by title, an unknown title answers with the panels there are, a slash command is explained,
    /// and the state JSON lists the sessions with their creators, the approvals and the closed panels.
    @Test
    void panelsAreFoundByTitleAndTheStateSaysWhatTheWindowShows() throws Exception {
        assertThat(call("foreman", "panel_rename", "{\"panel\":\"" + state.get().panels().getFirst().title() + "\",\"title\":\"notes\"}").error()).isFalse();
        assertThat(call("foreman", "panel_focus", "{\"panel\":\"no such\"}").text()).contains("panels:");
        assertThat(call("foreman", "session_command", "{\"session\":\"mine\",\"command\":\"compact\"}").text()).contains("slash");
        final JsonNode s = json.readTree(tools.stateJson());
        assertThat(s.path("sessions").findValuesAsString("createdBy")).contains("foreman", "mine");
        assertThat(s.has("approvals")).isTrue();
        assertThat(s.has("closedPanels")).isTrue();
    }

    /// Every registered action is listed as a tool, none twice, and calling it reaches a runner instead of answering
    /// unknown tool.
    @Test
    void everyActionIsListedWithItsSchemaAndHasARunner() throws Exception {
        final Set<String> listed = new HashSet<>();
        tools.definitions().forEach(d -> listed.add(d.path("name").asString()));
        final List<String> unrun = new ArrayList<>();
        for (final Action a : Actions.ALL) {
            assertThat(listed).as("listed: " + a.tool()).contains(a.tool());
            final McpTools.Outcome out = call("foreman", a.tool(), "{}");
            if (out.text().contains("unknown tool") || out.text().contains("has no runner")) {
                unrun.add(a.tool());
            }
        }
        assertThat(unrun).as("actions without a runner").isEmpty();
        assertThat(listed).as("no tool listed twice").hasSize(tools.definitions().size());
    }

    private McpTools.Outcome call(final String caller, final String tool, final String args) throws Exception {
        return tools.call(caller, tool, json.readTree(args));
    }

    /// A server that records the directions asked of it; a stop is refused, as for an Edge another instance started.
    private static final class FakeServer implements ServerSwitch {
        private final String name;
        private volatile boolean running;
        final List<Boolean> asked = new ArrayList<>();

        FakeServer(final String name, final boolean running) {
            this.name = name;
            this.running = running;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public boolean isRunning() {
            return running;
        }

        @Override
        public String want(final boolean up) {
            asked.add(up);
            if (!up) {
                return "refused: started by another comando";
            }
            running = true;
            return name + " up";
        }
    }

    private static Approval card(final long id, final String session) {
        return new Approval(id, session, ApprovalKind.TOOL, "Bash", "{\"command\":\"ls\"}", "ls", 0L);
    }

    /// An action on a session whose checkpoint runs is refused with an answer naming record 130, where the reducer would
    /// drop it without a word; an interrupt and a stop still go through, and other sessions are unaffected.
    @Test
    void anActionOnASessionWhoseCheckpointRunsIsRefusedAsLockedRecord130() throws Exception {
        final SessionState reflecting = session("users", "").withStatus(Status.OPEN, "").withTurns(1, 0).withCheckpointTurns(1);
        state.set(state.get().withSession(reflecting));
        for (final String tool : List.of("session_checkpoint", "session_model", "session_effort")) {
            final McpTools.Outcome out = call("foreman", tool, "{\"session\":\"users\"}");
            assertThat(out.error()).as(tool).isTrue();
            assertThat(out.text()).as(tool).contains("record 130");
        }
        assertThat(call("foreman", "session_command", "{\"session\":\"users\",\"command\":\"/compact\"}").text()).contains("record 130");
        assertThat(sink.of(Intent.Checkpoint.class)).isEmpty();
        assertThat(sink.of(Intent.SessionCommand.class)).isEmpty();
        assertThat(call("foreman", "session_interrupt", "{\"session\":\"users\"}").error()).as("interrupt goes through").isFalse();
        assertThat(call("foreman", "session_stop", "{\"session\":\"users\"}").error()).as("stop goes through").isFalse();
        assertThat(call("foreman", "session_checkpoint", "{\"session\":\"mine\"}").error()).as("another session").isFalse();
    }

    private static SessionState session(final String name, final String createdBy) {
        return SessionState.closed(SessionSpec.fresh(name, "/tmp").withCreatedBy(createdBy)).withStatus(Status.CLOSED, "");
    }

    private static AppState with(final AppState s, final SessionState... more) {
        final List<SessionState> sessions = new ArrayList<>(s.sessions());
        sessions.addAll(List.of(more));
        return s.withSessions(sessions);
    }
}
