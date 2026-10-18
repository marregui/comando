package dev.marregui.comando.ui.panels;

import dev.marregui.comando.core.state.Reflection;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * One session in the sidebar's list: its state, name, the panels it is bound to (or headless) and pending approvals;
 * its folder and branch; how full its context is and how long it has run; and a row of controls. On
 * the left, packed: the plug, green while the session runs and grey while it does not, a click on it stopping or
 * starting the session; a fresh start (a stopped session with a conversation starts on a new one); a checkpoint (the
 * session's checkpoint command sent as a prompt), purple once the session is due one; and, while it runs, compact and
 * clear ({@code /compact}, {@code /clear}). On the right, the model and the effort. Under the mouse, a copy button at
 * the top right copies {@link #details}. The list paints it as a cell renderer, so its buttons are not live: the list
 * finds the control under a click with {@link #hit}.
 */
final class SessionRow extends JPanel {

    /** The controls of a row, in their order. */
    enum Control {
        POWER, FRESH, CHECKPOINT, COMPACT, CLEAR, MODEL, EFFORT, COPY
    }

    static final String COMPACT_COMMAND = "/compact";
    static final String CLEAR_COMMAND = "/clear";

    /** The first line, beside the copy button; the lines under it take the row's whole width. */
    private final JLabel title = new JLabel();
    private final JLabel body = new JLabel();
    private final JButton power = control();
    private final JButton fresh = control();
    private final JButton checkpoint = control();
    private final JButton compact = control();
    private final JButton clear = control();
    private final JButton model = control();
    private final JButton effort = control();
    private final JButton copy = control();

    SessionRow() {
        super(new BorderLayout(0, 3));
        setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));
        int indent = Icons.get("claude").getIconWidth() + 6;
        title.setIcon(Icons.get("claude"));
        title.setIconTextGap(6);
        body.setBorder(BorderFactory.createEmptyBorder(0, indent, 0, 0));
        fresh.setIcon(Icons.get("fresh", 14));
        checkpoint.setIcon(Icons.get("checkpoint", 14));
        compact.setIcon(Icons.get("compact", 14));
        compact.setToolTipText("Compact the conversation: send " + COMPACT_COMMAND + " to the session");
        clear.setIcon(Icons.get("clear-conversation", 14));
        copy.setIcon(Icons.get("copy", 14));
        copy.setToolTipText("Copy the session's details: name, folder, conversation, uptime, context");
        copy.setMargin(new Insets(0, 3, 0, 3));
        JPanel head = new JPanel(new BorderLayout());
        head.setOpaque(false);
        head.add(title, BorderLayout.CENTER);
        head.add(copy, BorderLayout.EAST);
        JPanel text = new JPanel(new BorderLayout(0, 1));
        text.setOpaque(false);
        text.add(head, BorderLayout.NORTH);
        text.add(body, BorderLayout.CENTER);
        // the session's own controls packed at the left, under the text past the icon's column; its settings at the right
        JPanel actions = group(FlowLayout.LEFT, power, fresh, checkpoint, compact, clear);
        actions.setBorder(BorderFactory.createEmptyBorder(0, indent - 4, 0, 0));
        JPanel controls = new JPanel(new BorderLayout());
        controls.setOpaque(false);
        controls.add(actions, BorderLayout.WEST);
        controls.add(group(FlowLayout.RIGHT, model, effort), BorderLayout.EAST);
        add(text, BorderLayout.CENTER);
        add(controls, BorderLayout.SOUTH);
    }

    /**
     * Shows {@code s}; {@code bound} names the panels it is bound to, "headless" when none, in the accent colour when
     * {@code focused} (one of them is the focused panel); {@code approvals} counts the waiting approvals; {@code hovered}
     * shows the copy button.
     */
    SessionRow show(SessionState s, String bound, boolean focused, int approvals, boolean selected, boolean hovered,
                    Color background, Color foreground) {
        setOpaque(true);
        setBackground(background);
        title.setForeground(foreground);
        body.setForeground(foreground);
        title.setText(titleHtml(s, bound, focused, approvals));
        body.setText(bodyHtml(s, System.currentTimeMillis()));
        boolean running = s.status().isUsable();
        power.setIcon(Icons.get(running ? "plug" : "unplug", 14));
        power.setToolTipText(running ? "Connected; click to disconnect (stops the session)" : "Disconnected; click to connect (starts the session)");
        fresh.setVisible(EditorPanel.showsFresh(s));
        fresh.setToolTipText("Start a new conversation; the current one (" + s.spec().claudeSessionId() + ") stays importable");
        checkpoint.setVisible(EditorPanel.showsCheckpoint(s));
        checkpoint.setIcon(Icons.get(EditorPanel.checkpointIcon(s), 14));
        checkpoint.setToolTipText(EditorPanel.checkpointTip(s));
        compact.setVisible(running);
        clear.setVisible(running);
        clear.setToolTipText("Clear the conversation: send " + CLEAR_COMMAND + "; a new one starts"
                + (s.spec().claudeSessionId().isEmpty() ? "" : ", the current one (" + s.spec().claudeSessionId() + ") stays importable"));
        model.setText(modelName(s) + " ▾");
        model.setToolTipText("Model: " + modelName(s) + "; click to change");
        effort.setText(effortName(s) + " ▾");
        effort.setToolTipText("Effort: " + effortName(s) + "; click to change");
        copy.setVisible(hovered);
        for (JButton b : new JButton[]{power, fresh, checkpoint, compact, clear, model, effort, copy}) {
            b.setForeground(selected ? foreground : Theme.MUTED_STRONG);
        }
        return this;
    }

    /** "up 3 h 05 min" while the session runs, empty while it does not; when it started is in {@link #details}. */
    static String uptimeText(SessionState s, long nowMillis) {
        long started = s.startedMillis();
        return started <= 0L || !s.status().isUsable() ? "" : "up " + Labels.uptime(nowMillis - started);
    }

    /** How full the context is, "42%", once a turn has reported it; empty before. */
    static String contextText(SessionState s) {
        return s.contextWindow() <= 0L || s.contextTokens() <= 0L ? ""
                : Math.min(100L, s.contextTokens() * 100L / s.contextWindow()) + "%";
    }

    /**
     * What the copy button, the menu's Copy details and ⌘C put on the clipboard: what a decision on the session (go on,
     * compact, clear, stop) needs, one fact a line.
     */
    static String details(SessionState s, long nowMillis, ZoneId zone) {
        StringBuilder sb = new StringBuilder();
        sb.append("Session: ").append(s.name()).append('\n');
        sb.append("Path: ").append(s.spec().workDir()).append('\n');
        if (!s.branch().isEmpty()) {
            sb.append("Branch: ").append(s.branch()).append('\n');
        }
        sb.append("Session id: ").append(s.spec().claudeSessionId().isEmpty() ? "none yet" : s.spec().claudeSessionId()).append('\n');
        sb.append("Status: ").append(s.status().isUsable() ? "running" : s.status().name().toLowerCase(Locale.ROOT)).append('\n');
        if (s.status().isUsable() && s.startedMillis() > 0L) {
            sb.append("Uptime: ").append(Labels.uptime(nowMillis - s.startedMillis())).append(", since ")
                    .append(Labels.startedAt(s.startedMillis(), nowMillis, zone)).append('\n');
        }
        String context = contextText(s);
        sb.append("Context: ").append(context.isEmpty() ? "unknown until a turn ends"
                : context + " (" + Labels.tokens(s.contextTokens()) + " of " + Labels.tokens(s.contextWindow()) + " tokens)").append('\n');
        sb.append("Model: ").append(modelName(s)).append(", effort ").append(effortName(s)).append('\n');
        return sb.toString();
    }

    /**
     * {@link #details} as a tooltip: the same lines, each escaped, so a name or path is shown as typed and never read
     * as markup.
     */
    static String detailsTip(String details) {
        return "<html>" + String.join("<br>", details.strip().lines().map(MarkdownHtml::escape).toList()) + "</html>";
    }

    /** The control at {@code p}, a point in the cell whose bounds this row was laid out to. */
    Optional<Control> hit(Rectangle cell, Point p) {
        layOut(cell);
        Component c = SwingUtilities.getDeepestComponentAt(this, p.x - cell.x, p.y - cell.y);
        for (Control control : Control.values()) {
            JButton b = button(control);
            if (c == b && b.isVisible()) {
                return Optional.of(control);
            }
        }
        return Optional.empty();
    }

    /** Where {@code control} is, in the list's coordinates, for a menu to drop from it; empty while it is hidden. */
    Rectangle bounds(Rectangle cell, Control control) {
        layOut(cell);
        JButton b = button(control);
        if (!b.isVisible()) {
            return new Rectangle();
        }
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
            case COMPACT -> compact;
            case CLEAR -> clear;
            case MODEL -> model;
            case EFFORT -> effort;
            case COPY -> copy;
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

    /** The state, name, where it is bound, pending approvals and what it is doing; never wraps, clipped at the edge. */
    private static String titleHtml(SessionState s, String bound, boolean focused, int approvals) {
        String dot = "<span style='color:" + Theme.hex(EditorPanel.color(s.status())) + "'>" + EditorPanel.dot(s.status()) + "</span>";
        String badge = approvals == 0 ? "" : "&nbsp;<span style='color:" + Theme.hex(Theme.WARN) + "'>⚑" + approvals + "</span>";
        String where = "&nbsp;<span style='color:" + Theme.hex(focused ? Theme.ACCENT : Theme.MUTED_STRONG)
                + "'>· " + MarkdownHtml.escape(Clip.keepEnd(bound, 24)) + "</span>";
        String doing = s.isBusy() ? (s.activity().isEmpty() ? "working" : s.activity()) : s.isWaiting() ? TabStatus.waiting(s) : "";
        String busy = doing.isEmpty() ? "" : "&nbsp;&nbsp;<span style='color:" + Theme.hex(Theme.MUTED) + "'>"
                + MarkdownHtml.escape(Clip.keepEnd(doing, 30)) + "</span>";
        return "<html><nobr>" + dot + "&nbsp;<b>" + MarkdownHtml.escape(s.name()) + "</b>" + where + badge + busy + "</nobr></html>";
    }

    /**
     * Lines that never wrap, clipped at the edge: the folder and branch; then how full the context is (purple from the
     * checkpoint's threshold) and how long it has run, whatever is known, a line only when something is, so a
     * disconnected row has its controls right under its folder. The conversation's id is in the tooltip and the copied
     * details.
     */
    static String bodyHtml(SessionState s, long nowMillis) {
        String branch = s.branch().isEmpty() ? "" : " ⎇ " + MarkdownHtml.escape(s.branch());
        String muted = Theme.hex(Theme.MUTED);
        List<String> facts = new ArrayList<>(2);
        String context = contextText(s);
        if (!context.isEmpty()) {
            boolean full = s.contextTokens() >= Reflection.THRESHOLD * s.contextWindow();
            facts.add("<span style='color:" + Theme.hex(full ? Theme.THINKING : Theme.MUTED) + "'>ctx " + context + "</span>");
        }
        String uptime = uptimeText(s, nowMillis);
        if (!uptime.isEmpty()) {
            facts.add("<span style='color:" + muted + "'>" + MarkdownHtml.escape(uptime) + "</span>");
        }
        return "<html><nobr><span style='color:" + Theme.hex(Theme.MUTED_STRONG) + "'>"
                + MarkdownHtml.escape(Clip.keepEnd(Labels.shortPath(s.spec().workDir()), 34)) + branch + "</span></nobr>"
                + (facts.isEmpty() ? "" : "<br><nobr>" + String.join("<span style='color:" + muted + "'> · </span>", facts) + "</nobr>")
                + "</html>";
    }

    /** Buttons side by side with no gap between them. */
    private static JPanel group(int align, JButton... buttons) {
        JPanel p = new JPanel(new FlowLayout(align, 0, 0));
        p.setOpaque(false);
        for (JButton b : buttons) {
            p.add(b);
        }
        return p;
    }

    private static JButton control() {
        JButton b = new JButton();
        b.putClientProperty("JButton.buttonType", "toolBarButton");
        b.setFocusable(false);
        b.setFont(b.getFont().deriveFont(b.getFont().getSize2D() - 1f));
        b.setMargin(new Insets(1, 3, 1, 3));
        return b;
    }
}
