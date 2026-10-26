package dev.marregui.comando.ui.panels;

import dev.marregui.comando.core.intent.Intent;
import dev.marregui.comando.core.model.LinearTickets;
import dev.marregui.comando.core.model.PanelId;
import dev.marregui.comando.core.model.Topic;
import dev.marregui.comando.core.port.IntentSink;
import dev.marregui.comando.core.port.TopicPort;
import dev.marregui.comando.core.schema.SchemaRegistry;
import dev.marregui.comando.core.state.AppState;
import dev.marregui.comando.core.state.Notice;
import dev.marregui.comando.core.state.PanelState;
import dev.marregui.comando.core.state.SessionState;
import dev.marregui.comando.core.transcript.Block;
import dev.marregui.comando.core.transcript.Transcript;
import dev.marregui.comando.core.transcript.TopicSource;
import dev.marregui.comando.core.view.Clip;
import dev.marregui.comando.ui.PanelTexts;
import dev.marregui.comando.ui.kit.async.Edt;
import dev.marregui.comando.ui.kit.format.KeyText;
import dev.marregui.comando.ui.kit.format.Labels;
import dev.marregui.comando.ui.kit.icon.Icons;
import dev.marregui.comando.ui.kit.icon.SpinnerIcon;
import dev.marregui.comando.ui.kit.tabs.MovableTabs;
import dev.marregui.comando.ui.kit.text.Plain;
import dev.marregui.comando.ui.kit.theme.Theme;

import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JTabbedPane;
import javax.swing.Timer;
import javax.swing.ToolTipManager;
import java.awt.Component;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Rectangle;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.IntConsumer;
import java.util.function.LongSupplier;
import java.util.function.Predicate;

/**
 * The open panels as tabs, in state order; the selected tab is the focused panel, and a tab dragged along the row moves
 * its panel. The tabs closed most recently are remembered for ⌘⇧T: a panel of comando's own by its id (the state keeps
 * it, closed), a document by its file. Each tab shows its panel's {@link TabStatus} in its icon, and a "● " before its
 * title while the status asks for a look: a turn or query that finished (or failed) while another tab was in front is
 * marked until the user looks at it; a session waiting for the user is marked until answered; a running one spins.
 * A tab's menu closes it, the others, or those to its right or its left; such a close keeps the tabs waiting for the
 * user and those whose shell runs a program.
 *
 * <p>A tab shows a {@link TabCard} rather than a tooltip: its status, the Linear tickets its text names, and the
 * panel's topic, a few sentences Haiku wrote on what the panel is about. A topic is read when its card first shows,
 * again when the card shows after what it is read from changed, and whenever the card's button asks; never on a timer.
 * The card closes when the pointer leaves it, or leaves the tab without reaching the card within
 * {@link #LEAVE_GRACE_MILLIS}.</p>
 */
public final class Workspace {

    /** A tab as it closed: a panel of comando's own by its id, a document by its file. */
    public record ClosedPanel(PanelId id, String title, boolean document, Path file) {
    }

    static final int CLOSED_KEPT = 10;
    static final int TITLE_CHARS = 32;
    /** How long the pointer has from leaving a tab to reaching its card below before the card closes. */
    static final int LEAVE_GRACE_MILLIS = 500;
    /** How much of the end of a panel's text its topic and tickets are read from. */
    static final int PANEL_TEXT_READ = 4 * TopicSource.MAX_CHARS;
    /** At most this many tickets show on a card. */
    static final int MAX_TICKETS = 3;
    /** How far back a session's conversation is read for Linear's links, which teach the tickets' addresses. */
    static final int LINK_BLOCKS = 300;

