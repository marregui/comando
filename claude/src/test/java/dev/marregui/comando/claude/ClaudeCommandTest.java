package dev.marregui.comando.claude;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;

import dev.marregui.comando.core.model.SessionSpec;
import dev.marregui.comando.core.model.StartMode;

import static org.assertj.core.api.Assertions.assertThat;

/// [ClaudeCommand] builds the headless session command line from a session specification.
///
/// - Gives new sessions their generated identifier and MCP configuration.
/// - Resumes or forks stored sessions by their recorded identifier.
/// - Omits optional flags whose specification values are empty.
/// - Names no permission mode and no extra folder: the kit's settings hold.
class ClaudeCommandTest {

    /// A new `edge` session includes stream JSON, its supplied `id-new`, MCP approval plumbing, and UI instructions;
    /// absent model and budget values must not add optional flags.
    @Test
    void newSessionGetsItsIdAndComandosTools() {
        final List<String> argv = ClaudeCommand.of("claude", SessionSpec.fresh("edge", "/w"), "id-new", Path.of("/run/x.json"), "ui text");
        assertThat(String.join(" ", argv)).startsWith("claude -p --input-format stream-json --output-format stream-json --verbose")
                .contains("--include-hook-events", "--session-id id-new", "--name edge", "--mcp-config /run/x.json",
                        "--permission-prompt-tool mcp__comando__approve", "--append-system-prompt ui text")
                .doesNotContain("--model", "--resume", "--max-budget-usd");
    }

    /// A fork of stored session `id-1` uses `--resume` and `--fork-session` while retaining model, effort and budget
    /// options; no new-session or MCP options are emitted without a configuration file.
    @Test
    void resumeAndForkUseTheStoredId() {
        final SessionSpec spec = new SessionSpec("edge", "/w", "opus", "high", 5.0, "id-1", StartMode.FORK, "", "");
        final String argv = String.join(" ", ClaudeCommand.of("claude", spec, "", null, "ui text"));
        assertThat(argv).contains("--resume id-1 --fork-session", "--model opus", "--effort high", "--max-budget-usd 5.0")
                .doesNotContain("--session-id", "--mcp-config", "--permission-prompt-tool", "--append-system-prompt");
    }

    /// No session's command line names a permission mode or an extra folder, with or without comando's tools: the kit's
    /// `settings.json` rules hold for every session alike (record 7), so a session an agent creates runs with its
    /// creator's permissions.
    @Test
    void noSessionIsGivenAPermissionModeOrAnExtraFolder() {
        final SessionSpec made = SessionSpec.fresh("helper", "/w").withCreatedBy("foreman");
        for (final List<String> argv : List.of(ClaudeCommand.of("claude", made, "id", Path.of("/run/x.json"), "ui"),
                ClaudeCommand.of("claude", made, "id", null, ""))) {
            assertThat(argv).doesNotContain("--permission-mode", "--add-dir", "--dangerously-skip-permissions");
        }
    }
}
