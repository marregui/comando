package dev.marregui.comando.ui.panels;

import java.awt.Component;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Point;
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
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.IntConsumer;
import java.util.function.LongSupplier;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import javax.swing.Box;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JTabbedPane;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.ToolTipManager;

import dev.marregui.comando.core.intent.Intent;
import dev.marregui.comando.core.model.LinearTickets;
import dev.marregui.comando.core.model.PanelId;
import dev.marregui.comando.core.model.Topic;
import dev.marregui.comando.core.port.IntentSink;
import dev.marregui.comando.core.port.TopicPort;
import dev.marregui.comando.core.schema.SchemaRegistry;
import dev.marregui.comando.core.state.AppState;
import dev.marregui.comando.core.state.BackgroundTask;
import dev.marregui.comando.core.state.Notice;
import dev.marregui.comando.core.state.PanelState;
import dev.marregui.comando.core.state.SessionState;
import dev.marregui.comando.core.transcript.Block;
import dev.marregui.comando.core.transcript.TopicSource;
import dev.marregui.comando.core.transcript.Transcript;
import dev.marregui.comando.core.view.Clip;
import dev.marregui.comando.ui.PanelTexts;
import dev.marregui.comando.ui.kit.async.Edt;
import dev.marregui.comando.ui.kit.async.ShownTimer;
import dev.marregui.comando.ui.kit.format.KeyText;
import dev.marregui.comando.ui.kit.format.Labels;
import dev.marregui.comando.ui.kit.icon.Icons;
import dev.marregui.comando.ui.kit.icon.SpinnerIcon;
import dev.marregui.comando.ui.kit.tabs.MovableTabs;
import dev.marregui.comando.ui.kit.text.Plain;
import dev.marregui.comando.ui.kit.theme.NamedTheme;
import dev.marregui.comando.ui.kit.theme.Theme;

/**
 * The open panels as tabs, in state order; the selected tab is the focused panel, and a tab dragged along the row moves
 * its panel. The tabs closed most recently are remembered for ⌘⇧T: a panel of comando's own by its id (the state keeps
 * it, closed), a document by its file. Each tab shows its panel's {@link TabStatus} as its one icon, its title plain: a
 * turn or query that finished (or failed) while another tab was in front keeps its check (or red mark) until the user
 * looks at it; a session waiting for the user keeps its question mark until answered; a running one spins.
 * A tab's menu closes it, the others, or those to its right or its left; such a close keeps the tabs waiting for the
 * user and those whose shell runs a program.
 *
 * <p>A tab shows a {@link TabCard} rather than a tooltip: its status, the Linear tickets its text names, and the
 * panel's topic, a few sentences Haiku wrote on what the panel is about. A topic is read when its card first shows,
 * again when the card shows after what it is read from changed, and whenever the card's button asks; never on a timer.
 * The card closes when the pointer leaves it, or leaves the tab without reaching the card within
 * {@link TabCard#LEAVE_GRACE_MILLIS}.</p>
 */
public final class Workspace {

    /**
     * A tab as it closed: a panel of comando's own by its id, a document by its file; {@code side} when it closed in
     * the right-hand partition, where it reopens (record 141).
     */
    public record ClosedPanel(PanelId id, String title, boolean document, Path file, boolean side) {
    }

