package dev.marregui.comando.ui.panels;

import dev.marregui.comando.ui.kit.text.Plain;
import dev.marregui.comando.core.intent.Intent;
import dev.marregui.comando.core.model.PanelId;
import dev.marregui.comando.core.port.IntentSink;
import dev.marregui.comando.core.schema.SchemaRegistry;
import dev.marregui.comando.core.state.AppState;
import dev.marregui.comando.core.state.PanelState;
import dev.marregui.comando.ui.PanelTexts;
import dev.marregui.comando.ui.kit.icon.Icons;

import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JOptionPane;
import javax.swing.JTabbedPane;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntConsumer;

/** The editor panels as tabs, in state order; the selected tab is the focused panel. */
public final class Workspace {

    private final IntentSink intents;
    private final PanelTexts texts;
    private final SchemaRegistry schemas;
    private final JTabbedPane tabs = named(new JTabbedPane(), "workspace");
    private final Map<PanelId, EditorPanel> panels = new LinkedHashMap<>();
    private boolean rendering;

    public Workspace(IntentSink intents, PanelTexts texts, SchemaRegistry schemas) {
        this.intents = intents;
        this.texts = texts;
        this.schemas = schemas;
        tabs.putClientProperty("JTabbedPane.tabClosable", true);
        tabs.putClientProperty("JTabbedPane.tabCloseToolTipText", "Close the panel (⌘W)");
        tabs.putClientProperty("JTabbedPane.tabCloseCallback", (IntConsumer) i -> {
            EditorPanel p = (EditorPanel) tabs.getComponentAt(i);
            intents.dispatch(new Intent.ClosePanel(p.panel().id()));
        });
        tabs.putClientProperty("JTabbedPane.tabType", "card");
        tabs.putClientProperty("JTabbedPane.scrollButtonsPolicy", "asNeededSingle");
        tabs.setTabLayoutPolicy(JTabbedPane.SCROLL_TAB_LAYOUT);
        JButton add = new JButton(Icons.get("add"));
        add.setToolTipText("New panel (⌘T)");
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
        rendering = true;
        try {
            // remove panels the state no longer has
            List<PanelId> ids = s.panels().stream().map(PanelState::id).toList();
            for (PanelId id : new ArrayList<>(panels.keySet())) {
                if (!ids.contains(id)) {
                    EditorPanel gone = panels.remove(id);
                    tabs.remove(gone);
                    gone.editor().dispose();
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
                    tabs.insertTab(Plain.text(p.title()), Icons.get("file"), view, null, Math.min(i, tabs.getTabCount()));
                } else if (tabs.indexOfComponent(view) != i) {
                    tabs.remove(view);
                    tabs.insertTab(Plain.text(p.title()), Icons.get("file"), view, null, Math.min(i, tabs.getTabCount()));
                }
                int at = tabs.indexOfComponent(view);
                if (!tabs.getTitleAt(at).equals(p.title())) {
                    tabs.setTitleAt(at, Plain.text(p.title()));
                }
                tabs.setToolTipTextAt(at, tooltip(p));
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

    private static String tooltip(PanelState p) {
        return p.title() + (p.connection().isEmpty() ? "" : " · " + p.connection()) + (p.session().isEmpty() ? "" : " · " + p.session());
    }

    private static JTabbedPane named(JTabbedPane tabs, String name) {
        tabs.setName(name);
        return tabs;
    }
}
