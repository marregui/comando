package dev.marregui.comando.ui.panels;

import dev.marregui.comando.ui.kit.text.Plain;
import dev.marregui.comando.core.state.AppState;
import dev.marregui.comando.core.state.Notice;
import dev.marregui.comando.core.state.PanelState;
import dev.marregui.comando.core.state.SessionState;
import dev.marregui.comando.core.state.SqlRun;
import dev.marregui.comando.ui.kit.format.Labels;
import dev.marregui.comando.ui.kit.icon.Icons;
import dev.marregui.comando.ui.kit.theme.Theme;

import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.awt.FlowLayout;

/** The last notice on the left; the focused panel's session, connection and running work on the right. */
public final class StatusBar {

    /** Context window size assumed for the percentage; Claude Code reports tokens, not the window. */

    private final JPanel root = new JPanel(new BorderLayout());
    private final JLabel notice = new JLabel(" ");
    private final JLabel session = new JLabel();
    private final JLabel connection = new JLabel();
    private final JLabel work = new JLabel();

    public StatusBar() {
        root.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, Theme.CARD_BORDER),
                BorderFactory.createEmptyBorder(3, Theme.PAD, 3, Theme.PAD)));
        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, Theme.PAD * 2, 0));
        right.add(session);
        right.add(connection);
        right.add(work);
        root.add(notice, BorderLayout.CENTER);
        root.add(right, BorderLayout.EAST);
        for (JLabel l : new JLabel[]{session, connection, work}) {
            l.setForeground(Theme.MUTED);
        }
    }

    public JComponent component() {
        return root;
    }

    public void render(AppState s) {
        Notice n = s.notice();
        notice.setText(n.text().isEmpty() ? " " : Plain.text(n.text()));
        notice.setForeground(switch (n.level()) {
            case INFO -> Theme.MUTED;
            case WARN -> Theme.WARN;
            case ERROR -> Theme.DANGER;
        });
        notice.setIcon(n.text().isEmpty() ? null : Icons.get(switch (n.level()) {
            case INFO -> "info";
            case WARN -> "warning";
            case ERROR -> "error";
        }, 14));
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
