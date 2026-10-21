package dev.marregui.comando.ui.shell;

import dev.marregui.comando.ui.kit.action.Command;
import dev.marregui.comando.ui.kit.action.CommandPalette;
import dev.marregui.comando.ui.kit.format.KeyText;

import javax.swing.Action;
import javax.swing.JComponent;
import javax.swing.KeyStroke;
import java.awt.Component;
import java.awt.EventQueue;
import java.awt.event.ActionEvent;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Keys the shell forwards to the focused component, and the table Actions ▸ Keyboard shortcuts shows. */
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

    /**
     * Every command with a key, with its menu or area, as a Markdown table, grouped by where it belongs, each group where
     * its first command is; then the keys that belong to one place and are no command:
     * an approval card's, and those of the find bar, the transcript, the sessions list, the Files tab and the dialogs.
     */
    static String markdown(List<Command> commands) {
        StringBuilder md = new StringBuilder("# Keyboard shortcuts\n\n| Where | Command | Keys |\n|---|---|---|\n");
        Map<String, List<Command>> byPlace = new LinkedHashMap<>();
        for (Command c : commands) {
            if (c.key() != null) {
                byPlace.computeIfAbsent(c.group(), g -> new ArrayList<>()).add(c);
            }
        }
        for (Command c : byPlace.values().stream().flatMap(List::stream).toList()) {
            md.append("| ").append(c.group().isEmpty() ? "—" : c.group()).append(" | ").append(c.label().replace("|", "\\|"))
                    .append(" | ").append(CommandPalette.keyText(c.key())).append(" |\n");
        }
        md.append(KeyText.of(PLACES));
        return md.toString();
    }

    /** The keys of one place each, which no menu lists. */
    static final String PLACES = """

            ## On an approval card

            | Keys | Does |
            |---|---|
            | ⏎ or A | the green answer: allow, run, answer, approve |
            | D or ⎋ | deny; ⎋ first cancels a pending Always |
            | S | a tool card: allow for the session |
            | L, then L again within 3 s | a tool card: allow always |
            | ↑ ↓ | the card above, the card below |
            | ⏎ in the note | deny with the note once it holds text; on an empty note, the green answer |
            | ⌘⏎ in the note | deny with the note |
            | ⏎ in the Other answer | a new line; only the Answer button sends it |
            | ⎋ in the note or the Other answer | back to the card |

            ## Elsewhere

            | Where | Keys | Does |
            |---|---|---|
            | Find bar | ⏎, ⇧⏎, ⎋ | next match, previous match, close |
            | Transcript | ⌘C, ⌘A | copy the selection, select all |
            | Sessions list | ⌘C | copy the selected session's details |
            | Files tab | ⏎ | open the selected file in a panel |
            | Files tab filter | ⎋ | clear the filter |
            | Connection and session dialogs | ⏎, ⌘⏎, ⎋ | save, save and assign to the panel, close (asking first when unsaved) |
            """;
}
