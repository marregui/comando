package dev.marregui.comando.mcp;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.marregui.comando.core.action.Action;
import dev.marregui.comando.core.action.Actions;
import dev.marregui.comando.core.effect.Effect;
import dev.marregui.comando.core.intent.Intent;
import dev.marregui.comando.core.json.Json;
import dev.marregui.comando.core.json.JsonNode;
import dev.marregui.comando.core.model.Approval;
import dev.marregui.comando.core.model.ApprovalDecision;
import dev.marregui.comando.core.model.ApprovalKind;
import dev.marregui.comando.core.model.ConnectionSpec;
import dev.marregui.comando.core.model.PanelId;
import dev.marregui.comando.core.model.SessionSpec;
import dev.marregui.comando.core.model.StartMode;
import dev.marregui.comando.core.model.Status;
import dev.marregui.comando.core.port.QueryPort;
import dev.marregui.comando.core.port.ServerSwitch;
import dev.marregui.comando.core.reduce.Reducer;
import dev.marregui.comando.core.reduce.Transition;
import dev.marregui.comando.core.state.AppState;
import dev.marregui.comando.core.state.ConnectionState;
import dev.marregui.comando.core.state.PanelState;
import dev.marregui.comando.core.state.SessionState;
import dev.marregui.comando.core.state.ViewState;
import dev.marregui.comando.core.transcript.TranscriptRegistry;
import dev.marregui.comando.core.view.Placement;
import dev.marregui.comando.core.view.ViewSpec;
import dev.marregui.comando.testkit.FakeClock;
import dev.marregui.comando.testkit.RecordingSink;
import dev.marregui.comando.testkit.States;

import static org.assertj.core.api.Assertions.assertThat;

/// The registry's actions as MCP tools (record 121): creating and driving sessions, and the two rules; record 44 on
/// every route that starts a session; no permission mode nor extra folder; a connection's guards against the agent.
class ActionToolsTest {

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

    /// `panel_place` answers why the last panel of the main tabs stays there and sends nothing; a panel on the side
    /// goes back to the main tabs.
    @Test
    void panelPlaceKeepsTheLastPanelOfTheMainTabs() throws Exception {
        state.set(state.get().withPanel(state.get().panel(States.P1).orElseThrow().withSide(true)));
        final McpTools.Outcome refused = call("foreman", "panel_place", "{\"panel\":\"two\"}");
        assertThat(refused.text()).isEqualTo("refused: The last panel of the main tabs cannot move to the right side");
        assertThat(sink.of(Intent.PlacePanel.class)).isEmpty();

        assertThat(call("foreman", "panel_place", "{\"panel\":\"one\",\"side\":false}").error()).isFalse();
        assertThat(sink.of(Intent.PlacePanel.class)).containsExactly(new Intent.PlacePanel(States.P1, false));
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
        assertThat(call("foreman", "server_start", "{\"server\":\"kafka\"}").text()).contains("one of [edge, questdb]");
        final JsonNode servers = Json.readTree(tools.stateJson()).path("servers");
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
        final JsonNode s = Json.readTree(tools.stateJson());
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
        for (final Action a : Actions.ALL) {
            assertThat(listed).as("listed: " + a.tool()).contains(a.tool());
        }
        assertThat(listed).as("no tool listed twice").hasSize(tools.definitions().size());
    }