    static final int CLOSED_KEPT = 10;
    /**
     * Asks waiting for a panel to show, at most: one per file being opened or previewed, and the user starts a few at
     * a time; past this many, the oldest are of panels replaced before they showed.
     */
    private static final int WAITING_KEPT = 16;
    /** The preview tab's close tooltip: it is never reopened, so it offers no ⌘⇧T (record 141). */
    private static final String PREVIEW_CLOSE_TIP = KeyText.of("Close the preview (⌘W)");
    static final int TITLE_CHARS = 32;
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
    /** The right-hand partition's strip, where the side panels go; null until {@link #partition} hands it over. */
    private JTabbedPane side;
    /** The focused panel at the last render: the partition brings a side panel forward when the focus moves to it. */
    private PanelId focusedBefore;
    private final Map<PanelId, EditorPanel> panels = new LinkedHashMap<>();
    private final Deque<ClosedPanel> closed = new ArrayDeque<>();
    private final LongSupplier clock;
    /** Whether each panel's query ran when last rendered; a change to false is an outcome. */
    private final Map<PanelId, Boolean> lastActive = new HashMap<>();
    /** Panels whose query ended while another tab was in front, until the user looks at them. */
    private final Set<PanelId> unseen = new HashSet<>();
    /** The panels whose tab title is greyed: their session runs its checkpoint (record 130). */
    private final Set<PanelId> dimmed = new HashSet<>();
    private final Map<PanelId, TabStatus> statuses = new HashMap<>();
    /** The theme chosen, beside New panel (record 104). */
    private final ThemeButton theme = new ThemeButton();
    /** The tab row's right end: the corner's command buttons, the theme's swatch, New panel. */
    private final JPanel trailing = new JPanel(new GridBagLayout());
    /** One wheel for every spinning tab; the timer turns it and repaints only the spinning tabs' bounds. */
    private final SpinnerIcon spinner = new SpinnerIcon(16);
    private final ShownTimer spin = SpinnerIcon.timer(tabs, this::spinTabs);
    private Runnable attention = () -> {
    };
    private Consumer<PanelId> closer;
    /** Whether a panel's shell runs a program, which closing the panel would end; the shell tells. */
    private Predicate<PanelId> runsProgram = id -> false;
    /** The tabs a menu's close closes, which post no notice each, and the one notice once they are gone. EDT only. */
    private final Set<PanelId> bulkClosing = new HashSet<>();
    private String bulkSummary;
    /** Opens a file a panel's link names; the shell's own open. */
    private Consumer<Path> opener = f -> {
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
    /**
     * What runs with a panel once it shows, by its id, the latest ask for a panel replacing the one before; at most
     * {@link #WAITING_KEPT}, the oldest let go, since a panel replaced before it showed never shows. EDT only.
     */
    private final Map<PanelId, Consumer<EditorPanel>> toRun = new LinkedHashMap<>();
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
    /** What each card's topic was last asked from; the same source is asked again only at the user's ask. EDT only. */
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
    /** A card of facts not this window's own (record 124), an instance's or one of its sessions': its owner keeps it current. */
    static final String OTHER_KEY = "other:";
    private final TabCard card = new TabCard(key -> readTopic(key, true));
    /** The tab the pointer rests on, whose card shows when the hover timer fires; -1 when none. */
    private int hoverIndex = -1;
    /** The tab a mouse press landed on: no card for it until the pointer leaves it; -1 when none. */
    private int pressedIndex = -1;
    private final Timer hover = new Timer(ToolTipManager.sharedInstance().getInitialDelay(), e -> showCard(hoverIndex));
    /** Linear's workspace and team keys as learnt so far, and where they are kept. */
    private LinearTickets.Site site = LinearTickets.Site.NONE;
    private Consumer<LinearTickets.Site> keepSite = s -> { };

    public Workspace(final IntentSink intents, final PanelTexts texts, final SchemaRegistry schemas) {
        this(intents, texts, schemas, System::currentTimeMillis);
    }

    /** With the clock the tab statuses read; tests set it. */
    Workspace(final IntentSink intents, final PanelTexts texts, final SchemaRegistry schemas, final LongSupplier clock) {
        this.clock = clock;
        this.intents = intents;
        this.texts = texts;
        this.schemas = schemas;
        this.closer = id -> intents.dispatch(new Intent.ClosePanel(id));
        texts.onOutsideChange(change -> Edt.later(() -> takeOutside(change)));
        texts.onDeleted(deleted -> Edt.later(() -> closeDeleted(deleted)));
        TabLook.apply(tabs);
        tabs.putClientProperty(TabLook.CLOSE_CALLBACK, (IntConsumer) i -> {
            final EditorPanel p = (EditorPanel) tabs.getComponentAt(i);
            closer.accept(p.panel().id());
        });
        final JButton add = new JButton(Icons.get("add"));
        add.setToolTipText(KeyText.of("New panel (⌘T)"));
        add.getAccessibleContext().setAccessibleName("New panel");
        add.putClientProperty("JButton.buttonType", "toolBarButton");
        add.addActionListener(e -> newPanel());
        // the look stretches the trailing component over the space the tabs leave: the buttons sit at its right end,
        // centred in the tabs' height, rather than in the middle of that space; the theme's swatch left of New panel,
        // which keeps the corner (record 104)
        trailing.setOpaque(false);
        final GridBagConstraints at = new GridBagConstraints();
        at.weightx = 1;
        at.anchor = GridBagConstraints.EAST;
        trailing.add(Box.createHorizontalGlue(), at);
        at.weightx = 0;
        at.insets = new Insets(0, 0, 0, Theme.SPACE_XS);
        trailing.add(theme, at);
        at.insets = new Insets(0, 0, 0, Theme.GAP);
        trailing.add(add, at);
        tabs.putClientProperty("JTabbedPane.trailingComponent", trailing);
        tabs.addChangeListener(e -> {
            if (!rendering && tabs.getSelectedComponent() instanceof EditorPanel p) {
                intents.dispatch(new Intent.FocusPanel(p.panel().id()));
            }
        });
        // a dragged tab moves its panel to where the tab it lands on sits among all panels, side ones included; the
        // render that follows puts the tabs in the state's order
        tabs.onMove((from, to) -> intents.dispatch(
                new Intent.MovePanel(((EditorPanel) tabs.getComponentAt(from)).panel().id(), listIndex(to))));
        hover.setRepeats(false);
        card.onPointerIn(card::stay);
        tabs.addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(final ComponentEvent e) {
                placeCard();
            }
        });
        tabs.addMouseMotionListener(new MouseAdapter() {
            @Override
            public void mouseMoved(final MouseEvent e) {
                final int i = tabs.indexAtLocation(e.getX(), e.getY());
                if (i != pressedIndex) {
                    pressedIndex = -1;
                }
                if (i != hoverIndex) {
                    hoverIndex = i;
                    hover.restart();
                }
                if (i < 0 || i == pressedIndex) {
                    hover.stop();
                }
                // off the tab whose card shows, the card closes unless the pointer goes on into it
                if (card.shown() != null && (i < 0 || !(tabs.getComponentAt(i) instanceof EditorPanel v) || !v.panel().id().value().equals(card.shown()))) {
                    card.left();
                } else {
                    card.stay();
                }
            }
        });
        tabs.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseExited(final MouseEvent e) {
                // a card not yet shown is not shown; one shown closes unless the pointer goes on into it
                hoverIndex = -1;
                pressedIndex = -1;
                hover.stop();
                if (card.shown() != null) {
                    card.left();
                }
            }

            @Override
            public void mousePressed(final MouseEvent e) {
                // any press, a click or the menu's, is the user acting on the tab: its card closes, or never shows,
                // until the pointer leaves the tab and rests on one again
                pressedIndex = tabs.indexAtLocation(e.getX(), e.getY());
                hover.stop();
                if (card.shown() != null && card.over() == tabs) {
                    card.stay();
                    card.hide();
                }
                focusOnPress(tabs, e);
                popup(e);
            }

            @Override
            public void mouseClicked(final MouseEvent e) {
                pinOrRename(tabs, e);
            }

            @Override
            public void mouseReleased(final MouseEvent e) {
                popup(e);
            }

