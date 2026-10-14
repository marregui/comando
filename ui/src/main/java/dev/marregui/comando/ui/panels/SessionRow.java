package dev.marregui.comando.ui.panels;

import dev.marregui.comando.core.state.SessionState;
import dev.marregui.comando.ui.kit.format.Labels;
import dev.marregui.comando.ui.kit.icon.Icons;
import dev.marregui.comando.ui.kit.text.MarkdownHtml;
import dev.marregui.comando.ui.kit.theme.Theme;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.FlowLayout;
import java.awt.Insets;
import java.awt.Point;
import java.awt.Rectangle;
import java.util.Locale;
import java.util.Optional;

/**
 * One session in the sidebar's list: its state, name, where it is bound and pending approvals; its folder and
 * branch; and a row of controls: connect or disconnect, the model, the effort, and edit. The list paints it as a
 * cell renderer, so its buttons are not live: the list finds the control under a click with {@link #hit}.
 */
final class SessionRow extends JPanel {

    /** The controls of a row, in their order. */
    enum Control {
        POWER, MODEL, EFFORT, EDIT
    }

    private final JLabel text = new JLabel();
    private final JPanel controls = new JPanel(new FlowLayout(FlowLayout.LEFT, 2, 0));
    private final JButton power = control();
    private final JButton model = control();
    private final JButton effort = control();
    private final JButton edit = control();

    SessionRow() {
        super(new BorderLayout(0, 2));
        setBorder(BorderFactory.createEmptyBorder(5, 6, 5, 6));
        text.setIcon(Icons.get("claude"));
        text.setIconTextGap(6);
        controls.setOpaque(false);
        // under the text, past the icon's column
        controls.setBorder(BorderFactory.createEmptyBorder(0, Icons.get("claude").getIconWidth() + 2, 0, 0));
        edit.setIcon(Icons.get("edit", 14));
        controls.add(power);
        controls.add(model);
        controls.add(effort);
        controls.add(edit);
        add(text, BorderLayout.CENTER);
        add(controls, BorderLayout.SOUTH);
    }

    /** Shows {@code s}; {@code bound} names the panels it is bound to, {@code approvals} how many wait. */
    SessionRow show(SessionState s, String bound, int approvals, boolean selected, Color background, Color foreground) {
        setOpaque(true);
        setBackground(background);
        text.setForeground(foreground);
        text.setText(html(s, bound, approvals));
        boolean running = s.status().isUsable();
        power.setIcon(Icons.get(running ? "unplug" : "plug", 14));
        power.setToolTipText(running ? "Disconnect: stop the session" : "Connect: start the session");
        model.setText(modelName(s) + " ▾");
        model.setToolTipText("Model: " + modelName(s) + "; click to change");
        effort.setText(effortName(s) + " ▾");
        effort.setToolTipText("Effort: " + effortName(s) + "; click to change");
        edit.setToolTipText("Edit the session…");
        for (JButton b : new JButton[]{power, model, effort, edit}) {
            b.setForeground(selected ? foreground : Theme.MUTED_STRONG);
        }
        return this;
    }

    /** The control at {@code p}, a point in the cell whose bounds this row was laid out to. */
    Optional<Control> hit(Rectangle cell, Point p) {
        layOut(cell);
        Component c = SwingUtilities.getDeepestComponentAt(this, p.x - cell.x, p.y - cell.y);
        return c == power ? Optional.of(Control.POWER) : c == model ? Optional.of(Control.MODEL)
                : c == effort ? Optional.of(Control.EFFORT) : c == edit ? Optional.of(Control.EDIT) : Optional.empty();
    }

    /** Where {@code control} is, in the list's coordinates, for a menu to drop from it. */
    Rectangle bounds(Rectangle cell, Control control) {
        layOut(cell);
        JButton b = button(control);
        Rectangle r = SwingUtilities.convertRectangle(b.getParent(), b.getBounds(), this);
        r.translate(cell.x, cell.y);
        return r;
    }

    /** The tooltip of the control at {@code p}, or empty over the text. */
    Optional<String> tip(Rectangle cell, Point p) {
        return hit(cell, p).map(c -> button(c).getToolTipText());
    }

    private JButton button(Control c) {
        return switch (c) {
            case POWER -> power;
            case MODEL -> model;
            case EFFORT -> effort;
            case EDIT -> edit;
        };
    }

    /** A renderer is never in a window, so nothing lays it out for a hit test; this does, as painting would. */
    private void layOut(Rectangle cell) {
        setBounds(0, 0, cell.width, cell.height);
        layOut((Container) this);
    }

    private static void layOut(Container c) {
        c.doLayout();
        for (Component child : c.getComponents()) {
            if (child instanceof Container k) {
                layOut(k);
            }
        }
    }

    /** The model the session asked for, else the one Claude Code reported, else "default"; a full id by its family. */
    static String modelName(SessionState s) {
        String m = !s.spec().model().isBlank() ? s.spec().model() : s.info().model();
        if (m.isBlank()) {
            return "default";
        }
        String lower = m.toLowerCase(Locale.ROOT);
        for (String family : new String[]{"fable", "opus", "sonnet", "haiku"}) {
            if (lower.contains(family)) {
                return family;
            }
        }
        return m;
    }

    static String effortName(SessionState s) {
        return s.spec().effort().isBlank() ? "default" : s.spec().effort();
    }

    /** Two lines that never wrap: a narrow sidebar clips them at its edge. */
    private static String html(SessionState s, String bound, int approvals) {
        String dot = "<span style='color:" + Theme.hex(EditorPanel.color(s.status())) + "'>" + EditorPanel.dot(s.status()) + "</span>";
        String badge = approvals == 0 ? "" : "&nbsp;<span style='color:" + Theme.hex(Theme.WARN) + "'>⚑" + approvals + "</span>";
        String where = bound.isEmpty() ? "" : "&nbsp;<span style='color:" + Theme.hex(bound.equals("this panel") ? Theme.ACCENT : Theme.MUTED_STRONG)
                + "'>· " + MarkdownHtml.escape(clip(bound, 24)) + "</span>";
        String busy = !s.isBusy() ? "" : "&nbsp;&nbsp;<span style='color:" + Theme.hex(Theme.MUTED) + "'>"
                + MarkdownHtml.escape(s.activity().isEmpty() ? "working" : clip(s.activity(), 30)) + "</span>";
        String branch = s.branch().isEmpty() ? "" : " ⎇ " + MarkdownHtml.escape(s.branch());
        return "<html><nobr>" + dot + "&nbsp;<b>" + MarkdownHtml.escape(s.name()) + "</b>" + where + badge + busy
                + "</nobr><br><nobr><span style='color:" + Theme.hex(Theme.MUTED_STRONG) + "'>"
                + MarkdownHtml.escape(clip(Labels.shortPath(s.spec().workDir()), 34)) + branch + "</span></nobr></html>";
    }

    private static String clip(String s, int max) {
        return s.length() <= max ? s : "…" + s.substring(s.length() - max + 1);
    }

    private static JButton control() {
        JButton b = new JButton();
        b.putClientProperty("JButton.buttonType", "toolBarButton");
        b.setFocusable(false);
        b.setFont(b.getFont().deriveFont(b.getFont().getSize2D() - 1f));
        b.setMargin(new Insets(1, 4, 1, 4));
        return b;
    }
}
