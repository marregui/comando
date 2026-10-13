package dev.marregui.comando.core.port;

import dev.marregui.comando.core.model.ConnectionSpec;
import dev.marregui.comando.core.model.SessionSpec;
import dev.marregui.comando.core.state.PanelState;

import java.util.List;

/** Writes the store files; each call replaces the file's content with the given snapshot. */
public interface PersistencePort {

    void saveConnections(List<ConnectionSpec> specs);

    void saveSessions(List<SessionSpec> specs);

    void savePanels(List<PanelState> panels);
}