            private void popup(final MouseEvent e) {
                final int i = tabs.indexAtLocation(e.getX(), e.getY());
                if (e.isPopupTrigger() && i >= 0) {
                    tabMenu(i).show(tabs, e.getX(), e.getY());
                }
            }
        });
    }

    /**
     * Hands the side panels to {@code pane}'s strip, where they sit before its views, with the main tabs' close button,
     * menu, double click and focus on a press; until then side panels stay in the main tabs. Called once, before the
     * first render.
     */
    public void partition(final SidePane pane) {
        side = pane.strip();
        side.putClientProperty(TabLook.CLOSE_CALLBACK, (IntConsumer) i -> {
            if (side.getComponentAt(i) instanceof EditorPanel p) {
                closer.accept(p.panel().id());
            }
        });
        side.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(final MouseEvent e) {
                focusOnPress(side, e);
                popup(e);
            }

            @Override
            public void mouseClicked(final MouseEvent e) {
                pinOrRename(side, e);
            }

            @Override
            public void mouseReleased(final MouseEvent e) {
                popup(e);
            }

            private void popup(final MouseEvent e) {
                final int i = side.indexAtLocation(e.getX(), e.getY());
                if (e.isPopupTrigger() && i >= 0 && side.getComponentAt(i) instanceof EditorPanel) {
                    tabMenu(side, i).show(side, e.getX(), e.getY());
                }
            }
        });
    }

    /** The menu of the main tab at {@code index}; see {@link #tabMenu(JTabbedPane, int)}. */
    JPopupMenu tabMenu(final int index) {
        return tabMenu(tabs, index);
    }

    /**
     * The menu of the panel's tab at {@code index} of {@code strip}: close it, the strip's other panels, or those to
     * its right or its left, in the order shown; then its move to the other strip, once there is a partition. The tabs
     * each item closes are taken as the menu opens, so a tab that closes while it is open changes nothing. The last
     * panel of the main tabs does not move: the main tabs keep one.
     */
    JPopupMenu tabMenu(final JTabbedPane strip, final int index) {
        final PanelId clicked = ((EditorPanel) strip.getComponentAt(index)).panel().id();
        final int n = strip.getTabCount();
        final List<PanelId> others = ids(strip, 0, n, index);
        final List<PanelId> right = ids(strip, index + 1, n, -1);
        final List<PanelId> left = ids(strip, 0, index, -1);
        final JPopupMenu menu = new JPopupMenu();
        menu.add(menuItem("Close", "close", true, () -> closer.accept(clicked)));
        menu.addSeparator();
        menu.add(menuItem("Close others", null, !others.isEmpty(), () -> closeTabs(clicked, others)));
        menu.add(menuItem("Close to the right", null, !right.isEmpty(), () -> closeTabs(clicked, right)));
        menu.add(menuItem("Close to the left", null, !left.isEmpty(), () -> closeTabs(clicked, left)));
        if (side != null) {
            menu.addSeparator();
            if (strip == side) {
                menu.add(menuItem("Move to the main tabs", "move-main", true,
                        () -> intents.dispatch(new Intent.PlacePanel(clicked, false))));
            } else {
                menu.add(menuItem("Move to the right side", "move-side", n > 1,
                        () -> intents.dispatch(new Intent.PlacePanel(clicked, true))));
            }
        }
        return menu;
    }

    /** A press on a panel's tab focuses its panel, also when the tab is in front already and so selects nothing new. */
    private void focusOnPress(final JTabbedPane strip, final MouseEvent e) {
        final int i = strip.indexAtLocation(e.getX(), e.getY());
        if (!e.isPopupTrigger() && SwingUtilities.isLeftMouseButton(e) && i >= 0 && shown != null
                && strip.getComponentAt(i) instanceof EditorPanel v && !v.panel().id().equals(shown.focused())) {
            intents.dispatch(new Intent.FocusPanel(v.panel().id()));
        }
    }

    /** A double click on a panel's tab pins it when it is the preview tab, else asks for its new name. */
    private void pinOrRename(final JTabbedPane strip, final MouseEvent e) {
        final int i = strip.indexAtLocation(e.getX(), e.getY());
        if (i >= 0 && e.getClickCount() == 2 && strip.getComponentAt(i) instanceof EditorPanel v) {
            if (v.panel().preview()) {
                intents.dispatch(new Intent.PinPanel(v.panel().id()));
            } else {
                rename(v);
            }
        }
    }

    private static JMenuItem menuItem(final String text, final String icon, final boolean enabled, final Runnable run) {
        final JMenuItem item = icon == null ? new JMenuItem(text) : new JMenuItem(text, Icons.get(icon));
        item.setEnabled(enabled);
        item.addActionListener(e -> run.run());
        return item;
    }

    /**
     * The panels of {@code strip}'s tabs from {@code from} to {@code to}, not included, leaving out the tab at
     * {@code skip}, and the partition's views.
     */
    private static List<PanelId> ids(final JTabbedPane strip, final int from, final int to, final int skip) {
        final List<PanelId> out = new ArrayList<>(Math.max(0, to - from));
        for (int i = from; i < to; i++) {
            if (i != skip && strip.getComponentAt(i) instanceof EditorPanel v) {
                out.add(v.panel().id());
            }
        }
        return out;
    }

    /** Where among all panels the main tab at {@code tab} sits: side panels take places in the state's order too. */
    private int listIndex(final int tab) {
        final int n = tabs.getTabCount();
        if (shown == null || n == 0 || !(tabs.getComponentAt(Math.clamp(tab, 0, n - 1)) instanceof EditorPanel at)) {
            return tab;
        }
        final List<PanelId> ids = shown.panels().stream().map(PanelState::id).toList();
        return ids.indexOf(at.panel().id());
    }

    /**
     * Closes {@code ids} with {@code keep} in front, as their close buttons would, but without asking: a tab waiting
     * for the user stays, so no question is hidden, and so does a tab whose shell runs a program, which closing would
     * end. One notice, once the tabs are gone, says how many closed and what stayed, in place of a notice per tab.
     */
    void closeTabs(final PanelId keep, final List<PanelId> ids) {
        intents.dispatch(new Intent.FocusPanel(keep));
        int waiting = 0;
        int running = 0;
        final List<PanelId> closing = new ArrayList<>(ids.size());
        for (final PanelId id : ids) {
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
        final List<String> parts = new ArrayList<>(3);
        if (!closing.isEmpty()) {
            parts.add("Closed " + closing.size() + (closing.size() == 1 ? " tab" : " tabs") + KeyText.of("; ⌘⇧T or the Sessions tab reopens them"));
        }
        if (waiting + running > 0) {
            final List<String> why = new ArrayList<>(2);
            if (waiting > 0) {
                why.add(waiting + " waiting for you");
            }
            if (running > 0) {
                why.add(running + " running a program in the shell");
            }
            final int kept = waiting + running;
            parts.add((closing.isEmpty() ? "Kept " : "kept ") + kept + (kept == 1 ? " tab open: " : " tabs open: ") + String.join(", ", why));
        }
        if (parts.isEmpty()) {
            return;
        }
        final String summary = String.join("; ", parts);
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
    void takeOutside(final PanelTexts.Outside change) {
        final EditorPanel view = panels.get(change.panel());
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
    void closeDeleted(final PanelTexts.Deleted deleted) {
        if (!deleted.remove().getAsBoolean()) {
            intents.dispatch(new Intent.Notify(Notice.Level.INFO, deleted.file()
                    + " was deleted outside comando; the panel's unsaved edits write it again", false));
            return;
        }
        // a session's own panel is the session (record 88): removing it would remove and stop the session, so its text,
        // still in the editor or the store, is written again instead
        final Optional<PanelState> own = shown == null ? Optional.empty() : shown.panel(deleted.panel()).or(() -> shown.closedPanel(deleted.panel()))
                .filter(p -> !p.document() && shown.session(p.session()).isPresent());
        if (own.isPresent()) {
            final EditorPanel view = panels.get(deleted.panel());
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

    /** {@code buttons} sit left of the theme's swatch, in their order: the shell, the plots, the embedded server. */
    public void corner(final List<CommandButton> buttons) {
        final GridBagConstraints at = new GridBagConstraints();
        at.anchor = GridBagConstraints.EAST;
        at.insets = new Insets(0, 0, 0, Theme.SPACE_XS);
        for (int i = 0; i < buttons.size(); i++) {
            trailing.add(buttons.get(i), at, 1 + i);
        }
        trailing.revalidate();
    }

    /** {@code chosen} hears the theme picked from the swatch beside New panel. */
    public void onThemeChosen(final Consumer<NamedTheme> chosen) {
        theme.onChosen(chosen);
    }

    /** How a tab's close button closes its panel: the shell asks first when the panel's shell runs a job. */
    public void onClose(final Consumer<PanelId> close) {
        closer = close;
    }

    /** Whether a panel's shell runs a program: a tab closed with others stays open while it does. */
    public void onRunsProgram(final Predicate<PanelId> runs) {
        runsProgram = runs;
    }

    /** Where a panel's Locate button shows its file, or its session, in the sidebar. */
    private BiConsumer<Path, String> locator = (f, session) -> {
    };

    /** Where a panel's Locate button shows its file, or its session, in the sidebar (record 119). */
    public void onLocate(final BiConsumer<Path, String> locate) {
        locator = locate;
    }

    /** How a link followed in a panel's rendered Markdown opens its file. */
    public void onOpenFile(final Consumer<Path> open) {
        opener = open;
    }

    /** Whether a panel opens with its document's outline (record 113): the user's last choice. */
    private boolean outlineWanted;
    private Consumer<Boolean> outlineKept = on -> { };

    /** Panels open with the outline when {@code wanted}; {@code keep} hears each choice the user makes in a panel. */
    public void outline(final boolean wanted, final Consumer<Boolean> keep) {
        outlineWanted = wanted;
        outlineKept = keep;
    }

    /** The most recently closed panel, removed from the list; empty when none is left. */
    public Optional<ClosedPanel> popClosed() {
        return Optional.ofNullable(closed.pollFirst());
    }

    public boolean hasClosed() {
        return !closed.isEmpty();
    }

    /** What runs when a tab comes to need the user (a request, a failure): the shell asks the desktop for attention. */
    public void onAttention(final Runnable run) {
        attention = run;
    }

    /** How the tabs' topics are read, and a session's transcript by name, which a session panel's topic reads. */
    public void topics(final TopicPort port, final Function<String, Transcript> sessions) {
        topics = port;
        transcripts = sessions;
    }

    /**
     * Shows the card of the tab at {@code index}, replacing any card shown; and reads the panel's topic when it has
     * none yet or what it is read from changed. Not over the tab's close button, whose own tooltip shows there: the
     * look puts it on the tab while the pointer is over the button.
     */
    void showCard(final int index) {
        if (index >= 0 && index == pressedIndex) {
            return; // pressed since the wait began: the user acts on the tab
        }
        if (index >= 0 && index < tabs.getTabCount() && tabs.getToolTipTextAt(index) != null) {
            hoverIndex = -1; // over the close button: the next move on the tab starts the wait again
            return;
        }
        if (index < 0 || index >= tabs.getTabCount() || !(tabs.getComponentAt(index) instanceof EditorPanel view)) {
            return;
        }
        final PanelId id = view.panel().id();
        final Rectangle r = tabs.getUI().getTabBounds(tabs, index);
        if (r == null) {
            return;
        }
        // shown first, so its read goes ahead of the other tabs'
        card.stay();
        card.show(id.value(), tabs, r, factsOf(id.value()), "", false, false);
        card.tickets(tickets(view.panel(), view));
        readTopic(id.value(), false);
        updateCard(id.value());
    }

    /**
     * The sidebar's session rows show the same card as the tabs: the card of the session's panel (the focused one when
     * it is the session's, else its first), or, for a headless session, a card of its own, read from the end of its
     * conversation. A connection's row shows a card of its facts, without a summary. Shown under {@code row}, a
     * rectangle of {@code over}. Its Linear site is the one the tabs' cards learnt so far, read on the EDT.
     */
    public SessionsSidebar.Cards rowCards() {
        return new SessionsSidebar.Cards() {
            @Override
            public void show(final String session, final JComponent over, final Rectangle row) {
                showSessionCard(session, over, row, -1);
            }

            @Override
            public void show(final String session, final JComponent over, final Rectangle row, final int beside) {
                showSessionCard(session, over, row, beside);
            }

            @Override
            public void showConnection(final String connection, final JComponent over, final Rectangle row) {
                showConnectionCard(connection, over, row, -1);
            }

            @Override
            public void showConnection(final String connection, final JComponent over, final Rectangle row, final int beside) {
                showConnectionCard(connection, over, row, beside);
            }

            @Override
            public void showFacts(final String key, final String title, final List<Map.Entry<String, String>> facts, final String heading, final String text,
                                  final JComponent over, final Rectangle row, final int beside) {
                card.stay();
                card.showOther(OTHER_KEY + key, over, row, beside, otherFacts(title, facts), heading, text);
            }

            @Override
            public void updateFacts(final String key, final String title, final List<Map.Entry<String, String>> facts, final String heading, final String text) {
                if ((OTHER_KEY + key).equals(card.shown())) {
                    card.updateOther(otherFacts(title, facts), heading, text);
                }
            }

            @Override
            public boolean showing(final String key) {
                return (OTHER_KEY + key).equals(card.shown());
            }

            @Override
            public void stay() {
                card.stay();
            }

            @Override
            public void left() {
                if (card.shown() != null) {
                    card.left();
                }
            }

            @Override
            public LinearTickets.Site linearSite() {
                return site;
            }

            @Override
            public void onClose(final Consumer<Point> r) {
                // a tab's card closing is not a row's
                card.onHidden((over, at) -> {
                    if (over != tabs) {
                        r.accept(at);
                    }
                });
            }
        };
    }

    private static CardFacts otherFacts(final String title, final List<Map.Entry<String, String>> facts) {
        return new CardFacts(title, facts.stream().map(f -> new CardFacts.Fact(f.getKey(), f.getValue())).toList());
    }

    /** {@code session}'s card under {@code row}, or beside the rows from {@code beside} when it is not negative. */
    void showSessionCard(final String session, final JComponent over, final Rectangle row, final int beside) {
        if (shown == null || shown.session(session).isEmpty()) {
            return;
        }
        card.stay();
        final Optional<EditorPanel> view = sessionPanel(session);
        final String key = view.map(v -> v.panel().id().value()).orElse(SESSION_KEY + session);
        card.show(key, over, row, beside, factsOf(key), "", false, false);
        card.tickets(view.map(v -> tickets(v.panel(), v)).orElse(List.of()));
        readTopic(key, false);
        updateCard(key);
    }

    void showConnectionCard(final String connection, final JComponent over, final Rectangle row, final int beside) {
        if (shown == null || shown.connection(connection).isEmpty()) {
            return;
        }
        card.stay();
        final String key = CONNECTION_KEY + connection;
        card.showFacts(key, over, row, beside, factsOf(key));
    }

    /** The open panel a session's card is its card of: the focused one when it is the session's, else the first. */
    private Optional<EditorPanel> sessionPanel(final String session) {
        final List<EditorPanel> open = shown.panelsBoundToSession(session).stream().map(p -> panels.get(p.id())).filter(v -> v != null).toList();
        return open.stream().filter(v -> v.panel().id().equals(shown.focused())).findFirst().or(() -> open.stream().findFirst());
    }

    /**
     * What card {@code key} says above its summary (record 90): its panel's title and facts, with its session's; or a
     * headless session's name and facts.
     */
    private CardFacts factsOf(final String key) {
        if (shown == null) {
            return new CardFacts("", List.of());
        }
        if (key.startsWith(CONNECTION_KEY)) {
            final String name = key.substring(CONNECTION_KEY.length());
            final List<String> using = shown.panels().stream().filter(p -> p.connection().equals(name)).map(PanelState::title).toList();
            return shown.connection(name).map(c -> CardFacts.of(c, using)).orElse(new CardFacts(name, List.of()));
        }
        final long now = clock.getAsLong();
        final String newest = SessionRow.newestCli(shown.sessions());
        if (key.startsWith(SESSION_KEY)) {
            final String name = key.substring(SESSION_KEY.length());
            final CardFacts facts = CardFacts.of(Optional.empty(), shown.session(name), "", "", now, newest);
            return facts.title().isEmpty() ? new CardFacts(name, facts.facts()) : facts;
        }
        final PanelId id = new PanelId(key);
        final Optional<PanelState> panel = shown.panel(id);
        final Optional<SessionState> session = panel.map(PanelState::session).filter(x -> !x.isEmpty()).flatMap(shown::session);
        final String doing = panel.map(p -> TabStatus.describe(p, shown, statuses.getOrDefault(id, TabStatus.NONE), now)).orElse("");
        final String file = texts.file(id).map(f -> Labels.shortPath(f.toString())).orElse("");
        return CardFacts.of(panel, session, doing, file, now, newest);
    }

    /** Where Linear's tickets are found: learnt so far, kept by {@code keep} as more is learnt. */
    public void linear(final LinearTickets.Site known, final Consumer<LinearTickets.Site> keep) {
        site = known;
        keepSite = keep;
    }

    /**
     * The Linear tickets the panel's text names, the newest command's first; Linear's links in the text, and in the end
     * of the panel's session's conversation, teach which ids are tickets and their addresses.
     */
    private List<LinearTickets.Ticket> tickets(final PanelState p, final EditorPanel view) {
        final String text = view.textEnd(PANEL_TEXT_READ);
        LinearTickets.Site learnt = LinearTickets.learn(site, text);
        final Transcript t = p.session().isEmpty() ? null : transcripts.apply(p.session());
        for (int i = t == null ? 0 : t.size() - 1, last = Math.max(0, i - LINK_BLOCKS); t != null && i >= last; i--) {
            final String said = switch (t.get(i)) {
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
        final EditorPanel view = card.shown() == null || card.over() != tabs ? null : panels.get(new PanelId(card.shown()));
        final int at = view == null ? -1 : tabs.indexOfComponent(view);
        final Rectangle r = at < 0 ? null : tabs.getUI().getTabBounds(tabs, at);
        if (r != null) {
            card.moveUnder(r);
        }
    }

    /**
     * Asks for the topic of card {@code key} when what it is read from changed since the last ask, or always when
     * {@code force}d (the card's button), ahead of the other cards. A card with nothing to read has no topic.
     */
    private void readTopic(final String key, final boolean force) {
        final String source = topicSource(key);
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
    private boolean exists(final String key) {
        if (key.startsWith(OTHER_KEY)) {
            // its owner, the Instances tab, keeps it current and closes it
            return true;
        }
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
    private String topicSource(final String key) {
        if (key.startsWith(SESSION_KEY)) {
            final String name = key.substring(SESSION_KEY.length());
            final Optional<SessionState> session = shown == null ? Optional.empty() : shown.session(name);
            if (session.isEmpty()) {
                return null;
            }
            final Transcript t = transcripts.apply(name);
            return t == null || t.size() == 0 ? "" : TopicSource.ofSession(name, "", workPlace(session.get()),
                    branchOf(session.get()), t,
                    TopicSource.MAX_CHARS);
        }
        final EditorPanel view = panels.get(new PanelId(key));
        return view == null ? null : topicSource(view.panel(), view);
    }

    /** Where {@code x} works for its topic: the repository it writes to when known, else its working folder. */
    private static String workPlace(final SessionState x) {
        return x.subject().isKnown() ? x.subject().repository() : x.spec().workDir();
    }

    /** The branch {@code x} works on for its topic: its subject's when known, else its working folder's. */
    private static String branchOf(final SessionState x) {
        return x.subject().isKnown() && !x.subject().branch().isEmpty() ? x.subject().branch() : x.branch();
    }

    /**
     * A session panel's own text, its last paragraph first, with the session's last response and where the session
     * works; when the text is blank, the end of the session's conversation. A panel without a session: the start of its
     * own text with its file. Empty when there is nothing.
     */
    private String topicSource(final PanelState p, final EditorPanel view) {
        if (!p.session().isEmpty()) {
            final Optional<SessionState> session = shown == null ? Optional.empty() : shown.session(p.session());
            final String workDir = session.map(Workspace::workPlace).orElse("");
            final String branch = session.map(Workspace::branchOf).orElse("");
            final Transcript t = transcripts.apply(p.session());
            final String response = t == null ? "" : TopicSource.lastResponse(t);
            String source = TopicSource.ofPanel(p.title(), p.session(), workDir, branch, view.textEnd(PANEL_TEXT_READ), response,
                    TopicSource.MAX_CHARS);
            if (source.isEmpty() && t != null && t.size() > 0) {
                source = TopicSource.ofSession(p.session(), p.title(), workDir, branch, t, TopicSource.MAX_CHARS);
            }
            if (!source.isEmpty()) {
                return source;
            }
        }
        final String file = texts.file(p.id()).map(Path::toString).orElse("");
        return TopicSource.ofText(p.title(), file, view.textStart(TopicSource.MAX_CHARS), TopicSource.MAX_CHARS);
    }

    private void forgetTopicsOfNewConversations(final AppState s) {
        final Set<String> names = new HashSet<>();
        for (final SessionState x : s.sessions()) {
            names.add(x.name());
            final String before = conversationOf.put(x.name(), x.spec().claudeSessionId());
            if (before == null || before.equals(x.spec().claudeSessionId())) {
                continue;
            }
            final List<String> keys = new ArrayList<>();
            keys.add(SESSION_KEY + x.name());
            s.panelsBoundToSession(x.name()).forEach(p -> keys.add(p.id().value()));
            for (final String key : keys) {
                topicOf.remove(key);
                topicAsked.remove(key);
                updateCardOf(key);
            }
        }
        conversationOf.keySet().retainAll(names);
    }

    private void updateCardOf(final String key) {
        if (key.equals(card.shown())) {
            updateCard(key);
        }
    }

    private void updateCard(final String key) {
        if (key.startsWith(OTHER_KEY)) {
            return;
        }
        if (key.startsWith(CONNECTION_KEY)) {
            card.update(factsOf(key), "", false, false); // facts alone: nothing is read
            return;
        }
        final boolean reading = topicReading.contains(key);
        final Topic topic = topicOf.get(key);
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
        final String session = sessionOfCard(key);
        card.agents(session.isEmpty() ? List.of() : delegation(session), id -> intents.dispatch(new Intent.OpenSubagent(session, id)));
    }

    /** The session a card is of: a headless session's, or its panel's; empty for a panel without one. */
    private String sessionOfCard(final String key) {
        if (shown == null) {
            return "";
        }
        if (key.startsWith(SESSION_KEY)) {
            return key.substring(SESSION_KEY.length());
        }
        return shown.panel(new PanelId(key)).map(PanelState::session).orElse("");
    }

    /** The outline last read, of which session's transcript at which version, with which running background tasks. */
    private String delegationOf = "";
    private long delegationAt = -1;
    private Set<String> delegationRunning = Set.of();
    private List<Delegation.Line> delegation = List.of();

    /** {@code session}'s sub-agents for its card (record 109), read from its transcript when it changed. */
    private List<Delegation.Line> delegation(final String session) {
        final Transcript t = transcripts.apply(session);
        if (t == null) {
            return List.of();
        }
        final Set<String> running = shown.session(session).map(x -> x.tasks().stream()
                .filter(task -> task.kind().equals("local_agent")).map(BackgroundTask::id)
                .collect(Collectors.toSet())).orElse(Set.of());
        if (!session.equals(delegationOf) || t.version() != delegationAt || !running.equals(delegationRunning)) {
            delegationOf = session;
            delegationAt = t.version();
            delegationRunning = running;
            delegation = Delegation.of(t, running);
        }
        return delegation;
    }

    /** The panel whose card shows, null when none, a headless session's or a connection's; for tests. */
    PanelId cardShown() {
        final String key = card.shown();
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
        if (shown == null) {
            return;
        }
        placeCard();
        for (final PanelState p : shown.panels()) {
            final EditorPanel view = panels.get(p.id());
            if (view != null && holder(view) != null) {
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
        spinTabs(tabs);
        if (side != null) {
            spinTabs(side);
        }
    }

    private void spinTabs(final JTabbedPane strip) {
        for (int i = 0; i < strip.getTabCount(); i++) {
            if (strip.getIconAt(i) == spinner) {
                final Rectangle r = strip.getUI().getTabBounds(strip, i);
                if (r != null) {
                    strip.repaint(r);
                }
            }
        }
    }

    /** The status each open tab shows, by panel; for tests. */
    Map<PanelId, TabStatus> statuses() {
        return Map.copyOf(statuses);
    }

    boolean spinning() {
        return spin.running();
    }

    /** For tests: whether a working tab wants the spinner turned, shown or not. */
    boolean spinWanted() {
        return spin.wanted();
    }

    /**
     * The panel that holds {@code file} shows its text to edit: an open one now, one that opens or reopens at the
     * render that shows it. Called on the EDT.
     */
    public void editWhenOpen(final Path file) {
        for (final Map.Entry<PanelId, EditorPanel> e : panels.entrySet()) {
            if (texts.file(e.getKey()).map(f -> PanelTexts.samePath(f, file)).orElse(false)) {
                e.getValue().edit();
                return;
            }
        }
        toEdit.add(file);
    }

    /**
     * Runs {@code then} with the panel {@code id}: now when it shows, else at the render that shows it, in place of
     * what an earlier ask for it would have run (record 141). Called on the EDT.
     */
    public void whenShown(final PanelId id, final Consumer<EditorPanel> then) {
        final EditorPanel shownNow = panels.get(id);
        if (shownNow != null) {
            toRun.remove(id);
            then.accept(shownNow);
            return;
        }
        toRun.remove(id);
        toRun.put(id, then);
        while (toRun.size() > WAITING_KEPT) {
            toRun.remove(toRun.keySet().iterator().next());
        }
    }

    /**
     * Brings {@code id}'s tab forward in the strip that holds it, over a view the user brought forward in the
     * partition; true when that strip is the partition's.
     */
    public boolean bringForward(final PanelId id) {
        final EditorPanel view = panels.get(id);
        final JTabbedPane strip = view == null ? null : holder(view);
        if (strip != null && strip.getSelectedComponent() != view) {
            strip.setSelectedComponent(view);
        }
        return strip != null && strip == side;
    }

    /**
     * The panel ⌘{@code n} goes to, from 1: the main tabs first, left to right, then the partition's; null past the
     * last (record 141).
     */
    public EditorPanel numbered(final int n) {
        int left = n;
        for (final JTabbedPane strip : side == null ? List.of(tabs) : List.of(tabs, side)) {
            for (int i = 0; i < strip.getTabCount() && left > 0; i++) {
                if (strip.getComponentAt(i) instanceof EditorPanel p && --left == 0) {
                    return p;
                }
            }
        }
        return null;
    }

    /**
     * The panel that holds {@code file} scrolls its preview to the heading whose id is {@code heading}: an open one
     * now, one that opens or reopens at the render that shows it. Called on the EDT.
     */
    public void headingWhenOpen(final Path file, final String heading) {
        for (final Map.Entry<PanelId, EditorPanel> e : panels.entrySet()) {
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
    public void entryWhenOpen(final Path file, final long atMillis) {
        for (final Map.Entry<PanelId, EditorPanel> e : panels.entrySet()) {
            if (texts.file(e.getKey()).map(f -> PanelTexts.samePath(f, file)).orElse(false)) {
                e.getValue().showLogEntry(atMillis);
                return;
            }
        }
        toEntry.put(file, atMillis);
    }

    private String takeHeading(final Path file) {
        for (final Iterator<Map.Entry<Path, String>> it = toHeading.entrySet().iterator(); it.hasNext(); ) {
            final Map.Entry<Path, String> e = it.next();
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

    public void rename(final EditorPanel p) {
        rename(p.panel(), tabs, intents);
    }

    /** Asks for a panel's new name over {@code owner}; the panel tabs and the sidebar's panel rows rename this way. */
    static void rename(final PanelState panel, final Component owner, final IntentSink intents) {
        final String title = (String) JOptionPane.showInputDialog(owner, "Panel name", "Rename panel", JOptionPane.PLAIN_MESSAGE, null, null,
                panel.title());
        if (title != null && !title.isBlank()) {
            intents.dispatch(new Intent.RenamePanel(panel.id(), title));
        }
    }

    /** The state's focused panel, in the main tabs or the partition; before a render, the main tab in front. */
    public EditorPanel focused() {
        final EditorPanel p = shown == null ? null : panels.get(shown.focused());
        if (p != null) {
            return p;
        }
        return tabs.getSelectedComponent() instanceof EditorPanel v ? v : null;
    }

    /** The panel in front of the main tabs, as last rendered; the right side's tabs are not among them. */
    public Optional<PanelId> mainFront() {
        return tabs.getSelectedComponent() instanceof EditorPanel v ? Optional.of(v.panel().id()) : Optional.empty();
    }

    public EditorPanel panel(final PanelId id) {
        return panels.get(id);
    }

    public List<EditorPanel> panels() {
        return new ArrayList<>(panels.values());
    }

    /** Selects the panel at {@code index} (0-based), for ⌘1…⌘9. */
    public void select(final int index) {
        if (index >= 0 && index < tabs.getTabCount()) {
            tabs.setSelectedIndex(index);
        }
    }

    public void selectRelative(final int delta) {
        final int n = tabs.getTabCount();
        if (n > 0) {
            tabs.setSelectedIndex(Math.floorMod(tabs.getSelectedIndex() + delta, n));
        }
    }

    public void render(final AppState s) {
        shown = s;
        rendering = true;
        try {
            forgetTopicsOfNewConversations(s);
            // remove panels the state no longer has
            final List<PanelId> ids = s.panels().stream().map(PanelState::id).toList();
            for (final PanelId id : new ArrayList<>(panels.keySet())) {
                if (!ids.contains(id)) {
                    final EditorPanel gone = panels.remove(id);
                    final PanelState was = gone.panel();
                    final String deletedFile = deletedFiles.remove(id);
                    // the preview tab is a passing look at a file: not reopened, so not in the closed list
                    final boolean kept = !was.preview() && s.closedPanel(id).isPresent();
                    final boolean document = !was.preview() && was.document();
                    if (deletedFile == null && (kept || document)) {
                        closed.addFirst(new ClosedPanel(id, was.title(), was.document(), texts.file(id).orElse(null),
                                was.side()));
                        while (closed.size() > CLOSED_KEPT) {
                            closed.removeLast();
                        }
                    }
                    lastActive.remove(id);
                    unseen.remove(id);
                    dimmed.remove(id);
                    statuses.remove(id);
                    tips.remove(id);
                    topicOf.remove(id.value());
                    topicAsked.remove(id.value());
                    topicReading.remove(id.value());
                    if (id.value().equals(card.shown())) {
                        card.hide();
                    }
                    final JTabbedPane strip = holder(gone);
                    if (strip != null) {
                        strip.remove(gone);
                    }
                    gone.editor().unmount();
                    if (deletedFile != null) {
                        intents.dispatch(new Intent.Notify(Notice.Level.INFO, "Removed " + was.title() + ": " + deletedFile
                                + " was deleted outside comando", false));
                    } else if (bulkClosing.contains(id)) {
                        // a menu's close says it once, below
                    } else if (kept) {
                        intents.dispatch(new Intent.Notify(Notice.Level.INFO, "Closed " + was.title()
                                + KeyText.of("; ⌘⇧T or the Sessions tab reopens it"), false));
                    } else if (document) {
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
            // add new panels at their position in their strip, the main tabs or the partition's, update titles and
            // bindings; a panel whose strip changed moves to it, the same component with its state
            int mainAt = 0;
            int sideAt = 0;
            boolean movedIn = false;
            for (final PanelState p : s.panels()) {
                final JTabbedPane strip = p.side() && side != null ? side : tabs;
                final int i = strip == tabs ? mainAt++ : sideAt++;
                EditorPanel view = panels.get(p.id());
                if (view == null) {
                    view = new EditorPanel(p, intents, schemas);
                    view.editor().load(texts.load(p.id()));
                    view.outline(outlineWanted, on -> {
                        outlineWanted = on;
                        outlineKept.accept(on);
                    });
                    final PanelId id = p.id();
                    view.editor().onChange(text -> texts.changed(id, text));
                    view.onLocate((f, session) -> locator.accept(f, session));
                    view.onOpenFile((f, heading) -> {
                        if (heading != null) {
                            headingWhenOpen(f, heading);
                        }
                        opener.accept(f);
                    });
                    panels.put(p.id(), view);
                    strip.insertTab(title(p), Icons.get("file"), view, null, Math.min(i, strip.getTabCount()));
                    movedIn |= strip == side;
                } else if (strip.indexOfComponent(view) != i) {
                    final JTabbedPane was = holder(view);
                    if (was != null) {
                        was.remove(view);
                    }
                    strip.insertTab(title(p), Icons.get("file"), view, null, Math.min(i, strip.getTabCount()));
                    // a tab put in again has the strip's colour: its grey, if any, is set again below
                    dimmed.remove(p.id());
                    movedIn |= strip == side && was != side;
                }
                refreshTab(p, s, view);
                final Path file = texts.file(p.id()).orElse(null);
                view.file(file, texts.document(p.id()));
                if (file != null && toEdit.removeIf(f -> PanelTexts.samePath(f, file))) {
                    view.edit();
                }
                final String heading = file == null ? null : takeHeading(file);
                if (heading != null) {
                    view.showHeading(heading);
                }
                if (file != null && !toEntry.isEmpty()) {
                    for (final Iterator<Map.Entry<Path, Long>> it = toEntry.entrySet().iterator(); it.hasNext(); ) {
                        final Map.Entry<Path, Long> e = it.next();
                        if (PanelTexts.samePath(e.getKey(), file)) {
                            it.remove();
                            view.showLogEntry(e.getValue());
                        }
                    }
                }
                view.render(p, s);
                runWhenShown(p.id(), view);
            }
            // the strip holding the focused panel shows it; the partition only when the focus or the panel moved there,
            // so a view the user brought forward over it stays
            final EditorPanel focused = panels.get(s.focused());
            final JTabbedPane holding = focused == null ? null : holder(focused);
            if (holding == tabs && tabs.getSelectedComponent() != focused) {
                tabs.setSelectedComponent(focused);
            } else if (holding != null && holding == side && (movedIn || !s.focused().equals(focusedBefore))
                    && side.getSelectedComponent() != focused) {
                side.setSelectedComponent(focused);
            }
            focusedBefore = s.focused();
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

    /** Runs what waits for the panel {@code id}, now that {@code view} shows it. */
    private void runWhenShown(final PanelId id, final EditorPanel view) {
        final Consumer<EditorPanel> then = toRun.remove(id);
        if (then != null) {
            then.accept(view);
        }
    }

    /** The strip whose tab holds {@code view}, the main tabs or the partition's; null when neither does. */
    private JTabbedPane holder(final EditorPanel view) {
        if (tabs.indexOfComponent(view) >= 0) {
            return tabs;
        }
        return side != null && side.indexOfComponent(view) >= 0 ? side : null;
    }

    /**
     * The tab's status, icon, title mark and tooltip for {@code p} in {@code s}. The preview tab's title is in italics,
     * on a label of its own, since a tab's font is the strip's; a side panel's tooltip is the tab's own, the hover card
     * being the main tabs'.
     */
    private void refreshTab(final PanelState p, final AppState s, final EditorPanel view) {
        final TabStatus status = track(p, s);
        final JTabbedPane strip = holder(view);
        final int at = strip.indexOfComponent(view);
        final String title = title(p);
        if (!strip.getTitleAt(at).equals(title)) {
            strip.setTitleAt(at, title);
        }
        // at rest, a session's tab shows whether its session is connected, as its row in the sidebar does (record 89)
        final Icon icon = status == TabStatus.IDLE ? s.session(p.session()).map(x -> Icons.get(SessionIcons.of(x.status()))).orElse(icon(status))
                : icon(status);
        if (strip.getIconAt(at) != icon) {
            strip.setIconAt(at, icon);
        }
        // its session runs its checkpoint: the title greys with the panel, the tab still opens it (record 130)
        final boolean locked = s.session(p.session()).map(SessionState::isCheckpointing).orElse(false);
        if (locked ? dimmed.add(p.id()) : dimmed.remove(p.id())) {
            strip.setForegroundAt(at, locked ? LockVeil.dimmed() : null);
        }
        // the preview tab is never reopened, so its close button does not offer ⌘⇧T
        final String closeTip = p.preview() ? PREVIEW_CLOSE_TIP : null;
        if (!Objects.equals(view.getClientProperty(TabLook.CLOSE_TIP), closeTip)) {
            view.putClientProperty(TabLook.CLOSE_TIP, closeTip);
        }
        if (p.preview()) {
            final JLabel label = strip.getTabComponentAt(at) instanceof JLabel l ? l : new JLabel();
            final Font italic = strip.getFont().deriveFont(Font.ITALIC);
            if (!italic.equals(label.getFont())) {
                label.setFont(italic);
            }
            label.setText(title);
            label.setIcon(icon);
            label.setForeground(locked ? LockVeil.dimmed() : null);
            if (strip.getTabComponentAt(at) != label) {
                strip.setTabComponentAt(at, label);
            }
        } else if (strip.getTabComponentAt(at) != null) {
            strip.setTabComponentAt(at, null);
        }
        final String tip = tooltip(p, s, status);
        if (strip == side) {
            strip.setToolTipTextAt(at, tip);
        }
        if (!tip.equals(tips.put(p.id(), tip)) && p.id().value().equals(card.shown())) {
            updateCard(p.id().value());
        }
    }

    private void spinWhileWorking() {
        spin.want(statuses.containsValue(TabStatus.WORKING));
    }

    /**
     * The tab's status now. A session's outcome is marked on the session itself, by the reducer (record 136). A query
     * that ends while another panel is in front marks the panel until the user looks at it; a query that starts again
     * drops the mark, its outcome is superseded. A tab that comes to need the user asks the desktop for attention, once
     * per change.
     */
    private TabStatus track(final PanelState p, final AppState s) {
        final PanelId id = p.id();
        final boolean active = s.runOf(id).map(r -> r.status().isActive()).orElse(false);
        final Boolean before = lastActive.put(id, active);
        if (id.equals(s.focused()) || (active && before != null && !before)) {
            unseen.remove(id);
        } else if (before != null && before && !active) {
            unseen.add(id);
        }
        final TabStatus status = TabStatus.of(p, s, unseen.contains(id), clock.getAsLong());
        final TabStatus was = statuses.put(id, status);
        if (status.wantsAttention() && status != was) {
            attention.run();
        }
        return status;
    }

    private Icon icon(final TabStatus status) {
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

    private String tooltip(final PanelState p, final AppState s, final TabStatus status) {
        return Plain.text(tooltip(p) + texts.file(p.id()).map(f -> " · " + Labels.shortPath(f.toString())).orElse("")
                + TabStatus.describe(p, s, status, System.currentTimeMillis()));
    }

    /** The tab's title: plain and clipped; the icon alone carries the status. */
    static String title(final PanelState p) {
        return Plain.text(Clip.keepStart(p.title(), TITLE_CHARS));
    }

    /** The title, the connection, and the session unless the title names it already (a session's own panel). */
    private static String tooltip(final PanelState p) {
        return p.title() + (p.connection().isEmpty() ? "" : " · " + p.connection())
                + (p.session().isEmpty() || p.session().equals(p.title()) ? "" : " · " + p.session());
    }

    private static MovableTabs named(final MovableTabs tabs, final String name) {
        tabs.setName(name);
        return tabs;
    }
}