    /// One valid call per arm of the actions' runner, each with every argument it needs: none is an error, and each
    /// sends one of the intents its action declares, so an arm deleted or mapped to another action's intent fails here.
    /// The callers and targets are set up so no rule refuses: `foreman` made `mine` and `grandchild` and holds the
    /// destructive grant; the card is `users`', a session of another family.
    @Test
    void eachActionsRunnerSendsItsOwnIntentForAValidCall() throws Exception {
        Files.writeString(home.resolve("agents.json"), "{\"grants\": {\"foreman\": [\"destructive\", \"terminal\"]}}");
        tools.terminals(new TerminalToolsTest.FakeTerminals());
        final AppState s = state.get();
        final String p1 = s.panel(States.P1).orElseThrow().title();
        final String p2 = s.panel(States.P2).orElseThrow().title();
        final ViewSpec spec = new ViewSpec.Markdown("x");
        state.set(s.withClosed(List.of(new PanelState(new PanelId("shut"), "shut", "", "")))
                .withViews(List.of(new ViewState(9L, "view", spec, Placement.LOWER, false, "foreman", 1L)))
                .withApprovals(List.of(card(41, "users"))));
        // work, in panel one, is foreman's too: a checkpoint and a command go to a session with a panel
        state.set(state.get().withSession(state.get().session("work").orElseThrow().withSpec(States.WORK.withCreatedBy("foreman"))));
        final FakeServer edge = new FakeServer("Edge", false);
        tools.servers(List.of(edge));
        final String dir = home.toString().replace("\\", "\\\\");
        final Map<String, String> calls = new LinkedHashMap<>();
        calls.put("session_new", "{\"name\":\"fresh-one\",\"work_dir\":\"" + dir + "\"}");
        calls.put("session_edit", "{\"session\":\"mine\",\"model\":\"opus\"}");
        calls.put("session_open", "{\"session\":\"mine\"}");
        calls.put("session_start", "{\"session\":\"mine\"}");
        calls.put("session_fresh", "{\"session\":\"mine\"}");
        calls.put("session_stop", "{\"session\":\"mine\"}");
        calls.put("session_interrupt", "{\"session\":\"mine\"}");
        calls.put("session_checkpoint", "{\"session\":\"work\"}");
        calls.put("session_command", "{\"session\":\"work\",\"command\":\"/compact\"}");
        calls.put("session_model", "{\"session\":\"mine\",\"model\":\"opus\"}");
        calls.put("session_effort", "{\"session\":\"mine\",\"effort\":\"high\"}");
        calls.put("session_order", "{\"names\":[\"users\",\"foreman\"]}");
        calls.put("session_import", "{\"name\":\"imported\",\"work_dir\":\"" + dir + "\",\"conversation\":\"abc\"}");
        calls.put("session_clear", "{\"session\":\"mine\"}");
        calls.put("session_remove", "{\"session\":\"grandchild\"}");
        calls.put("session_subagent", "{\"session\":\"mine\",\"tool_use_id\":\"toolu_1\"}");
        calls.put("panel_add", "{\"title\":\"new one\"}");
        calls.put("panel_close", "{\"panel\":\"" + p2 + "\"}");
        calls.put("panel_reopen", "{\"panel\":\"shut\"}");
        calls.put("panel_remove", "{\"panel\":\"shut\"}");
        calls.put("panel_rename", "{\"panel\":\"" + p2 + "\",\"title\":\"renamed\"}");
        calls.put("panel_move", "{\"panel\":\"" + p2 + "\",\"index\":0}");
        calls.put("panel_place", "{\"panel\":\"" + p2 + "\"}");
        calls.put("panel_pin", "{\"panel\":\"" + p2 + "\"}");
        calls.put("panel_focus", "{\"panel\":\"" + p2 + "\"}");
        calls.put("panel_bind_session", "{\"panel\":\"" + p2 + "\",\"session\":\"mine\"}");
        calls.put("panel_bind_connection", "{\"panel\":\"" + p2 + "\",\"connection\":\"local\"}");
        calls.put("panel_run", "{\"panel\":\"" + p2 + "\",\"text\":\"SELECT 1\",\"target\":\"sql\"}");
        calls.put("panel_cancel", "{\"panel\":\"" + p2 + "\"}");
        calls.put("panel_refresh", "{\"panel\":\"" + p2 + "\",\"sql\":\"SELECT 1\"}");
        calls.put("approval_answer", "{\"id\":41,\"decision\":\"deny\"}");
        calls.put("connection_save", "{\"name\":\"lab\",\"host\":\"h\",\"database\":\"qdb\"}");
        calls.put("connection_test", "{\"name\":\"local\"}");
        calls.put("connection_open", "{\"name\":\"local\"}");
        calls.put("connection_close", "{\"name\":\"local\"}");
        calls.put("connection_schema", "{\"name\":\"local\"}");
        calls.put("connection_remove", "{\"name\":\"local\"}");
        calls.put("view_pin", "{\"id\":9}");
        calls.put("view_place", "{\"id\":9,\"placement\":\"side\"}");
        calls.put("view_close", "{\"id\":9}");
        calls.put("notice_dismiss", "{}");
        calls.put("gradle_run", "{\"panel\":\"" + p1 + "\",\"tasks\":\"build\"}");
        calls.put("command_cancel", "{\"run_id\":3}");
        calls.put("server_start", "{\"server\":\"edge\"}");
        calls.put("server_stop", "{\"server\":\"edge\"}");
        calls.put("folders_browse", "{}");
        calls.put("terminal_list", "{}");
        calls.put("terminal_start", "{\"panel\":\"" + p1 + "\"}");
        calls.put("terminal_send", "{\"panel\":\"" + p1 + "\",\"text\":\"ls\"}");
        calls.put("terminal_read", "{\"panel\":\"" + p1 + "\"}");
        calls.put("terminal_interrupt", "{\"panel\":\"" + p1 + "\"}");
        calls.put("terminal_stop", "{\"panel\":\"" + p1 + "\"}");
        final Set<String> built = tools.builtIn();
        assertThat(calls.keySet()).as("a call for every action the runner performs")
                .containsExactlyInAnyOrderElementsOf(Actions.ALL.stream().map(Action::tool).filter(t -> !built.contains(t)).toList());
        for (final Map.Entry<String, String> c : calls.entrySet()) {
            final Action a = Actions.byTool(c.getKey()).orElseThrow();
            final int before = sink.intents().size();
            final McpTools.Outcome out = call("foreman", c.getKey(), c.getValue());
            if (!c.getKey().equals("server_stop")) {
                // the fake refuses every stop, as for an Edge another instance started: its ask is checked below
                assertThat(out.error()).as(c.getKey() + ": " + out.text()).isFalse();
            }
            if (!a.intents().isEmpty()) {
                assertThat(sink.intents().subList(before, sink.intents().size())).as(c.getKey() + " sends its own intent")
                        .anyMatch(i -> a.intents().stream().anyMatch(k -> k.isInstance(i)));
            }
        }
        assertThat(edge.asked).as("server_start, then server_stop").containsExactly(true, false);
    }