    private final IntentSink intents;
    private final PanelTexts texts;
    private final SchemaRegistry schemas;
    private final MovableTabs tabs = named(new MovableTabs(), "workspace");
    private final Map<PanelId, EditorPanel> panels = new LinkedHashMap<>();
    private final Deque<ClosedPanel> closed = new ArrayDeque<>();
    /** Whether each panel's session or query was at work when last rendered; a change to false is an outcome. */
    private final LongSupplier clock;
    private final Map<PanelId, Boolean> lastActive = new HashMap<>();
    /** Panels whose work ended while another tab was in front, until the user looks at them. */
    private final Set<PanelId> unseen = new HashSet<>();
    private final Map<PanelId, TabStatus> statuses = new HashMap<>();
    /** One wheel for every spinning tab; the timer turns it and repaints only the spinning tabs' bounds. */
    private final SpinnerIcon spinner = new SpinnerIcon(16);
    private final Timer spin = new Timer(SpinnerIcon.STEP_MILLIS, e -> spinTabs());
    private Runnable attention = () -> {
    };
    private Consumer<PanelId> closer;
    /** Whether a panel's shell runs a program, which closing the panel would end; the shell tells. */
    private Predicate<PanelId> runsProgram = id -> false;
    /** The tabs a menu's close is closing, which post no notice of their own, and the one notice said once they are gone. EDT only. */
    private final Set<PanelId> bulkClosing = new HashSet<>();
    private String bulkSummary;
    /** Opens a file a panel's link names; the shell's own open. */
    private Consumer<Path> opener = f -> {
    };
    /** Opens the session dialog on a new session named as given: a panel's "New session ‹title›…". */
    private Consumer<String> newSession = name -> {
    };
    /** Panels removed because their file was deleted outside comando, with the file's name. EDT only. */
    private final Map<PanelId, String> deletedFiles = new HashMap<>();
    private boolean rendering;
    /** Files asked for to edit: the panel that holds one, now or once it opens, shows its text rather than rendered. */
    private final Set<Path> toEdit = new HashSet<>();
    /** Files a link opens at one of their headings, with the heading, until their panel shows. EDT only. */
    private final Map<Path, String> toHeading = new HashMap<>();
    /** Log files whose panel shows an entry, by its time, at the render that shows the panel. */
    private final Map<Path, Long> toEntry = new HashMap<>();
    /** The state last rendered, for a deleted file of a closed panel, which has no tab. EDT only. */
    private AppState shown;
    /** What each tab's tooltip would say, which its card shows. EDT only. */
    private final Map<PanelId, String> tips = new HashMap<>();
    /** Reads the panels' topics; none until the shell gives one. */
    private TopicPort topics = TopicPort.NONE;
    /** A session's transcript by name, which a session panel's topic is read from. */
    private Function<String, Transcript> transcripts = name -> null;
    /** Each card's topic as last read, by the card's key: its panel's id, or a headless session's key. EDT only. */
    private final Map<String, Topic> topicOf = new HashMap<>();
    /** What each card's topic was last asked from; the same source is not asked again unless the user asks. EDT only. */
    private final Map<String, String> topicAsked = new HashMap<>();
    /**
     * Each session's conversation as last rendered: a new one (a checkpoint's clear, a fresh start) is about something
     * else, so the topics of the session's panels are dropped and read again at the next show. EDT only.
     */
    private final Map<String, String> conversationOf = new HashMap<>();
    /** Cards whose topic is being read. EDT only. */
    private final Set<String> topicReading = new HashSet<>();
    /** The key of a headless session's card: the session's name after it; a panel's card is keyed by the panel's id. */
    static final String SESSION_KEY = "session:";
    /** The key of a connection's card, which has facts alone (record 92). */
    static final String CONNECTION_KEY = "connection:";
    private final TabCard card = new TabCard(key -> readTopic(key, true));
    /** The tab the pointer rests on, whose card shows when the hover timer fires; -1 when none. */
    private int hoverIndex = -1;
    private final Timer hover = new Timer(ToolTipManager.sharedInstance().getInitialDelay(), e -> showCard(hoverIndex));
    /** Runs when the pointer left the card's tab: the card closes unless the pointer reached it. */
    private final Timer leave = new Timer(LEAVE_GRACE_MILLIS, e -> closeCardIfLeft());
    /** Linear's workspace and team keys as learnt so far, and where they are kept. */
    private LinearTickets.Site site = LinearTickets.Site.NONE;
    private Consumer<LinearTickets.Site> keepSite = s -> { };

    public Workspace(IntentSink intents, PanelTexts texts, SchemaRegistry schemas) {
        this(intents, texts, schemas, System::currentTimeMillis);
    }

