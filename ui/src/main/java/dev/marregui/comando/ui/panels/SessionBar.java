package dev.marregui.comando.ui.panels;

import dev.marregui.comando.core.intent.Intent;
import dev.marregui.comando.core.model.PanelId;
import dev.marregui.comando.core.model.Status;
import dev.marregui.comando.core.port.IntentSink;
import dev.marregui.comando.core.state.AppState;
import dev.marregui.comando.core.state.PanelState;
import dev.marregui.comando.core.state.Reflection;
import dev.marregui.comando.core.state.SessionState;
import dev.marregui.comando.core.view.Clip;
import dev.marregui.comando.ui.kit.format.Labels;
import dev.marregui.comando.ui.kit.icon.Icons;
import dev.marregui.comando.ui.kit.layout.ShrinkingRow;
import dev.marregui.comando.ui.kit.text.Plain;
import dev.marregui.comando.ui.kit.theme.Theme;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import java.awt.BorderLayout;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * The focused panel's session, under the lower panel (record 105). On the left its chip, the session's state icon, name
 * and branch, whose menu binds, attaches or detaches it; the plug that starts or stops it; the new conversation; then
 * what it does ("working · 12 s"), last, so that its changing width moves nothing. On the right its model and effort,
 * each a menu that changes it, then its context and, beside it, the checkpoint the context makes due, both purple once
 * it is. A panel without a session shows the chip that binds one, and nothing else.
 */
public final class SessionBar {

    private final IntentSink intents;
    private final JPanel root = new JPanel(new BorderLayout());
    private final JButton session = EditorPanel.chip();
    private final JButton toggle = EditorPanel.iconButton();
    /** Starts a stopped session on a new conversation; greyed out while it runs, shown once it has a conversation. */
    private final JButton fresh = EditorPanel.iconButton();
    /** What the session does: working, waiting on background work, with the time; muted. */
    private final JLabel activity = new JLabel();
    private final JButton model = EditorPanel.chip();
    private final JButton effort = EditorPanel.chip();
    /** How full the context is; purple once the checkpoint is due. */
    private final JLabel context = new JLabel();
    /**
     * Sends the session's checkpoint command; once its turn has finished, the conversation is cleared and the turn's
     * summary goes in front of the next prompt. Beside the context, which makes it due; shown while the session has
     * one.
     */
    private final JButton checkpoint = EditorPanel.iconButton();
    /** Opens the session dialog on a new session with the name given; the shell's. */
    private Consumer<String> newSession = name -> {
    };
    private PanelState panel;
    private AppState state;

