package dev.marregui.comando.ui.panels;

import dev.marregui.comando.core.intent.Intent;
import dev.marregui.comando.core.model.PanelId;
import dev.marregui.comando.core.port.IntentSink;
import dev.marregui.comando.core.state.AppState;
import dev.marregui.comando.core.state.PanelState;
import dev.marregui.comando.ui.PanelTexts;
import dev.marregui.comando.ui.kit.icon.Icons;
import dev.marregui.comando.ui.kit.resource.ResourceActions;
import dev.marregui.comando.ui.kit.text.MarkdownHtml;
import dev.marregui.comando.ui.kit.text.Plain;
import dev.marregui.comando.ui.kit.theme.SidebarLook;
import dev.marregui.comando.ui.kit.theme.Theme;

import javax.swing.BorderFactory;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Rectangle;
import java.awt.Graphics;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * The foot of the Sessions tab: comando's own panels that belong to no session, open and closed, by title (record 91).
 * A session's own panel is its session's row, and a document is the Files tab's, so neither is here; the section shows
 * only while it has a row. A row's menu opens or brings forward, renames, makes the panel a session's own and removes;
 * a double-click opens a closed panel or brings an open one forward.
 */
final class PanelsWithoutSession {

    /** A panel and whether it is open. */
    record Row(PanelState panel, boolean open) {
    }

    /** The most rows shown before the section scrolls: the sessions keep the tab. */
    private static final int MOST_ROWS = 4;

    private final IntentSink intents;
    private final PanelTexts texts;
    private final Consumer<String> newSession;
    private final DefaultListModel<Row> model = new DefaultListModel<>();
    private final JList<Row> list = new JList<>(model) {
        /** The report's bar down the left edge of the selected row (record 98). */
        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            for (int i : getSelectedIndices()) {
                Rectangle r = getCellBounds(i, i);
                if (r != null) {
                    SidebarLook.bar(g, r);
                }
            }
        }
    };
    private final ResourceActions<Row> actions;
    private final JPanel section = new JPanel(new BorderLayout());
    private AppState state;

    /** {@code newSession} opens the session dialog on a new session with the name given, as a panel's chip does. */
    PanelsWithoutSession(IntentSink intents, PanelTexts texts, Consumer<String> newSession, ResourceActions.Questions questions) {
        this.intents = intents;
        this.texts = texts;
        this.newSession = newSession;
        list.getAccessibleContext().setAccessibleName("Panels without a session");
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setCellRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> l, Object value, int index, boolean selected, boolean focus) {
                JLabel label = (JLabel) super.getListCellRendererComponent(l, "", index, selected, focus);
                Row r = (Row) value;
                label.setText(html(r));
                label.setIcon(Icons.get("file"));
                label.setBorder(BorderFactory.createEmptyBorder(Theme.SPACE_XS, Theme.SPACE_S, Theme.SPACE_XS, Theme.SPACE_S));
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
                // a new panel is ⌘T, or the tab menu's New panel
            }

            @Override
            public void edit(String id) {
                row(id).ifPresent(r -> Workspace.rename(r.panel(), list, intents));
            }

            @Override
            public void cloneOf(String id) {
                // panels are not cloned (record 91)
            }
        }, id -> intents.dispatch(new Intent.RemovePanel(new PanelId(id))), questions);
        // a group's header, as the Files tab's (record 98)
        JLabel title = SidebarLook.header("Without a session");
        JScrollPane scroll = new JScrollPane(list);
        scroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setBorder(null);
        SidebarLook.list(list);
        SidebarLook.ground(scroll);
        SidebarLook.ground(section);
        section.add(title, BorderLayout.NORTH);
        section.add(scroll, BorderLayout.CENTER);
        section.setVisible(false);
    }

    /** The section: its title and its rows; hidden while there is none. */
    JComponent component() {
        return section;
    }

    JList<Row> list() {
        return list;
    }

    /** Whether {@code p} is a panel of comando's own that no session has: none named, or one named that is gone. */
    static boolean withoutSession(AppState s, PanelState p) {
        return !p.document() && (p.session().isEmpty() || s.session(p.session()).isEmpty());
    }

    void render(AppState s) {
        state = s;
        List<Row> rows = new ArrayList<>();
        s.panels().stream().filter(p -> withoutSession(s, p)).forEach(p -> rows.add(new Row(p, true)));
        s.closed().stream().filter(p -> withoutSession(s, p)).forEach(p -> rows.add(new Row(p, false)));
        rows.sort(Comparator.comparing((Row r) -> r.panel().title().toLowerCase(Locale.ROOT)));
        Row selected = list.getSelectedValue();
        if (!rows.equals(Collections.list(model.elements()))) {
            model.clear();
            model.addAll(rows);
            list.setVisibleRowCount(Math.clamp(rows.size(), 1, MOST_ROWS));
            section.setVisible(!rows.isEmpty());
            section.revalidate();
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
        return "Remove panel " + p.title() + "? Its file " + file + " is deleted." + (r.open() ? " The panel closes first." : "");
    }

    /** A row's menu: open or bring forward, rename, make it a session's own, remove. */
    JPopupMenu menu(Row r) {
        JPopupMenu m = new JPopupMenu();
        m.add(item(r.open() ? "Bring forward" : "Open", "file", () -> open(r)));
        m.addSeparator();
        m.add(item("Rename…", "edit", () -> Workspace.rename(r.panel(), list, intents)));
        JMenuItem create = item(Plain.text("New session " + r.panel().title() + "…"), "add", () -> newSession.accept(r.panel().title()));
        create.setToolTipText("A session with this panel's name, this panel its own");
        m.add(create);
        m.addSeparator();
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

    /** Two lines: open or closed and the title; then the connection and the file. */
    private String html(Row r) {
        PanelState p = r.panel();
        String dot = "<span style='color:" + Theme.hex(r.open() ? Theme.tint().body : Theme.MUTED) + "'>" + (r.open() ? "●" : "○") + "</span>";
        boolean focused = state != null && r.open() && Objects.equals(state.focused(), p.id());
        List<String> parts = new ArrayList<>();
        if (!p.connection().isEmpty()) {
            parts.add(p.connection());
        }
        texts.file(p.id()).ifPresent(f -> parts.add(f.getFileName().toString()));
        return "<html><nobr>" + dot + "&nbsp;<b>" + MarkdownHtml.escape(p.title()) + "</b>"
                + (focused ? "&nbsp;<span style='color:" + Theme.hex(Theme.tint().body) + "'>· focused</span>" : r.open() ? "" :
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