    /// A connection an agent points at another host keeps the password the same call gives, even when it equals the
    /// stored one: the reducer applying the intent drops only a password carried along, never one given (record 20).
    @Test
    void aPasswordTheCallGivesWithANewHostIsKept() throws Exception {
        final ConnectionSpec lab = new ConnectionSpec("lab", "db.internal", 5432, "sales", "app", "s3cret", false, "prefer");
        state.set(state.get().withConnections(List.of(ConnectionState.closed(lab))));
        final McpTools.Outcome out = call("users", "connection_save", "{\"name\":\"lab\",\"host\":\"db2.internal\",\"database\":\"sales\",\"password\":\"s3cret\"}");
        assertThat(out.error()).as(out.text()).isFalse();
        final Intent.SaveConnection save = sink.of(Intent.SaveConnection.class).getFirst();
        assertThat(save.passwordGiven()).isTrue();
        final AppState after = new Reducer(new FakeClock()).apply(state.get(), save).state();
        assertThat(after.connection("lab").orElseThrow().spec().password()).as("given by the call: kept").isEqualTo("s3cret");
    }

    /// A card that would clear a connection's production flag or lower its TLS is the user's alone (D2, record 23): no
    /// session answers it through `approval_answer`, not even one of another family, and nothing is sent.
    @Test
    void noAgentAnswersAConnectionCard() throws Exception {
        final Approval connection = new Approval(51, "users", ApprovalKind.CONNECTION, "connection_save", "{}", "prod: clears production", 0L);
        state.set(state.get().withApprovals(List.of(connection)));
        final McpTools.Outcome out = call("foreman", "approval_answer", "{\"id\":51,\"decision\":\"allow\"}");
        assertThat(out.error()).isTrue();
        assertThat(out.text()).contains("only the user answers it");
        assertThat(sink.of(Intent.ApprovalAnswered.class)).isEmpty();
    }

