package dev.marregui.comando.claude;

import dev.marregui.comando.core.model.SessionSpec;
import dev.marregui.comando.core.model.StartMode;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ClaudeCommandTest {

    @Test
    void newSessionGetsItsIdAndComandosTools() {
        List<String> argv = ClaudeCommand.of("claude", SessionSpec.fresh("edge", "/w"), "id-new", Path.of("/run/x.json"), "ui text");
        assertThat(String.join(" ", argv)).startsWith("claude -p --input-format stream-json --output-format stream-json --verbose")
                .contains("--include-hook-events", "--session-id id-new", "--name edge", "--mcp-config /run/x.json",
                        "--permission-prompt-tool mcp__comando__approve", "--append-system-prompt ui text")
                .doesNotContain("--model", "--resume", "--max-budget-usd");
    }

    @Test
    void resumeAndForkUseTheStoredId() {
        SessionSpec spec = new SessionSpec("edge", "/w", "opus", "high", "acceptEdits", List.of("/a"), 5.0, "id-1", StartMode.FORK, "", "");
        String argv = String.join(" ", ClaudeCommand.of("claude", spec, "", null, "ui text"));
        assertThat(argv).contains("--resume id-1 --fork-session", "--model opus", "--effort high", "--permission-mode acceptEdits",
                        "--add-dir /a", "--max-budget-usd 5.0")
                .doesNotContain("--session-id", "--mcp-config", "--permission-prompt-tool", "--append-system-prompt");
    }
}
