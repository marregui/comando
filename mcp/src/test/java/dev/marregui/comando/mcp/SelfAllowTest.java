package dev.marregui.comando.mcp;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.marregui.comando.core.intent.Intent;
import dev.marregui.comando.core.json.Json;
import dev.marregui.comando.core.json.JsonNode;
import dev.marregui.comando.core.resource.SessionKind;
import dev.marregui.comando.core.state.SessionInfo;
import dev.marregui.comando.core.transcript.TranscriptRegistry;
import dev.marregui.comando.testkit.FakeClock;
import dev.marregui.comando.testkit.States;

import static org.assertj.core.api.Assertions.assertThat;

/// A session in bypass gets no card for Claude Code's own checks (record 164): comando allows the call once itself,
/// says so in a notice and records it in the audit log.
/// - a tool call of a session in bypass is allowed with no card, its input unchanged and no rule saved
/// - a command that removes files, a question, a plan and a session not in bypass still get a card
class SelfAllowTest {

    @TempDir
    Path audit;

    private final List<Intent> intents = new CopyOnWriteArrayList<>();

    @AfterEach
    void kitDefaults() {
        SessionKind.INSTANCE.defaults(SessionKind.Defaults.NONE);
    }

    /// A Bash command Claude Code's parser cannot read, from a session that reported bypass, comes back allowed with
    /// its input unchanged and no rule: no card is raised, a notice names the session, the audit log says comando
    /// allowed it. Any tool, not only Bash, is allowed so.
    @Test
    void aSessionInBypassIsAllowedWithNoCard() throws Exception {
        final McpTools tools = tools("bypassPermissions");
        final String command = "F=(${(f)\"$(git status --porcelain)\"}); wc -l \"${F[@]}\"";
        final JsonNode r = approve(tools, "Bash", Json.object().put("command", command).toString());
        assertThat(r.path("behavior").asString()).isEqualTo("allow");
        assertThat(r.path("updatedInput").path("command").asString()).isEqualTo(command);
        assertThat(r.has("updatedPermissions")).as("allowed once: no rule kept").isFalse();
        assertThat(carded()).isFalse();
        assertThat(notices()).singleElement().satisfies(t -> assertThat(t).startsWith("Allowed for work: "));
        assertThat(auditText()).contains(McpTools.SELF_ALLOWED);

        intents.clear();
        final String write = "{\"file_path\":\"/w/a.txt\",\"content\":\"x\"}";
        assertThat(approve(tools, "Write", write).path("behavior").asString())
                .isEqualTo("allow");
        assertThat(carded()).isFalse();
    }

    /// A session that reported nothing yet goes by the kit's mode, as a plan's approval does.
    @Test
    void aSessionThatReportedNothingGoesByTheKit() throws Exception {
        SessionKind.INSTANCE.defaults(new SessionKind.Defaults("", "", "bypassPermissions"));
        assertThat(approve(tools(""), "Bash", "{\"command\":\"ls\"}").path("behavior").asString()).isEqualTo("allow");
        assertThat(carded()).isFalse();
    }

    /// A removal anywhere in a command keeps its card in bypass: at its start, after `&&` or `;`, in a loop or a
    /// group, through `xargs`, `find -exec`, `sudo`, `bash -c`, by path; `git rm`, `trash` and a word holding "rm" do
    /// not count as one.
    @Test
    void aRemovalStillGoesToTheUser() throws Exception {
        final McpTools tools = tools("bypassPermissions");
        for (final String command : List.of("rm -rf build", "cd /tmp && rm -rf x", "ls; rmdir d",
                "git ls-files | xargs -0 rm", "find . -name '*.o' -exec rm {} +", "sudo -n rm -f /etc/x", "for f in a b; do rm $f; done", "{ rm x; }",
                "/bin/rm x", "bash -c 'rm x'", "xargs -I {} rm {}", "find . -execdir rm {} +", "command rm x")) {
            intents.clear();
            approve(tools, "Bash", Json.object().put("command", command).toString());
            assertThat(carded()).as(command).isTrue();
        }
        for (final String command : List.of("git rm --cached a", "echo firmware", "trash build", "ls ./rmdir-notes")) {
            intents.clear();
            approve(tools, "Bash", Json.object().put("command", command).toString());
            assertThat(carded()).as(command).isFalse();
        }
    }

    /// A question, a plan, an MCP tool (the kit's ask rules name Slack and Gmail sends), a session in plan mode with a
    /// kit in bypass, and any call of a session not in bypass get their card.
    @Test
    void questionsPlansMcpToolsAndOtherModesGetTheirCard() throws Exception {
        final McpTools bypass = tools("bypassPermissions");
        approve(bypass, "mcp__claude_ai_Slack__slack_send_message", "{\"channel_id\":\"C1\",\"message\":\"hi\"}");
        assertThat(carded()).as("an MCP tool").isTrue();
        intents.clear();
        SessionKind.INSTANCE.defaults(new SessionKind.Defaults("", "", "bypassPermissions"));
        approve(tools("plan"), "Edit", "{\"file_path\":\"/w/a.txt\",\"old_string\":\"a\",\"new_string\":\"b\"}");
        assertThat(carded()).as("a session in plan mode").isTrue();
        intents.clear();
        final String question = "{\"questions\":[{\"question\":\"Colour?\",\"options\":[{\"label\":\"Red\"}]}]}";
        approve(bypass, "AskUserQuestion", question);
        assertThat(carded()).as("a question").isTrue();
        intents.clear();
        approve(bypass, "ExitPlanMode", "{\"plan\":\"1. do it\"}");
        assertThat(carded()).as("a plan").isTrue();
        intents.clear();
        approve(tools("default"), "Bash", "{\"command\":\"ls\"}");
        assertThat(carded()).as("a session not in bypass").isTrue();
    }

    /// Tools whose session `work` reported `mode`; a card waits 50 ms and is denied, so a test that raises one goes on.
    private McpTools tools(final String mode) {
        final SessionInfo info = new SessionInfo("conversation", "opus", "/w", "2.1", mode, List.of());
        return new McpTools(intents::add, () -> States.twoPanels().withSession("work", s -> s.withInfo(info)),
                new TranscriptRegistry(), null, new AuditLog(audit), new FakeClock(), Duration.ofMillis(50));
    }

    private static JsonNode approve(final McpTools tools, final String tool, final String input) throws Exception {
        final McpTools.Outcome out = tools.call("work", McpTools.APPROVE,
                Json.readTree("{\"tool_name\":\"" + tool + "\",\"input\":" + input + ",\"tool_use_id\":\"t1\"}"));
        return out.error() ? Json.object().put("behavior", "error") : Json.readTree(out.text());
    }

    private boolean carded() {
        return intents.stream().anyMatch(Intent.ApprovalRequested.class::isInstance);
    }

    private List<String> notices() {
        final List<String> out = new ArrayList<>();
        for (final Intent i : intents) {
            if (i instanceof Intent.Notify(_, final String text, _, _)) {
                out.add(text);
            }
        }
        return out;
    }

    private String auditText() throws Exception {
        final StringBuilder all = new StringBuilder();
        try (Stream<Path> files = Files.list(audit)) {
            for (final Path f : files.toList()) {
                all.append(Files.readString(f));
            }
        }
        return all.toString();
    }
}
