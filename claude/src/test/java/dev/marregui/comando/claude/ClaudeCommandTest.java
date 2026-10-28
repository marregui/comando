package dev.marregui.comando.claude;

import dev.marregui.comando.core.model.SessionSpec;
import dev.marregui.comando.core.model.StartMode;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/// [ClaudeCommand] builds the headless session command line from a session specification.
///
/// - Gives new sessions their generated identifier and MCP configuration.
/// - Resumes or forks stored sessions by their recorded identifier.
/// - Omits optional flags whose specification values are empty.
class ClaudeCommandTest {

    /// A new `edge` session includes stream JSON, its supplied `id-new`, MCP approval plumbing, and UI instructions;
    /// absent model and budget values must not add optional flags.
    @Test
    void newSessionGetsItsIdAndComandosTools() {
        List<String> argv = ClaudeCommand.of("claude", SessionSpec.fresh("edge", "/w"), "id-new", Path.of("/run/x.json"), "ui text");
        assertThat(String.join(" ", argv)).startsWith("claude -p --input-format stream-json --output-format stream-json --verbose")
                .contains("--include-hook-events", "--session-id id-new", "--name edge", "--mcp-config /run/x.json",
                        "--permission-prompt-tool mcp__comando__approve", "--append-system-prompt ui text")
                .doesNotContain("--model", "--resume", "--max-budget-usd");
    }

    /// A fork of stored session `id-1` uses `--resume` and `--fork-session` while retaining model, effort, directory,
    /// permission mode, and budget options; no new-session or MCP options are emitted without a configuration file.
    @Test
    void resumeAndForkUseTheStoredId() {
        SessionSpec spec = new SessionSpec("edge", "/w", "opus", "high", "acceptEdits", List.of("/a"), 5.0, "id-1", StartMode.FORK, "", "");
        String argv = String.join(" ", ClaudeCommand.of("claude", spec, "", null, "ui text"));
        assertThat(argv).contains("--resume id-1 --fork-session", "--model opus", "--effort high", "--permission-mode acceptEdits",
                        "--add-dir /a", "--max-budget-usd 5.0")
                .doesNotContain("--session-id", "--mcp-config", "--permission-prompt-tool", "--append-system-prompt");
    }
}
