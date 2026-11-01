package dev.marregui.comando.mcp;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import dev.marregui.comando.core.intent.Intent;
import dev.marregui.comando.core.model.Decision;
import dev.marregui.comando.core.model.SessionSpec;
import dev.marregui.comando.core.model.Status;
import dev.marregui.comando.core.port.IntentSink;
import dev.marregui.comando.core.port.QueryPort;
import dev.marregui.comando.core.reduce.Reducer;
import dev.marregui.comando.core.state.AppState;
import dev.marregui.comando.core.state.SessionState;
import dev.marregui.comando.core.transcript.TranscriptRegistry;
import dev.marregui.comando.testkit.FakeClock;
import dev.marregui.comando.testkit.States;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/// The decision tools (record 131): what an automatic checkpoint holds back for the user, and how it is settled.
///
/// - `decision_hold` from a session holds the decision back under that session and answers that it waits in the Later
///   group; any other caller is refused
/// - `decisions_settled` takes a JSON array of integers, or that array as JSON text, and refuses anything else
/// - `decision_settle` is refused as locked while the target session's checkpoint runs; `decision_dismiss` removes one
/// - `ui_get_state` lists the decisions held back under `later`
class DecisionToolsTest {

    private final ObjectMapper json = new ObjectMapper();
    private final FakeClock clock = new FakeClock();
    private final Reducer reducer = new Reducer(clock);
    private final AtomicReference<AppState> state = new AtomicReference<>(States.twoPanels());
    private final List<Intent> sent = new ArrayList<>();
    /** Records each intent and reduces it at once, as the state loop would a moment later. */
    private final IntentSink sink = intent -> {
        synchronized (sent) {
            sent.add(intent);
        }
        state.updateAndGet(s -> reducer.apply(s, intent).state());
    };
    private McpTools tools;

    @BeforeEach
    void start() {
        final QueryPort db = null;
        tools = new McpTools(sink, state::get, new TranscriptRegistry(), db, AuditLog.none(), clock, Duration.ofMinutes(1));
        final List<SessionState> sessions = new ArrayList<>(state.get().sessions());
        sessions.add(session("work"));
        sessions.add(session("other"));
        state.set(state.get().withSessions(sessions));
    }

    /// `decision_hold` from session `work` sends `DecisionHeldBack` with `work` as the session and posts no notice (a
    /// decision held back brings nothing forward); the reducer keeps both decisions, with ids 1 and 2.
    @Test
    void aSessionHoldsADecisionBackQuietly() throws Exception {
        final McpTools.Outcome first = call("work", "decision_hold", "{\"title\":\"Merge the two entries?\",\"detail\":\"both say SVG only\"}");
        assertThat(first.error()).as(first.text()).isFalse();
        assertThat(first.text()).contains("Later group");
        final McpTools.Outcome second = call("work", "decision_hold", "{\"title\":\"Archive the old lane?\"}");
        assertThat(second.error()).as(second.text()).isFalse();
        assertThat(of(Intent.DecisionHeldBack.class)).extracting(Intent.DecisionHeldBack::session, Intent.DecisionHeldBack::title,
                Intent.DecisionHeldBack::detail).containsExactly(tuple("work", "Merge the two entries?", "both say SVG only"),
                tuple("work", "Archive the old lane?", ""));
        assertThat(state.get().later()).extracting(Decision::id, Decision::session).containsExactly(tuple(1L, "work"), tuple(2L, "work"));
        assertThat(of(Intent.Notify.class)).as("no notice for a decision held back").isEmpty();
    }

    /// A caller that is not a session, here an MCP client named `claude-p`, is refused with a message and holds nothing
    /// back; a hold without a title is refused too.
    @Test
    void aCallerThatIsNotASessionIsRefused() throws Exception {
        final McpTools.Outcome refused = call("claude-p", "decision_hold", "{\"title\":\"Anything?\"}");
        assertThat(refused.error()).isTrue();
        assertThat(refused.text()).contains("claude-p is not a session");
        assertThat(call("work", "decision_hold", "{\"detail\":\"no title\"}").text()).contains("needs \"title\"");
        assertThat(of(Intent.DecisionHeldBack.class)).isEmpty();
        assertThat(state.get().later()).isEmpty();
    }

