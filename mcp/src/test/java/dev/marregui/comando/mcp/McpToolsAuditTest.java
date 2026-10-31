package dev.marregui.comando.mcp;

import dev.marregui.comando.core.intent.Intent;
import dev.marregui.comando.core.port.QueryPort;
import dev.marregui.comando.core.state.AppState;
import dev.marregui.comando.core.transcript.TranscriptRegistry;
import dev.marregui.comando.testkit.FakeClock;
import dev.marregui.comando.testkit.RecordingSink;
import dev.marregui.comando.testkit.States;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/// The audit log records every tool call's arguments with each credential written `***`: a password Claude saves
/// through `connection_save` reaches the connection, not the audit file.
class McpToolsAuditTest {

    private final ObjectMapper json = new ObjectMapper();

    @TempDir
    Path auditDir;

    /// A `connection_save` call through the tool path saves the password it carries, and the audit file holds
    /// `"password":"***"` and nowhere the password itself, while the other arguments stay readable.
    @Test
    void aSavedConnectionsPasswordIsWrittenAsStarsInTheAuditFile() throws Exception {
        final RecordingSink sink = new RecordingSink();
        final AtomicReference<AppState> state = new AtomicReference<>(States.twoPanels());
        final QueryPort db = null;
        final McpTools tools = new McpTools(sink, state::get, new TranscriptRegistry(), db, new AuditLog(auditDir), new FakeClock(),
                Duration.ofMinutes(1));

        final McpTools.Outcome out = tools.call("work", "connection_save", json.readTree(
                "{\"name\":\"quest\",\"host\":\"localhost\",\"database\":\"qdb\",\"user\":\"admin\",\"password\":\"hunter2-s3cret\"}"));

        assertThat(out.error()).as(out.text()).isFalse();
        assertThat(sink.of(Intent.SaveConnection.class).getFirst().spec().password()).isEqualTo("hunter2-s3cret");
        final String audit;
        try (final Stream<Path> files = Files.list(auditDir)) {
            final List<Path> days = files.toList();
            assertThat(days).hasSize(1);
            audit = Files.readString(days.getFirst());
        }
        assertThat(audit).doesNotContain("hunter2-s3cret");
        final JsonNode detail = json.readTree(json.readTree(audit).path("detail").asString());
        assertThat(detail.path("password").asString()).isEqualTo(McpTools.REDACTED);
        assertThat(detail.path("host").asString()).isEqualTo("localhost");
        assertThat(detail.path("user").asString()).isEqualTo("admin");
    }

    /// Every field whose name says it holds a credential is written `***` at any depth, in objects and in arrays, the
    /// match ignoring case; other fields keep their values and the node passed in is not changed.
    @Test
    void secretBearingFieldsAreRedactedAtAnyDepthAndTheInputIsLeftAsItWas() throws Exception {
        final JsonNode args = json.readTree("{\"Password\":\"p\",\"nested\":{\"client_secret\":\"s\",\"list\":[{\"accessToken\":\"t\"},"
                + "{\"api-key\":\"k\",\"apiKey\":\"k2\",\"api_key\":\"k3\"}]},\"name\":\"n\"}");

        final JsonNode out = json.readTree(McpTools.redacted(args));

        assertThat(out.path("Password").asString()).isEqualTo("***");
        assertThat(out.path("nested").path("client_secret").asString()).isEqualTo("***");
        assertThat(out.path("nested").path("list").path(0).path("accessToken").asString()).isEqualTo("***");
        assertThat(out.path("nested").path("list").path(1).path("api-key").asString()).isEqualTo("***");
        assertThat(out.path("nested").path("list").path(1).path("apiKey").asString()).isEqualTo("***");
        assertThat(out.path("nested").path("list").path(1).path("api_key").asString()).isEqualTo("***");
        assertThat(out.path("name").asString()).isEqualTo("n");
        assertThat(args.path("Password").asString()).as("the caller's node").isEqualTo("p");
    }
}
