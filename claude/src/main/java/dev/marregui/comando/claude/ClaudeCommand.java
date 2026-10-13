package dev.marregui.comando.claude;

import dev.marregui.comando.core.model.SessionSpec;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Builds the argument list of a headless Claude Code process for a session. */
public final class ClaudeCommand {

    public static final String APPROVE_TOOL = "mcp__comando__approve";

    private ClaudeCommand() {
    }

    /**
     * @param newSessionId the id to give a NEW session ({@code --session-id}); ignored for RESUME and FORK
     * @param mcpConfig    the MCP config file naming comando's server, or null for none
     */
    public static List<String> of(String executable, SessionSpec spec, String newSessionId, Path mcpConfig, String uiInstructions) {
        List<String> a = new ArrayList<>();
        a.add(executable);
        a.add("-p");
        a.add("--input-format");
        a.add("stream-json");
        a.add("--output-format");
        a.add("stream-json");
        a.add("--verbose");
        a.add("--include-partial-messages");
        a.add("--forward-subagent-text");
        a.add("--include-hook-events"); // hook output beyond SessionStart (UserPromptSubmit, PreToolUse, ...) reaches the transcript
        switch (spec.startMode()) {
            case NEW -> {
                a.add("--session-id");
                a.add(newSessionId);
            }
            case RESUME -> {
                a.add("--resume");
                a.add(spec.claudeSessionId());
            }
            case FORK -> {
                a.add("--resume");
                a.add(spec.claudeSessionId());
                a.add("--fork-session");
            }
        }
        a.add("--name");
        a.add(spec.name());
        if (mcpConfig != null) {
            a.add("--mcp-config");
            a.add(mcpConfig.toString());
            a.add("--permission-prompt-tool");
            a.add(APPROVE_TOOL);
        }
        if (!uiInstructions.isEmpty() && mcpConfig != null) {
            a.add("--append-system-prompt");
            a.add(uiInstructions);
        }
        option(a, "--model", spec.model());
        option(a, "--effort", spec.effort());
        option(a, "--permission-mode", spec.permissionMode());
        for (String dir : spec.addDirs()) {
            a.add("--add-dir");
            a.add(dir);
        }
        if (spec.budgetUsd() > 0.0) {
            a.add("--max-budget-usd");
            a.add(Double.toString(spec.budgetUsd()));
        }
        return a;
    }

    private static void option(List<String> args, String flag, String value) {
        if (!value.isEmpty()) {
            args.add(flag);
            args.add(value);
        }
    }
}
