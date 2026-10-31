package dev.marregui.comando.ui.panels;

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

import dev.marregui.comando.core.intent.Intent;
import dev.marregui.comando.core.model.PlanUsage;
import dev.marregui.comando.core.port.IntentSink;
import dev.marregui.comando.core.state.AppState;
import dev.marregui.comando.core.state.Notice;
import dev.marregui.comando.core.state.SessionState;
import dev.marregui.comando.core.state.SqlRun;
import dev.marregui.comando.core.view.Clip;
import dev.marregui.comando.ui.kit.format.KeyText;
import dev.marregui.comando.ui.kit.icon.Icons;
import dev.marregui.comando.ui.kit.text.MarkdownHtml;
import dev.marregui.comando.ui.kit.text.Plain;
import dev.marregui.comando.ui.kit.text.Tooltips;
import dev.marregui.comando.ui.kit.theme.Theme;

/**
 * The window's status: the standing notice on the left, which takes the user to what it is about when clicked and opens
 * the recent notices on a right click (or a click, when it is about nothing to go to); Claude's and Codex's plan usage
 * and the work running across every panel and session on the right. The kit is the Files tab's KIT row (record 119).
 * The focused panel's session is in the {@link SessionBar}, its connection in the panel's header (record 105).
 */
public final class StatusBar {

    /** Where a click on a notice goes: what it is about, brought into view and flashed; empty when there is nowhere. */
    public interface Targets {
        Optional<Runnable> of(Notice notice);
    }

    private final JPanel root = new JPanel(new BorderLayout());
    private final JLabel notice = new JLabel(" ");
    private final JLabel work = new JLabel();
    /** Claude's plan usage, then Codex's, each a label of its own. */
    private final Usage claudeUsage = new Usage(PlanUsage.CLAUDE, "Claude Code's /usage");
    private final Usage codexUsage = new Usage(PlanUsage.CODEX, "codex app-server");

    /** One plan's usage label. */
    private static final class Usage {
        final JLabel label = new JLabel();
        /** Where the numbers come from, for the tooltip. */
        final String source;
        /** The last read that had windows; a failed read after it keeps showing it, marked as old. */
        PlanUsage last;

        Usage(final String provider, final String source) {
            this.source = source;
            label.setVisible(false);
            label.getAccessibleContext().setAccessibleName(provider.equals(PlanUsage.CLAUDE) ? "Plan usage" : provider + " plan usage");
        }
    }

