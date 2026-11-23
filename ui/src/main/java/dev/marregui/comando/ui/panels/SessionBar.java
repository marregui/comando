package dev.marregui.comando.ui.panels;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Insets;
import java.awt.Rectangle;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.IntSupplier;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;

import dev.marregui.comando.core.intent.Intent;
import dev.marregui.comando.core.model.PanelId;
import dev.marregui.comando.core.model.SessionSpec;
import dev.marregui.comando.core.model.Status;
import dev.marregui.comando.core.port.IntentSink;
import dev.marregui.comando.core.state.AppState;
import dev.marregui.comando.core.state.PanelState;
import dev.marregui.comando.core.state.Reflection;
import dev.marregui.comando.core.state.SessionEta;
import dev.marregui.comando.core.state.SessionState;
import dev.marregui.comando.core.state.SessionSubject;
import dev.marregui.comando.core.view.Clip;
import dev.marregui.comando.ui.kit.async.Debouncer;
import dev.marregui.comando.ui.kit.format.Labels;
import dev.marregui.comando.ui.kit.icon.BlendIcon;
import dev.marregui.comando.ui.kit.icon.Icons;
import dev.marregui.comando.ui.kit.layout.ShrinkingRow;
import dev.marregui.comando.ui.kit.text.Plain;
import dev.marregui.comando.ui.kit.theme.Gauge;
import dev.marregui.comando.ui.kit.theme.SidebarLook;
import dev.marregui.comando.ui.kit.theme.Style;
import dev.marregui.comando.ui.kit.theme.Theme;

/**
 * The focused panel's session, under the lower panel (record 105). On the left its chip, the session's state icon, name
 * and branch, whose menu binds, attaches or detaches it; the plug that starts or stops it; the new conversation; then
 * its run's ETA ("16:02 UTC ▸ 12 min 00 s ▸ due 16:30 UTC ▸ 60%"), always shown, last, so that its changing width
 * moves nothing. On the right its model and effort, each a menu that changes it, then its context and, beside it,
 * Reflect, Compact and Clear: Reflect sends the session's checkpoint command alone, its bookmark turning from grey to
 * purple as the context fills towards the checkpoint threshold and blinking from two points below it until the command
 * is sent; Compact and Clear send {@code /compact} and {@code /clear} to the session, a stopped one started first. A
 * panel without a session shows the chip that binds one, and nothing else. While the session runs its checkpoint every
 * control but the plug is off and the ETA, purple, ends with how to carry on (record 130).
 */
public final class SessionBar {

