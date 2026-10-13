package dev.marregui.comando.ui.panels;

import dev.marregui.comando.core.model.PanelId;
import dev.marregui.comando.core.state.AppState;
import dev.marregui.comando.core.state.SessionState;
import dev.marregui.comando.ui.widgets.terminal.TerminalWidget;

import javax.swing.JComponent;
import javax.swing.JPanel;
import java.awt.CardLayout;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * One terminal per editor panel, shown for the focused panel. A shell starts the first time its panel's
 * terminal is shown, in the working directory of the panel's session, else the home directory.
 */
public final class TerminalTabs {

    private final CardLayout layout = new CardLayout();
    private final JPanel cards = new JPanel(layout);
    private final Map<PanelId, TerminalWidget> terminals = new HashMap<>();
    private PanelId shown;

    public JComponent component() {
        return cards;
    }

    /** Shows the focused panel's terminal; starts its shell when {@code visible} and not yet started. */
    public void render(AppState s, boolean visible) {
        PanelId id = s.focused();
        Path dir = directory(s);
        TerminalWidget t = terminals.computeIfAbsent(id, k -> {
            TerminalWidget w = new TerminalWidget(dir);
            cards.add(w.component(), k.value());
            return w;
        });
        if (visible && !t.isStarted()) {
            t.setDirectory(dir);
            t.start();
        }
        if (!id.equals(shown)) {
            shown = id;
            layout.show(cards, id.value());
        }
    }

    static Path directory(AppState s) {
        return s.session(s.focusedPanel().session()).map(SessionState::spec).map(x -> Path.of(x.workDir()))
                .filter(Files::isDirectory).orElse(Path.of(System.getProperty("user.home")));
    }

    /** The focused panel's terminal, null before the panel's terminal was first rendered. */
    public TerminalWidget focused() {
        return shown == null ? null : terminals.get(shown);
    }

    /** Ends the shell of a closed panel. */
    public void forget(PanelId panel) {
        TerminalWidget t = terminals.remove(panel);
        if (t != null) {
            t.close();
            cards.remove(t.component());
        }
        if (panel.equals(shown)) {
            shown = null;
        }
    }

    public void closeAll() {
        terminals.values().forEach(TerminalWidget::close);
        terminals.clear();
        cards.removeAll();
        shown = null;
    }
}
