package dev.marregui.comando.core.resource;

import dev.marregui.comando.core.model.ConnectionSpec;
import dev.marregui.comando.core.model.SessionSpec;
import dev.marregui.comando.core.model.StartMode;
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
        assertThatThrownBy(() -> SessionKind.INSTANCE.fromAttributes(s)).hasMessageContaining("needs a claudeSessionId");
    }

    @Test
    void cloningASessionWithAConversationForksIt() {
        SessionSpec spec = SessionSpec.imported("edge", "/x", "id-1");
        assertThat(SessionKind.INSTANCE.cloneOf(spec, "edge 2").startMode()).isEqualTo(StartMode.FORK);
        assertThat(SessionKind.INSTANCE.cloneOf(SessionSpec.fresh("n", "/x"), "n 2").startMode()).isEqualTo(StartMode.NEW);
        assertThat(SessionSpec.imported("t", "/x", "c0ffee").startMode()).isEqualTo(StartMode.FORK);
    }
}
