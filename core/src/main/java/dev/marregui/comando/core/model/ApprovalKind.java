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
    /**
     * An agent asks, through {@code connection_save}, to clear a connection's production flag or to weaken its TLS
     * mode: guards the user set against the agent itself (records 20, 23).
     */
    CONNECTION;

    public static ApprovalKind ofTool(final String toolName) {
        return switch (toolName) {
            case "AskUserQuestion" -> QUESTION;
            case "ExitPlanMode" -> PLAN;
            default -> TOOL;
        };
    }
}
