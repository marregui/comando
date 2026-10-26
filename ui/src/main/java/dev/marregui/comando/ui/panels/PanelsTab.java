package dev.marregui.comando.ui.panels;

import dev.marregui.comando.core.intent.Intent;
import dev.marregui.comando.core.model.PanelId;
import dev.marregui.comando.core.port.IntentSink;
import dev.marregui.comando.core.resource.SessionKind;
import dev.marregui.comando.core.state.AppState;
import dev.marregui.comando.core.state.PanelState;
import dev.marregui.comando.ui.PanelTexts;
import dev.marregui.comando.ui.kit.icon.Icons;
import dev.marregui.comando.ui.kit.resource.ResourceActions;
import dev.marregui.comando.ui.kit.text.MarkdownHtml;
import dev.marregui.comando.ui.kit.text.Plain;
import dev.marregui.comando.ui.kit.theme.Theme;

import javax.swing.BorderFactory;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * The sidebar's Panels tab: comando's own panels, open and closed, as one flat list by title, as a folder of files
 * would show them; documents opened from elsewhere are not here. No bar of buttons: the tab's menu adds a panel, and a
 * row's menu renames, clones or removes it. Double-click opens a closed panel or brings an open one forward.
 */
final class PanelsTab {

    /** A panel and whether it is open. */
    record Row(PanelState panel, boolean open) {
    }

    private final IntentSink intents;
    private final PanelTexts texts;
    private final DefaultListModel<Row> model = new DefaultListModel<>();
    private final JList<Row> list = new JList<>(model);
    private final ResourceActions<Row> actions;
    private AppState state;