    private final IntentSink intents;
    private List<Notice> history = List.of();
    private Notice shown = Notice.NONE;
    private final Targets targets;

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());

    public StatusBar(final IntentSink intents) {
        this(intents, n -> Optional.empty());
    }

    public StatusBar(final IntentSink intents, final Targets targets) {
        this.intents = intents;
        this.targets = targets;
        root.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, Theme.CARD_BORDER),
                BorderFactory.createEmptyBorder(3, Theme.PAD, 3, Theme.PAD)));
        final JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, Theme.PAD * 2, 0));
        right.add(claudeUsage.label);
        right.add(codexUsage.label);
        right.add(work);
        notice.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        notice.getAccessibleContext().setAccessibleName("Status");
        notice.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(final MouseEvent e) {
                if (e.isPopupTrigger()) {
                    showHistory();
                }
            }

            @Override
            public void mouseReleased(final MouseEvent e) {
                if (e.isPopupTrigger()) {
                    showHistory();
                }
            }

            @Override
            public void mouseClicked(final MouseEvent e) {
                // a control-click is the Mac's right click: its press opened the recent notices
                if (SwingUtilities.isLeftMouseButton(e) && !e.isControlDown()) {
                    goTo(shown);
                }
            }
        });
        root.add(notice, BorderLayout.CENTER);
        root.add(right, BorderLayout.EAST);
        work.setForeground(Theme.MUTED);
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
     * Shows a plan's usage limits, Claude's or Codex's, each on its own label: each window's share used, and when the
     * session window resets; the tooltip has every reset time. A failed read keeps the last good one, muted, with the
     * reason in the tooltip; with none, it shows nothing.
     */
    public void planUsage(final PlanUsage read) {
        final Usage u = read.provider().equals(PlanUsage.CODEX) ? codexUsage : claudeUsage;
        if (!read.windows().isEmpty()) {
            u.last = read;
        }
        if (u.last == null) {
            u.label.setVisible(false);
            return;
        }
        final boolean stale = read != u.last;
        u.label.setVisible(true);
        u.label.setText(usageText(u.last));
        final int worst = u.last.windows().stream().mapToInt(PlanUsage.Window::percent).max().orElse(0);
        u.label.setForeground(stale ? Theme.MUTED : worst >= USAGE_DANGER ? Theme.DANGER : worst >= USAGE_WARN ? Theme.ATTENTION : Theme.MUTED);
        final StringBuilder tip = new StringBuilder();
        for (final PlanUsage.Window w : u.last.windows()) {
            tip.append(MarkdownHtml.escape(w.name() + ": " + w.percent() + "% used" + (w.resets().isEmpty() ? "" : ", resets " + w.resets())))
                    .append("<br>");
        }
        tip.append(MarkdownHtml.escape("Read at " + HOUR_MINUTE.format(Instant.ofEpochMilli(u.last.readAtMillis()))
                + " from " + u.source + ", every 5 minutes"));
        if (stale) {
            tip.append("<br>").append(MarkdownHtml.escape("The read at " + HOUR_MINUTE.format(Instant.ofEpochMilli(read.readAtMillis()))
                    + " failed: " + read.problem()));
        }
        u.label.setToolTipText("<html>" + tip + "</html>");
    }

    /** Claude's usage label; for tests. */
    JLabel usageLabel() {
        return claudeUsage.label;
    }

    /** Codex's usage label; for tests. */
    JLabel codexUsageLabel() {
        return codexUsage.label;
    }

    /** The standing notice's label; for tests. */
    JLabel noticeLabel() {
        return notice;
    }

    /**
     * "Session 5% (resets 7:19pm) · Week 43% · Fable 17%": the session's reset is the one that comes within hours.
     * Codex's starts with its name: "Codex Session 4% (resets 12:06) · Week 3%".
     */
    static String usageText(final PlanUsage u) {
        final StringBuilder sb = new StringBuilder(u.provider().equals(PlanUsage.CLAUDE) ? "" : u.provider() + " ");
        final int start = sb.length();
        for (final PlanUsage.Window w : u.windows()) {
            if (sb.length() > start) {
                sb.append(" · ");
            }
            sb.append(w.name()).append(' ').append(w.percent()).append('%');
            if (w.name().equals("Session")) {
                final String at = resetTime(w.resets());
                if (!at.isEmpty()) {
                    sb.append(" (resets ").append(at).append(')');
                }
            }
        }
        return sb.toString();
    }

    /** The time of day in Claude Code's reset text: "Oct 14 at 7:19pm (Europe/Madrid)" gives "7:19pm". */
    static String resetTime(final String resets) {
        final int at = resets.lastIndexOf(" at ");
        if (at < 0) {
            return "";
        }
        final String t = resets.substring(at + 4);
        final int paren = t.indexOf(" (");
        return (paren < 0 ? t : t.substring(0, paren)).strip();
    }

    /** Goes where {@code n} is about; a notice about nothing to go to opens the recent notices instead. */
    void goTo(final Notice n) {
        final Optional<Runnable> target = n.isEmpty() ? Optional.empty() : targets.of(n);
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
        final JPopupMenu menu = new JPopupMenu();
        final List<Notice> recent = history.reversed();
        final DefaultListModel<Notice> model = new DefaultListModel<>();
        model.addAll(recent);
        final JList<Notice> list = new JList<>(model);
        list.getAccessibleContext().setAccessibleName("Recent notices");
        list.setCellRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(final JList<?> l, final Object value, final int index, final boolean selected,
                                                                   final boolean focus) {
                final JLabel c = (JLabel) super.getListCellRendererComponent(l, "", index, selected, focus);
                final Notice n = (Notice) value;
                c.setText(Plain.text(historyLine(n)));
                c.setIcon(Icons.get(icon(n.level()), 14));
                if (!selected) {
                    c.setForeground(color(n.level()));
                }
                c.setToolTipText(Tooltips.wrapped(KeyText.of(n.text()) + (targets.of(n).isPresent()
                        ? " · click to go there, right-click to copy" : " · right-click to copy")));
                return c;
            }
        });
        list.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(final MouseEvent e) {
                copyMenu(e);
            }

            @Override
            public void mouseReleased(final MouseEvent e) {
                copyMenu(e);
            }

            private void copyMenu(final MouseEvent e) {
                final int i = list.locationToIndex(e.getPoint());
                if (e.isPopupTrigger() && i >= 0) {
                    final Notice n = model.get(i);
                    list.setSelectedIndex(i);
                    final JPopupMenu copy = new JPopupMenu();
                    final JMenuItem item = new JMenuItem("Copy");
                    item.addActionListener(a -> Toolkit.getDefaultToolkit().getSystemClipboard()
                            .setContents(new StringSelection(KeyText.of(n.text())), null));
                    copy.add(item);
                    copy.show(list, e.getX(), e.getY());
                }
            }

            @Override
            public void mouseClicked(final MouseEvent e) {
                final int i = list.locationToIndex(e.getPoint());
                if (i >= 0 && SwingUtilities.isLeftMouseButton(e) && !e.isControlDown()) {
                    final Optional<Runnable> target = targets.of(model.get(i));
                    if (target.isPresent()) {
                        menu.setVisible(false);
                        target.get().run();
                    }
                }
            }
        });
        final JScrollPane scroll = new JScrollPane(list);
        scroll.setPreferredSize(new Dimension(640, Math.min(360, Math.max(40, recent.size() * 22 + 6))));
        if (recent.isEmpty()) {
            final JMenuItem none = new JMenuItem("No notices yet");
            none.setEnabled(false);
            menu.add(none);
        } else {
            final JLabel hint = new JLabel("Click a notice to go where it is about; right-click to copy it");
            hint.setForeground(Theme.MUTED);
            hint.setBorder(BorderFactory.createEmptyBorder(2, Theme.PAD, 2, Theme.PAD));
            menu.add(hint);
            menu.add(scroll);
        }
        menu.addSeparator();
        final JMenuItem dismiss = new JMenuItem("Dismiss");
        dismiss.setEnabled(!notice.getText().isBlank());
        dismiss.addActionListener(e -> intents.dispatch(new Intent.DismissNotice()));
        menu.add(dismiss);
        menu.show(notice, 0, -menu.getPreferredSize().height);
    }


    /** The notice with its time and its whole text, for the tooltip. */
    static String fullLine(final Notice n) {
        return (n.atMillis() > 0 ? TIME.format(Instant.ofEpochMilli(n.atMillis())) + "  " : "") + KeyText.of(n.text());
    }

    static String historyLine(final Notice n) {
        return (n.atMillis() > 0 ? TIME.format(Instant.ofEpochMilli(n.atMillis())) + "  " : "") + Clip.keepStart(KeyText.of(n.text()), 160);
    }

    private static Color color(final Notice.Level level) {
        return switch (level) {
            case INFO -> Theme.MUTED;
            case WARN -> Theme.ATTENTION;
            case ERROR -> Theme.DANGER;
        };
    }

    private static String icon(final Notice.Level level) {
        return switch (level) {
            case INFO -> "info";
            case WARN -> "warning";
            case ERROR -> "error";
        };
    }

    public void render(final AppState s) {
        final Notice n = s.notice();
        shown = n;
        history = s.notices();
        notice.setText(n.text().isEmpty() ? " " : Plain.text(KeyText.of(n.text())));
        notice.setToolTipText(n.text().isEmpty() ? (history.isEmpty() ? null : "Recent notices")
                : Tooltips.wrapped(fullLine(n) + (targets.of(n).isPresent()
                ? " · click to go there, right-click for recent notices" : " · click for recent notices")));
        notice.setForeground(color(n.level()));
        notice.setIcon(n.text().isEmpty() ? null : Icons.get(icon(n.level()), 14));
        int running = 0;
        for (final SqlRun r : s.runs()) {
            if (r.status().isActive()) {
                running++;
            }
        }
        int busy = 0;
        int waiting = 0;
        for (final SessionState x : s.sessions()) {
            if (x.isBusy()) {
                busy++;
            } else if (x.isWaiting()) {
                waiting++;
            }
        }
        final StringBuilder w = new StringBuilder();
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
        work.setForeground(s.approvals().isEmpty() ? Theme.MUTED : Theme.ATTENTION);
    }
}