    /** How a session whose checkpoint runs is left: the panel's Stop, which interrupts it (record 130). */
    static final String LOCKED_HINT = "Stop to carry on";
    /** The ETA's times, in UTC: the clock the status bar shows. */
    private static final DateTimeFormatter UTC_TIME = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneOffset.UTC);
    private static final long MINUTE_MILLIS = 60_000L;
    /** Between the parts of the ETA's line. */
    private static final String HUD_SEPARATOR = " ▸ ";
    /** The cards' times, in UTC to the second. */
    private static final DateTimeFormatter UTC_SECONDS = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneOffset.UTC);
    private final IntentSink intents;
    private final JPanel root = new JPanel(new BorderLayout());
    private final JButton session = EditorPanel.chip();
    private final JButton toggle = EditorPanel.iconButton();
    /** Starts a stopped session on a new conversation; greyed out while it runs, shown once it has a conversation. */
    private final JButton fresh = EditorPanel.iconButton();
    /**
     * The ETA's widest, in widths of the digit zero. Set 2026-11-15 from Miguel's screenshot, where a whole estimate
     * filled the bar and cut the session's name; raised the same day to 80 with the always-on line, which took the
     * activity's place: a late default run with an aged report, "16:02 UTC ▸ 1 h 12 min ▸ due ~16:30 UTC (+42 min)
     * ▸ 60% (14 min ago)", is 66 characters, most narrower than a digit, so it reads whole; a longer one, the
     * checkpoint's hint after it, ends in an ellipsis and reads whole in the card.
     */
    static final int ETA_COLUMNS = 80;
    /**
     * The run's line: when it started, for how long, when it is due and how far the session says it is, in UTC; muted,
     * attention once late, purple while the checkpoint runs.
     */
    private final JLabel eta = new JLabel() {
        @Override
        public Dimension getPreferredSize() {
            final Dimension d = super.getPreferredSize();
            final Insets in = getInsets();
            final int widest = getFontMetrics(getFont()).charWidth('0') * ETA_COLUMNS + in.left + in.right;
            return new Dimension(Math.min(d.width, widest), d.height);
        }
    };
    /** The ETA's card, the card a panel's tab and a session's row show, in place of a tooltip. */
    private final TabCard card = new TabCard();
    /** Closes the tabs' and the session rows' card, so one card shows at a time; the shell's. */
    private Runnable othersCard = () -> { };

    /** What a card says: its title and facts, then under {@code heading} the {@code text}. */
    record CardText(CardFacts head, String heading, String text) {
    }
    private final JButton model = EditorPanel.chip();
    private final JButton effort = EditorPanel.chip();
    /** How full the context is; purple once the checkpoint is due. */
    private final JLabel context = new JLabel();
    /**
     * The context under the console style, once its share of the window is known: filled to it, purple once the
     * checkpoint is due, ticked at the checkpoint threshold (record 151).
     */
    private final Gauge contextGauge = new Gauge("CTX");
    /**
     * Sends the session's checkpoint command, and nothing after it; beside the context, which makes it due; shown while
     * the session has one. Its icon is drawn from grey to purple by the context's share of the threshold.
     */
    private final JButton reflect = EditorPanel.iconButton();
    /** Reflect's bookmark, drawn the share of the way to the checkpoint's purple that the context has filled. */
    private final BlendIcon reflectIcon = new BlendIcon("checkpoint", Theme.THINKING);
    /** Sends {@code /compact} to the session, starting it first when it is stopped. */
    private final JButton compact = EditorPanel.iconButton();
    /** Sends {@code /clear} to the session, started first when stopped, which goes on in a new conversation. */
    private final JButton clear = EditorPanel.iconButton();
    /**
     * The blink's phase: while Reflect blinks, the shell's one-second tick turns this over and the bookmark is drawn
     * grey on every other tick; false while it does not blink.
     */
    private boolean dim;
    /** Opens the session dialog on a new session with the name given; the shell's. */
    private Consumer<String> newSession = name -> {
    };
    /** The checkpoint threshold, in percent of the context window, as the window's setting has it (record 129). */
    private IntSupplier threshold = () -> Reflection.DEFAULT_PERCENT;
    private PanelState panel;
    private AppState state;

    public SessionBar(final IntentSink intents) {
        this.intents = intents;
        root.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, Theme.CARD_BORDER),
                BorderFactory.createEmptyBorder(3, Theme.PAD, 3, Theme.PAD)));
        root.setName("session bar");
        session.getAccessibleContext().setAccessibleName("Session");
        toggle.getAccessibleContext().setAccessibleName("Start or stop the session");
        fresh.setIcon(Icons.get("fresh"));
        fresh.getAccessibleContext().setAccessibleName("Start a new conversation");
        model.getAccessibleContext().setAccessibleName("Model");
        effort.getAccessibleContext().setAccessibleName("Effort");
        model.setForeground(Theme.MUTED);
        effort.setForeground(Theme.MUTED);
        context.getAccessibleContext().setAccessibleName("Context");
        reflect.setIcon(reflectIcon);
        reflect.getAccessibleContext().setAccessibleName("Reflect");
        compact.setIcon(Icons.get("compact"));
        compact.getAccessibleContext().setAccessibleName("Compact the conversation");
        compact.setToolTipText(SessionRow.COMPACT_TIP);
        clear.setIcon(Icons.get("clear-conversation"));
        clear.getAccessibleContext().setAccessibleName("Clear the conversation");
        // on a narrow window the ETA gives way first, then the chip's name, each ending in "…"; the buttons stay whole,
        // and a longer ETA while the session runs moves none of them
        final JPanel left = new JPanel(new ShrinkingRow(2, 64, Set.of(session, eta)));
        left.add(session);
        left.add(toggle);
        left.add(fresh);
        left.add(eta);
        eta.setBorder(BorderFactory.createEmptyBorder(0, Theme.PAD, 0, 0));
        eta.getAccessibleContext().setAccessibleName("ETA");
        eta.setForeground(Theme.MUTED);
        cardOnRest(eta, ETA_CARD);
        // centred on the chip's line, as the left's row is; Reflect right after the context that makes it due
        final JPanel right = new JPanel(new ShrinkingRow(2, 0, Set.of()));
        right.add(model);
        right.add(effort);
        right.add(context);
        right.add(contextGauge);
        right.add(reflect);
        right.add(compact);
        right.add(clear);
        context.setBorder(BorderFactory.createEmptyBorder(0, Theme.PAD, 0, 0));
        contextGauge.setBorder(BorderFactory.createEmptyBorder(0, Theme.PAD, 0, 0));
        contextGauge.setVisible(false);
        root.add(left, BorderLayout.CENTER);
        root.add(right, BorderLayout.EAST);

        session.addActionListener(e -> sessionMenu().show(session, 0, session.getHeight()));
        model.addActionListener(e -> modelMenu().show(model, 0, model.getHeight()));
        effort.addActionListener(e -> effortMenu().show(effort, 0, effort.getHeight()));
        // the panel as rendered last: the session it shows is the one they act on
        toggle.addActionListener(e -> shown().ifPresent(x -> intents.dispatch(x.status().isUsable()
                ? new Intent.StopSession(x.name()) : new Intent.StartSession(x.name()))));
        fresh.addActionListener(e -> shown().ifPresent(x -> intents.dispatch(new Intent.StartFresh(x.name()))));
        reflect.addActionListener(e -> shown().ifPresent(x -> intents.dispatch(reflectIntent(x))));
        compact.addActionListener(e -> shown().ifPresent(x -> intents.dispatch(compactIntent(x))));
        clear.addActionListener(e -> shown().ifPresent(x -> intents.dispatch(clearIntent(x))));
        // the console style shows the context as a gauge, in the tint: drawn again on another theme while in a window,
        // whose close stops it
        SidebarLook.whileShown(root, () -> {
            if (state != null) {
                render(state);
            }
        });
    }

    public JComponent component() {
        return root;
    }

    /** What closes the tabs' and the session rows' card when the bar's opens. */
    public void onCard(final Runnable closeOthers) {
        othersCard = closeOthers;
    }

    /** What "New session ‹title›…" opens: the session dialog with the name filled in. */
    public void onNewSession(final Consumer<String> open) {
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

    /** The items as the sidebar makes them; one it gives no icon shows {@code icon}, as every popup item has one. */
    private JPopupMenu choices(final Function<SessionState, List<JMenuItem>> items) {
        final JPopupMenu m = new JPopupMenu();
        shown().ifPresent(s -> items.apply(s).forEach(m::add));
        return m;
    }

    /** Shows the focused panel's session. */
    public void render(final AppState s) {
        state = s;
        panel = s.focusedPanel();
        refreshCard();
        final Optional<SessionState> x = shown();
        if (x.isEmpty()) {
            session.setIcon(Icons.get("claude-off"));
            session.setText("no session ▾");
            session.setToolTipText("Bind a session");
            for (final JComponent c : new JComponent[]{toggle, fresh, eta, model, effort, context, contextGauge,
                    reflect, compact, clear}) {
                c.setVisible(false);
            }
            return;
        }
        final SessionState ss = x.get();
        // the session runs its checkpoint: every control but the plug, which stops it, waits until it ends (record 130)
        final boolean free = !ss.isCheckpointing();
        session.setEnabled(free);
        // the state as the session's row and its tab show it: orange running, grey stopped, faint starting, red failed
        session.setIcon(Icons.get(SessionIcons.of(ss.status())));
        session.setText(chipText(ss));
        session.setToolTipText(stateText(ss) + " · " + Labels.shortPath(ss.spec().workDir()) + (ss.error().isEmpty() ? "" : " · " + ss.error()));
        toggle.setVisible(true);
        // the plug, as on the session's row in the sidebar: green while running, grey while not; a ▶ here would read as
        // Run
        toggle.setIcon(Icons.get(ss.status().isUsable() ? "plug" : "unplug"));
        toggle.setToolTipText(ss.status().isUsable() ? "Running; click to stop the session" : "Stopped; click to start it");
        // shown while the session has a conversation to leave behind; a fresh start clears it, which hides the button
        // until the new conversation's id arrives; usable while stopped
        fresh.setVisible(!ss.spec().claudeSessionId().isEmpty());
        fresh.setEnabled(canStartFresh(ss));
        fresh.setToolTipText(showsFresh(ss) ? SessionRow.freshTip(ss) : "Start a new conversation; stop the session first");
        showEta(ss);
        model.setVisible(true);
        model.setEnabled(free);
        model.setText(SessionRow.modelName(ss) + " ▾");
        model.setToolTipText(SessionRow.modelTip(ss));
        effort.setVisible(true);
        effort.setEnabled(free);
        effort.setText(SessionRow.effortName(ss) + " ▾");
        effort.setToolTipText(SessionRow.effortTip(ss));
        final boolean due = ss.reflection().due() && showsReflect(ss);
        final int percent = threshold.getAsInt();
        // a gauge shows a share it knows: before a turn reports the context, or without its window, the label stays
        final boolean gauge = Theme.style() == Style.CONSOLE && !SessionRow.contextText(ss).isEmpty();
        context.setVisible(!gauge);
        context.setText(contextText(ss));
        context.setForeground(due ? Theme.THINKING : Theme.MUTED);
        context.setToolTipText(contextTip(ss, due, percent));
        contextGauge.setVisible(gauge);
        if (gauge) {
            contextGauge.show((int) Math.min(100L, ss.contextTokens() * 100L / ss.contextWindow()),
                    due ? Theme.THINKING : Theme.tint().body);
            contextGauge.tick(showsReflect(ss) ? OptionalInt.of(percent) : OptionalInt.empty());
            contextGauge.setToolTipText(context.getToolTipText());
        }
        reflect.setVisible(showsReflect(ss));
        reflect.setEnabled(canReflect(ss));
        reflect.setToolTipText(reflectTip(ss));
        shade(ss, percent);
        compact.setVisible(true);
        compact.setEnabled(canCompact(ss));
        clear.setVisible(true);
        clear.setEnabled(canClear(ss));
        clear.setToolTipText(clearTip(ss));
    }

    /**
     * Refreshes what changes with time alone: the ETA's elapsed time, lateness and report age, the open card, and
     * Reflect's blink, which turns over at each call (the shell's one-second tick).
     */
    public void tick() {
        shown().ifPresent(ss -> {
            showEta(ss);
            refreshCard();
            final int percent = threshold.getAsInt();
            if (blinks(ss, percent) || dim) {
                dim = !dim;
                shade(ss, percent);
            }
        });
    }

    /**
     * Draws Reflect's bookmark at the context's share of the threshold, grey in the dim half of a blink; a session that
     * stops blinking is drawn lit again at once, not left grey until the next tick.
     */
    private void shade(final SessionState ss, final int percent) {
        if (!blinks(ss, percent)) {
            dim = false;
        }
        reflectIcon.share(dim ? 0.0 : reflectShare(ss, percent));
        reflect.repaint();
    }

    /**
     * The session chip's menu, by the panel's kind (record 88). A session's own panel names itself as such: the two are
     * one, removed together. A document attached to a session detaches from it; one attached to none lists the sessions
     * to attach to. A panel of comando's own without a session offers a new session with its name, and lists the
     * sessions, whose own panels a choice brings forward.
     */
    JPopupMenu sessionMenu() {
        final JPopupMenu m = new JPopupMenu();
        if (!panel.session().isEmpty()) {
            if (panel.document()) {
                final JMenuItem detach = new JMenuItem(Plain.text("Detach from " + panel.session()),
                        Icons.get("close"));
                detach.setToolTipText("The document stays open; its selection no longer runs in the session");
                detach.addActionListener(e -> intents.dispatch(new Intent.BindSession(panel.id(), "")));
                m.add(detach);
            } else {
                m.add(new JMenuItem(Plain.text(panel.session() + "'s own panel: removing either removes both"),
                        Icons.get("info"))).setEnabled(false);
            }
            return m;
        }
        if (!panel.document()) {
            final JMenuItem create = new JMenuItem(Plain.text("New session " + panel.title() + "…"), Icons.get("add"));
            create.setToolTipText("A session with this panel's name, this panel its own");
            final String title = panel.title();
            create.addActionListener(e -> newSession.accept(title));
            m.add(create);
            if (!state.sessions().isEmpty()) {
                m.addSeparator();
            }
        }
        final PanelState p = panel;
        for (final SessionState x : state.sessions()) {
            final String verb = p.document() ? "Attach to " : "Open ";
            final JMenuItem item = new JMenuItem(Plain.text(verb + x.name() + "  " + Labels.shortPath(x.spec().workDir())), Icons.get("claude"));
            item.addActionListener(e -> intents.dispatch(p.document() ? new Intent.BindSession(p.id(), x.name())
                    : new Intent.OpenSession(x.name(), p.id(), PanelId.random())));
            m.add(item);
        }
        if (state.sessions().isEmpty() && p.document()) {
            m.add(new JMenuItem("No sessions; New session… or Import stored conversations… in the Sessions tab's "
                    + "menu adds one", Icons.get("info"))).setEnabled(false);
        }
        return m;
    }

    /**
     * The session chip: its name, then the branch of what it works on with that repository's short name, else the
     * branch of its working folder; these change only when the session or its work does; shown as text.
     */
    static String chipText(final SessionState ss) {
        final SessionSubject subject = ss.subject();
        if (subject.isKnown()) {
            final String path = subject.repository();
        final String repository = path.substring(path.lastIndexOf(File.separatorChar) + 1);
            final String branch = subject.branch().isEmpty() ? "" : "  ⎇ " + subject.branch();
        return Plain.text(ss.name() + branch + " · " + repository);
        }
        return Plain.text(ss.name() + (ss.branch().isEmpty() ? "" : "  ⎇ " + ss.branch()));
    }

    /** The state the chip's icon shows, in words, for its tooltip. */
    static String stateText(final SessionState ss) {
        return switch (ss.status()) {
            case OPEN -> "Running";
            case OPENING -> "Starting";
            case CLOSING -> "Stopping";
            case FAILED -> "Failed";
            case CLOSED -> "Stopped";
        };
    }

    /**
     * What the session does, for the status bar's work rows: "  Bash · turn 12 s" while a turn runs, what it does now
     * and how long the whole turn has taken; "  waiting on …" while background work runs. While the checkpoint runs,
     * what it does and how to leave it: "  checkpoint · compacting · 12 s · Stop to carry on".
     */
    static String doingText(final SessionState ss) {
        if (ss.isCheckpointing()) {
            final long since = ss.turnStartedMillis();
            final String elapsed = since > 0 ? " · " + Labels.elapsed(System.currentTimeMillis() - since) : "";
            return "  checkpoint · " + TabStatus.working(ss) + elapsed + " · " + LOCKED_HINT;
        }
        if (ss.isWaiting()) {
            final long since = ss.waitingSinceMillis();
            return "  " + TabStatus.waiting(ss) + (since > 0 ? " · " + Labels.elapsed(System.currentTimeMillis() - since) : "");
        }
        if (!ss.isBusy()) {
            return "";
        }
        final String what = TabStatus.working(ss);
        final long since = ss.turnStartedMillis();
        final int queued = ss.pendingTurns() - 1;
        return "  " + what + (since > 0 ? " · turn " + Labels.elapsed(System.currentTimeMillis() - since) : "")
                + (queued > 0 ? " · " + queued + " queued" : "");
    }

    /** The ETA label: always shown for a session, its colour as {@link #lineColour} has it. */
    private void showEta(final SessionState ss) {
        final long nowMillis = System.currentTimeMillis();
        eta.setVisible(true);
        eta.setText(lineText(ss, nowMillis));
        eta.setForeground(lineColour(ss, nowMillis));
    }

    private static final String ETA_CARD = "session-bar:eta";

    /**
     * {@code label}'s card once the pointer rests on it for {@link SessionsSidebar#FOLLOW_MILLIS}, as a session row's;
     * it closes when the pointer leaves for anywhere but the card.
     */
    private void cardOnRest(final JLabel label, final String key) {
        final Debouncer rest = new Debouncer(SessionsSidebar.FOLLOW_MILLIS, () -> {
            if (label.isShowing()) {
                othersCard.run();
                cardText(key).ifPresent(c -> card.showOther(key, label, new Rectangle(0, 0, label.getWidth(), label.getHeight()), -1,
                        c.head(), c.heading(), c.text()));
            }
        });
        label.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseEntered(final MouseEvent e) {
                if (key.equals(card.shown())) {
                    card.stay();
                } else {
                    rest.request();
                }
            }

            @Override
            public void mouseExited(final MouseEvent e) {
                rest.stop();
                card.left();
            }
        });
    }

    /** What the card {@code key} says of the session shown now; empty without one. */
    private Optional<CardText> cardText(final String key) {
        final long nowMillis = System.currentTimeMillis();
        return key.equals(ETA_CARD) ? shown().map(ss -> etaCard(ss, nowMillis)) : Optional.empty();
    }

    /** The card shown, its facts again: the times in it move with the shell's tick; one with nothing to say closes. */
    private void refreshCard() {
        final String key = card.shown();
        if (ETA_CARD.equals(key)) {
            cardText(key).ifPresentOrElse(c -> card.updateOther(c.head(), c.heading(), c.text()), card::hide);
        }
    }

    /**
     * The ETA's card: the run, where its deadline came from, when it started, for how long, when it is due and the
     * session's last report; the step, the goal and the timebox the session set; what the session does now (the turn,
     * its queue, the background work it waits on, how to leave its checkpoint); the time now. All in UTC.
     */
    static CardText etaCard(final SessionState ss, final long nowMillis) {
        final SessionEta e = ss.eta();
        final SessionEta.Run r = e.run();
        final List<CardFacts.Fact> facts = new ArrayList<>();
        if (r.exists()) {
            facts.add(new CardFacts.Fact("Run", r.source() == SessionEta.Run.Source.DEFAULT ? "turn" : "task"));
            facts.add(new CardFacts.Fact("Deadline from", deadlineFrom(e)));
            facts.add(new CardFacts.Fact("Started", utcTime(r.startedMillis()) + " UTC"));
            if (r.running()) {
                facts.add(new CardFacts.Fact("Running for", Labels.elapsed(nowMillis - r.startedMillis())));
            } else {
                facts.add(new CardFacts.Fact("Took", Labels.elapsed(r.endedMillis() - r.startedMillis())));
            }
            if (r.deadlineMillis() > 0L) {
                facts.add(new CardFacts.Fact("Due", r.running() ? due(r.deadlineMillis(), nowMillis)
                        : utcTime(r.deadlineMillis()) + " UTC, " + doneText(r)));
            }
            facts.add(new CardFacts.Fact("Progress", r.percent() < 0 ? "not reported"
                    : r.percent() + "%, said " + utcTime(r.percentAtMillis()) + " UTC, "
                            + Labels.elapsed(nowMillis - r.percentAtMillis()) + " ago"));
        } else {
            facts.add(new CardFacts.Fact("Run", "none yet"));
        }
        if (e.stepAtMillis() > 0L) {
            facts.add(new CardFacts.Fact("Step", e.step() + " · due " + due(e.stepAtMillis(), nowMillis)));
        }
        if (e.goalAtMillis() > 0L) {
            facts.add(new CardFacts.Fact("Goal", e.goal() + " · due " + due(e.goalAtMillis(), nowMillis)));
        }
        if (e.boxMinutes() > 0) {
            final long endsMillis = e.boxGivenAtMillis() + e.boxMinutes() * MINUTE_MILLIS;
            facts.add(new CardFacts.Fact("Timebox", e.boxMinutes() + " min, given at " + utcTime(e.boxGivenAtMillis())
                    + " UTC · ends " + due(endsMillis, nowMillis)));
        }
        if (ss.isCheckpointing()) {
            facts.add(new CardFacts.Fact("Doing", "checkpoint · " + TabStatus.working(ss)));
            facts.add(new CardFacts.Fact("To carry on", LOCKED_HINT));
        } else if (ss.isBusy()) {
            facts.add(new CardFacts.Fact("Doing", TabStatus.working(ss)));
            if (ss.turnStartedMillis() > 0L) {
                facts.add(new CardFacts.Fact("Turn started", utcTime(ss.turnStartedMillis()) + " UTC, "
                        + Labels.elapsed(nowMillis - ss.turnStartedMillis()) + " ago"));
            }
            if (ss.pendingTurns() > 1) {
                facts.add(new CardFacts.Fact("Queued", (ss.pendingTurns() - 1) + " prompt" + (ss.pendingTurns() > 2 ? "s" : "")));
            }
        } else if (ss.isWaiting()) {
            facts.add(new CardFacts.Fact("Waiting on", ss.waitingFor()));
            if (ss.waitingSinceMillis() > 0L) {
                facts.add(new CardFacts.Fact("Since", utcTime(ss.waitingSinceMillis()) + " UTC, "
                        + Labels.elapsed(nowMillis - ss.waitingSinceMillis()) + " ago"));
            }
        }
        facts.add(new CardFacts.Fact("Now", utcTime(nowMillis) + " UTC"));
        return new CardText(new CardFacts("ETA · " + ss.name(), facts), "What it means",
                "The run is the task the session declared, else the turn under way, else the last one. Its deadline "
                        + "is the timebox you gave, else the session's estimate, else comando's default for a turn, "
                        + "marked ~ on the bar: the median of the session's last turns. Progress is the session's own "
                        + "report. Times are in UTC; a run past its deadline turns the bar's text gold, a checkpoint "
                        + "purple.");
    }

    /** "timebox 45 min", "session estimate", "default: median of last 7 turns" or "default: 5 min, no turns yet". */
    private static String deadlineFrom(final SessionEta e) {
        // the run's own span, not the estimate's: the estimate may have moved on since the run began
        final SessionEta.Run r = e.run();
        final long spanMinutes = minutesUp(r.deadlineMillis() - r.startedMillis());
        return switch (r.source()) {
            case BOX -> "timebox " + spanMinutes + " min";
            case ESTIMATE -> "session estimate, " + spanMinutes + " min";
            case DEFAULT -> "default " + spanMinutes + " min: the median of the session's last turns, 5 min before any";
        };
    }

    /** "12:40:00 UTC, 8 min left", or "12:40:00 UTC, 3 min late" once it has passed. */
    private static String due(final long atMillis, final long nowMillis) {
        final long leftMillis = atMillis - nowMillis;
        return utcTime(atMillis) + " UTC, " + minutesUp(Math.abs(leftMillis)) + " min "
                + (leftMillis < 0L ? "late" : "left");
    }

    private static String utcTime(final long millis) {
        return UTC_SECONDS.format(Instant.ofEpochMilli(millis));
    }

    /**
     * The run's line, HH:mm in UTC. Running: "16:02 UTC ▸ 12 min 00 s ▸ due 16:30 UTC ▸ 60% (4 min ago)", the
     * deadline marked "~" when it is comando's default, "(+6 min)" after it once late, rounded up; the percent "–%"
     * before the session reports one and without its age while the report is under a minute old. Ended: "15:30 UTC ▸
     * 27 min 00 s ▸ due 15:58 UTC ▸ done, 1 min early". Before any run: "no run yet".
     */
    static String hudText(final SessionEta e, final long nowMillis) {
        final SessionEta.Run r = e.run();
        if (!r.exists()) {
            return "no run yet";
        }
        final String deadline = "due " + (r.source() == SessionEta.Run.Source.DEFAULT ? "~" : "")
                + (r.deadlineMillis() > 0L ? hhmm(r.deadlineMillis()) : "–");
        if (!r.running()) {
            return hhmm(r.startedMillis()) + HUD_SEPARATOR + Labels.elapsed(r.endedMillis() - r.startedMillis())
                    + HUD_SEPARATOR + deadline + HUD_SEPARATOR + doneText(r);
        }
        final String late = late(r, nowMillis) ? " (+" + minutesUp(nowMillis - r.deadlineMillis()) + " min)" : "";
        return hhmm(r.startedMillis()) + HUD_SEPARATOR + Labels.elapsed(nowMillis - r.startedMillis())
                + HUD_SEPARATOR + deadline + late + HUD_SEPARATOR + percentText(r, nowMillis);
    }

    /** The bar's line: {@link #hudText}, then, while the checkpoint runs, "▸ checkpoint · Stop to carry on". */
    static String lineText(final SessionState ss, final long nowMillis) {
        final String hint = ss.isCheckpointing() ? HUD_SEPARATOR + "checkpoint · " + LOCKED_HINT : "";
        return hudText(ss.eta(), nowMillis) + hint;
    }

    /** The line's colour: purple while the checkpoint runs, else attention while the run is late, else muted. */
    static Color lineColour(final SessionState ss, final long nowMillis) {
        if (ss.isCheckpointing()) {
            return Theme.THINKING;
        }
        return late(ss.eta().run(), nowMillis) ? Theme.ATTENTION : Theme.MUTED;
    }

    /** Whether the run runs past its deadline. */
    static boolean late(final SessionEta.Run r, final long nowMillis) {
        return r.running() && r.deadlineMillis() > 0L && nowMillis > r.deadlineMillis();
    }

    /** "–%" before a report, "60%" while it is under a minute old, "60% (4 min ago)" after, to the minute. */
    private static String percentText(final SessionEta.Run r, final long nowMillis) {
        if (r.percent() < 0) {
            return "–%";
        }
        final long ageMillis = nowMillis - r.percentAtMillis();
        return r.percent() + "%" + (ageMillis < MINUTE_MILLIS ? "" : " (" + Labels.uptime(ageMillis) + " ago)");
    }

    /**
     * How an ended run met its deadline: "done, on time" within a minute either way, else "done, 3 min late" or "done,
     * 1 min early", in whole minutes, so the minute after "on time" reads 1.
     */
    private static String doneText(final SessionEta.Run r) {
        if (r.deadlineMillis() <= 0L) {
            return "done";
        }
        final long overMillis = r.endedMillis() - r.deadlineMillis();
        if (Math.abs(overMillis) < MINUTE_MILLIS) {
            return "done, on time";
        }
        return "done, " + Math.abs(overMillis) / MINUTE_MILLIS + " min " + (overMillis < 0L ? "early" : "late");
    }

    /** "16:02 UTC". */
    private static String hhmm(final long millis) {
        return UTC_TIME.format(Instant.ofEpochMilli(millis)) + " UTC";
    }

    /** Whole minutes in {@code millis}, rounded up. */
    private static long minutesUp(final long millis) {
        return (millis + MINUTE_MILLIS - 1L) / MINUTE_MILLIS;
    }

    /** "ctx 412k (41%)" once a turn has reported the context, "ctx –" before; no cost. */
    static String contextText(final SessionState ss) {
        if (ss.contextTokens() <= 0) {
            return "ctx –";
        }
        final String share = SessionRow.contextText(ss);
        return "ctx " + Labels.tokens(ss.contextTokens()) + (share.isEmpty() ? "" : " (" + share + ")");
    }

    /** Where the checkpoint threshold is read, in percent of the context window; the shell's setting. */
    public void checkpointThreshold(final IntSupplier percent) {
        threshold = percent;
    }

    /**
     * How full the context is and how it relates to Reflect beside it, which falls due at {@code percent} of the
     * context window.
     */
    static String contextTip(final SessionState ss, final boolean due, final int percent) {
        final String threshold = percent + "%";
        final String used = ss.contextTokens() <= 0 ? "Context: unknown until a turn ends"
                : "Context: " + SessionRow.contextText(ss) + " (" + Labels.tokens(ss.contextTokens())
                + (ss.contextWindow() > 0 ? " of " + Labels.tokens(ss.contextWindow()) : "") + " tokens)";
        if (!showsReflect(ss)) {
            return used;
        }
        return used + (due ? "; past " + threshold + ", Reflect beside it is due"
                : "; Reflect beside it falls due at " + threshold);
    }

    /** The fresh-start button works while the session is stopped and has a conversation to leave behind. */
    public static boolean showsFresh(final SessionState ss) {
        return !ss.status().isUsable() && ss.status() != Status.CLOSING && !ss.spec().claudeSessionId().isEmpty();
    }

    /**
     * Whether Start a new conversation can run, wherever offered: the session bar, the session's menu, the group
     * menu and the palette; as {@link #showsFresh}, and not while the session runs its checkpoint (record 130).
     */
    public static boolean canStartFresh(final SessionState ss) {
        return showsFresh(ss) && !ss.isCheckpointing();
    }

    /** Reflect shows for a session with a checkpoint command: on the session bar, the session's menu, the palette. */
    public static boolean showsReflect(final SessionState ss) {
        return !ss.spec().checkpoint().isEmpty();
    }

    /**
     * Whether Reflect can run, the same on the session bar, the session's menu and the palette: the session has a
     * checkpoint command, is not stopping, which the reducer refuses, and does not run its checkpoint already (record
     * 130). A stopped session takes it: it starts, then the command is sent, as a prompt would be.
     */
    public static boolean canReflect(final SessionState ss) {
        return showsReflect(ss) && ss.status() != Status.CLOSING && !ss.isCheckpointing();
    }

    /** Whether Start can run, wherever offered: a session not running and not stopping, which the reducer refuses. */
    public static boolean canStart(final SessionState ss) {
        return !ss.status().isUsable() && ss.status() != Status.CLOSING;
    }

    /**
     * Whether Compact can run, wherever offered: a session not stopping, which the reducer refuses, and not while its
     * checkpoint runs (record 130). A stopped session takes it, as Reflect: it starts, then the command is sent.
     */
    public static boolean canCompact(final SessionState ss) {
        return ss.status() != Status.CLOSING && !ss.isCheckpointing();
    }

    /** Whether Clear can run, wherever it is offered: as Compact; a stopped session starts, then clears. */
    public static boolean canClear(final SessionState ss) {
        return canCompact(ss);
    }

    /**
     * What Reflect dispatches: the session's checkpoint command, alone, which the reducer marks as the checkpoint sent
     * and runs locked until its turn ends; no compaction follows, Compact is beside it.
     */
    public static Intent reflectIntent(final SessionState ss) {
        return new Intent.SessionCommand(ss.name(), ss.spec().checkpoint());
    }

    /** What Compact dispatches: {@code /compact} to the session. */
    public static Intent compactIntent(final SessionState ss) {
        return new Intent.SessionCommand(ss.name(), SessionSpec.COMPACT_COMMAND);
    }

    /** What Clear dispatches: {@code /clear} to the session, whose new conversation the reducer then follows. */
    public static Intent clearIntent(final SessionState ss) {
        return new Intent.SessionCommand(ss.name(), SessionSpec.CLEAR_COMMAND);
    }

    /** What Reflect sends, and whether the context has made it due. */
    public static String reflectTip(final SessionState ss) {
        final String send = "sends " + Clip.keepStart(ss.spec().checkpoint(), 40);
        return ss.reflection().due() ? "Reflect, due: the context passed the checkpoint threshold; " + send
                : "Reflect: " + send;
    }

    /** What Clear sends and what it leaves; that a stopped session starts for it. */
    public static String clearTip(final SessionState ss) {
        final String tip = "Clear the conversation: sends " + SessionSpec.CLEAR_COMMAND
                + "; the session goes on in a new one";
        return ss.status().isUsable() ? tip : tip + "; the session starts first";
    }

    /** The icon a menu shows for Reflect: grey; purple once due, the context past the threshold since the last. */
    public static String reflectIconName(final SessionState ss) {
        return ss.reflection().due() ? "checkpoint-due" : "checkpoint";
    }

    /**
     * How far Reflect's bookmark is drawn from grey to purple: the context's share of the threshold, {@code percent} of
     * the window, at most 1; 0 while the context or its window is unknown.
     */
    static double reflectShare(final SessionState ss, final int percent) {
        if (ss.contextTokens() <= 0L || ss.contextWindow() <= 0L || percent <= 0) {
            return 0.0;
        }
        return Math.clamp(ss.contextTokens() * 100.0 / ((double) percent * ss.contextWindow()), 0.0, 1.0);
    }

    /**
     * Whether Reflect blinks, as {@link Reflection#blinks} has it: due, or the context within
     * {@link Reflection#BLINK_POINTS} of the threshold, {@code percent} of the window, and the checkpoint command not
     * sent since it last came into that band.
     */
    static boolean blinks(final SessionState ss, final int percent) {
        return showsReflect(ss) && ss.reflection().blinks(ss.contextTokens(), ss.contextWindow(), percent);
    }
}
