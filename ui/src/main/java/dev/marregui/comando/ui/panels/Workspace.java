package dev.marregui.comando.ui.panels;

import dev.marregui.comando.core.intent.Intent;
import dev.marregui.comando.core.model.PanelId;
import dev.marregui.comando.core.port.IntentSink;
import dev.marregui.comando.core.schema.SchemaRegistry;
import dev.marregui.comando.core.state.AppState;
import dev.marregui.comando.core.state.Notice;
import dev.marregui.comando.core.state.PanelState;
import dev.marregui.comando.core.state.RunStatus;
import dev.marregui.comando.core.state.SqlRun;
import dev.marregui.comando.ui.PanelTexts;
import dev.marregui.comando.ui.kit.async.Edt;
import dev.marregui.comando.ui.kit.format.Labels;
import dev.marregui.comando.ui.kit.icon.Icons;
import dev.marregui.comando.ui.kit.text.Plain;

import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JOptionPane;
import javax.swing.JTabbedPane;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

/**
 * The open panels as tabs, in state order; the selected tab is the focused panel. The tabs closed most recently are
 * remembered for ⌘⇧T: a panel of comando's own by its id (the state keeps it, closed), a document by its file. A panel
 * whose query finished while another was in front is marked until the user looks at it.
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
    private final JTabbedPane tabs = named(new JTabbedPane(), "workspace");
    private final Map<PanelId, EditorPanel> panels = new LinkedHashMap<>();
    private final Deque<ClosedPanel> closed = new ArrayDeque<>();
    private final Map<PanelId, RunStatus> lastRun = new HashMap<>();
    private final Set<PanelId> unseen = new HashSet<>();
    private Consumer<PanelId> closer;
    /** Panels removed because their file was deleted outside comando, with the file's name. EDT only. */
    private final Map<PanelId, String> deletedFiles = new HashMap<>();
    private boolean rendering;
    /** The state last rendered, for a deleted file of a closed panel, which has no tab. EDT only. */
    private AppState shown;

    public Workspace(IntentSink intents, PanelTexts texts, SchemaRegistry schemas) {
        this.intents = intents;
        this.texts = texts;
        this.schemas = schemas;
        this.closer = id -> intents.dispatch(new Intent.ClosePanel(id));
        texts.onOutsideChange(change -> Edt.later(() -> takeOutside(change)));
        texts.onDeleted(deleted -> Edt.later(() -> closeDeleted(deleted)));
        tabs.putClientProperty("JTabbedPane.tabClosable", true);
        tabs.putClientProperty("JTabbedPane.tabCloseToolTipText", "Close the panel (⌘W; ⌘⇧T reopens it)");
        tabs.putClientProperty("JTabbedPane.tabCloseCallback", (IntConsumer) i -> {
            EditorPanel p = (EditorPanel) tabs.getComponentAt(i);
            closer.accept(p.panel().id());
        });
        tabs.putClientProperty("JTabbedPane.tabType", "card");
        tabs.putClientProperty("JTabbedPane.scrollButtonsPolicy", "asNeededSingle");
        tabs.setTabLayoutPolicy(JTabbedPane.SCROLL_TAB_LAYOUT);
        JButton add = new JButton(Icons.get("add"));
        add.setToolTipText("New panel (⌘T)");
        add.getAccessibleContext().setAccessibleName("New panel");
        add.putClientProperty("JButton.buttonType", "toolBarButton");
        add.addActionListener(e -> newPanel());
        tabs.putClientProperty("JTabbedPane.trailingComponent", add);
        tabs.addChangeListener(e -> {
            if (!rendering && tabs.getSelectedComponent() instanceof EditorPanel p) {
                intents.dispatch(new Intent.FocusPanel(p.panel().id()));
            }
        });
        tabs.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                int i = tabs.indexAtLocation(e.getX(), e.getY());
                if (i >= 0 && e.getClickCount() == 2) {
                    rename((EditorPanel) tabs.getComponentAt(i));
                }
            }
        });
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
        intents.dispatch(new Intent.Notify(Notice.Level.INFO, "Reloaded " + change.file() + ": it changed outside comando", false));
    }

    /**
     * A panel's file was deleted outside comando: the panel, open or closed, is removed, and leaves nothing for ⌘⇧T.
     */
    void closeDeleted(PanelTexts.Deleted deleted) {
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

    /** The most recently closed panel, removed from the list; empty when none is left. */
    public Optional<ClosedPanel> popClosed() {
        return Optional.ofNullable(closed.pollFirst());
    }

    public boolean hasClosed() {
        return !closed.isEmpty();
    }

    /** Refreshes what changes with time alone in every panel. */
    public void tick() {
        panels.values().forEach(EditorPanel::tick);
    }

    public void newPanel() {
        intents.dispatch(new Intent.AddPanel(PanelId.random(), ""));
    }

    public void rename(EditorPanel p) {
        String title = (String) JOptionPane.showInputDialog(tabs, "Panel name", "Rename panel", JOptionPane.PLAIN_MESSAGE, null, null,
                p.panel().title());
        if (title != null && !title.isBlank()) {
            intents.dispatch(new Intent.RenamePanel(p.panel().id(), title));
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
                    lastRun.remove(id);
                    unseen.remove(id);
                    tabs.remove(gone);
                    gone.editor().dispose();
                    if (deletedFile != null) {
                        intents.dispatch(new Intent.Notify(Notice.Level.INFO, "Removed " + was.title() + ": " + deletedFile
                                + " was deleted outside comando", false));
                    } else if (kept) {
                        intents.dispatch(new Intent.Notify(Notice.Level.INFO, "Closed " + was.title()
                                + "; ⌘⇧T or the Panels tab reopens it", false));
                    } else if (was.document()) {
                        intents.dispatch(new Intent.Notify(Notice.Level.INFO, "Closed " + was.title() + " — ⌘⇧T reopens it", false));
                    }
                }
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
                    panels.put(p.id(), view);
                    tabs.insertTab(title(p, false), Icons.get("file"), view, null, Math.min(i, tabs.getTabCount()));
                } else if (tabs.indexOfComponent(view) != i) {
                    tabs.remove(view);
                    tabs.insertTab(title(p, false), Icons.get("file"), view, null, Math.min(i, tabs.getTabCount()));
                }
                trackRun(p.id(), s);
                int at = tabs.indexOfComponent(view);
                String title = title(p, unseen.contains(p.id()));
                if (!tabs.getTitleAt(at).equals(title)) {
                    tabs.setTitleAt(at, title);
                }
                tabs.setToolTipTextAt(at, Plain.text(tooltip(p) + texts.file(p.id()).map(f -> " · " + Labels.shortPath(f.toString())).orElse("")
                        + (unseen.contains(p.id()) ? " · a query finished" : "")));
                view.file(texts.file(p.id()).orElse(null), texts.document(p.id()));
                view.render(p, s);
            }
            EditorPanel focused = panels.get(s.focused());
            if (focused != null && tabs.getSelectedComponent() != focused) {
                tabs.setSelectedComponent(focused);
            }
        } finally {
            rendering = false;
        }
    }

    /** Marks a panel whose query ended while another panel was in front; looking at it clears the mark. */
    private void trackRun(PanelId id, AppState s) {
        RunStatus now = s.runOf(id).map(SqlRun::status).orElse(null);
        RunStatus before = lastRun.put(id, now);
        if (id.equals(s.focused())) {
            unseen.remove(id);
        } else if (before != null && before.isActive() && now != null && !now.isActive()) {
            unseen.add(id);
        }
    }

    /** The tab's title: plain, clipped, with a dot when a query finished unseen. */
    static String title(PanelState p, boolean marked) {
        String t = p.title().length() > TITLE_CHARS ? p.title().substring(0, TITLE_CHARS - 1) + "…" : p.title();
        return Plain.text((marked ? "● " : "") + t);
    }

    private static String tooltip(PanelState p) {
        return p.title() + (p.connection().isEmpty() ? "" : " · " + p.connection()) + (p.session().isEmpty() ? "" : " · " + p.session());
    }

    private static JTabbedPane named(JTabbedPane tabs, String name) {
        tabs.setName(name);
        return tabs;
    }
}
