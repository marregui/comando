package dev.marregui.comando.frontear.watch;

import java.util.List;

import dev.marregui.comando.frontear.gateway.Gateway;
import dev.marregui.comando.frontear.octopus.LinkState;

/**
 * Node {@code node} on port {@code port} at {@code atMillis}: its broker's figures, its clients' connections by client
 * id, its links by id and their logs by link id. Each part is read on its own, so two parts may be a moment apart.
 */
public record NodeSnapshot(String node, int port, long atMillis, Gateway.Stats gateway,
        List<ConnectionView> connections, List<LinkState> links, List<LogView> logs) {
}