    /** With the clock the tab statuses read; tests set it. */
    Workspace(IntentSink intents, PanelTexts texts, SchemaRegistry schemas, LongSupplier clock) {
        this.clock = clock;
        this.intents = intents;
        this.texts = texts;
        this.schemas = schemas;
        this.closer = id -> intents.dispatch(new Intent.ClosePanel(id));
        texts.onOutsideChange(change -> Edt.later(() -> takeOutside(change)));
        texts.onDeleted(deleted -> Edt.later(() -> closeDeleted(deleted)));
        tabs.putClientProperty("JTabbedPane.tabClosable", true);
        tabs.putClientProperty("JTabbedPane.tabCloseToolTipText", KeyText.of("Close the panel (⌘W; ⌘⇧T reopens it)"));
        tabs.putClientProperty("JTabbedPane.tabCloseCallback", (IntConsumer) i -> {
            EditorPanel p = (EditorPanel) tabs.getComponentAt(i);
            closer.accept(p.panel().id());
        });
        tabs.putClientProperty("JTabbedPane.tabType", "card");
        tabs.putClientProperty("JTabbedPane.scrollButtonsPolicy", "asNeededSingle");
        tabs.setTabLayoutPolicy(JTabbedPane.SCROLL_TAB_LAYOUT);
        JButton add = new JButton(Icons.get("add"));
        add.setToolTipText(KeyText.of("New panel (⌘T)"));
        add.getAccessibleContext().setAccessibleName("New panel");
        add.putClientProperty("JButton.buttonType", "toolBarButton");
        add.addActionListener(e -> newPanel());
        // the look stretches the trailing component over the space the tabs leave: the button sits at its right end,
        // centred in the tabs' height, rather than in the middle of that space
        JPanel trailing = new JPanel(new GridBagLayout());
        trailing.setOpaque(false);
        GridBagConstraints at = new GridBagConstraints();
        at.weightx = 1;
        at.anchor = GridBagConstraints.EAST;
        at.insets = new Insets(0, 0, 0, Theme.GAP);
        trailing.add(add, at);
        tabs.putClientProperty("JTabbedPane.trailingComponent", trailing);
        tabs.addChangeListener(e -> {
            if (!rendering && tabs.getSelectedComponent() instanceof EditorPanel p) {
                intents.dispatch(new Intent.FocusPanel(p.panel().id()));
            }
        });
        // a dragged tab moves its panel; the render that follows puts the tabs in the state's order
        tabs.onMove((from, to) -> intents.dispatch(new Intent.MovePanel(((EditorPanel) tabs.getComponentAt(from)).panel().id(), to)));
        hover.setRepeats(false);
        leave.setRepeats(false);
        card.onPointerIn(leave::stop);
        tabs.addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent e) {
                placeCard();
            }
        });
        tabs.addMouseMotionListener(new MouseAdapter() {
            @Override
            public void mouseMoved(MouseEvent e) {
                int i = tabs.indexAtLocation(e.getX(), e.getY());
                if (i != hoverIndex) {
                    hoverIndex = i;
                    hover.restart();
                }
                if (i < 0) {
                    hover.stop();
                }
                // off the tab whose card shows, the card closes unless the pointer goes on into it
                if (card.shown() != null && (i < 0 || !(tabs.getComponentAt(i) instanceof EditorPanel v) || !v.panel().id().value().equals(card.shown()))) {
                    leave.restart();
                } else {
                    leave.stop();
                }
            }
        });
        tabs.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseExited(MouseEvent e) {
                // a card not yet shown is not shown; one shown closes unless the pointer goes on into it
                hoverIndex = -1;
                hover.stop();
                if (card.shown() != null) {
                    leave.restart();
                }
            }

            @Override
            public void mousePressed(MouseEvent e) {
                // a press selects the tab, as a tooltip's would: no card until the pointer rests on another tab
                hover.stop();
                popup(e);
            }

            @Override
            public void mouseClicked(MouseEvent e) {
                int i = tabs.indexAtLocation(e.getX(), e.getY());
                if (i >= 0 && e.getClickCount() == 2) {
                    rename((EditorPanel) tabs.getComponentAt(i));
                }
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                popup(e);
            }

            private void popup(MouseEvent e) {
                int i = tabs.indexAtLocation(e.getX(), e.getY());
                if (e.isPopupTrigger() && i >= 0) {
                    tabMenu(i).show(tabs, e.getX(), e.getY());
                }
            }
        });
    }

    /**
     * The menu of the tab at {@code index}: close it, the others, or the tabs to its right or its left, in the order
     * shown. The tabs each item closes are taken as the menu opens, so a tab that closes while it is open changes nothing.
     */
    JPopupMenu tabMenu(int index) {
        PanelId clicked = ((EditorPanel) tabs.getComponentAt(index)).panel().id();
        int n = tabs.getTabCount();
        List<PanelId> others = ids(0, n, index);
        List<PanelId> right = ids(index + 1, n, -1);
        List<PanelId> left = ids(0, index, -1);
        JPopupMenu menu = new JPopupMenu();
        menu.add(menuItem("Close", "close", true, () -> closer.accept(clicked)));
        menu.addSeparator();
        menu.add(menuItem("Close others", null, !others.isEmpty(), () -> closeTabs(clicked, others)));
        menu.add(menuItem("Close to the right", null, !right.isEmpty(), () -> closeTabs(clicked, right)));
        menu.add(menuItem("Close to the left", null, !left.isEmpty(), () -> closeTabs(clicked, left)));
        return menu;
    }

    private static JMenuItem menuItem(String text, String icon, boolean enabled, Runnable run) {
        JMenuItem item = icon == null ? new JMenuItem(text) : new JMenuItem(text, Icons.get(icon));
        item.setEnabled(enabled);
        item.addActionListener(e -> run.run());
        return item;
    }

    /** The panels of the tabs from {@code from} to {@code to}, not included, leaving out the tab at {@code skip}. */
    private List<PanelId> ids(int from, int to, int skip) {
        List<PanelId> out = new ArrayList<>(Math.max(0, to - from));
        for (int i = from; i < to; i++) {
            if (i != skip) {
                out.add(((EditorPanel) tabs.getComponentAt(i)).panel().id());
            }
        }
        return out;
    }

    /**
     * Closes {@code ids} with {@code keep} in front, as their close buttons would, but without asking: a tab waiting for
     * the user stays, so no question is hidden, and so does a tab whose shell runs a program, which closing would end.
     * One notice, once the tabs are gone, says how many closed and what stayed, in place of a notice per tab.
     */
    void closeTabs(PanelId keep, List<PanelId> ids) {
        intents.dispatch(new Intent.FocusPanel(keep));
        int waiting = 0;
        int running = 0;
        List<PanelId> closing = new ArrayList<>(ids.size());
        for (PanelId id : ids) {
            if (!panels.containsKey(id)) {
                continue;
            }
            if (statuses.get(id) == TabStatus.NEEDS_YOU) {
                waiting++;
            } else if (runsProgram.test(id)) {
                running++;
            } else {
                closing.add(id);
            }
        }
        List<String> parts = new ArrayList<>(3);
        if (!closing.isEmpty()) {
            parts.add("Closed " + closing.size() + (closing.size() == 1 ? " tab" : " tabs") + KeyText.of("; ⌘⇧T or the Sessions tab reopens them"));
        }
        if (waiting + running > 0) {
            List<String> why = new ArrayList<>(2);
            if (waiting > 0) {
                why.add(waiting + " waiting for you");
            }
            if (running > 0) {
                why.add(running + " running a program in the shell");
            }
            int kept = waiting + running;
            parts.add((closing.isEmpty() ? "Kept " : "kept ") + kept + (kept == 1 ? " tab open: " : " tabs open: ") + String.join(", ", why));
        }
        if (parts.isEmpty()) {
            return;
        }
        String summary = String.join("; ", parts);
        if (closing.isEmpty()) {
            intents.dispatch(new Intent.Notify(Notice.Level.INFO, summary, false));
            return;
        }
        bulkClosing.clear();
        bulkClosing.addAll(closing);
        bulkSummary = summary;
        closing.forEach(id -> intents.dispatch(new Intent.ClosePanel(id)));
    }

    public JComponent component() {
        return tabs;
    }

    /**
     * A panel's file changed outside comando (another editor, Claude's Edit tool): the panel shows the new text,
     * unless it has edits not yet saved, whose save keeps the outside version next to the file.
     */
    void takeOutside(PanelTexts.Outside change) {
        EditorPanel view = panels.get(change.panel());
        if (view == null || !change.take().getAsBoolean() || view.editor().text().equals(change.text())) {
            return;
        }
        view.reload(change.text());
        if (!view.isLog()) {
            // a log grows all the time: following it is what the panel is for, not news
            intents.dispatch(new Intent.Notify(Notice.Level.INFO, "Reloaded " + change.file() + ": it changed outside comando", false));
        }
    }

    /**
     * A panel's file was deleted outside comando: the panel, open or closed, is removed, and leaves nothing for ⌘⇧T;
     * unless edits were typed since the store saw it gone, whose save writes the file again.
     */
    void closeDeleted(PanelTexts.Deleted deleted) {
        if (!deleted.remove().getAsBoolean()) {
            intents.dispatch(new Intent.Notify(Notice.Level.INFO, deleted.file()
                    + " was deleted outside comando; the panel's unsaved edits write it again", false));
            return;
        }
        // a session's own panel is the session (record 88): removing it would remove and stop the session, so its text,
        // still in the editor or the store, is written again instead
        Optional<PanelState> own = shown == null ? Optional.empty() : shown.panel(deleted.panel()).or(() -> shown.closedPanel(deleted.panel()))
                .filter(p -> !p.document() && shown.session(p.session()).isPresent());
        if (own.isPresent()) {
            EditorPanel view = panels.get(deleted.panel());
            texts.changed(deleted.panel(), view != null ? view.editor().text() : texts.load(deleted.panel()));
            intents.dispatch(new Intent.Notify(Notice.Level.INFO, deleted.file() + " was deleted outside comando; it is session "
                    + own.get().session() + "'s own panel, so its text is written again", false));
            return;
        }
        if (panels.containsKey(deleted.panel())) {
            deletedFiles.put(deleted.panel(), deleted.file());
        } else if (shown != null && shown.closedPanel(deleted.panel()).isPresent()) {
            intents.dispatch(new Intent.Notify(Notice.Level.INFO, deleted.file() + " was deleted outside comando; its closed panel is removed", false));
        } else {
            return;
        }
        intents.dispatch(new Intent.RemovePanel(deleted.panel()));
    }

    /** How a tab's close button closes its panel: the shell asks first when the panel's shell runs a job. */
    public void onClose(Consumer<PanelId> close) {
        closer = close;
    }

    /** Whether a panel's shell runs a program: a tab closed with others stays open while it does. */
    public void onRunsProgram(Predicate<PanelId> runs) {
        runsProgram = runs;
    }

    /** How a panel's "New session ‹title›…" opens the session dialog, with the panel's title as the name. */
    public void onNewSession(Consumer<String> open) {
        newSession = open;
    }

    /** How a link followed in a panel's rendered Markdown opens its file. */
    public void onOpenFile(Consumer<Path> open) {
        opener = open;
    }

    /** The most recently closed panel, removed from the list; empty when none is left. */
    public Optional<ClosedPanel> popClosed() {
        return Optional.ofNullable(closed.pollFirst());
    }

    public boolean hasClosed() {
        return !closed.isEmpty();
    }

    /** What runs when a tab comes to need the user (a request, a failure): the shell asks the desktop for attention. */
    public void onAttention(Runnable run) {
        attention = run;
    }

    /** How the tabs' topics are read, and a session's transcript by name, which a session panel's topic is read from. */
    public void topics(TopicPort port, Function<String, Transcript> sessions) {
        topics = port;
        transcripts = sessions;
    }

    /**
     * Shows the card of the tab at {@code index}, replacing any card shown; and reads the panel's topic when it has
     * none yet or what it is read from changed. Not over the tab's close button, whose own tooltip shows there: the
     * look puts it on the tab while the pointer is over the button.
     */
    void showCard(int index) {
        if (index >= 0 && index < tabs.getTabCount() && tabs.getToolTipTextAt(index) != null) {
            hoverIndex = -1; // over the close button: the next move on the tab starts the wait again
            return;
        }
        if (index < 0 || index >= tabs.getTabCount() || !(tabs.getComponentAt(index) instanceof EditorPanel view)) {
            return;
        }
        PanelId id = view.panel().id();
        Rectangle r = tabs.getUI().getTabBounds(tabs, index);
        if (r == null) {
            return;
        }
        // shown first, so its read goes ahead of the other tabs'
        leave.stop();
        card.show(id.value(), tabs, r, factsOf(id.value()), "", false, false);
        card.tickets(tickets(view.panel(), view));
        readTopic(id.value(), false);
        updateCard(id.value());
    }

    /**
     * The sidebar's session rows show the same card as the tabs: the card of the session's panel (the focused one when it
     * is the session's, else its first), or, for a headless session, a card of its own, read from the end of its
     * conversation. A connection's row shows a card of its facts, without a summary. Shown under {@code row}, a
     * rectangle of {@code over}.
     */
    public SessionsSidebar.Cards rowCards() {
        return new SessionsSidebar.Cards() {
            @Override
            public void show(String session, JComponent over, Rectangle row) {
                showSessionCard(session, over, row);
            }

            @Override
            public void showConnection(String connection, JComponent over, Rectangle row) {
                showConnectionCard(connection, over, row);
            }

            @Override
            public void left() {
                if (card.shown() != null) {
                    leave.restart();
                }
            }
        };
    }

    void showSessionCard(String session, JComponent over, Rectangle row) {
        if (shown == null || shown.session(session).isEmpty()) {
            return;
        }
        leave.stop();
        Optional<EditorPanel> view = sessionPanel(session);
        String key = view.map(v -> v.panel().id().value()).orElse(SESSION_KEY + session);
        card.show(key, over, row, factsOf(key), "", false, false);
        card.tickets(view.map(v -> tickets(v.panel(), v)).orElse(List.of()));
        readTopic(key, false);
        updateCard(key);
    }

    void showConnectionCard(String connection, JComponent over, Rectangle row) {
        if (shown == null || shown.connection(connection).isEmpty()) {
            return;
        }
        leave.stop();
        String key = CONNECTION_KEY + connection;
        card.showFacts(key, over, row, factsOf(key));
    }

    /** The open panel a session's card is its card of: the focused one when it is the session's, else the first. */
    private Optional<EditorPanel> sessionPanel(String session) {
        List<EditorPanel> open = shown.panelsBoundToSession(session).stream().map(p -> panels.get(p.id())).filter(v -> v != null).toList();
        return open.stream().filter(v -> v.panel().id().equals(shown.focused())).findFirst().or(() -> open.stream().findFirst());
    }

    /**
     * What card {@code key} says above its summary (record 90): its panel's title and facts, with its session's; or a
     * headless session's name and facts.
     */
    private CardFacts factsOf(String key) {
        if (shown == null) {
            return new CardFacts("", List.of());
        }
        if (key.startsWith(CONNECTION_KEY)) {
            String name = key.substring(CONNECTION_KEY.length());
            List<String> using = shown.panels().stream().filter(p -> p.connection().equals(name)).map(PanelState::title).toList();
            return shown.connection(name).map(c -> CardFacts.of(c, using)).orElse(new CardFacts(name, List.of()));
        }
        long now = clock.getAsLong();
        String newest = SessionRow.newestCli(shown.sessions());
        if (key.startsWith(SESSION_KEY)) {
            String name = key.substring(SESSION_KEY.length());
            CardFacts facts = CardFacts.of(Optional.empty(), shown.session(name), "", "", now, newest);
            return facts.title().isEmpty() ? new CardFacts(name, facts.facts()) : facts;
        }
        PanelId id = new PanelId(key);
        Optional<PanelState> panel = shown.panel(id);
        Optional<SessionState> session = panel.map(PanelState::session).filter(x -> !x.isEmpty()).flatMap(shown::session);
        String doing = panel.map(p -> TabStatus.describe(p, shown, statuses.getOrDefault(id, TabStatus.NONE), now)).orElse("");
        String file = texts.file(id).map(f -> Labels.shortPath(f.toString())).orElse("");
        return CardFacts.of(panel, session, doing, file, now, newest);
    }

    /** The pointer left the card's tab and did not reach the card: it closes. */
    void closeCardIfLeft() {
        if (!card.pointerIn()) {
            card.hide();
        }
    }

    /** Where Linear's tickets are found: learnt so far, kept by {@code keep} as more is learnt. */
    public void linear(LinearTickets.Site known, Consumer<LinearTickets.Site> keep) {
        site = known;
        keepSite = keep;
    }

    /**
     * The Linear tickets the panel's text names, the newest command's first; Linear's links in the text, and in the end of
     * the panel's session's conversation, teach which ids are tickets and their addresses.
     */
    private List<LinearTickets.Ticket> tickets(PanelState p, EditorPanel view) {
        String text = view.textEnd(PANEL_TEXT_READ);
        LinearTickets.Site learnt = LinearTickets.learn(site, text);
        Transcript t = p.session().isEmpty() ? null : transcripts.apply(p.session());
        for (int i = t == null ? 0 : t.size() - 1, last = Math.max(0, i - LINK_BLOCKS); t != null && i >= last; i--) {
            String said = switch (t.get(i)) {
                case Block.AssistantText a -> a.text();
                case Block.ToolCall c -> c.inputJson() + '\n' + c.resultText();
                case Block.UserPrompt u -> u.text();
                case null, default -> "";
            };
            learnt = LinearTickets.learn(learnt, said);
        }
        if (!learnt.equals(site)) {
            site = learnt;
            keepSite.accept(learnt);
        }
        return LinearTickets.in(text, site, MAX_TICKETS);
    }

    /**
     * The card shown over the tabs, under its tab again: tabs move as others close, are dragged, scroll or the window
     * resizes. A card shown from the sidebar stays under its row.
     */
    private void placeCard() {
        EditorPanel view = card.shown() == null || card.over() != tabs ? null : panels.get(new PanelId(card.shown()));
        int at = view == null ? -1 : tabs.indexOfComponent(view);
        Rectangle r = at < 0 ? null : tabs.getUI().getTabBounds(tabs, at);
        if (r != null) {
            card.moveUnder(r);
        }
    }

    /**
     * Asks for the topic of card {@code key} when what it is read from changed since the last ask, or always when
     * {@code force}d (the card's button), ahead of the other cards. A card with nothing to read has no topic.
     */
    private void readTopic(String key, boolean force) {
        String source = topicSource(key);
        if (source == null) {
            return; // its panel or session is gone
        }
        if (source.isEmpty()) {
            topicAsked.remove(key);
            topicOf.remove(key);
            updateCardOf(key);
            return;
        }
        if (!force && source.equals(topicAsked.get(key))) {
            return;
        }
        topicAsked.put(key, source);
        topicReading.add(key);
        updateCardOf(key);
        topics.read(key, source, force || key.equals(card.shown()), topic -> Edt.later(() -> {
            if (!exists(key)) {
                return; // closed or removed while it was read
            }
            topicReading.remove(key);
            topicOf.put(key, topic);
            updateCardOf(key);
        }));
    }

    /** Whether card {@code key}'s panel is open, or its headless session or its connection still configured. */
    private boolean exists(String key) {
        if (key.startsWith(CONNECTION_KEY)) {
            return shown != null && shown.connection(key.substring(CONNECTION_KEY.length())).isPresent();
        }
        return key.startsWith(SESSION_KEY) ? shown != null && shown.session(key.substring(SESSION_KEY.length())).isPresent()
                : panels.containsKey(new PanelId(key));
    }

    /**
     * What card {@code key}'s topic is read from: its panel's (see below), or a headless session's end of conversation,
     * headed by where it works; empty when there is nothing; null when the panel or the session is gone.
     */
    private String topicSource(String key) {
        if (key.startsWith(SESSION_KEY)) {
            String name = key.substring(SESSION_KEY.length());
            Optional<SessionState> session = shown == null ? Optional.empty() : shown.session(name);
            if (session.isEmpty()) {
                return null;
            }
            Transcript t = transcripts.apply(name);
            return t == null || t.size() == 0 ? "" : TopicSource.ofSession(name, "", session.get().spec().workDir(), session.get().branch(), t,
                    TopicSource.MAX_CHARS);
        }
        EditorPanel view = panels.get(new PanelId(key));
        return view == null ? null : topicSource(view.panel(), view);
    }

    /**
     * A session panel's own text, its last paragraph first, with the session's last response and where the session works;
     * when the text is blank, the end of the session's conversation. A panel without a session: the start of its own text with its file. Empty when
     * there is nothing.
     */
    private String topicSource(PanelState p, EditorPanel view) {
        if (!p.session().isEmpty()) {
            Optional<SessionState> session = shown == null ? Optional.empty() : shown.session(p.session());
            String workDir = session.map(x -> x.spec().workDir()).orElse("");
            String branch = session.map(SessionState::branch).orElse("");
            Transcript t = transcripts.apply(p.session());
            String response = t == null ? "" : TopicSource.lastResponse(t);
            String source = TopicSource.ofPanel(p.title(), p.session(), workDir, branch, view.textEnd(PANEL_TEXT_READ), response,
                    TopicSource.MAX_CHARS);
            if (source.isEmpty() && t != null && t.size() > 0) {
                source = TopicSource.ofSession(p.session(), p.title(), workDir, branch, t, TopicSource.MAX_CHARS);
            }
            if (!source.isEmpty()) {
                return source;
            }
        }
        String file = texts.file(p.id()).map(Path::toString).orElse("");
        return TopicSource.ofText(p.title(), file, view.textStart(TopicSource.MAX_CHARS), TopicSource.MAX_CHARS);
    }

    private void forgetTopicsOfNewConversations(AppState s) {
        Set<String> names = new HashSet<>();
        for (SessionState x : s.sessions()) {
            names.add(x.name());
            String before = conversationOf.put(x.name(), x.spec().claudeSessionId());
            if (before == null || before.equals(x.spec().claudeSessionId())) {
                continue;
            }
            List<String> keys = new ArrayList<>();
            keys.add(SESSION_KEY + x.name());
            s.panelsBoundToSession(x.name()).forEach(p -> keys.add(p.id().value()));
            for (String key : keys) {
                topicOf.remove(key);
                topicAsked.remove(key);
                updateCardOf(key);
            }
        }
        conversationOf.keySet().retainAll(names);
    }

    private void updateCardOf(String key) {
        if (key.equals(card.shown())) {
            updateCard(key);
        }
    }

    private void updateCard(String key) {
        if (key.startsWith(CONNECTION_KEY)) {
            card.update(factsOf(key), "", false, false); // facts alone: nothing is read
            return;
        }
        boolean reading = topicReading.contains(key);
        Topic topic = topicOf.get(key);
        String line;
        boolean muted;
        if (topic != null && !topic.line().isEmpty()) {
            line = topic.line();
            muted = false;
        } else if (reading) {
            line = "Reading the summary…";
            muted = true;
        } else if (topic != null) {
            line = "No summary: " + topic.problem();
            muted = true;
        } else {
            line = "Nothing to summarise yet";
            muted = true;
        }
        card.update(factsOf(key), line, muted, reading);
    }

    /** The panel whose card shows, null when none, a headless session's or a connection's; for tests. */
    PanelId cardShown() {
        String key = card.shown();
        return key == null || key.startsWith(SESSION_KEY) || key.startsWith(CONNECTION_KEY) ? null : new PanelId(key);
    }

    TabCard card() {
        return card;
    }

    /** Whether a card waits for the pointer to rest; for tests. */
    boolean hoverWaiting() {
        return hover.isRunning();
    }

    /**
     * Refreshes what changes with time alone in every panel, and every tab's status: the elapsed time in a working or
     * waiting tab's tooltip, and the end of the grace a returned background task has to start its reading turn.
     */
    public void tick() {
        panels.values().forEach(EditorPanel::tick);
        if (shown == null) {
            return;
        }
        placeCard();
        for (PanelState p : shown.panels()) {
            EditorPanel view = panels.get(p.id());
            if (view != null && tabs.indexOfComponent(view) >= 0) {
                refreshTab(p, shown, view);
            }
        }
        // a session's uptime and its working time move on
        if (card.shown() != null) {
            updateCard(card.shown());
        }
        spinWhileWorking();
    }

    /** One step of the wheel; the spinning tabs are repainted where they are, the editors below are not touched. */
    private void spinTabs() {
        spinner.advance();
        for (int i = 0; i < tabs.getTabCount(); i++) {
            if (tabs.getIconAt(i) == spinner) {
                Rectangle r = tabs.getUI().getTabBounds(tabs, i);
                if (r != null) {
                    tabs.repaint(r);
                }
            }
        }
    }

    /** The status each open tab shows, by panel; for tests. */
    Map<PanelId, TabStatus> statuses() {
        return Map.copyOf(statuses);
    }

    boolean spinning() {
        return spin.isRunning();
    }

    /**
     * The panel that holds {@code file} shows its text to edit: an open one now, one that opens or reopens at the render
     * that shows it. Called on the EDT.
     */
    public void editWhenOpen(Path file) {
        for (Map.Entry<PanelId, EditorPanel> e : panels.entrySet()) {
            if (texts.file(e.getKey()).map(f -> PanelTexts.samePath(f, file)).orElse(false)) {
                e.getValue().edit();
                return;
            }
        }
        toEdit.add(file);
    }

    /**
     * The panel that holds {@code file} scrolls its preview to the heading whose id is {@code heading}: an open one now,
     * one that opens or reopens at the render that shows it. Called on the EDT.
     */
    public void headingWhenOpen(Path file, String heading) {
        for (Map.Entry<PanelId, EditorPanel> e : panels.entrySet()) {
            if (texts.file(e.getKey()).map(f -> PanelTexts.samePath(f, file)).orElse(false)) {
                e.getValue().showHeading(heading);
                return;
            }
        }
        toHeading.put(file, heading);
    }

    /**
     * The panel that holds the log {@code file} shows its entry written at {@code atMillis} (see
     * {@link EditorPanel#showLogEntry}): an open one now, one that opens or reopens at the render that shows it.
     * Called on the EDT.
     */
    public void entryWhenOpen(Path file, long atMillis) {
        for (Map.Entry<PanelId, EditorPanel> e : panels.entrySet()) {
            if (texts.file(e.getKey()).map(f -> PanelTexts.samePath(f, file)).orElse(false)) {
                e.getValue().showLogEntry(atMillis);
                return;
            }
        }
        toEntry.put(file, atMillis);
    }

    private String takeHeading(Path file) {
        for (Iterator<Map.Entry<Path, String>> it = toHeading.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<Path, String> e = it.next();
            if (PanelTexts.samePath(e.getKey(), file)) {
                it.remove();
                return e.getValue();
            }
        }
        return null;
    }

    public void newPanel() {
        intents.dispatch(new Intent.AddPanel(PanelId.random(), ""));
    }

    public void rename(EditorPanel p) {
        rename(p.panel(), tabs, intents);
    }

    /** Asks for a panel's new name over {@code owner}; the panel tabs and the sidebar's panel rows rename this way. */
    static void rename(PanelState panel, Component owner, IntentSink intents) {
        String title = (String) JOptionPane.showInputDialog(owner, "Panel name", "Rename panel", JOptionPane.PLAIN_MESSAGE, null, null,
                panel.title());
        if (title != null && !title.isBlank()) {
            intents.dispatch(new Intent.RenamePanel(panel.id(), title));
        }
    }

    public EditorPanel focused() {
        return tabs.getSelectedComponent() instanceof EditorPanel p ? p : null;
    }

    public EditorPanel panel(PanelId id) {
        return panels.get(id);
    }

    public List<EditorPanel> panels() {
        return new ArrayList<>(panels.values());
    }

    /** Selects the panel at {@code index} (0-based), for ⌘1…⌘9. */
    public void select(int index) {
        if (index >= 0 && index < tabs.getTabCount()) {
            tabs.setSelectedIndex(index);
        }
    }

    public void selectRelative(int delta) {
        int n = tabs.getTabCount();
        if (n > 0) {
            tabs.setSelectedIndex(Math.floorMod(tabs.getSelectedIndex() + delta, n));
        }
    }

    public void render(AppState s) {
        shown = s;
        rendering = true;
        try {
            forgetTopicsOfNewConversations(s);
            // remove panels the state no longer has
            List<PanelId> ids = s.panels().stream().map(PanelState::id).toList();
            for (PanelId id : new ArrayList<>(panels.keySet())) {
                if (!ids.contains(id)) {
                    EditorPanel gone = panels.remove(id);
                    PanelState was = gone.panel();
                    String deletedFile = deletedFiles.remove(id);
                    boolean kept = s.closedPanel(id).isPresent();
                    if (deletedFile == null && (kept || was.document())) {
                        closed.addFirst(new ClosedPanel(id, was.title(), was.document(), texts.file(id).orElse(null)));
                        while (closed.size() > CLOSED_KEPT) {
                            closed.removeLast();
                        }
                    }
                    lastActive.remove(id);
                    unseen.remove(id);
                    statuses.remove(id);
                    tips.remove(id);
                    topicOf.remove(id.value());
                    topicAsked.remove(id.value());
                    topicReading.remove(id.value());
                    if (id.value().equals(card.shown())) {
                        card.hide();
                    }
                    tabs.remove(gone);
                    gone.editor().dispose();
                    if (deletedFile != null) {
                        intents.dispatch(new Intent.Notify(Notice.Level.INFO, "Removed " + was.title() + ": " + deletedFile
                                + " was deleted outside comando", false));
                    } else if (bulkClosing.contains(id)) {
                        // a menu's close says it once, below
                    } else if (kept) {
                        intents.dispatch(new Intent.Notify(Notice.Level.INFO, "Closed " + was.title()
                                + KeyText.of("; ⌘⇧T or the Sessions tab reopens it"), false));
                    } else if (was.document()) {
                        intents.dispatch(new Intent.Notify(Notice.Level.INFO, "Closed " + was.title() + KeyText.of(" — ⌘⇧T reopens it"), false));
                    }
                }
            }
            // a menu's close is done once none of its tabs is open: its one notice now
            if (bulkSummary != null && bulkClosing.stream().noneMatch(ids::contains)) {
                intents.dispatch(new Intent.Notify(Notice.Level.INFO, bulkSummary, false));
                bulkSummary = null;
                bulkClosing.clear();
            }
            // add new panels at their position, update titles and bindings
            for (int i = 0; i < s.panels().size(); i++) {
                PanelState p = s.panels().get(i);
                EditorPanel view = panels.get(p.id());
                if (view == null) {
                    view = new EditorPanel(p, intents, schemas);
                    view.editor().load(texts.load(p.id()));
                    PanelId id = p.id();
                    view.editor().onChange(text -> texts.changed(id, text));
                    view.onOpenFile((f, heading) -> {
                        if (heading != null) {
                            headingWhenOpen(f, heading);
                        }
                        opener.accept(f);
                    });
                    view.onNewSession(name -> newSession.accept(name));
                    panels.put(p.id(), view);
                    tabs.insertTab(title(p, false), Icons.get("file"), view, null, Math.min(i, tabs.getTabCount()));
                } else if (tabs.indexOfComponent(view) != i) {
                    tabs.remove(view);
                    tabs.insertTab(title(p, false), Icons.get("file"), view, null, Math.min(i, tabs.getTabCount()));
                }
                refreshTab(p, s, view);
                Path file = texts.file(p.id()).orElse(null);
                view.file(file, texts.document(p.id()));
                if (file != null && toEdit.removeIf(f -> PanelTexts.samePath(f, file))) {
                    view.edit();
                }
                String heading = file == null ? null : takeHeading(file);
                if (heading != null) {
                    view.showHeading(heading);
                }
                if (file != null && !toEntry.isEmpty()) {
                    for (Iterator<Map.Entry<Path, Long>> it = toEntry.entrySet().iterator(); it.hasNext(); ) {
                        Map.Entry<Path, Long> e = it.next();
                        if (PanelTexts.samePath(e.getKey(), file)) {
                            it.remove();
                            view.showLogEntry(e.getValue());
                        }
                    }
                }
                view.render(p, s);
            }
            EditorPanel focused = panels.get(s.focused());
            if (focused != null && tabs.getSelectedComponent() != focused) {
                tabs.setSelectedComponent(focused);
            }
            spinWhileWorking();
            placeCard();
            // the card shown follows its session: status, context, model; it closes when its panel or session is gone
            if (card.shown() != null) {
                if (exists(card.shown())) {
                    updateCard(card.shown());
                } else {
                    card.hide();
                }
            }
        } finally {
            rendering = false;
        }
    }

    /** The tab's status, icon, title mark and tooltip for {@code p} in {@code s}. */
    private void refreshTab(PanelState p, AppState s, EditorPanel view) {
        TabStatus status = track(p, s);
        int at = tabs.indexOfComponent(view);
        String title = title(p, status.marked());
        if (!tabs.getTitleAt(at).equals(title)) {
            tabs.setTitleAt(at, title);
        }
        // at rest, a session's tab shows whether its session is connected, as its row in the sidebar does (record 89)
        Icon icon = status == TabStatus.IDLE ? s.session(p.session()).map(x -> Icons.get(SessionIcons.of(x.status()))).orElse(icon(status))
                : icon(status);
        if (tabs.getIconAt(at) != icon) {
            tabs.setIconAt(at, icon);
        }
        String tip = tooltip(p, s, status);
        if (!tip.equals(tips.put(p.id(), tip)) && p.id().value().equals(card.shown())) {
            updateCard(p.id().value());
        }
    }

    private void spinWhileWorking() {
        boolean working = statuses.containsValue(TabStatus.WORKING);
        if (working && !spin.isRunning()) {
            spin.start();
        } else if (!working && spin.isRunning()) {
            spin.stop();
        }
    }

    /**
     * The tab's status now. Work that ends while another panel is in front marks the panel until the user looks at it;
     * work that starts again drops the mark, its outcome is superseded. A tab that comes to need the user asks the
     * desktop for attention, once per change.
     */
    private TabStatus track(PanelState p, AppState s) {
        PanelId id = p.id();
        boolean active = TabStatus.active(p, s, clock.getAsLong());
        Boolean before = lastActive.put(id, active);
        if (id.equals(s.focused()) || (active && before != null && !before)) {
            unseen.remove(id);
        } else if (before != null && before && !active) {
            unseen.add(id);
        }
        TabStatus status = TabStatus.of(p, s, unseen.contains(id));
        TabStatus was = statuses.put(id, status);
        if (status.wantsAttention() && status != was) {
            attention.run();
        }
        return status;
    }

    private Icon icon(TabStatus status) {
        return switch (status) {
            case NEEDS_YOU -> Icons.get("question");
            case ERROR -> Icons.get("error");
            case DONE -> Icons.get("check");
            case WORKING -> spinner;
            case WAITING -> Icons.get("hourglass");
            case IDLE -> Icons.get("claude");
            case NONE -> Icons.get("file");
        };
    }

    private String tooltip(PanelState p, AppState s, TabStatus status) {
        return Plain.text(tooltip(p) + texts.file(p.id()).map(f -> " · " + Labels.shortPath(f.toString())).orElse("")
                + TabStatus.describe(p, s, status, System.currentTimeMillis()));
    }

    /** The tab's title: plain, clipped, with a dot when a query finished unseen. */
    static String title(PanelState p, boolean marked) {
        return Plain.text((marked ? "● " : "") + Clip.keepStart(p.title(), TITLE_CHARS));
    }

    /** The title, the connection, and the session unless the title names it already (a session's own panel). */
    private static String tooltip(PanelState p) {
        return p.title() + (p.connection().isEmpty() ? "" : " · " + p.connection())
                + (p.session().isEmpty() || p.session().equals(p.title()) ? "" : " · " + p.session());
    }

    private static MovableTabs named(MovableTabs tabs, String name) {
        tabs.setName(name);
        return tabs;
    }
}