    /// An agent's setting the tool let through before the lock began, and the reducer then refuses under it, is told:
    /// the notice names the agent, as for `session_command` (record 130), for every action the lock refuses.
    @Test
    void anAgentsSettingRefusedUnderTheLockIsTold() throws Exception {
        final Reducer reducer = new Reducer(new FakeClock());
        AppState s = reducer.apply(state.get(), new Intent.StartSession("work")).state();
        s = reducer.apply(s, new Intent.SessionStarted("work")).state();
        state.set(s);
        assertThat(call("foreman", "session_effort", "{\"session\":\"work\",\"effort\":\"high\"}").text()).startsWith("sent");
        assertThat(call("foreman", "session_model", "{\"session\":\"work\",\"model\":\"opus\"}").text()).startsWith("sent");
        s = reducer.apply(s, new Intent.Checkpoint("work")).state();
        for (final Intent i : sink.intents()) {
            if (i instanceof Intent.SetSessionEffort || i instanceof Intent.SetSessionModel) {
                final AppState before = s;
                s = reducer.apply(s, i).state();
                assertThat(s.session("work").orElseThrow().spec()).as("not applied under the lock").isEqualTo(before.session("work").orElseThrow().spec());
                assertThat(s.notice()).as("told: " + i).isNotEqualTo(before.notice());
                assertThat(s.notice().text()).contains("foreman");
            }
        }
    }

    /// An argument the schema requires and the call leaves out, a value outside its choices, or an integer that is none
    /// is refused before anything reads it: no tab moves to the front, no panel unbinds, no run 0 is cancelled.
    @Test
    void aMissingRequiredArgumentOrAValueOutsideItsChoicesIsRefused() throws Exception {
        final String p2 = state.get().panel(States.P2).orElseThrow().title();
        for (final List<String> bad : List.of(List.of("panel_move", "{\"panel\":\"" + p2 + "\"}", "needs \"index\""),
                List.of("panel_bind_session", "{\"panel\":\"" + p2 + "\"}", "needs \"session\""),
                List.of("session_model", "{\"session\":\"mine\"}", "needs \"model\""),
                List.of("command_cancel", "{}", "needs \"run_id\""),
                List.of("session_effort", "{\"session\":\"mine\",\"effort\":\"huge\"}", "\"effort\" is \"huge\"; one of"),
                List.of("panel_move", "{\"panel\":\"" + p2 + "\",\"index\":\"first\"}", "an integer"))) {
            final McpTools.Outcome out = call("foreman", bad.get(0), bad.get(1));
            assertThat(out.error()).as(bad.get(0)).isTrue();
            assertThat(out.text()).as(bad.get(0)).contains(bad.get(2));
        }
        assertThat(sink.intents()).as("nothing sent").isEmpty();
        assertThat(call("foreman", "panel_bind_session", "{\"panel\":\"" + p2 + "\",\"session\":\"\"}").error()).as("empty unbinds, on purpose").isFalse();
    }

    /// A grants file that does not read grants nothing: a destructive action stays refused.
    @Test
    void aGrantsFileThatDoesNotReadGrantsNothing() throws Exception {
        Files.writeString(home.resolve("agents.json"), "{bad");
        assertThat(call("foreman", "session_remove", "{\"session\":\"users\"}").text()).contains("is destructive");
        assertThat(sink.of(Intent.RemoveSession.class)).isEmpty();
    }

    private McpTools.Outcome call(final String caller, final String tool, final String args) throws Exception {
        return tools.call(caller, tool, Json.readTree(args));
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
        for (final List<String> call : List.of(List.of("session_checkpoint", "{\"session\":\"users\"}"),
                List.of("session_model", "{\"session\":\"users\",\"model\":\"opus\"}"), List.of("session_effort", "{\"session\":\"users\",\"effort\":\"high\"}"))) {
            final String tool = call.getFirst();
            final McpTools.Outcome out = call("foreman", tool, call.getLast());
            assertThat(out.error()).as(tool).isTrue();
            assertThat(out.text()).as(tool).contains("record 130");
        }
        assertThat(call("foreman", "session_command", "{\"session\":\"users\",\"command\":\"/compact\"}").text()).contains("record 130");
        assertThat(sink.of(Intent.Checkpoint.class)).isEmpty();
        assertThat(sink.of(Intent.SessionCommand.class)).isEmpty();
        assertThat(call("foreman", "session_interrupt", "{\"session\":\"users\"}").error()).as("interrupt goes through").isFalse();
        assertThat(call("foreman", "session_stop", "{\"session\":\"users\"}").error()).as("stop goes through").isFalse();
        state.set(state.get().withSession(state.get().session("work").orElseThrow().withStatus(Status.OPEN, "")));
        assertThat(call("foreman", "session_checkpoint", "{\"session\":\"work\"}").error()).as("another session").isFalse();
    }

