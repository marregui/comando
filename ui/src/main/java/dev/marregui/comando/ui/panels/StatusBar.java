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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
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
import dev.marregui.comando.core.model.Approval;
import dev.marregui.comando.core.model.PanelId;
import dev.marregui.comando.core.model.PlanUsage;
import dev.marregui.comando.core.port.IntentSink;
import dev.marregui.comando.core.state.AppState;
import dev.marregui.comando.core.state.Attention;
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
import dev.marregui.comando.ui.kit.text.Tooltips;
import dev.marregui.comando.ui.kit.theme.Gauge;
import dev.marregui.comando.ui.kit.theme.SidebarLook;
import dev.marregui.comando.ui.kit.theme.Style;
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
    /** The count at the bar's right: what runs and waits, muted, then what waits for approval, in attention colour. */
    private final JPanel count = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
    private final JLabel work = new JLabel();
    private final JLabel approvals = new JLabel();
    /** Claude's plan usage, then Codex's, each a label of its own, or under the console style its gauges. */
    private final Usage claudeUsage = new Usage(PlanUsage.CLAUDE, "Claude Code's /usage");
    private final Usage codexUsage = new Usage(PlanUsage.CODEX, "codex app-server");

    /** One plan's usage: its label, and its gauges, one per window of its last read, which the console style shows. */
    private static final class Usage {
        final JLabel label = new JLabel();
        final JPanel gauges = new JPanel(new FlowLayout(FlowLayout.RIGHT, Theme.SPACE_S, 0));
        /** Where the numbers come from, for the tooltip. */
        final String source;
        /** The last read that had windows; a failed read after it keeps showing it, marked as old. */
        PlanUsage last;

        Usage(final String provider, final String source) {
            this.source = source;
            label.setVisible(false);
            label.getAccessibleContext().setAccessibleName(provider + " plan usage");
            gauges.setOpaque(false);
            gauges.setVisible(false);
        }

        /** The label under the plain style, the gauges under the console style; neither before a good read. */
        void showStyle(final Style style) {
            label.setVisible(last != null && style == Style.PLAIN);
            gauges.setVisible(last != null && style == Style.CONSOLE);
        }
    }

    /**
     * A line of the work list: who, what it does now, and the panel a click goes to, empty when it has none.
     *
     * @param rank   needs you, then to read, then working, then waiting
     * @param toRead the session whose finished work this line stands for, which a click opens at its report; empty on
     *               every other line
     */
    record WorkRow(int rank, String text, Optional<PanelId> panel, String toRead) {

        WorkRow(final int rank, final String text, final Optional<PanelId> panel) {
            this(rank, text, panel, "");
        }
    }

    private final IntentSink intents;
    /** The state last rendered, which the work list reads when it opens. */
    private AppState state;
    private List<Notice> history = List.of();
    private Notice shown = Notice.NONE;
    private final Targets targets;
    /** Told the session a click on a To read line opens, for the window to land on its report. */
    private Consumer<String> onReport = _ -> { };
    /** What a click on the count does; a test replaces it, the list needing a window to open on. */
    Runnable workOpener = this::showWork;
    /** The time the work is read at; the system's, or a test's. */
    LongSupplier clock = System::currentTimeMillis;

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
        right.add(claudeUsage.gauges);
        right.add(codexUsage.label);
        right.add(codexUsage.gauges);
        count.add(work);
        count.add(approvals);
        right.add(count);
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
        approvals.setForeground(Theme.ATTENTION);
        approvals.setVisible(false);
        final MouseAdapter opensWork = new MouseAdapter() {
            @Override
            public void mouseClicked(final MouseEvent e) {
                if (SwingUtilities.isLeftMouseButton(e) && !e.isControlDown()) {
                    workOpener.run();
                }
            }
        };
        for (final JLabel part : List.of(work, approvals)) {
            part.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            part.setToolTipText("Click to see who is working, and on what");
            part.getAccessibleContext().setAccessibleName("Work");
            part.addMouseListener(opensWork);
        }
        // while the bar is in a window: its window's close stops it
        SidebarLook.whileShown(root, this::followStyle);
    }

    public JComponent component() {
        return root;
    }

    /** The work list's width: a session's name and what it waits on, as a recent notice's line. */
    private static final int WORK_WIDTH = 640;
    /** At or above this much of a window used, the usage shows as a warning. */
    static final int USAGE_WARN = 75;
    /** At or above this much, as a danger. */
    static final int USAGE_DANGER = 90;
    private static final DateTimeFormatter HOUR_MINUTE = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault());
    /** The window that resets within hours; a read may not have it. */
    private static final String SESSION_WINDOW = "Session";

    /**
     * Shows a plan's usage limits, Claude's or Codex's, each on its own label: each window's share used, and when the
     * session window resets; the tooltip has every reset time, and says so when the plan's tool reports no session
     * window. A failed read keeps the last good one, muted, with the reason in the tooltip; with none, shows nothing.
     * Under the console style the plan shows a gauge per window instead, each coloured by its own share as the label
     * is by the worst, with the label's tooltip (record 151).
     */
    public void planUsage(final PlanUsage read) {
        final Usage u = read.provider().equals(PlanUsage.CODEX) ? codexUsage : claudeUsage;
        if (!read.windows().isEmpty()) {
            u.last = read;
        }
        u.showStyle(Theme.style());
        if (u.last == null) {
            return;
        }
        final boolean stale = read != u.last;
        u.label.setText(usageText(u.last));
        final int worst = u.last.windows().stream().mapToInt(PlanUsage.Window::percent).max().orElse(0);
        u.label.setForeground(usageColour(worst, stale));
        final StringBuilder tip = new StringBuilder();
        for (final PlanUsage.Window w : u.last.windows()) {
            tip.append(MarkdownHtml.escape(w.name() + ": " + w.percent() + "% used" + (w.resets().isEmpty() ? "" : ", resets " + w.resets())))
                    .append("<br>");
        }
        if (u.last.windows().stream().noneMatch(w -> w.name().equals(SESSION_WINDOW))) {
            tip.append(MarkdownHtml.escape(u.source + " reports no session window")).append("<br>");
        }
        tip.append(MarkdownHtml.escape("Read at " + HOUR_MINUTE.format(Instant.ofEpochMilli(u.last.readAtMillis()))
                + " from " + u.source + ", every 5 minutes"));
        if (stale) {
            tip.append("<br>").append(MarkdownHtml.escape("The read at " + HOUR_MINUTE.format(Instant.ofEpochMilli(read.readAtMillis()))
                    + " failed: " + read.problem()));
        }
        u.label.setToolTipText("<html>" + tip + "</html>");
        showGauges(u, stale);
    }

    /** The colour of a share used: muted, attention from {@link #USAGE_WARN}, danger from {@link #USAGE_DANGER}. */
    private static Color usageColour(final int percent, final boolean stale) {
        return stale ? Theme.MUTED : percent >= USAGE_DANGER ? Theme.DANGER : percent >= USAGE_WARN ? Theme.ATTENTION : Theme.MUTED;
    }

    /** {@code u}'s last read on its gauges, one per window, made again only when its windows are others. */
    private static void showGauges(final Usage u, final boolean stale) {
        final List<PlanUsage.Window> windows = u.last.windows();
        final List<String> captions = new ArrayList<>();
        for (final PlanUsage.Window w : windows) {
            captions.add(u.last.provider().substring(0, Math.min(2, u.last.provider().length())).toUpperCase(Locale.ROOT) + " " + w.name().toUpperCase(Locale.ROOT));
        }
        if (!captions.equals(gauges(u).stream().map(Gauge::caption).toList())) {
            u.gauges.removeAll();
            for (final String caption : captions) {
                u.gauges.add(new Gauge(caption));
            }
            u.gauges.revalidate();
        }
        final List<Gauge> shown = gauges(u);
        for (int i = 0; i < windows.size(); i++) {
            final int percent = windows.get(i).percent();
            shown.get(i).show(percent, usageColour(percent, stale));
            shown.get(i).setToolTipText(u.label.getToolTipText());
        }
    }

    private static List<Gauge> gauges(final Usage u) {
        final List<Gauge> out = new ArrayList<>();
        for (final Component c : u.gauges.getComponents()) {
            out.add((Gauge) c);
        }
        return out;
    }

    /** Each plan's label or gauges, as the style chosen has it; a theme chosen keeps the reads shown. */
    private void followStyle() {
        final Style style = Theme.style();
        claudeUsage.showStyle(style);
        codexUsage.showStyle(style);
        root.revalidate();
        root.repaint();
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
     * "Claude Session 5% (resets 7:19pm) · Week 43% · Fable 17%": the plan's name, then each window; the session's
     * reset is the one that comes within hours. Codex's reads the same: "Codex Session 4% (resets 12:06) · Week 3%".
     */
    static String usageText(final PlanUsage u) {
        final StringBuilder sb = new StringBuilder(u.provider() + " ");
        final int start = sb.length();
        for (final PlanUsage.Window w : u.windows()) {
            if (sb.length() > start) {
                sb.append(" · ");
            }
            sb.append(w.name()).append(' ').append(w.percent()).append('%');
            if (w.name().equals(SESSION_WINDOW)) {
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


    /** Told of each session a click on a To read line opens, before it opens. */
    public void onReport(final Consumer<String> report) {
        onReport = report;
    }

    /**
     * What every session and query is doing in {@code s}: who needs you first, then what is there to read, then who
     * works, then who waits.
     */
    static List<WorkRow> workRows(final AppState s, final long now) {
        final List<WorkRow> rows = new ArrayList<>();
        for (final SessionState x : s.sessions()) {
            if (Attention.of(s, x, now) == Attention.TO_READ) {
                rows.add(new WorkRow(1, x.name() + " · to read" + since(x.spec().unreadMillis(), now),
                        s.homePanel(x.name()).map(PanelState::id), x.name()));
            }
            final int asked = s.pendingApprovals(x.name());
            final String doing = SessionBar.busyText(x).strip();
            if (asked > 0 || !doing.isEmpty()) {
                final String what = asked > 0
                        ? "needs you: " + asked + (asked == 1 ? " request" : " requests")
                                + since(oldestAsk(s, x.name()), now)
                        : doing;
                final int rank = asked > 0 ? 0 : x.isBusy() ? 2 : 3;
                rows.add(new WorkRow(rank, x.name() + " · " + what, s.homePanel(x.name()).map(PanelState::id)));
            }
        }
        for (final PanelState p : s.panels()) {
            s.runOf(p.id()).filter(r -> r.status().isActive()).ifPresent(r -> rows.add(
                    new WorkRow(2, p.title() + " · query running" + since(r.startedMillis(), now),
                            Optional.of(p.id()))));
        }
        rows.sort(Comparator.comparingInt(WorkRow::rank));
        return rows;
    }

    /** When the oldest request of {@code session} arrived. */
    private static long oldestAsk(final AppState s, final String session) {
        return s.approvals().stream().filter(a -> a.session().equals(session)).mapToLong(Approval::createdMillis).min()
                .orElse(0L);
    }

    /** " · 4 min 02 s" from {@code millis} to {@code now}; empty for 0, which is not yet started. */
    private static String since(final long millis, final long now) {
        return millis > 0 ? " · " + Labels.elapsed(now - millis) : "";
    }

    /** The work list as it stands at the last render; {@code then} runs before a click on a row goes to its panel. */
    JList<WorkRow> workList(final Runnable then) {
        final DefaultListModel<WorkRow> model = new DefaultListModel<>();
        model.addAll(state == null ? List.of() : workRows(state, clock.getAsLong()));
        final JList<WorkRow> list = new JList<>(model);
        list.getAccessibleContext().setAccessibleName("Work");
        list.setCellRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(final JList<?> l, final Object value, final int index,
                                                          final boolean selected, final boolean focus) {
                final JLabel c = (JLabel) super.getListCellRendererComponent(l, "", index, selected, focus);
                final WorkRow r = (WorkRow) value;
                c.setText(Plain.text(r.text()));
                if (!selected && r.rank() == 0) {
                    c.setForeground(Theme.ATTENTION);
                }
                c.setToolTipText(Tooltips.wrapped(r.text() + (!r.toRead().isEmpty() ? " · click to read it"
                        : r.panel().isPresent() ? " · click to go there" : "")));
                return c;
            }
        });
        list.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(final MouseEvent e) {
                final int i = list.locationToIndex(e.getPoint());
                if (i >= 0 && SwingUtilities.isLeftMouseButton(e) && !e.isControlDown()) {
                    go(model.get(i), then);
                }
            }
        });
        return list;
    }

    /** A To read line opens its session, which may have no panel, at its report; any other line goes to its panel. */
    private void go(final WorkRow r, final Runnable then) {
        if (!r.toRead().isEmpty()) {
            then.run();
            onReport.accept(r.toRead());
            intents.dispatch(r.panel().<Intent>map(Intent.FocusPanel::new)
                    .orElseGet(() -> new Intent.OpenSession(r.toRead(), state.focused(), PanelId.random())));
            return;
        }
        r.panel().ifPresent(p -> {
            then.run();
            intents.dispatch(new Intent.FocusPanel(p));
        });
    }

    /** The muted part of the count; for tests. */
    JLabel workLabel() {
        return work;
    }

    /** What the status bar's count stands for, one line each; a click on one goes to its panel. */
    private void showWork() {
        final JPopupMenu menu = new JPopupMenu();
        final JList<WorkRow> list = workList(() -> menu.setVisible(false));
        final JScrollPane scroll = new JScrollPane(list);
        final int rows = list.getModel().getSize();
        scroll.setPreferredSize(new Dimension(WORK_WIDTH, Math.min(360, Math.max(40, rows * 22 + 6))));
        menu.add(scroll);
        final int height = menu.getPreferredSize().height;
        menu.show(count, count.getWidth() - WORK_WIDTH, -height);
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
        state = s;
        final Notice n = s.notice();
        shown = n;
        history = s.notices();
        notice.setText(n.text().isEmpty() ? " " : Plain.text(KeyText.of(n.text())));
        notice.setToolTipText(n.text().isEmpty() ? (history.isEmpty() ? null : "Recent notices")
                : Tooltips.wrapped(fullLine(n) + (targets.of(n).isPresent()
                ? " · click to go there, right-click for recent notices" : " · click for recent notices")));
        notice.setForeground(color(n.level()));
        notice.setIcon(n.text().isEmpty() ? null : Icons.get(icon(n.level()), 14));
        showWork(s);
    }

    /** Once a second: a session whose reading turn never came counts as to read with no state change to say so. */
    public void tick() {
        if (state != null) {
            showWork(state);
        }
    }

    private void showWork(final AppState s) {
        int running = 0;
        for (final SqlRun r : s.runs()) {
            if (r.status().isActive()) {
                running++;
            }
        }
        final long now = clock.getAsLong();
        int busy = 0;
        int waiting = 0;
        int toRead = 0;
        for (final SessionState x : s.sessions()) {
            if (x.isBusy()) {
                busy++;
            } else if (x.isWaiting()) {
                waiting++;
            } else if (Attention.of(s, x, now) == Attention.TO_READ) {
                toRead++;
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
        if (toRead > 0) {
            w.append(w.isEmpty() ? "" : " · ").append(toRead).append(" to read");
        }
        final int asked = s.approvals().size();
        work.setText(w + (asked > 0 && !w.isEmpty() ? " · " : ""));
        approvals.setText(asked + " waiting for approval");
        approvals.setVisible(asked > 0);
    }
}
