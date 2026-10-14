package dev.marregui.comando.ui.shell;

import dev.marregui.comando.ui.kit.action.Command;
import dev.marregui.comando.ui.kit.action.CommandPalette;

import javax.swing.Action;
import javax.swing.JComponent;
import javax.swing.KeyStroke;
import java.awt.Component;
import java.awt.EventQueue;
import java.awt.event.ActionEvent;
import java.util.List;

/** Keys the shell forwards to the focused component, and the table Help ▸ Keyboard shortcuts shows. */
final class Keys {

    private Keys() {
    }

    /**
     * Whether {@code target} binds {@code key} to an enabled action, in its focused or ancestor input map; runs the
     * action when {@code run}. Components that read keys themselves, as the terminal does, have no such binding.
     */
    static boolean forward(Component target, KeyStroke key, boolean run) {
        if (!(target instanceof JComponent c)) {
            return false;
        }
        Object name = c.getInputMap(JComponent.WHEN_FOCUSED).get(key);
        if (name == null) {
            name = c.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).get(key);
        }
        Action a = name == null ? null : c.getActionMap().get(name);
        if (a == null || !a.isEnabled()) {
            return false;
        }
        if (run) {
            a.actionPerformed(new ActionEvent(c, ActionEvent.ACTION_PERFORMED, String.valueOf(name), EventQueue.getMostRecentEventTime(),
                    key.getModifiers()));
        }
        return true;
    }

    /** Every command with a key, by menu, as a Markdown table, then the keys of the cards. */
    static String markdown(List<Command> commands) {
        StringBuilder md = new StringBuilder("# Keyboard shortcuts\n\n| Menu | Command | Keys |\n|---|---|---|\n");
        for (Command c : commands) {
            if (c.key() != null) {
                md.append("| ").append(c.group().isEmpty() ? "—" : c.group()).append(" | ").append(c.label().replace("|", "\\|"))
                        .append(" | ").append(CommandPalette.keyText(c.key())).append(" |\n");
            }
        }
        md.append("\nOn an approval card: ⏎ or A gives the green answer (allow, run, answer, approve), D or ⎋ denies; on a tool card S ")
                .append("allows for the session and L twice allows always; ↑ and ↓ move between cards.\n");
        return md.toString();
    }
}