    private static SessionState session(final String name, final String createdBy) {
        return SessionState.closed(SessionSpec.fresh(name, "/tmp").withCreatedBy(createdBy)).withStatus(Status.CLOSED, "");
    }

    private static AppState with(final AppState s, final SessionState... more) {
        final List<SessionState> sessions = new ArrayList<>(s.sessions());
        sessions.addAll(List.of(more));
        return s.withSessions(sessions);
    }

    // ---- the stopped session, the creator chain, the permissions, the connections

    /// Record 44 holds on every route: `panel_run` into a stopped session's panel and `session_command` to it, from a
    /// session that did not create it, are refused, and once reduced nothing starts; from its creator they start it.
    @Test
    void anUnrelatedSessionStartsNoStoppedSessionThroughItsPanelOrACommandRecord44() throws Exception {
        final String panel = state.get().panel(States.P1).orElseThrow().title();
        final McpTools.Outcome run = call("users", "panel_run", "{\"panel\":\"" + panel + "\",\"text\":\"keep going\",\"target\":\"claude\"}");
        final McpTools.Outcome command = call("users", "session_command", "{\"session\":\"work\",\"command\":\"/compact\"}");
        assertThat(List.of(run.error(), command.error())).as(run.text() + " / " + command.text()).containsExactly(true, true);
        assertThat(run.text()).contains("record 44");
        assertThat(reduced()).noneMatch(e -> e instanceof Effect.StartClaude);

        state.set(state.get().withSession(state.get().session("work").orElseThrow().withSpec(States.WORK.withCreatedBy("foreman"))));
        assertThat(call("foreman", "session_command", "{\"session\":\"work\",\"command\":\"/compact\"}").error()).as("its creator").isFalse();
        assertThat(reduced()).anyMatch(e -> e instanceof Effect.StartClaude s && s.spec().name().equals("work"));
    }

    /// Rule 1 holds down the chain too: a card raised by `foreman` is not answered by `mine`, the session it created,
    /// nor by `grandchild` under it, both its own work; an unrelated session still answers it.
    @Test
    void aSessionAnswersNoCardThroughASessionItMadeRule1() throws Exception {
        state.set(state.get().withApprovals(List.of(card(7, "foreman"))));
        final McpTools.Outcome child = call("mine", "approval_answer", "{\"id\":7,\"decision\":\"allow\",\"scope\":\"always\"}");
        final McpTools.Outcome grandchild = call("grandchild", "approval_answer", "{\"id\":7,\"decision\":\"allow\"}");
        assertThat(List.of(child.error(), grandchild.error())).containsExactly(true, true);
        assertThat(child.text()).contains("which created mine").contains("rule 1");
        assertThat(sink.of(Intent.ApprovalAnswered.class)).isEmpty();
        assertThat(call("users", "approval_answer", "{\"id\":7,\"decision\":\"deny\"}").error()).as("unrelated").isFalse();
    }

    /// Rule 1 holds across the whole creation family: siblings and cousins share their creators' work, so a card raised
    /// by `grandchild` is answered neither by `sibling` (made by `foreman`, beside `mine`) nor by `cousin` (made by
    /// `sibling`); a session a client made is in that client's family; the user's own session, unrelated, answers.
    @Test
    void noSessionOfTheAskersCreationFamilyAnswersItsCardRule1() throws Exception {
        state.set(with(state.get(), session("sibling", "foreman"), session("cousin", "sibling"), session("clients", "ext")));
        state.set(state.get().withApprovals(List.of(card(8, "grandchild"), card(9, "mine"), card(10, "clients"))));
        for (final String caller : List.of("sibling", "cousin")) {
            final McpTools.Outcome out = call(caller, "approval_answer", "{\"id\":8,\"decision\":\"allow\"}");
            assertThat(out.error()).as(caller).isTrue();
            assertThat(out.text()).as(caller).contains("creation family").contains("rule 1");
        }
        assertThat(call("sibling", "approval_answer", "{\"id\":9,\"decision\":\"allow\"}").error()).as("a sibling").isTrue();
        assertThat(call("ext", "approval_answer", "{\"id\":10,\"decision\":\"allow\"}").error()).as("the client that made it").isTrue();
        assertThat(sink.of(Intent.ApprovalAnswered.class)).isEmpty();
        assertThat(call("foreman", "approval_answer", "{\"id\":10,\"decision\":\"deny\"}").error()).as("another family").isFalse();
        assertThat(call("users", "approval_answer", "{\"id\":8,\"decision\":\"deny\"}").error()).as("unrelated").isFalse();
        assertThat(sink.of(Intent.ApprovalAnswered.class)).extracting(Intent.ApprovalAnswered::id).containsExactly(10L, 8L);
    }

