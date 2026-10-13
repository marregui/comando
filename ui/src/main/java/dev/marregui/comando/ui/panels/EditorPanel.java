package dev.marregui.comando.ui.panels;

import dev.marregui.comando.core.dispatch.Target;
import dev.marregui.comando.core.intent.Intent;
import dev.marregui.comando.core.model.Status;
import dev.marregui.comando.core.port.IntentSink;
import dev.marregui.comando.core.schema.Schema;
import dev.marregui.comando.core.schema.SchemaRegistry;
import dev.marregui.comando.core.state.AppState;
import dev.marregui.comando.core.state.ConnectionState;
import dev.marregui.comando.core.state.PanelState;
import dev.marregui.comando.core.state.SessionState;
import dev.marregui.comando.ui.kit.format.Labels;
import dev.marregui.comando.ui.kit.icon.Icons;
import dev.marregui.comando.ui.kit.theme.Theme;
import dev.marregui.comando.ui.widgets.editor.CommandEditor;
import dev.marregui.comando.ui.widgets.editor.PromptBar;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.FlowLayout;
import java.util.Optional;

/**
 * One editor panel: a header with its connection and session, the command editor, and the prompt bar.
 * A production connection frames the panel in red.
 */
public final class EditorPanel extends JPanel {

    private final IntentSink intents;
    private final CommandEditor editor = new CommandEditor();
    private final PromptBar prompt;
    private final JButton connection = chip();
    private final JButton connectionToggle = iconButton();
    private final JButton session = chip();
    private final JButton sessionToggle = iconButton();
    private final JButton run = new JButton("Run", Icons.get("play"));
    private final JButton stop = new JButton(Icons.get("stop"));
    private final SchemaRegistry schemas;
    private PanelState panel;
    private AppState state;

