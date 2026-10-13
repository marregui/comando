package dev.marregui.comando.testkit;

import dev.marregui.comando.core.model.ConnectionSpec;
import dev.marregui.comando.core.model.PanelId;
import dev.marregui.comando.core.model.SessionSpec;
import dev.marregui.comando.core.state.AppState;
import dev.marregui.comando.core.state.ConnectionState;
import dev.marregui.comando.core.state.PanelState;
import dev.marregui.comando.core.state.SessionState;

import java.util.List;

/** A small state used by most tests: panel p1 bound to connection "local" and session "work". */
public final class States {

    public static final PanelId P1 = new PanelId("p1");
    public static final PanelId P2 = new PanelId("p2");
    public static final ConnectionSpec LOCAL = ConnectionSpec.questDbDefaults("local");
    public static final SessionSpec WORK = SessionSpec.fresh("work", "/tmp");

    private States() {
    }

    public static AppState twoPanels() {
        return AppState.initial(
                List.of(new PanelState(P1, "one", "local", "work"), new PanelState(P2, "two", "local", "work")),
                List.of(ConnectionState.closed(LOCAL)),
                List.of(SessionState.closed(WORK)));
    }
}
