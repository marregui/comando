package dev.marregui.comando.core.resource;

import dev.marregui.comando.core.model.Approval;
import dev.marregui.comando.core.model.ApprovalKind;
import dev.marregui.comando.core.model.ConnectionSpec;
import dev.marregui.comando.core.model.PanelId;
import dev.marregui.comando.core.model.SessionSpec;
import dev.marregui.comando.core.model.StartMode;
import dev.marregui.comando.core.model.Status;
import dev.marregui.comando.core.state.AppState;
import dev.marregui.comando.core.state.ConnectionState;
import dev.marregui.comando.core.state.Notice;
import dev.marregui.comando.core.state.PanelState;
import dev.marregui.comando.core.state.SessionState;
import org.assertj.core.groups.Tuple;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ResourceKindTest {

    @Test
    void connectionRoundTrip() {
        ConnectionSpec spec = new ConnectionSpec("lab", "10.0.0.5", 8812, "qdb", "admin", "s3cret", true, "verify-full");
        assertThat(ConnectionKind.INSTANCE.fromAttributes(ConnectionKind.INSTANCE.toAttributes(spec))).isEqualTo(spec);
    }

    @Test
    void tlsModeDefaultsToPreferAndIsChecked() {
        assertThat(new ConnectionSpec("a", "h", 1, "d", "u", "p", false).sslMode()).isEqualTo("prefer");
        assertThat(new ConnectionSpec("a", "h", 1, "d", "u", "p", false, null).sslMode()).isEqualTo("prefer");
        assertThatThrownBy(() -> new ConnectionSpec("a", "h", 1, "d", "u", "p", false, "maybe")).hasMessageContaining("sslMode is one of");
    }

    @Test
    void sessionRoundTrip() {
        SessionSpec spec = new SessionSpec("edge", "/Users/x/edge", "opus", "high", "acceptEdits",
                List.of("/a", "/b"), 15.0, "id-1", StartMode.RESUME);
        assertThat(SessionKind.INSTANCE.fromAttributes(SessionKind.INSTANCE.toAttributes(spec))).isEqualTo(spec);
    }

    @Test
    void invalidFormsExplainThemselves() {
        Map<String, String> attrs = ConnectionKind.INSTANCE.toAttributes(ConnectionSpec.questDbDefaults("x"));
        attrs.put("port", "eighty");
        assertThatThrownBy(() -> ConnectionKind.INSTANCE.fromAttributes(attrs)).hasMessageContaining("port is not a number");

        Map<String, String> s = SessionKind.INSTANCE.toAttributes(SessionSpec.fresh("s", "/tmp"));
        s.put("startMode", "RESUME");
        assertThatThrownBy(() -> SessionKind.INSTANCE.fromAttributes(s))
                .hasMessage("Next start RESUME needs a Claude session id; choose NEW for a new conversation");
    }

    @Test
    void aStartModeOutsideTheChoicesNamesTheChoices() {
        Map<String, String> s = SessionKind.INSTANCE.toAttributes(SessionSpec.imported("s", "/tmp", "id-1"));
        s.put("startMode", "new conversation");
        assertThatThrownBy(() -> SessionKind.INSTANCE.fromAttributes(s)).hasMessage("Next start must be NEW, RESUME or FORK");
        s.put("startMode", "resume");
        assertThat(SessionKind.INSTANCE.fromAttributes(s).startMode()).isEqualTo(StartMode.RESUME);
    }

    @Test
    void onlyTheModelAcceptsTypedValues() {
        assertThat(SessionKind.INSTANCE.attributes()).filteredOn(a -> a.kind() == Attribute.Kind.CHOICE)
                .extracting(Attribute::key, Attribute::editable)
                .containsExactly(Tuple.tuple("model", true), Tuple.tuple("effort", false),
                        Tuple.tuple("permissionMode", false), Tuple.tuple("startMode", false));
        assertThat(ConnectionKind.INSTANCE.attributes()).filteredOn(a -> a.kind() == Attribute.Kind.CHOICE)
                .allMatch(a -> !a.editable());
        assertThat(Attribute.of("x", "X", Attribute.Kind.TEXT, false, "").editable()).isTrue();
    }

    @Test
    void theConversationFieldsAreAdvancedAndOpenWhenAnIdIsSet() {
        assertThat(SessionKind.INSTANCE.attributes()).filteredOn(Attribute::advanced).extracting(Attribute::key)
                .containsExactly("claudeSessionId", "startMode");
        assertThat(SessionKind.INSTANCE.showAdvanced(SessionKind.INSTANCE.toAttributes(SessionSpec.fresh("s", "/tmp")))).isFalse();
        assertThat(SessionKind.INSTANCE.showAdvanced(SessionKind.INSTANCE.toAttributes(SessionSpec.imported("s", "/tmp", "id-1")))).isTrue();
        assertThat(ConnectionKind.INSTANCE.attributes()).noneMatch(Attribute::advanced);
    }

    @Test
    void choosingNewForAStoredConversationSaysItWillNotBeContinued() {
        Map<String, String> s = SessionKind.INSTANCE.toAttributes(SessionSpec.imported("s", "/tmp", "c0ffee"));
        assertThat(SessionKind.INSTANCE.advice(s)).isEmpty();
        s.put("startMode", "NEW");
        assertThat(SessionKind.INSTANCE.advice(s)).isEqualTo("The stored conversation c0ffee will not be continued");
        assertThat(SessionKind.INSTANCE.advice(SessionKind.INSTANCE.toAttributes(SessionSpec.fresh("n", "/tmp")))).isEmpty();
    }

    @Test
    void removingASessionNamesWhatItStopsDeniesAndUnbinds() {
        SessionSpec work = SessionSpec.imported("work", "/tmp", "id-1");
        AppState s = AppState.initial(
                        List.of(new PanelState(new PanelId("p1"), "one", "", "work"), new PanelState(new PanelId("p2"), "two", "", "work")),
                        List.of(), List.of(SessionState.closed(work).withStatus(Status.OPEN, "")))
                .withApprovals(List.of(new Approval(1, "work", ApprovalKind.TOOL, "Bash", "{}", "ls", 0L),
                        new Approval(2, "work", ApprovalKind.TOOL, "Bash", "{}", "pwd", 0L)));

        assertThat(SessionKind.INSTANCE.removalQuestion(s, "work")).isEqualTo("Remove session work? This stops it, denies 2 waiting approvals"
                + " and unbinds 2 panels. The conversation stays in ~/.claude/projects and can be imported again.");

        AppState idle = AppState.initial(List.of(new PanelState(new PanelId("p1"), "one", "", "")), List.of(),
                List.of(SessionState.closed(SessionSpec.fresh("new", "/tmp"))));
        assertThat(SessionKind.INSTANCE.removalQuestion(idle, "new")).isEqualTo("Remove session new?");
    }

    @Test
    void removingAConnectionNamesWhatItClosesUnbindsAndDeletes() {
        ConnectionSpec lab = new ConnectionSpec("lab", "h", 8812, "qdb", "admin", "s3cret", false, "prefer");
        AppState s = AppState.initial(List.of(new PanelState(new PanelId("p1"), "one", "lab", "")),
                List.of(ConnectionState.closed(lab).with(Status.OPEN, "")), List.of());
        assertThat(ConnectionKind.INSTANCE.removalQuestion(s, "lab"))
                .isEqualTo("Remove connection lab? This closes it, unbinds 1 panel and deletes its saved password.");
        assertThat(ConnectionKind.INSTANCE.noticeSubject("lab")).isEqualTo(Notice.connection("lab"));
        assertThat(SessionKind.INSTANCE.noticeSubject("w")).isEqualTo(Notice.session("w"));
    }

    @Test
    void cloningASessionWithAConversationForksIt() {
        SessionSpec spec = SessionSpec.imported("edge", "/x", "id-1");
        assertThat(SessionKind.INSTANCE.cloneOf(spec, "edge 2").startMode()).isEqualTo(StartMode.FORK);
        assertThat(SessionKind.INSTANCE.cloneOf(SessionSpec.fresh("n", "/x"), "n 2").startMode()).isEqualTo(StartMode.NEW);
        assertThat(SessionSpec.imported("t", "/x", "c0ffee").startMode()).isEqualTo(StartMode.FORK);
    }
}
