package dev.marregui.comando.core.port;

import java.util.List;

import dev.marregui.comando.core.model.ConnectionSpec;
import dev.marregui.comando.core.model.FolderRoot;
import dev.marregui.comando.core.model.SessionSpec;
import dev.marregui.comando.core.state.PanelState;

/** Writes the store files; each call replaces the file's content with the given snapshot. */
public interface PersistencePort {

    void saveConnections(List<ConnectionSpec> specs);

    void saveSessions(List<SessionSpec> specs);

    void saveFolders(List<FolderRoot> folders);

    /** The open panels, in tab order, and the closed panels of comando's own, which keep their files. */
    void savePanels(List<PanelState> panels, List<PanelState> closed);
}
