package dev.marregui.comando.core.model;

/** What an approval card asks the user. */
public enum ApprovalKind {
    /** Claude Code asks permission to run a tool (Bash, Edit, Write, an MCP tool). */
    TOOL,
    /** Claude asks the user questions through AskUserQuestion. */
    QUESTION,
    /** Claude presents a plan through ExitPlanMode. */
    PLAN,
    /** Claude asks to run a statement that is not read-only through {@code db_run_sql}. */
    SQL_WRITE,
    /** A session asks to send a prompt to another session through {@code session_send}. */
    SESSION_PROMPT;

    public static ApprovalKind ofTool(String toolName) {
        return switch (toolName) {
            case "AskUserQuestion" -> QUESTION;
            case "ExitPlanMode" -> PLAN;
            default -> TOOL;
        };
    }
}
