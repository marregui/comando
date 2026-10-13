package dev.marregui.comando.mcp;

import jdk.jfr.Category;
import jdk.jfr.Description;
import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.StackTrace;

/** One MCP tool call from a session, including the wait for an approval. */
@Name("comando.McpCall")
@Label("MCP call")
@Category("comando")
@Description("A tool call from a Claude Code session to comando's MCP server")
@StackTrace(false)
final class McpCallEvent extends Event {

    @Label("Session")
    String session;

    @Label("Tool")
    String tool;

    @Label("Error")
    boolean error;
}
