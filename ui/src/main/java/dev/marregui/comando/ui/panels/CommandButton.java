package dev.marregui.comando.ui.panels;

import dev.marregui.comando.ui.kit.action.Command;
import dev.marregui.comando.ui.kit.icon.Icons;

import javax.swing.JButton;
import javax.swing.Timer;
import java.util.List;
import java.util.function.Function;

/**
 * A toolbar button beside the theme's swatch for one or two commands, such as start and stop: it shows the first of
 * them that can run now, its icon, its tooltip and its accessible name, and runs it on a click; disabled when none can. It
 * reads its commands on each refresh and once a second while shown, since a server or a plot changes on its own.
 */
public final class CommandButton extends JButton {

    private final List<Command> commands;
    private final Function<String, String> tips;
    private final Timer poll = new Timer(1000, e -> refresh());
    private Command shown;

    /** {@code tips} gives each command's tooltip by its id; its label when it gives none. */
    public CommandButton(List<Command> commands, Function<String, String> tips) {
        this.commands = List.copyOf(commands);
        this.tips = tips;
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

    /** Shows the first command that can run now, or the first one, disabled. */
    public void refresh() {
        Command now = commands.stream().filter(c -> c.enabled().getAsBoolean()).findFirst().orElse(null);
        Command face = now == null ? commands.getFirst() : now;
        if (face != shown || isEnabled() != (now != null)) {
            shown = face;
            setIcon(face.icon() == null ? null : Icons.get(face.icon()));
            String tip = tips.apply(face.id());
            setToolTipText(tip == null ? face.label() : face.label() + ": " + tip);
            getAccessibleContext().setAccessibleName(face.label());
            setEnabled(now != null);
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
