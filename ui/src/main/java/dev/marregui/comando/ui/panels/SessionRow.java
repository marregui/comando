package dev.marregui.comando.ui.panels;

import dev.marregui.comando.core.state.SessionState;
import dev.marregui.comando.core.view.Clip;
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
import java.time.ZoneId;
import java.util.Locale;
import java.util.Optional;

/**
 * One session in the sidebar's list: its state, name, the panels it is bound to (or headless) and pending approvals;
 * its folder and branch; and a row of controls: the plug, green while the session runs and grey while it does not, a
 * click on it stopping or starting the session; a fresh start (a stopped session with a conversation starts on a new
 * one); a checkpoint (the session's checkpoint command sent as a prompt), purple once the session is due one; the model;
 * the effort. While the session runs, a line under its folder says when it started and for how long. The list paints it
 * as a cell renderer, so its buttons are not live: the list finds the control under a click with {@link #hit}.
 */
final class SessionRow extends JPanel {

    /** The controls of a row, in their order. */
    enum Control {
        POWER, FRESH, CHECKPOINT, MODEL, EFFORT
    }

    private final JLabel text = new JLabel();
    private final JPanel controls = new JPanel(new FlowLayout(FlowLayout.LEFT, 2, 0));
    private final JButton power = control();
    private final JButton fresh = control();
    private final JButton checkpoint = control();
    private final JButton model = control();
    private final JButton effort = control();

    SessionRow() {
        super(new BorderLayout(0, 2));
        setBorder(BorderFactory.createEmptyBorder(5, 6, 5, 6));
        text.setIcon(Icons.get("claude"));
        text.setIconTextGap(6);
        controls.setOpaque(false);
        // under the text, past the icon's column
        controls.setBorder(BorderFactory.createEmptyBorder(0, Icons.get("claude").getIconWidth() + 2, 0, 0));
        controls.add(power);
        controls.add(fresh);
        controls.add(checkpoint);
        fresh.setIcon(Icons.get("fresh", 14));
        checkpoint.setIcon(Icons.get("checkpoint", 14));
        controls.add(model);
        controls.add(effort);
        add(text, BorderLayout.CENTER);
        add(controls, BorderLayout.SOUTH);
    }

    /**
     * Shows {@code s}; {@code bound} names the panels it is bound to, "headless" when none, in the accent colour when
     * {@code focused} (one of them is the focused panel); {@code approvals} counts the waiting approvals.
     */
    SessionRow show(SessionState s, String bound, boolean focused, int approvals, boolean selected, Color background, Color foreground) {
        setOpaque(true);
        setBackground(background);
        text.setForeground(foreground);
        text.setText(html(s, bound, focused, approvals, uptimeText(s, System.currentTimeMillis(), ZoneId.systemDefault())));
        boolean running = s.status().isUsable();
        power.setIcon(Icons.get(running ? "plug" : "unplug", 14));
        power.setToolTipText(running ? "Connected; click to disconnect (stops the session)" : "Disconnected; click to connect (starts the session)");
        fresh.setVisible(EditorPanel.showsFresh(s));
        fresh.setToolTipText("Start a new conversation; the current one (" + s.spec().claudeSessionId() + ") stays importable");
        checkpoint.setVisible(EditorPanel.showsCheckpoint(s));
        checkpoint.setIcon(Icons.get(EditorPanel.checkpointIcon(s), 14));
        checkpoint.setToolTipText(EditorPanel.checkpointTip(s));
        model.setText(modelName(s) + " ▾");
        model.setToolTipText("Model: " + modelName(s) + "; click to change");
        effort.setText(effortName(s) + " ▾");
        effort.setToolTipText("Effort: " + effortName(s) + "; click to change");
        for (JButton b : new JButton[]{power, fresh, checkpoint, model, effort}) {
            b.setForeground(selected ? foreground : Theme.MUTED_STRONG);
        }
        return this;
    }

    /** "since 09:12 · 3 h 05 min" while the session runs, empty while it does not. */
    static String uptimeText(SessionState s, long nowMillis, ZoneId zone) {
        long started = s.startedMillis();
        if (started <= 0L || !s.status().isUsable()) {
            return "";
        }
        return "since " + Labels.startedAt(started, nowMillis, zone) + " · " + Labels.uptime(nowMillis - started);
    }

    /** The control at {@code p}, a point in the cell whose bounds this row was laid out to. */
    Optional<Control> hit(Rectangle cell, Point p) {
        layOut(cell);
        Component c = SwingUtilities.getDeepestComponentAt(this, p.x - cell.x, p.y - cell.y);
        return c == power ? Optional.of(Control.POWER) : c == fresh ? Optional.of(Control.FRESH)
                : c == checkpoint ? Optional.of(Control.CHECKPOINT) : c == model ? Optional.of(Control.MODEL)
                : c == effort ? Optional.of(Control.EFFORT) : Optional.empty();
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
            case FRESH -> fresh;
            case CHECKPOINT -> checkpoint;
            case MODEL -> model;
            case EFFORT -> effort;
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

    /** Lines that never wrap: a narrow sidebar clips them at its edge. The third, the uptime, only while it runs. */
    private static String html(SessionState s, String bound, boolean focused, int approvals, String uptime) {
        String dot = "<span style='color:" + Theme.hex(EditorPanel.color(s.status())) + "'>" + EditorPanel.dot(s.status()) + "</span>";
        String badge = approvals == 0 ? "" : "&nbsp;<span style='color:" + Theme.hex(Theme.WARN) + "'>⚑" + approvals + "</span>";
        String where = "&nbsp;<span style='color:" + Theme.hex(focused ? Theme.ACCENT : Theme.MUTED_STRONG)
                + "'>· " + MarkdownHtml.escape(Clip.keepEnd(bound, 24)) + "</span>";
        String doing = s.isBusy() ? (s.activity().isEmpty() ? "working" : s.activity()) : s.isWaiting() ? TabStatus.waiting(s) : "";
        String busy = doing.isEmpty() ? "" : "&nbsp;&nbsp;<span style='color:" + Theme.hex(Theme.MUTED) + "'>"
                + MarkdownHtml.escape(Clip.keepEnd(doing, 30)) + "</span>";
        String branch = s.branch().isEmpty() ? "" : " ⎇ " + MarkdownHtml.escape(s.branch());
        return "<html><nobr>" + dot + "&nbsp;<b>" + MarkdownHtml.escape(s.name()) + "</b>" + where + badge + busy
                + "</nobr><br><nobr><span style='color:" + Theme.hex(Theme.MUTED_STRONG) + "'>"
                + MarkdownHtml.escape(Clip.keepEnd(Labels.shortPath(s.spec().workDir()), 34)) + branch + "</span></nobr>"
                + (uptime.isEmpty() ? "" : "<br><nobr><span style='color:" + Theme.hex(Theme.MUTED) + "'>" + MarkdownHtml.escape(uptime) + "</span></nobr>")
                + "</html>";
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