    PanelsTab(IntentSink intents, PanelTexts texts, ResourceActions.Questions questions) {
        this.intents = intents;
        this.texts = texts;
        list.getAccessibleContext().setAccessibleName("Panels");
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setCellRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> l, Object value, int index, boolean selected, boolean focus) {
                JLabel label = (JLabel) super.getListCellRendererComponent(l, "", index, selected, focus);
                Row r = (Row) value;
                label.setText(html(r));
                label.setIcon(Icons.get("file"));
                label.setBorder(BorderFactory.createEmptyBorder(5, 6, 5, 6));
                label.setToolTipText(Plain.text(texts.file(r.panel().id()).map(Object::toString).orElse(r.panel().title())));
                return label;
            }
        });
        list.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                Row r = at(e);
                if (r != null && SwingUtilities.isLeftMouseButton(e) && e.getClickCount() == 2) {
                    open(r);
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
                Row r = at(e);
                if (e.isPopupTrigger() && r != null) {
                    list.setSelectedValue(r, false);
                    menu(r).show(list, e.getX(), e.getY());
                }
            }
        });
        actions = new ResourceActions<>(list, new ResourceActions.Rows<>("panel", r -> r.panel().id().value(), r -> r.panel().title(),
                Row::open, "Close", this::question), new ResourceActions.Editor() {
            @Override
            public void add() {
                intents.dispatch(new Intent.AddPanel(PanelId.random(), ""));
            }

            @Override
            public void edit(String id) {
                row(id).ifPresent(r -> Workspace.rename(r.panel(), list, intents));
            }

            @Override
            public void cloneOf(String id) {
                row(id).ifPresent(PanelsTab.this::copy);
            }
        }, id -> intents.dispatch(new Intent.RemovePanel(new PanelId(id))), questions);
    }

    JList<Row> list() {
        return list;
    }

    void render(AppState s) {
        state = s;
        List<Row> rows = new ArrayList<>();
        s.panels().stream().filter(p -> !p.document()).forEach(p -> rows.add(new Row(p, true)));
        s.closed().forEach(p -> rows.add(new Row(p, false)));
        rows.sort(Comparator.comparing((Row r) -> r.panel().title().toLowerCase(Locale.ROOT)));
        Row selected = list.getSelectedValue();
        if (!rows.equals(Collections.list(model.elements()))) {
            model.clear();
            model.addAll(rows);
        }
        if (selected != null) {
            for (int i = 0; i < model.size(); i++) {
                if (model.get(i).panel().id().equals(selected.panel().id())) {
                    list.setSelectedIndex(i);
                    break;
                }
            }
        }
    }

    /** A closed panel opens again; an open one comes forward. */
    void open(Row r) {
        intents.dispatch(r.open() ? new Intent.FocusPanel(r.panel().id()) : new Intent.ReopenPanel(r.panel().id()));
    }

    /** What removing the panel does, for the question Remove asks. */
    String question(Row r) {
        PanelState p = r.panel();
        String file = texts.file(p.id()).map(f -> f.getFileName().toString()).orElse(p.title() + ".md");
        if (!p.document() && !p.session().isEmpty() && state != null && state.session(p.session()).isPresent()) {
            // a session's own panel and the session are one (record 88)
            return SessionKind.INSTANCE.removalQuestion(state, p.session());
        }
        return "Remove panel " + p.title() + "? Its file " + file + " is deleted." + (r.open() ? " The panel closes first." : "")
                + (p.session().isEmpty() ? "" : " Its session " + p.session() + " keeps running without a panel.");
    }

    /** A new panel with the text and connection of {@code r}, without its session: a session has one panel. */
    private void copy(Row r) {
        PanelId id = PanelId.random();
        texts.changed(id, texts.load(r.panel().id()));
        intents.dispatch(new Intent.AddPanel(id, r.panel().title() + " copy"));
        intents.dispatch(new Intent.BindConnection(id, r.panel().connection()));
    }

    /** A row's menu: open or bring forward, rename, clone, remove. */
    JPopupMenu menu(Row r) {
        JPopupMenu m = new JPopupMenu();
        m.add(item(r.open() ? "Bring forward" : "Open", "file", () -> open(r)));
        m.addSeparator();
        m.add(item("Rename…", "edit", () -> Workspace.rename(r.panel(), list, intents)));
        m.add(item("Clone", "copy", () -> copy(r)));
        m.add(item("Remove…", "close", () -> actions.remove(r)));
        return m;
    }

    private Optional<Row> row(String id) {
        for (int i = 0; i < model.size(); i++) {
            if (model.get(i).panel().id().value().equals(id)) {
                return Optional.of(model.get(i));
            }
        }
        return Optional.empty();
    }

    private Row at(MouseEvent e) {
        int i = list.locationToIndex(e.getPoint());
        return i >= 0 && list.getCellBounds(i, i).contains(e.getPoint()) ? model.get(i) : null;
    }

    /** Two lines: open or closed and the title; then the session, the connection and the file. */
    private String html(Row r) {
        PanelState p = r.panel();
        String dot = "<span style='color:" + Theme.hex(r.open() ? Theme.ACCENT : Theme.MUTED) + "'>" + (r.open() ? "●" : "○") + "</span>";
        boolean focused = state != null && r.open() && Objects.equals(state.focused(), p.id());
        List<String> parts = new ArrayList<>();
        if (!p.session().isEmpty()) {
            parts.add("✳ " + p.session());
        }
        if (!p.connection().isEmpty()) {
            parts.add(p.connection());
        }
        texts.file(p.id()).ifPresent(f -> parts.add(f.getFileName().toString()));
        return "<html><nobr>" + dot + "&nbsp;<b>" + MarkdownHtml.escape(p.title()) + "</b>"
                + (focused ? "&nbsp;<span style='color:" + Theme.hex(Theme.ACCENT) + "'>· focused</span>" : r.open() ? "" :
                "&nbsp;<span style='color:" + Theme.hex(Theme.MUTED) + "'>closed</span>")
                + "</nobr><br><nobr><span style='color:" + Theme.hex(Theme.MUTED_STRONG) + "'>"
                + MarkdownHtml.escape(String.join(" · ", parts)) + "</span></nobr></html>";
    }

    private static JMenuItem item(String text, String icon, Runnable r) {
        JMenuItem i = new JMenuItem(text, Icons.get(icon));
        i.addActionListener(e -> r.run());
        return i;
    }
}
