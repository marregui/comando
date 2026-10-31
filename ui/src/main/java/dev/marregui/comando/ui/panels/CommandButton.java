package dev.marregui.comando.ui.panels;

import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;
import javax.swing.JButton;
import javax.swing.Timer;

import dev.marregui.comando.ui.kit.action.Command;
import dev.marregui.comando.ui.kit.icon.Icons;

/**
 * A toolbar button beside the theme's swatch for one or two commands, such as start and stop: it shows the first of
 * them that can run now, its icon, its tooltip and its accessible name, and runs it on a click; disabled when none can,
 * keeping the face it had, so a stop under way still shows stop. It reads its commands on each refresh and once a
 * second while shown, since a server or a plot changes on its own; a status, when there is one, follows the tooltip.
 */
public final class CommandButton extends JButton {

    private final List<Command> commands;
    private final Function<String, String> tips;
    private final Supplier<String> status;
    private final Timer poll = new Timer(1000, e -> refresh());
    private Command shown;

    /** {@code tips} gives each command's tooltip by its id; its label when it gives none. */
    public CommandButton(final List<Command> commands, final Function<String, String> tips) {
        this(commands, tips, () -> "");
    }

    /** As above, with {@code status} read at each refresh and added to the tooltip when not empty. */
    public CommandButton(final List<Command> commands, final Function<String, String> tips, final Supplier<String> status) {
        this.commands = List.copyOf(commands);
        this.tips = tips;
        this.status = status;
        putClientProperty("JButton.buttonType", "toolBarButton");
        setFocusable(false);
        addActionListener(e -> {
            refresh();
            if (shown != null && shown.enabled().getAsBoolean()) {
                shown.run().run();
            }
            refresh();
        });
        refresh();
    }

    /** Shows the first command that can run now; when none can, the one shown before (first, the first), disabled. */
    public void refresh() {
        final Command now = commands.stream().filter(c -> c.enabled().getAsBoolean()).findFirst().orElse(null);
        final Command face = now != null ? now : shown != null ? shown : commands.getFirst();
        if (face != shown) {
            shown = face;
            setIcon(face.icon() == null ? null : Icons.get(face.icon()));
            getAccessibleContext().setAccessibleName(face.label());
        }
        setEnabled(now != null);
        final String tip = tips.apply(face.id());
        String line = tip == null ? face.label() : face.label() + ": " + tip;
        final String more = status.get();
        line = more == null || more.isEmpty() ? line : line + " (" + more + ")";
        if (!Objects.equals(line, getToolTipText())) {
            setToolTipText(line);
        }
    }

    @Override
    public void addNotify() {
        super.addNotify();
        poll.start();
    }

    @Override
    public void removeNotify() {
        poll.stop();
        super.removeNotify();
    }
}
