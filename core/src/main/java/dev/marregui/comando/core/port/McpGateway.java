package dev.marregui.comando.core.port;

/**
 * comando's MCP server as the Claude adapter sees it: each session gets its own bearer token, so the
 * server knows which session a tool call comes from.
 */
public interface McpGateway {

    /** URL and token a session's CLI uses to reach the server. */
    record Ticket(String url, String token) {
    }

    /** No server: sessions start without comando's tools or the approval tool. */
    McpGateway NONE = new McpGateway() {
        @Override
        public Ticket register(String session) {
            return null;
        }

        @Override
        public void unregister(String session, Ticket ticket) {
        }
    };

    /** A new ticket for one process of {@code session}, or null when no server runs. */
    Ticket register(String session);

    /**
     * Revokes {@code ticket}, the one {@link #register} gave this process; a newer process of a session with
     * the same name keeps its own ticket.
     */
    void unregister(String session, Ticket ticket);
}