    public SessionBar(IntentSink intents) {
        this.intents = intents;
        root.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, Theme.CARD_BORDER),
                BorderFactory.createEmptyBorder(3, Theme.PAD, 3, Theme.PAD)));
        root.setName("session bar");
        session.getAccessibleContext().setAccessibleName("Session");
        toggle.getAccessibleContext().setAccessibleName("Start or stop the session");
        fresh.setIcon(Icons.get("fresh"));
        fresh.getAccessibleContext().setAccessibleName("Start a new conversation");
        activity.getAccessibleContext().setAccessibleName("Activity");
        activity.setForeground(Theme.MUTED);
        model.getAccessibleContext().setAccessibleName("Model");
        effort.getAccessibleContext().setAccessibleName("Effort");
        model.setForeground(Theme.MUTED);
        effort.setForeground(Theme.MUTED);
        context.getAccessibleContext().setAccessibleName("Context");
        checkpoint.setIcon(Icons.get("checkpoint"));
        checkpoint.getAccessibleContext().setAccessibleName("Checkpoint");
        // on a narrow window the chip's name and the activity give way, ending in "…"; the buttons stay whole
        JPanel left = new JPanel(new ShrinkingRow(2, 64, Set.of(session, activity)));
        left.add(session);
        left.add(toggle);
        left.add(fresh);
        left.add(activity);
        activity.setBorder(BorderFactory.createEmptyBorder(0, Theme.PAD, 0, 0));
        // centred on the chip's line, as the left's row is; the checkpoint right after the context it belongs to
        JPanel right = new JPanel(new ShrinkingRow(2, 0, Set.of()));
        right.add(model);
        right.add(effort);
        right.add(context);
        right.add(checkpoint);
        context.setBorder(BorderFactory.createEmptyBorder(0, Theme.PAD, 0, 0));
        root.add(left, BorderLayout.CENTER);
        root.add(right, BorderLayout.EAST);

        session.addActionListener(e -> sessionMenu().show(session, 0, session.getHeight()));
        model.addActionListener(e -> modelMenu().show(model, 0, model.getHeight()));
        effort.addActionListener(e -> effortMenu().show(effort, 0, effort.getHeight()));
        // the panel as rendered last: the session it shows is the one they act on
        toggle.addActionListener(e -> shown().ifPresent(x -> intents.dispatch(x.status().isUsable()
                ? new Intent.StopSession(x.name()) : new Intent.StartSession(x.name()))));
        fresh.addActionListener(e -> shown().ifPresent(x -> intents.dispatch(new Intent.StartFresh(x.name()))));
        checkpoint.addActionListener(e -> shown().ifPresent(x -> intents.dispatch(new Intent.Checkpoint(x.name()))));
    }

    public JComponent component() {
        return root;
    }

    /** What "New session ‹title›…" opens: the session dialog with the name filled in. */
    public void onNewSession(Consumer<String> open) {
        newSession = open;
    }

    /** The session of the panel rendered last, if it has one. */
    private Optional<SessionState> shown() {
        return state == null || panel.session().isEmpty() ? Optional.empty() : state.session(panel.session());
    }

    /** The shown session's models, its own checked, as in the sidebar's menu; a choice applies now and is saved. */
    JPopupMenu modelMenu() {
        return choices(s -> SessionsSidebar.modelItems(s, intents));
    }

    /** The shown session's efforts, as the sidebar's menu has them. */
    JPopupMenu effortMenu() {
        return choices(s -> SessionsSidebar.effortItems(s, intents));
    }

    private JPopupMenu choices(Function<SessionState, List<JMenuItem>> items) {
        JPopupMenu m = new JPopupMenu();
        shown().ifPresent(s -> items.apply(s).forEach(m::add));
        return m;
    }

    /** Shows the focused panel's session. */
    public void render(AppState s) {
        state = s;
        panel = s.focusedPanel();
        Optional<SessionState> x = shown();
        if (x.isEmpty()) {
            session.setIcon(Icons.get("claude-off"));
            session.setText("no session ▾");
            session.setToolTipText("Bind a session");
            for (JComponent c : new JComponent[]{toggle, fresh, activity, model, effort, context, checkpoint}) {
                c.setVisible(false);
            }
            return;
        }
        SessionState ss = x.get();
        // the state as the session's row and its tab show it: orange running, grey stopped, faint starting, red failed
        session.setIcon(Icons.get(SessionIcons.of(ss.status())));
        session.setText(chipText(ss));
        session.setToolTipText(stateText(ss) + " · " + Labels.shortPath(ss.spec().workDir()) + (ss.error().isEmpty() ? "" : " · " + ss.error()));
        toggle.setVisible(true);
        // the plug, as on the session's row in the sidebar: green while running, grey while not; a ▶ here would read as
        // Run
        toggle.setIcon(Icons.get(ss.status().isUsable() ? "plug" : "unplug"));
        toggle.setToolTipText(ss.status().isUsable() ? "Connected; click to disconnect (stops the session)"
                : "Disconnected; click to connect (starts the session)");
        // shown from the first conversation on and never hidden again, so nothing after it moves; usable while stopped
        fresh.setVisible(!ss.spec().claudeSessionId().isEmpty());
        fresh.setEnabled(showsFresh(ss));
        fresh.setToolTipText(showsFresh(ss) ? SessionRow.freshTip(ss) : "Start a new conversation; stop the session first");
        String doing = busyText(ss).strip();
        activity.setVisible(true);
        activity.setText(doing);
        model.setVisible(true);
        model.setText(SessionRow.modelName(ss) + " ▾");
        model.setToolTipText(SessionRow.modelTip(ss));
        effort.setVisible(true);
        effort.setText(SessionRow.effortName(ss) + " ▾");
        effort.setToolTipText(SessionRow.effortTip(ss));
        boolean due = ss.reflection().due() && showsCheckpoint(ss);
        context.setVisible(true);
        context.setText(contextText(ss));
        context.setForeground(due ? Theme.THINKING : Theme.MUTED);
        context.setToolTipText(contextTip(ss, due));
        checkpoint.setVisible(showsCheckpoint(ss));
        checkpoint.setEnabled(ss.status() != Status.CLOSING);
        checkpoint.setIcon(Icons.get(checkpointIcon(ss)));
        checkpoint.setToolTipText(checkpointTip(ss));
    }

    /** Refreshes what changes with time alone: the elapsed time of what the session does, working or waiting. */
    public void tick() {
        shown().filter(SessionBar::counting).ifPresent(ss -> activity.setText(busyText(ss).strip()));
    }

    /**
     * The session chip's menu, by the panel's kind (record 88). A session's own panel names itself as such: the two are
     * one, removed together. A document attached to a session detaches from it; one attached to none lists the sessions
     * to attach to. A panel of comando's own without a session offers a new session with its name, and lists the
     * sessions, whose own panels a choice brings forward.
     */
    JPopupMenu sessionMenu() {
        JPopupMenu m = new JPopupMenu();
        if (!panel.session().isEmpty()) {
            if (panel.document()) {
                JMenuItem detach = new JMenuItem(Plain.text("Detach from " + panel.session()));
                detach.setToolTipText("The document stays open; its selection no longer runs in the session");
                detach.addActionListener(e -> intents.dispatch(new Intent.BindSession(panel.id(), "")));
                m.add(detach);
            } else {
                m.add(new JMenuItem(Plain.text(panel.session() + "'s own panel: removing either removes both"))).setEnabled(false);
            }
            return m;
        }
        if (!panel.document()) {
            JMenuItem create = new JMenuItem(Plain.text("New session " + panel.title() + "…"), Icons.get("add"));
            create.setToolTipText("A session with this panel's name, this panel its own");
            String title = panel.title();
            create.addActionListener(e -> newSession.accept(title));
            m.add(create);
            if (!state.sessions().isEmpty()) {
                m.addSeparator();
            }
        }
        PanelState p = panel;
        for (SessionState x : state.sessions()) {
            String verb = p.document() ? "Attach to " : "Open ";
            JMenuItem item = new JMenuItem(Plain.text(verb + x.name() + "  " + Labels.shortPath(x.spec().workDir())), Icons.get("claude"));
            item.addActionListener(e -> intents.dispatch(p.document() ? new Intent.BindSession(p.id(), x.name())
                    : new Intent.OpenSession(x.name(), p.id(), PanelId.random())));
            m.add(item);
        }
        if (state.sessions().isEmpty() && p.document()) {
            m.add(new JMenuItem("No sessions; New session… or Import stored conversations… in the Sessions tab's menu adds one")).setEnabled(false);
        }
        return m;
    }

    /** The session chip: name and branch, which change only when the session or its branch does; shown as text. */
    static String chipText(SessionState ss) {
        return Plain.text(ss.name() + (ss.branch().isEmpty() ? "" : "  ⎇ " + ss.branch()));
    }

    /** The state the chip's icon shows, in words, for its tooltip. */
    static String stateText(SessionState ss) {
        return switch (ss.status()) {
            case OPEN -> "Running";
            case OPENING -> "Starting";
            case CLOSING -> "Stopping";
            case FAILED -> "Failed";
            case CLOSED -> "Stopped";
        };
    }

    /** "  working · 12 s" while a turn runs, "  waiting on …" while background work runs; the shell's tick moves it. */
    static String busyText(SessionState ss) {
        if (ss.isWaiting()) {
            long since = ss.waitingSinceMillis();
            return "  " + TabStatus.waiting(ss) + (since > 0 ? " · " + Labels.elapsed(System.currentTimeMillis() - since) : "");
        }
        if (!ss.isBusy()) {
            return "";
        }
        String what = TabStatus.working(ss);
        long since = ss.turnStartedMillis();
        int queued = ss.pendingTurns() - 1;
        return "  " + what + (since > 0 ? " · " + Labels.elapsed(System.currentTimeMillis() - since) : "")
                + (queued > 0 ? " · " + queued + " queued" : "");
    }

    /** Whether the activity shows a time that the shell's one-second tick moves on: working, or waiting. */
    static boolean counting(SessionState ss) {
        return ss.isBusy() || ss.isWaiting();
    }

    /** "ctx 412k (41%)" once a turn has reported the context, "ctx –" before; no cost. */
    static String contextText(SessionState ss) {
        if (ss.contextTokens() <= 0) {
            return "ctx –";
        }
        String share = SessionRow.contextText(ss);
        return "ctx " + Labels.tokens(ss.contextTokens()) + (share.isEmpty() ? "" : " (" + share + ")");
    }

    /** How full the context is and how it relates to the checkpoint beside it. */
    static String contextTip(SessionState ss, boolean due) {
        String threshold = Math.round(Reflection.THRESHOLD * 100) + "%";
        String used = ss.contextTokens() <= 0 ? "Context: unknown until a turn ends"
                : "Context: " + SessionRow.contextText(ss) + " (" + Labels.tokens(ss.contextTokens())
                + (ss.contextWindow() > 0 ? " of " + Labels.tokens(ss.contextWindow()) : "") + " tokens)";
        if (!showsCheckpoint(ss)) {
            return used;
        }
        return used + (due ? "; past " + threshold + ", the checkpoint beside it is due"
                : "; the checkpoint beside it falls due at " + threshold);
    }

    /** The fresh-start button works while the session is stopped and has a conversation to leave behind. */
    static boolean showsFresh(SessionState ss) {
        return !ss.status().isUsable() && ss.status() != Status.CLOSING && !ss.spec().claudeSessionId().isEmpty();
    }

    static boolean showsCheckpoint(SessionState ss) {
        return !ss.spec().checkpoint().isEmpty();
    }

    /** Grey; purple once the checkpoint is due: the context filled past the threshold since the last one. */
    static String checkpointIcon(SessionState ss) {
        return ss.reflection().due() ? "checkpoint-due" : "checkpoint";
    }

    /** What the checkpoint sends and what follows: the clear, or nothing while the last one's summary still waits. */
    static String checkpointTip(SessionState ss) {
        String send = "sends " + Clip.keepStart(ss.spec().checkpoint(), 40) + (ss.spec().carried().isEmpty()
                ? ", then clears the conversation and carries its summary into the next prompt"
                : "; the last checkpoint's summary still waits for the next prompt");
        return ss.reflection().due() ? "Checkpoint due, context past " + Math.round(Reflection.THRESHOLD * 100) + "%: " + send
                : "Checkpoint: " + send;
    }
}