    /// The creation family outlives a removal or a rename in its middle: `foreman` removes `mine`, or renames it, and
    /// `grandchild`, still `foreman`'s own work, answers none of `foreman`'s cards (rule 1). The reducer applies the
    /// removal and the rename as the window would.
    @Test
    void aRemovalOrARenameInTheMiddleCutsNoCreationFamilyRule1() throws Exception {
        final AppState start = state.get();
        for (final String cut : List.of("{\"session\":\"mine\"}", "{\"session\":\"mine\",\"new_name\":\"mine2\"}")) {
            state.set(start);
            Files.writeString(home.resolve("agents.json"), "{\"grants\": {\"foreman\": [\"destructive\"]}}");
            sink.clear();
            assertThat(call("foreman", cut.contains("new_name") ? "session_edit" : "session_remove", cut).error()).as(cut).isFalse();
            final Reducer reducer = new Reducer(new FakeClock());
            AppState s = state.get();
            for (final Intent i : sink.intents()) {
                s = reducer.apply(s, i).state();
            }
            state.set(s.withApprovals(List.of(card(7, "foreman"))));
            sink.clear();
            final McpTools.Outcome out = call("grandchild", "approval_answer", "{\"id\":7,\"decision\":\"allow\",\"scope\":\"always\"}");
            assertThat(out.error()).as(cut + ": " + out.text()).isTrue();
            assertThat(sink.of(Intent.ApprovalAnswered.class)).as(cut).isEmpty();
        }
        assertThat(state.get().session("grandchild").orElseThrow().spec().createdBy()).as("after the rename").isEqualTo("mine2");
    }

    /// No action sets a permission mode or extra folders: `session_new` and `session_edit` offer neither, a session an
    /// agent makes runs under the kit's settings as its creator does (record 7), and an argument a caller adds anyway
    /// changes nothing it saves.
    @Test
    void noActionSetsAPermissionModeOrExtraFolders() throws Exception {
        for (final JsonNode d : tools.definitions()) {
            if (List.of("session_new", "session_edit").contains(d.path("name").asString(""))) {
                assertThat(d.path("inputSchema").path("properties").propertyNames()).as(d.path("name").asString(""))
                        .doesNotContain("permission_mode", "add_dirs");
            }
        }
        final McpTools.Outcome made = call("foreman", "session_new", "{\"name\":\"helper\",\"work_dir\":\"" + home
                + "\",\"permission_mode\":\"bypassPermissions\",\"add_dirs\":[\"/\"]}");
        assertThat(made.error()).as(made.text()).isFalse();
        assertThat(sink.of(Intent.SaveSession.class).getFirst().spec()).isEqualTo(new SessionSpec("helper", home.toAbsolutePath().normalize().toString(),
                "", "", 0.0, "", StartMode.NEW, SessionSpec.DEFAULT_CHECKPOINT, "", "foreman"));
    }

    /// `session_new` named after a panel another session's panel holds is refused as the reducer would refuse it, so
    /// the answer never reports a session that is not made; nothing is sent.
    @Test
    void sessionNewDoesNotReportASessionTheReducerRefuses() throws Exception {
        final String taken = state.get().panel(States.P1).orElseThrow().title();
        final McpTools.Outcome out = call("foreman", "session_new", "{\"name\":\"" + taken + "\",\"work_dir\":\"" + home + "\"}");
        assertThat(out.error()).as(out.text()).isTrue();
        assertThat(out.text()).contains("A panel called " + taken + " exists");
        assertThat(sink.of(Intent.SaveSession.class)).isEmpty();
    }

