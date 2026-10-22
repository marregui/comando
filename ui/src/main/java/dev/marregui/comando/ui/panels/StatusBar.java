package dev.marregui.comando.ui.panels;

import dev.marregui.comando.core.intent.Intent;
import dev.marregui.comando.core.model.PlanUsage;
import dev.marregui.comando.core.port.IntentSink;
import dev.marregui.comando.core.state.AppState;
import dev.marregui.comando.core.state.Notice;
import dev.marregui.comando.core.state.PanelState;
import dev.marregui.comando.core.state.SessionState;
import dev.marregui.comando.core.state.SqlRun;
import dev.marregui.comando.core.view.Clip;
import dev.marregui.comando.ui.kit.format.KeyText;
import dev.marregui.comando.ui.kit.format.Labels;
import dev.marregui.comando.ui.kit.icon.Icons;
import dev.marregui.comando.ui.kit.text.MarkdownHtml;
import dev.marregui.comando.ui.kit.text.Plain;
import dev.marregui.comando.ui.kit.theme.Theme;

import javax.swing.BorderFactory;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The standing notice on the left, which takes the user to what it is about when clicked and opens the recent notices
 * on a right click (or a click, when it is about nothing to go to); the plan's usage limits, the focused panel's
 * session, connection and running work on the right.
 */
public final class StatusBar {

    /** Where a click on a notice goes: what it is about, brought into view and flashed; empty when there is nowhere. */
    public interface Targets {
        Optional<Runnable> of(Notice notice);
    }

