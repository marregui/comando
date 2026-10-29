package dev.marregui.comando.ui.panels;

import dev.marregui.comando.core.intent.Intent;
import dev.marregui.comando.core.state.ViewState;
import dev.marregui.comando.core.view.Placement;
import dev.marregui.comando.core.view.ViewSpec;
import dev.marregui.comando.ui.kit.icon.Icons;
import dev.marregui.comando.ui.kit.text.Plain;
import dev.marregui.comando.ui.kit.theme.Theme;
import dev.marregui.comando.ui.widget.Widget;
import dev.marregui.comando.ui.widget.WidgetContext;
import dev.marregui.comando.ui.widget.WidgetRegistry;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTabbedPane;
import java.awt.FlowLayout;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The view tabs of one placement inside a tabbed pane, after {@code fixedTabs} tabs the owner keeps.
 * A tab carries a pin toggle and a close button; a view re-renders when its version changes.
 */
final class ViewTabs {

    /** A placed view and the widget rendering it. */
    record Tab(ViewState view, Widget<ViewSpec> widget, JComponent header) {
    }

    private final JTabbedPane tabs;
    private final int fixedTabs;
    private final Placement placement;
    private final WidgetRegistry registry;
    private final WidgetContext context;
    private final Map<Long, Tab> open = new LinkedHashMap<>();

    ViewTabs(JTabbedPane tabs, int fixedTabs, Placement placement, WidgetRegistry registry, WidgetContext context) {
        this.tabs = tabs;
        this.fixedTabs = fixedTabs;
        this.placement = placement;
        this.registry = registry;
        this.context = context;
    }

    /** Syncs the tabs with the views of this placement; returns true when the set of tabs changed. */
    boolean render(List<ViewState> views) {
        boolean changed = false;
        List<Long> ids = new ArrayList<>();
        for (ViewState v : views) {
            if (v.placement() != placement) {
                continue;
            }
            ids.add(v.id());
            Tab t = open.get(v.id());
            if (t == null) {
                Widget<ViewSpec> w = registry.create(v.spec().type(), context);
                w.render(v.spec());
                JComponent header = header(v);
                t = new Tab(v, w, header);
                open.put(v.id(), t);
                tabs.addTab(Plain.text(v.title()), w.component());
                tabs.setTabComponentAt(tabs.getTabCount() - 1, header);
                changed = true;
            } else if (t.view().version() != v.version() || t.view().pinned() != v.pinned()) {
                int at = tabs.indexOfComponent(t.widget().component());
                Widget<ViewSpec> w = t.widget();
                if (!t.view().spec().type().equals(v.spec().type())) {
                    // replaced in place by a view of another type: a new widget in the same tab, selection kept
                    w.unmount();
                    w = registry.create(v.spec().type(), context);
                    w.render(v.spec());
                    if (at >= 0) {
                        tabs.setComponentAt(at, w.component());
                    }
                } else if (t.view().version() != v.version()) {
                    w.render(v.spec());
                }
                Tab updated = new Tab(v, w, header(v));
                open.put(v.id(), updated);
                if (at >= 0) {
                    tabs.setTabComponentAt(at, updated.header());
                }
            }
        }
        for (Long id : new ArrayList<>(open.keySet())) {
            if (!ids.contains(id)) {
                Tab t = open.remove(id);
                t.widget().unmount();
                tabs.remove(t.widget().component());
                changed = true;
            }
        }
        return changed;
    }

    /** The tab index of view {@code id}, or -1. */
    int indexOf(long id) {
        Tab t = open.get(id);
        return t == null ? -1 : tabs.indexOfComponent(t.widget().component());
    }

    /** The view id of tab {@code index}, or -1 for a fixed tab. */
    long viewAt(int index) {
        if (index < fixedTabs) {
            return -1;
        }
        for (Tab t : open.values()) {
            if (tabs.indexOfComponent(t.widget().component()) == index) {
                return t.view().id();
            }
        }
        return -1;
    }

    int size() {
        return open.size();
    }

    private JComponent header(ViewState v) {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        p.setOpaque(false);
        JLabel title = new JLabel(Plain.text(v.title()), Icons.get(icon(v.spec().type()), 14), JLabel.LEFT);
        title.setToolTipText(Plain.text(v.session().isEmpty() ? v.title() : v.title() + " · placed by " + v.session()));
        JButton pin = small(v.pinned() ? "pin" : "pin", v.pinned() ? "Unpin: Claude may replace this view" : "Pin: Claude will not replace this view");
        pin.setForeground(v.pinned() ? Theme.tint().body : Theme.MUTED);
        pin.setBorder(v.pinned() ? BorderFactory.createMatteBorder(0, 0, 2, 0, Theme.tint().body) : BorderFactory.createEmptyBorder(0, 0, 2, 0));
        pin.addActionListener(e -> context.intents().dispatch(new Intent.PinView(v.id(), !v.pinned())));
        JButton close = small("close", "Close the view");
        close.addActionListener(e -> context.intents().dispatch(new Intent.CloseView(v.id())));
        p.add(title);
        p.add(pin);
        p.add(close);
        return p;
    }

    private static JButton small(String icon, String tip) {
        JButton b = new JButton(Icons.get(icon, 12));
        b.putClientProperty("JButton.buttonType", "toolBarButton");
        b.setToolTipText(tip);
        b.getAccessibleContext().setAccessibleName(tip);
        b.setFocusable(false);
        return b;
    }

    static String icon(String type) {
        return switch (type) {
            case "table" -> "table";
            case "plot" -> "chart";
            case "diff" -> "diff";
            case "markdown" -> "markdown";
            case "tree" -> "tree";
            case "agent" -> "agent";
            default -> "file";
        };
    }
}