    /// A connection an agent saves keeps every field the call leaves out, production and TLS included, and a change of
    /// host, port, database or user drops the stored password unless the call gives one, so `connection_test` can
    /// never send it to a host of the agent's choosing (record 20).
    @Test
    void anAgentMustNotCarryAStoredPasswordToAHostOfItsChoosing() throws Exception {
        withProduction();
        final McpTools.Outcome save = call("users", "connection_save", "{\"name\":\"prod\",\"host\":\"127.0.0.1\",\"port\":15432,\"database\":\"x\"}");
        assertThat(save.error()).as(save.text()).isFalse();
        final ConnectionSpec saved = sink.of(Intent.SaveConnection.class).getFirst().spec();
        assertThat(saved.password()).as("repointed: the password stays behind").isEmpty();
        assertThat(List.of(saved.user(), saved.prod(), saved.sslMode())).as("left out: kept").containsExactly("app", true, "verify-full");

        call("users", "connection_save", "{\"name\":\"prod\",\"database\":\"sales\"}");
        assertThat(sink.of(Intent.SaveConnection.class).get(1).spec().password()).as("the same target: kept").isEqualTo("s3cret");
    }

    /// An agent clearing a connection's production flag, or lowering its TLS mode, puts a card before the user and
    /// changes nothing until the user allows it (record 23): denied, nothing is saved; allowed, the change is saved.
    @Test
    void anAgentWeakeningAConnectionWaitsForTheUsersCard() throws Exception {
        withProduction();
        final CompletableFuture<McpTools.Outcome> cleared = CompletableFuture.supplyAsync(() -> callQuietly("users", "connection_save",
                "{\"name\":\"prod\",\"production\":false}"));
        final Intent.ApprovalRequested asked = sink.await(Intent.ApprovalRequested.class, Duration.ofSeconds(10));
        assertThat(asked.approval().kind()).isEqualTo(ApprovalKind.CONNECTION);
        assertThat(asked.approval().summary()).contains("prod").contains("clears production");
        assertThat(sink.of(Intent.SaveConnection.class)).as("nothing changes while the card waits").isEmpty();
        tools.resolve(asked.approval(), new ApprovalDecision.Deny("no"));
        assertThat(cleared.get(10, TimeUnit.SECONDS).error()).isTrue();
        assertThat(sink.of(Intent.SaveConnection.class)).as("denied").isEmpty();

        final CompletableFuture<McpTools.Outcome> lowered = CompletableFuture.supplyAsync(() -> callQuietly("users", "connection_save",
                "{\"name\":\"prod\",\"ssl_mode\":\"prefer\"}"));
        sink.await(Intent.ApprovalRequested.class, a -> sink.of(Intent.ApprovalRequested.class).size() == 2, Duration.ofSeconds(10));
        final Intent.ApprovalRequested again = sink.of(Intent.ApprovalRequested.class).get(1);
        assertThat(again.approval().summary()).contains("lowers TLS from verify-full to prefer");
        tools.resolve(again.approval(), ApprovalDecision.allowOnce());
        assertThat(lowered.get(10, TimeUnit.SECONDS).error()).isFalse();
        assertThat(sink.of(Intent.SaveConnection.class)).singleElement().extracting(c -> c.spec().sslMode()).isEqualTo("prefer");
    }

    private void withProduction() {
        state.set(state.get().withConnections(List.of(ConnectionState.closed(
                new ConnectionSpec("prod", "db.internal", 5432, "sales", "app", "s3cret", true, "verify-full")))));
    }

    private McpTools.Outcome callQuietly(final String caller, final String tool, final String args) {
        try {
            return call(caller, tool, args);
        } catch (final Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /// The effects of every intent sent so far, reduced in order on the current state.
    private List<Effect> reduced() {
        final Reducer reducer = new Reducer(new FakeClock());
        AppState s = state.get();
        final List<Effect> effects = new ArrayList<>();
        for (final Intent i : sink.intents()) {
            final Transition t = reducer.apply(s, i);
            s = t.state();
            effects.addAll(t.effects());
        }
        return effects;
    }
}