    /// `decisions_settled` with `[1,3]` sends `DecisionsSettled([1, 3])`, and only decision 2 stays; a non-array, an
    /// array holding a string and one holding a fraction are refused, and nothing more is sent. Its schema says what it
    /// takes: an array of integers.
    @Test
    void decisionsSettledTakesAnArrayOfIntegersOnly() throws Exception {
        JsonNode schema = null;
        for (final JsonNode d : tools.definitions()) {
            if ("decisions_settled".equals(d.path("name").asString(""))) {
                schema = d.path("inputSchema");
            }
        }
        assertThat(schema).isNotNull();
        assertThat(schema.path("properties").path("ids").path("type").asString()).isEqualTo("array");
        assertThat(schema.path("properties").path("ids").path("items").path("type").asString()).isEqualTo("integer");
        for (final String title : List.of("One", "Two", "Three")) {
            call("work", "decision_hold", "{\"title\":\"" + title + "\"}");
        }
        final McpTools.Outcome ok = call("work", "decisions_settled", "{\"ids\":[1,3]}");
        assertThat(ok.error()).as(ok.text()).isFalse();
        assertThat(of(Intent.DecisionsSettled.class)).extracting(Intent.DecisionsSettled::ids).containsExactly(List.of(1L, 3L));
        assertThat(state.get().later()).extracting(Decision::title).containsExactly("Two");

        for (final String bad : List.of("{\"ids\":2}", "{\"ids\":\"2\"}", "{\"ids\":[2,\"x\"]}", "{\"ids\":[2.5]}")) {
            final McpTools.Outcome refused = call("work", "decisions_settled", bad);
            assertThat(refused.error()).as(bad).isTrue();
            assertThat(refused.text()).as(bad).contains("list of integers");
        }
        final McpTools.Outcome none = call("work", "decisions_settled", "{}");
        assertThat(none.error()).isTrue();
        assertThat(none.text()).as("no ids at all").contains("needs \"ids\"");
        assertThat(of(Intent.DecisionsSettled.class)).hasSize(1);
    }

    /// Claude Code sends an array argument as JSON text at times: `"[2]"` settles decision 2 as `[2]` would.
    @Test
    void decisionsSettledTakesTheArrayAsJsonTextToo() throws Exception {
        for (final String title : List.of("One", "Two")) {
            call("work", "decision_hold", "{\"title\":\"" + title + "\"}");
        }
        final McpTools.Outcome ok = call("work", "decisions_settled", "{\"ids\":\"[2]\"}");
        assertThat(ok.error()).as(ok.text()).isFalse();
        assertThat(of(Intent.DecisionsSettled.class)).extracting(Intent.DecisionsSettled::ids).containsExactly(List.of(2L));
        assertThat(state.get().later()).extracting(Decision::title).containsExactly("One");
    }

    /// `decision_settle` on a decision whose session runs its checkpoint is refused as locked (record 130) and sends
    /// nothing; once the checkpoint is over it sends `SettleDecision`. An unknown id is answered with the ids kept, and
    /// `decision_dismiss` sends `DismissDecision`, which removes the decision.
    @Test
    void settleIsRefusedWhileTheSessionsCheckpointRunsAndDismissRemoves() throws Exception {
        call("work", "decision_hold", "{\"title\":\"One\"}");
        call("other", "decision_hold", "{\"title\":\"Two\"}");
        // running: a Settle from another session starts no stopped session (record 44)
        final SessionState idle = state.get().session("work").orElseThrow().withStatus(Status.OPEN, "");
        state.set(state.get().withSession(idle.withStatus(Status.OPEN, "").withTurns(1, 0).withCheckpointTurns(1)));
        final McpTools.Outcome locked = call("other", "decision_settle", "{\"id\":1}");
        assertThat(locked.error()).isTrue();
        assertThat(locked.text()).contains("record 130");
        assertThat(of(Intent.SettleDecision.class)).isEmpty();

        state.set(state.get().withSession(idle));
        assertThat(call("other", "decision_settle", "{\"id\":1}").error()).isFalse();
        assertThat(of(Intent.SettleDecision.class)).extracting(Intent.SettleDecision::id).containsExactly(1L);

        assertThat(call("other", "decision_settle", "{\"id\":9}").text()).contains("no decision 9").contains("1 (work, One)");
        assertThat(call("work", "decision_dismiss", "{\"id\":2}").error()).isFalse();
        assertThat(of(Intent.DismissDecision.class)).extracting(Intent.DismissDecision::id).containsExactly(2L);
        assertThat(state.get().later()).extracting(Decision::id).containsExactly(1L);
    }

    /// `ui_get_state` lists the decisions held back under `later`, oldest first, each with its id, session, title,
    /// detail and when it was held back.
    @Test
    void theStateListsTheDecisionsHeldBack() throws Exception {
        assertThat(json.readTree(tools.stateJson()).path("later").isArray()).as("an empty list when none").isTrue();
        call("work", "decision_hold", "{\"title\":\"One\",\"detail\":\"why it matters\"}");
        call("other", "decision_hold", "{\"title\":\"Two\"}");
        final JsonNode later = json.readTree(call("work", "ui_get_state", "{}").text()).path("later");
        assertThat(later).hasSize(2);
        final JsonNode one = later.get(0);
        assertThat(List.of(one.path("id").asLong(), one.path("session").asString(), one.path("title").asString(), one.path("detail").asString(),
                one.path("created").asLong())).containsExactly(1L, "work", "One", "why it matters", clock.millis());
        assertThat(later.get(1).path("session").asString()).isEqualTo("other");
    }

    private McpTools.Outcome call(final String caller, final String tool, final String args) throws Exception {
        return tools.call(caller, tool, json.readTree(args));
    }

    private <T extends Intent> List<T> of(final Class<T> type) {
        synchronized (sent) {
            return sent.stream().filter(type::isInstance).map(type::cast).toList();
        }
    }

    private static SessionState session(final String name) {
        return SessionState.closed(SessionSpec.fresh(name, "/tmp")).withStatus(Status.CLOSED, "");
    }
}