    private final JPanel root = new JPanel(new BorderLayout());
    private final JLabel notice = new JLabel(" ");
    private final JLabel session = new JLabel();
    private final JLabel connection = new JLabel();
    private final JLabel work = new JLabel();
    private final JLabel usage = new JLabel();
    /** The last read that had windows; a failed read after it keeps showing it, marked as old. */
    private PlanUsage lastUsage;
    private final IntentSink intents;
    private List<Notice> history = List.of();
    private Notice shown = Notice.NONE;
    private final Targets targets;

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());

    public StatusBar(IntentSink intents) {
        this(intents, n -> Optional.empty());
    }

    public StatusBar(IntentSink intents, Targets targets) {
        this.intents = intents;
        this.targets = targets;
        root.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, Theme.CARD_BORDER),
                BorderFactory.createEmptyBorder(3, Theme.PAD, 3, Theme.PAD)));
        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, Theme.PAD * 2, 0));
        right.add(usage);
        right.add(session);
        right.add(connection);
        right.add(work);
        usage.setVisible(false);
        usage.getAccessibleContext().setAccessibleName("Plan usage");
        notice.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        notice.getAccessibleContext().setAccessibleName("Status");
        notice.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                if (e.isPopupTrigger()) {
                    showHistory();
                }
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                if (e.isPopupTrigger()) {
                    showHistory();
                }
            }

            @Override
            public void mouseClicked(MouseEvent e) {
                // a control-click is the Mac's right click: its press opened the recent notices
                if (SwingUtilities.isLeftMouseButton(e) && !e.isControlDown()) {
                    goTo(shown);
                }
            }
        });
        root.add(notice, BorderLayout.CENTER);
        root.add(right, BorderLayout.EAST);
        for (JLabel l : new JLabel[]{session, connection, work}) {
            l.setForeground(Theme.MUTED);
        }
    }

    public JComponent component() {
        return root;
    }

    /** At or above this much of a window used, the usage shows as a warning. */
    static final int USAGE_WARN = 75;
    /** At or above this much, as a danger. */
    static final int USAGE_DANGER = 90;
    private static final DateTimeFormatter HOUR_MINUTE = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault());

    /**
     * Shows the plan's usage limits: each window's share used, and when the session window resets; the tooltip has
     * every reset time. A failed read keeps the last good one, muted, with the reason in the tooltip; with none, it
     * shows nothing.
     */
    public void planUsage(PlanUsage read) {
        if (!read.windows().isEmpty()) {
            lastUsage = read;
        }
        if (lastUsage == null) {
            usage.setVisible(false);
            return;
        }
        boolean stale = read != lastUsage;
        usage.setVisible(true);
        usage.setText(usageText(lastUsage));
        int worst = lastUsage.windows().stream().mapToInt(PlanUsage.Window::percent).max().orElse(0);
        usage.setForeground(stale ? Theme.MUTED : worst >= USAGE_DANGER ? Theme.DANGER : worst >= USAGE_WARN ? Theme.warn() : Theme.MUTED);
        StringBuilder tip = new StringBuilder();
        for (PlanUsage.Window w : lastUsage.windows()) {
            tip.append(MarkdownHtml.escape(w.name() + ": " + w.percent() + "% used" + (w.resets().isEmpty() ? "" : ", resets " + w.resets())))
                    .append("<br>");
        }
        tip.append(MarkdownHtml.escape("Read at " + HOUR_MINUTE.format(Instant.ofEpochMilli(lastUsage.readAtMillis()))
                + " from Claude Code's /usage, every 5 minutes"));
        if (stale) {
            tip.append("<br>").append(MarkdownHtml.escape("The read at " + HOUR_MINUTE.format(Instant.ofEpochMilli(read.readAtMillis()))
                    + " failed: " + read.problem()));
        }
        usage.setToolTipText("<html>" + tip + "</html>");
    }

    /** The usage label; for tests. */
    JLabel usageLabel() {
        return usage;
    }

    /** The standing notice's label; for tests. */
    JLabel noticeLabel() {
        return notice;
    }

    /** "Session 5% (resets 7:19pm) · Week 43% · Fable 17%": the session's reset is the one that comes within hours. */
    static String usageText(PlanUsage u) {
        StringBuilder sb = new StringBuilder();
        for (PlanUsage.Window w : u.windows()) {
            if (!sb.isEmpty()) {
                sb.append(" · ");
            }
            sb.append(w.name()).append(' ').append(w.percent()).append('%');
            if (w.name().equals("Session")) {
                String at = resetTime(w.resets());
                if (!at.isEmpty()) {
                    sb.append(" (resets ").append(at).append(')');
                }
            }
        }
        return sb.toString();
    }

    /** The time of day in Claude Code's reset text: "Oct 14 at 7:19pm (Europe/Madrid)" gives "7:19pm". */
    static String resetTime(String resets) {
        int at = resets.lastIndexOf(" at ");
        if (at < 0) {
            return "";
        }
        String t = resets.substring(at + 4);
        int paren = t.indexOf(" (");
        return (paren < 0 ? t : t.substring(0, paren)).strip();
    }

    /** Goes where {@code n} is about; a notice about nothing to go to opens the recent notices instead. */
    void goTo(Notice n) {
        Optional<Runnable> target = n.isEmpty() ? Optional.empty() : targets.of(n);
        if (target.isPresent()) {
            target.get().run();
        } else {
            showHistory();
        }
    }

    /**
     * The recent notices, newest first (all the state keeps), each with the time it arrived, in a scrolling list; a
     * click on one goes where it is about, a right click offers to copy its full text. Dismiss clears the standing
     * notice.
     */
    public void showHistory() {
        JPopupMenu menu = new JPopupMenu();
        List<Notice> recent = history.reversed();
        DefaultListModel<Notice> model = new DefaultListModel<>();
        model.addAll(recent);
        JList<Notice> list = new JList<>(model);
        list.getAccessibleContext().setAccessibleName("Recent notices");
        list.setCellRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> l, Object value, int index, boolean selected,
                                                                   boolean focus) {
                JLabel c = (JLabel) super.getListCellRendererComponent(l, "", index, selected, focus);
                Notice n = (Notice) value;
                c.setText(Plain.text(historyLine(n)));
                c.setIcon(Icons.get(icon(n.level()), 14));
                if (!selected) {
                    c.setForeground(color(n.level()));
                }
                c.setToolTipText(wrapped(KeyText.of(n.text()) + (targets.of(n).isPresent()
                        ? " · click to go there, right-click to copy" : " · right-click to copy")));
                return c;
            }
        });
        list.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                copyMenu(e);
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                copyMenu(e);
            }

            private void copyMenu(MouseEvent e) {
                int i = list.locationToIndex(e.getPoint());
                if (e.isPopupTrigger() && i >= 0) {
                    Notice n = model.get(i);
                    list.setSelectedIndex(i);
                    JPopupMenu copy = new JPopupMenu();
                    JMenuItem item = new JMenuItem("Copy");
                    item.addActionListener(a -> Toolkit.getDefaultToolkit().getSystemClipboard()
                            .setContents(new StringSelection(KeyText.of(n.text())), null));
                    copy.add(item);
                    copy.show(list, e.getX(), e.getY());
                }
            }

            @Override
            public void mouseClicked(MouseEvent e) {
                int i = list.locationToIndex(e.getPoint());
                if (i >= 0 && SwingUtilities.isLeftMouseButton(e) && !e.isControlDown()) {
                    Optional<Runnable> target = targets.of(model.get(i));
                    if (target.isPresent()) {
                        menu.setVisible(false);
                        target.get().run();
                    }
                }
            }
        });
        JScrollPane scroll = new JScrollPane(list);
        scroll.setPreferredSize(new Dimension(640, Math.min(360, Math.max(40, recent.size() * 22 + 6))));
        if (recent.isEmpty()) {
            JMenuItem none = new JMenuItem("No notices yet");
            none.setEnabled(false);
            menu.add(none);
        } else {
            JLabel hint = new JLabel("Click a notice to go where it is about; right-click to copy it");
            hint.setForeground(Theme.MUTED);
            hint.setBorder(BorderFactory.createEmptyBorder(2, Theme.PAD, 2, Theme.PAD));
            menu.add(hint);
            menu.add(scroll);
        }
        menu.addSeparator();
        JMenuItem dismiss = new JMenuItem("Dismiss");
        dismiss.setEnabled(!notice.getText().isBlank());
        dismiss.addActionListener(e -> intents.dispatch(new Intent.DismissNotice()));
        menu.add(dismiss);
        menu.show(notice, 0, -menu.getPreferredSize().height);
    }

    /** Characters on a tooltip line before it wraps, about the 520 px the wrapping width allows. */
    private static final int LINE = 70;
    private static final Pattern LONG_WORD = Pattern.compile("\\S{" + LINE + "}(?=\\S)");

    /**
     * A tooltip that keeps line breaks and wraps a long text at a readable width, while a short one stays as wide as
     * its text; a word longer than a line (a path) is broken, as Swing's HTML never breaks inside a word. The text is
     * escaped, so nothing in it is markup (a notice can hold text from a database or from Claude).
     */
    static String wrapped(String text) {
        String broken = LONG_WORD.matcher(text).replaceAll("$0\n");
        String body = MarkdownHtml.escape(broken).replace("\n", "<br>");
        boolean wraps = broken.lines().anyMatch(l -> l.length() > LINE);
        return wraps ? "<html><div style='width:520px'>" + body + "</div></html>" : "<html>" + body + "</html>";
    }

    /** The notice with its time and its whole text, for the tooltip. */
    static String fullLine(Notice n) {
        return (n.atMillis() > 0 ? TIME.format(Instant.ofEpochMilli(n.atMillis())) + "  " : "") + KeyText.of(n.text());
    }

    static String historyLine(Notice n) {
        return (n.atMillis() > 0 ? TIME.format(Instant.ofEpochMilli(n.atMillis())) + "  " : "") + Clip.keepStart(KeyText.of(n.text()), 160);
    }

    private static Color color(Notice.Level level) {
        return switch (level) {
            case INFO -> Theme.MUTED;
            case WARN -> Theme.warn();
            case ERROR -> Theme.DANGER;
        };
    }

    private static String icon(Notice.Level level) {
        return switch (level) {
            case INFO -> "info";
            case WARN -> "warning";
            case ERROR -> "error";
        };
    }

    public void render(AppState s) {
        Notice n = s.notice();
        shown = n;
        history = s.notices();
        notice.setText(n.text().isEmpty() ? " " : Plain.text(KeyText.of(n.text())));
        notice.setToolTipText(n.text().isEmpty() ? (history.isEmpty() ? null : "Recent notices")
                : wrapped(fullLine(n) + (targets.of(n).isPresent()
                ? " · click to go there, right-click for recent notices" : " · click for recent notices")));
        notice.setForeground(color(n.level()));
        notice.setIcon(n.text().isEmpty() ? null : Icons.get(icon(n.level()), 14));
        PanelState p = s.focusedPanel();
        session.setText(s.session(p.session()).map(x -> Plain.text(sessionText(x))).orElse(""));
        session.setIcon(p.session().isEmpty() ? null : Icons.get("claude", 14));
        connection.setText(s.connection(p.connection()).map(c -> c.name() + " " + EditorPanel.dot(c.status())).orElse(""));
        connection.setIcon(p.connection().isEmpty() ? null : Icons.get("database", 14));
        int running = 0;
        for (SqlRun r : s.runs()) {
            if (r.status().isActive()) {
                running++;
            }
        }
        int busy = 0;
        int waiting = 0;
        for (SessionState x : s.sessions()) {
            if (x.isBusy()) {
                busy++;
            } else if (x.isWaiting()) {
                waiting++;
            }
        }
        StringBuilder w = new StringBuilder();
        if (running > 0) {
            w.append(running).append(running == 1 ? " query" : " queries").append(" running");
        }
        if (busy > 0) {
            w.append(w.isEmpty() ? "" : " · ").append(busy).append(busy == 1 ? " session" : " sessions").append(" working");
        }
        if (waiting > 0) {
            w.append(w.isEmpty() ? "" : " · ").append(waiting).append(waiting == 1 ? " session" : " sessions").append(" waiting on background work");
        }
        if (!s.approvals().isEmpty()) {
            w.append(w.isEmpty() ? "" : " · ").append(s.approvals().size()).append(" waiting for approval");
        }
        work.setText(w.toString());
        work.setForeground(s.approvals().isEmpty() ? Theme.MUTED : Theme.warn());
    }

    /** The focused panel's session: name, model, effort, context used and, during a turn, what it does; no cost. */
    static String sessionText(SessionState x) {
        StringBuilder sb = new StringBuilder(x.name());
        String model = SessionRow.modelName(x);
        if (!model.equals("default")) {
            sb.append(" · ").append(model);
        }
        if (!x.spec().effort().isEmpty()) {
            sb.append(" · ").append(x.spec().effort());
        }
        if (x.contextTokens() > 0) {
            sb.append(" · ctx ").append(Labels.tokens(x.contextTokens()))
                    .append(x.contextWindow() > 0 ? " (" + Math.min(100, x.contextTokens() * 100 / x.contextWindow()) + "%)" : "");
        }
        if (x.isBusy()) {
            sb.append(" · ").append(TabStatus.working(x));
        } else if (x.isWaiting()) {
            sb.append(" · ").append(TabStatus.waiting(x));
        }
        return sb.toString();
    }
}