    public EditorPanel(PanelState panel, IntentSink intents, SchemaRegistry schemas) {
        super(new BorderLayout(0, 0));
        this.panel = panel;
        this.intents = intents;
        this.schemas = schemas;
        this.prompt = new PromptBar(text -> intents.dispatch(new Intent.RunText(this.panel.id(), text, Target.CLAUDE)));
        JPanel header = new JPanel(new BorderLayout());
        header.setBorder(BorderFactory.createEmptyBorder(4, Theme.GAP, 4, Theme.GAP));
        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 2, 0));
        left.add(connection);
        left.add(connectionToggle);
        left.add(javax.swing.Box.createHorizontalStrut(Theme.PAD));
        left.add(session);
        left.add(sessionToggle);
        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, Theme.GAP, 0));
        run.setToolTipText("Run the selection or the block under the caret (⌘⏎); ⌘⇧⏎ sends to Claude, ⌥⏎ runs as SQL");
        stop.setToolTipText("Stop the running SQL or interrupt the session (⌘.)");
        stop.putClientProperty("JButton.buttonType", "toolBarButton");
        right.add(run);
        right.add(stop);
        header.add(left, BorderLayout.WEST);
        header.add(right, BorderLayout.EAST);
        JPanel south = new JPanel(new BorderLayout());
        south.setBorder(BorderFactory.createEmptyBorder(0, Theme.GAP, Theme.GAP, Theme.GAP));
        south.add(prompt, BorderLayout.CENTER);
        add(header, BorderLayout.NORTH);
        add(editor.component(), BorderLayout.CENTER);
        add(south, BorderLayout.SOUTH);

        run.addActionListener(e -> runSelection(Target.AUTO));
        stop.addActionListener(e -> intents.dispatch(new Intent.CancelRun(this.panel.id())));
        connection.addActionListener(e -> connectionMenu().show(connection, 0, connection.getHeight()));
        session.addActionListener(e -> sessionMenu().show(session, 0, session.getHeight()));
        connectionToggle.addActionListener(e -> toggleConnection());
        sessionToggle.addActionListener(e -> toggleSession());
    }

    public CommandEditor editor() {
        return editor;
    }

    public PromptBar prompt() {
        return prompt;
    }

    public PanelState panel() {
        return panel;
    }

    public void runSelection(Target target) {
        String text = editor.runText();
        if (!text.isBlank()) {
            intents.dispatch(new Intent.RunText(panel.id(), text, target));
        }
    }

    public void render(PanelState p, AppState s) {
        panel = p;
        state = s;
        Optional<ConnectionState> c = p.connection().isEmpty() ? Optional.empty() : s.connection(p.connection());
        Optional<SessionState> x = p.session().isEmpty() ? Optional.empty() : s.session(p.session());
        // connection chip
        if (c.isPresent()) {
            ConnectionState cs = c.get();
            connection.setIcon(Icons.get("database"));
            connection.setText(cs.name() + "  " + dot(cs.status()));
            connection.setToolTipText(cs.spec().label() + " · " + cs.status() + (cs.error().isEmpty() ? "" : " · " + cs.error()));
            connectionToggle.setVisible(true);
            connectionToggle.setIcon(Icons.get(cs.status().isUsable() ? "unplug" : "plug"));
            connectionToggle.setToolTipText(cs.status().isUsable() ? "Disconnect" : "Connect");
            setBorder(cs.spec().prod() ? BorderFactory.createLineBorder(Theme.DANGER, 2) : BorderFactory.createEmptyBorder(2, 2, 2, 2));
        } else {
            connection.setIcon(Icons.get("database"));
            connection.setText("no connection ▾");
            connection.setToolTipText("Bind a connection");
            connectionToggle.setVisible(false);
            setBorder(BorderFactory.createEmptyBorder(2, 2, 2, 2));
        }
        // session chip
        if (x.isPresent()) {
            SessionState ss = x.get();
            String branch = ss.branch().isEmpty() ? "" : "  ⎇ " + ss.branch();
            session.setIcon(Icons.get("claude"));
            session.setText(ss.name() + branch + "  " + dot(ss.status()) + (ss.isBusy() ? "  working" : ""));
            session.setToolTipText(Labels.shortPath(ss.spec().workDir()) + " · " + ss.status()
                    + (ss.error().isEmpty() ? "" : " · " + ss.error()));
            sessionToggle.setVisible(true);
            sessionToggle.setIcon(Icons.get(ss.status().isUsable() ? "stop" : "play"));
            sessionToggle.setToolTipText(ss.status().isUsable() ? "Stop the session" : "Start the session");
            prompt.setTarget(ss.name(), ss.isBusy());
        } else {
            session.setIcon(Icons.get("claude"));
            session.setText("no session ▾");
            session.setToolTipText("Bind a session");
            sessionToggle.setVisible(false);
            prompt.setTarget("", false);
        }
        editor.setSchema(p.connection().isEmpty() ? Schema.EMPTY : schemas.of(p.connection()));
        boolean running = s.runOf(p.id()).map(r -> r.status().isActive()).orElse(false) || x.map(SessionState::isBusy).orElse(false);
        stop.setEnabled(running);
    }

    private void toggleConnection() {
        state.connection(panel.connection()).ifPresent(c -> intents.dispatch(c.status().isUsable()
                ? new Intent.CloseConnection(c.name()) : new Intent.OpenConnection(c.name())));
    }

    private void toggleSession() {
        state.session(panel.session()).ifPresent(x -> intents.dispatch(x.status().isUsable()
                ? new Intent.StopSession(x.name()) : new Intent.StartSession(x.name())));
    }

    private JPopupMenu connectionMenu() {
        JPopupMenu m = new JPopupMenu();
        for (ConnectionState c : state.connections()) {
            JMenuItem item = new JMenuItem(c.name() + "  " + c.spec().label(), Icons.get("database"));
            item.addActionListener(e -> intents.dispatch(new Intent.BindConnection(panel.id(), c.name())));
            m.add(item);
        }
        if (!panel.connection().isEmpty()) {
            m.addSeparator();
            JMenuItem none = new JMenuItem("Unbind");
            none.addActionListener(e -> intents.dispatch(new Intent.BindConnection(panel.id(), "")));
            m.add(none);
        }
        if (state.connections().isEmpty()) {
            m.add(new JMenuItem("No connections; add one with Connections…")).setEnabled(false);
        }
        return m;
    }

    private JPopupMenu sessionMenu() {
        JPopupMenu m = new JPopupMenu();
        for (SessionState x : state.sessions()) {
            JMenuItem item = new JMenuItem(x.name() + "  " + Labels.shortPath(x.spec().workDir()), Icons.get("claude"));
            item.addActionListener(e -> intents.dispatch(new Intent.BindSession(panel.id(), x.name())));
            m.add(item);
        }
        if (!panel.session().isEmpty()) {
            m.addSeparator();
            JMenuItem none = new JMenuItem("Unbind");
            none.addActionListener(e -> intents.dispatch(new Intent.BindSession(panel.id(), "")));
            m.add(none);
        }
        if (state.sessions().isEmpty()) {
            m.add(new JMenuItem("No sessions; add or import one with Sessions…")).setEnabled(false);
        }
        return m;
    }

    static String dot(Status s) {
        return switch (s) {
            case OPEN -> "●";
            case OPENING, CLOSING -> "◐";
            case FAILED -> "✕";
            case CLOSED -> "○";
        };
    }

    static Color color(Status s) {
        return switch (s) {
            case OPEN -> Theme.OK;
            case OPENING, CLOSING -> Theme.WARN;
            case FAILED -> Theme.DANGER;
            case CLOSED -> Theme.MUTED;
        };
    }

    private static JButton chip() {
        JButton b = new JButton();
        b.putClientProperty("JButton.buttonType", "toolBarButton");
        b.setFocusable(false);
        return b;
    }

    private static JButton iconButton() {
        JButton b = new JButton();
        b.putClientProperty("JButton.buttonType", "toolBarButton");
        b.setFocusable(false);
        return b;
    }
}
