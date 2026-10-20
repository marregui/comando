package dev.marregui.comando.ui.panels;

import dev.marregui.comando.core.intent.Intent;
import dev.marregui.comando.core.model.PanelId;
import dev.marregui.comando.core.port.IntentSink;
import dev.marregui.comando.core.schema.SchemaRegistry;
import dev.marregui.comando.core.state.AppState;
import dev.marregui.comando.core.state.Notice;
import dev.marregui.comando.core.state.PanelState;
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
import java.awt.Component;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Rectangle;
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
 */
public final class Workspace {

    /** A tab as it closed: a panel of comando's own by its id, a document by its file. */
    public record ClosedPanel(PanelId id, String title, boolean document, Path file) {
    }

    static final int CLOSED_KEPT = 10;
    static final int TITLE_CHARS = 32;

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
    /** Whether every editor shows the light theme, its text and its rendered Markdown. EDT only. */
    private boolean light;
    /** Told when the theme button switches the editors, so the choice is kept. */
    private Consumer<Boolean> themeChanged = on -> {
    };
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
    /** The state last rendered, for a deleted file of a closed panel, which has no tab. EDT only. */
    private AppState shown;

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
        tabs.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                int i = tabs.indexAtLocation(e.getX(), e.getY());
                if (i >= 0 && e.getClickCount() == 2) {
                    rename((EditorPanel) tabs.getComponentAt(i));
                }
            }

            @Override
            public void mousePressed(MouseEvent e) {
                popup(e);
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
            parts.add("Closed " + closing.size() + (closing.size() == 1 ? " tab" : " tabs") + KeyText.of("; ⌘⇧T or the Panels tab reopens them"));
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

    /** Every editor, open now or opened later, in the light theme or the dark one. */
    public void light(boolean on) {
        light = on;
        panels.values().forEach(p -> p.light(on));
    }

    public boolean light() {
        return light;
    }

    /** What runs when a panel's theme button switches the editors, with the new choice. */
    public void onThemeChanged(Consumer<Boolean> listener) {
        themeChanged = listener;
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

    /**
     * Refreshes what changes with time alone in every panel, and every tab's status: the elapsed time in a working or
     * waiting tab's tooltip, and the end of the grace a returned background task has to start its reading turn.
     */
    public void tick() {
        panels.values().forEach(EditorPanel::tick);
        if (shown == null) {
            return;
        }
        for (PanelState p : shown.panels()) {
            EditorPanel view = panels.get(p.id());
            if (view != null && tabs.indexOfComponent(view) >= 0) {
                refreshTab(p, shown, view);
            }
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

    /** Asks for a panel's new name over {@code owner}; the panel tabs and the Panels tab both rename this way. */
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
                    tabs.remove(gone);
                    gone.editor().dispose();
                    if (deletedFile != null) {
                        intents.dispatch(new Intent.Notify(Notice.Level.INFO, "Removed " + was.title() + ": " + deletedFile
                                + " was deleted outside comando", false));
                    } else if (bulkClosing.contains(id)) {
                        // a menu's close says it once, below
                    } else if (kept) {
                        intents.dispatch(new Intent.Notify(Notice.Level.INFO, "Closed " + was.title()
                                + KeyText.of("; ⌘⇧T or the Panels tab reopens it"), false));
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
                    view.light(light);
                    view.onThemeToggle(() -> {
                        light(!light);
                        themeChanged.accept(light);
                    });
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
        Icon icon = icon(status);
        if (tabs.getIconAt(at) != icon) {
            tabs.setIconAt(at, icon);
        }
        String tip = tooltip(p, s, status);
        if (!tip.equals(tabs.getToolTipTextAt(at))) {
            tabs.setToolTipTextAt(at, tip);
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

    private static String tooltip(PanelState p) {
        return p.title() + (p.connection().isEmpty() ? "" : " · " + p.connection()) + (p.session().isEmpty() ? "" : " · " + p.session());
    }

    private static MovableTabs named(MovableTabs tabs, String name) {
        tabs.setName(name);
        return tabs;
    }
}
