package dev.marregui.comando.ui.panels;

import dev.marregui.comando.ui.kit.text.MarkdownHtml;
import dev.marregui.comando.ui.kit.text.Plain;
import dev.marregui.comando.core.state.AppState;
import dev.marregui.comando.core.state.Notice;
import dev.marregui.comando.core.state.PanelState;
import dev.marregui.comando.core.state.SessionState;
import dev.marregui.comando.core.state.SqlRun;
import dev.marregui.comando.ui.kit.format.Labels;
import dev.marregui.comando.ui.kit.icon.Icons;
import dev.marregui.comando.ui.kit.theme.Theme;

import dev.marregui.comando.core.intent.Intent;
import dev.marregui.comando.core.port.IntentSink;

import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import java.awt.BorderLayout;
import java.awt.Cursor;
import java.awt.FlowLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * The standing notice on the left, which opens the recent notices when clicked; the focused panel's session,
 * connection, running work and the embedded server on the right.
 */
public final class StatusBar {

    /** Context window size assumed for the percentage; Claude Code reports tokens, not the window. */

    private final JPanel root = new JPanel(new BorderLayout());
    private final JLabel notice = new JLabel(" ");
    private final JLabel session = new JLabel();
    private final JLabel connection = new JLabel();
    private final JLabel work = new JLabel();
    private final JLabel server = new JLabel();
    private final IntentSink intents;
    private List<Notice> history = List.of();
    private Runnable serverClick = () -> { };

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());

    public StatusBar(IntentSink intents) {
        this.intents = intents;
        root.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, Theme.CARD_BORDER),
                BorderFactory.createEmptyBorder(3, Theme.PAD, 3, Theme.PAD)));
        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, Theme.PAD * 2, 0));
        right.add(session);
        right.add(connection);
        right.add(work);
        right.add(server);
        server.setVisible(false);
        server.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        server.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                serverClick.run();
            }
        });
        notice.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        notice.getAccessibleContext().setAccessibleName("Status");
        notice.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                showHistory();
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

    /** Shows the embedded server's name and state; a click on it runs {@code click}, which starts or stops it. */
    public void server(String name, boolean running, Runnable click) {
        server.setVisible(true);
        server.setText(name + " " + (running ? "●" : "○"));
        server.setForeground(running ? Theme.OK : Theme.MUTED);
        server.setToolTipText(Plain.text(name + (running ? " is running; click to stop it" : " is stopped; click to start it")));
        serverClick = click;
    }

    /**
     * The recent notices, newest first (all the state keeps), each with the time it arrived, in a scrolling list; a
     * click on one copies its full text. Dismiss clears the standing notice.
     */
    public void showHistory() {
        JPopupMenu menu = new JPopupMenu();
        List<Notice> recent = history.reversed();
        javax.swing.DefaultListModel<Notice> model = new javax.swing.DefaultListModel<>();
        model.addAll(recent);
        javax.swing.JList<Notice> list = new javax.swing.JList<>(model);
        list.getAccessibleContext().setAccessibleName("Recent notices");
        list.setCellRenderer(new javax.swing.DefaultListCellRenderer() {
            @Override
            public java.awt.Component getListCellRendererComponent(javax.swing.JList<?> l, Object value, int index, boolean selected,
                                                                   boolean focus) {
                JLabel c = (JLabel) super.getListCellRendererComponent(l, "", index, selected, focus);
                Notice n = (Notice) value;
                c.setText(Plain.text(historyLine(n)));
                c.setIcon(Icons.get(icon(n.level()), 14));
                if (!selected) {
                    c.setForeground(color(n.level()));
                }
                c.setToolTipText(Plain.text(n.text()));
                return c;
            }
        });
        list.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                int i = list.locationToIndex(e.getPoint());
                if (i >= 0) {
                    java.awt.Toolkit.getDefaultToolkit().getSystemClipboard()
                            .setContents(new java.awt.datatransfer.StringSelection(model.get(i).text()), null);
                    menu.setVisible(false);
                }
            }
        });
        javax.swing.JScrollPane scroll = new javax.swing.JScrollPane(list);
        scroll.setPreferredSize(new java.awt.Dimension(640, Math.min(360, Math.max(40, recent.size() * 22 + 6))));
        if (recent.isEmpty()) {
            JMenuItem none = new JMenuItem("No notices yet");
            none.setEnabled(false);
            menu.add(none);
        } else {
            JLabel hint = new JLabel("Click a notice to copy it");
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
    private static final java.util.regex.Pattern LONG_WORD = java.util.regex.Pattern.compile("\\S{" + LINE + "}(?=\\S)");

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
        return (n.atMillis() > 0 ? TIME.format(Instant.ofEpochMilli(n.atMillis())) + "  " : "") + n.text();
    }

    static String historyLine(Notice n) {
        String text = n.text().length() > 160 ? n.text().substring(0, 159) + "…" : n.text();
        return (n.atMillis() > 0 ? TIME.format(Instant.ofEpochMilli(n.atMillis())) + "  " : "") + text;
    }

    private static java.awt.Color color(Notice.Level level) {
        return switch (level) {
            case INFO -> Theme.MUTED;
            case WARN -> Theme.WARN;
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
        history = s.notices();
        notice.setText(n.text().isEmpty() ? " " : Plain.text(n.text()));
        notice.setToolTipText(n.text().isEmpty() ? (history.isEmpty() ? null : "Recent notices")
                : wrapped(fullLine(n) + " · click for recent notices"));
        notice.setForeground(color(n.level()));
        notice.setIcon(n.text().isEmpty() ? null : Icons.get(icon(n.level()), 14));
        PanelState p = s.focusedPanel();
        session.setText(s.session(p.session()).map(StatusBar::sessionText).orElse(""));
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
        for (SessionState x : s.sessions()) {
            if (x.isBusy()) {
                busy++;
            }
        }
        StringBuilder w = new StringBuilder();
        if (running > 0) {
            w.append(running).append(running == 1 ? " query" : " queries").append(" running");
        }
        if (busy > 0) {
            w.append(w.isEmpty() ? "" : " · ").append(busy).append(busy == 1 ? " session" : " sessions").append(" working");
        }
        if (!s.approvals().isEmpty()) {
            w.append(w.isEmpty() ? "" : " · ").append(s.approvals().size()).append(" waiting for approval");
        }
        work.setText(w.toString());
        work.setForeground(s.approvals().isEmpty() ? Theme.MUTED : Theme.WARN);
    }

    static String sessionText(SessionState x) {
        StringBuilder sb = new StringBuilder(x.name());
        String model = x.info().model().isEmpty() ? x.spec().model() : x.info().model();
        if (!model.isEmpty()) {
            sb.append(" · ").append(model.replace("claude-", ""));
        }
        if (!x.spec().effort().isEmpty()) {
            sb.append(" · ").append(x.spec().effort());
        }
        if (x.costUsd() > 0) {
            sb.append(" · ").append(Labels.usd(x.costUsd()));
        }
        if (x.contextTokens() > 0) {
            sb.append(" · ctx ").append(Labels.tokens(x.contextTokens()))
                    .append(x.contextWindow() > 0 ? " (" + Math.min(100, x.contextTokens() * 100 / x.contextWindow()) + "%)" : "");
        }
        if (x.isBusy()) {
            sb.append(" · ").append(x.activity().isEmpty() ? "working" : x.activity());
        }
        return sb.toString();
    }
}
