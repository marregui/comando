package dev.marregui.comando.ui.panels;

import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JTabbedPane;
import javax.swing.SwingUtilities;

import dev.marregui.comando.core.intent.Intent;
import dev.marregui.comando.core.state.ViewState;
import dev.marregui.comando.core.view.Placement;
import dev.marregui.comando.core.view.ViewSpec;
import dev.marregui.comando.ui.kit.action.ToolButton;
import dev.marregui.comando.ui.kit.format.KeyText;
import dev.marregui.comando.ui.kit.icon.Icons;
import dev.marregui.comando.ui.kit.text.Plain;
import dev.marregui.comando.ui.kit.theme.Style;
import dev.marregui.comando.ui.kit.theme.Theme;
import dev.marregui.comando.ui.widget.Widget;
import dev.marregui.comando.ui.widget.WidgetContext;
import dev.marregui.comando.ui.widget.WidgetRegistry;

/**
 * The view tabs of one placement inside a tabbed pane, after {@code fixedTabs} tabs the owner keeps.
 * A tab carries a pin toggle and a close button, and no close button of the strip's own; its right-click menu moves the
 * view to the other placement. A view re-renders when its version changes.
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

    ViewTabs(
            final JTabbedPane tabs,
            final int fixedTabs,
            final Placement placement,
            final WidgetRegistry registry,
            final WidgetContext context
    ) {
        this.tabs = tabs;
        this.fixedTabs = fixedTabs;
        this.placement = placement;
        this.registry = registry;
        this.context = context;
    }

    /** Syncs the tabs with the views of this placement; returns true when the set of tabs changed. */
    boolean render(final List<ViewState> views) {
        boolean changed = false;
        final List<Long> ids = new ArrayList<>();
        for (final ViewState v : views) {
            if (v.placement() != placement) {
                continue;
            }
            ids.add(v.id());
            Tab t = open.get(v.id());
            if (t == null) {
                final Widget<ViewSpec> w = registry.create(v.spec().type(), context);
                w.render(v.spec());
                final JComponent header = header(v);
                t = new Tab(v, w, header);
                open.put(v.id(), t);
                w.component().putClientProperty(TabLook.CLOSABLE, false);
                tabs.addTab(Plain.text(v.title()), w.component());
                tabs.setTabComponentAt(tabs.getTabCount() - 1, header);
                changed = true;
            } else if (t.view().version() != v.version() || t.view().pinned() != v.pinned()) {
                final int at = tabs.indexOfComponent(t.widget().component());
                Widget<ViewSpec> w = t.widget();
                if (!t.view().spec().type().equals(v.spec().type())) {
                    // replaced in place by a view of another type: a new widget in the same tab, selection kept
                    w.unmount();
                    w = registry.create(v.spec().type(), context);
                    w.render(v.spec());
                    w.component().putClientProperty(TabLook.CLOSABLE, false);
                    if (at >= 0) {
                        tabs.setComponentAt(at, w.component());
                    }
                } else if (t.view().version() != v.version()) {
                    w.render(v.spec());
                }
                final Tab updated = new Tab(v, w, header(v));
                open.put(v.id(), updated);
                if (at >= 0) {
                    tabs.setTabComponentAt(at, updated.header());
                }
            }
        }
        for (final Long id : new ArrayList<>(open.keySet())) {
            if (!ids.contains(id)) {
                final Tab t = open.remove(id);
                t.widget().unmount();
                tabs.remove(t.widget().component());
                changed = true;
            }
        }
        return changed;
    }

    /** The tab index of view {@code id}, or -1. */
    int indexOf(final long id) {
        final Tab t = open.get(id);
        return t == null ? -1 : tabs.indexOfComponent(t.widget().component());
    }

    /** The view id of tab {@code index}, or -1 for a fixed tab. */
    long viewAt(final int index) {
        if (index < fixedTabs) {
            return -1;
        }
        for (final Tab t : open.values()) {
            if (tabs.indexOfComponent(t.widget().component()) == index) {
                return t.view().id();
            }
        }
        return -1;
    }

    int size() {
        return open.size();
    }

    /** The right-click menu of view {@code v}'s tab: the move to the other placement. */
    JPopupMenu menu(final ViewState v) {
        final Placement other = placement == Placement.SIDE ? Placement.LOWER : Placement.SIDE;
        final JMenuItem move = other == Placement.LOWER
                ? new JMenuItem("Move to the lower panel", Icons.get("move-lower"))
                : new JMenuItem("Move to the right side", Icons.get("move-side"));
        move.addActionListener(_ -> dispatch(new Intent.PlaceView(v.id(), other)));
        final JPopupMenu m = new JPopupMenu();
        m.add(move);
        return m;
    }

    private JComponent header(final ViewState v) {
        final JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        p.setOpaque(false);
        // a view's title in the face the fixed tabs' titles have under the console style (record 151)
        final JLabel title = new JLabel(Plain.text(v.title()), Icons.get(icon(v.spec().type()), 14), JLabel.LEFT) {
            @Override
            public Font getFont() {
                return Theme.style() == Style.CONSOLE ? Theme.technical() : super.getFont();
            }
        };
        final String placedBy = v.session().isEmpty() ? v.title() : v.title() + " · placed by " + v.session();
        title.setToolTipText(Plain.text(placedBy));
        final String pinTip = v.pinned()
                ? "Unpin: Claude may replace this view"
                : "Pin: Claude will not replace this view";
        final JButton pin = small("pin", pinTip, () -> dispatch(new Intent.PinView(v.id(), !v.pinned())));
        pin.setForeground(v.pinned() ? Theme.tint().body : Theme.MUTED);
        pin.setBorder(v.pinned()
                ? BorderFactory.createMatteBorder(0, 0, 2, 0, Theme.tint().body)
                : BorderFactory.createEmptyBorder(0, 0, 2, 0));
        final JButton close = small("close", KeyText.of("Close the view (⌘W)"),
                () -> dispatch(new Intent.CloseView(v.id())));
        // the header and its title (for its tooltip) take the tab's clicks: each selects the tab or opens its menu
        final MouseAdapter clicks = new MouseAdapter() {
            @Override
            public void mousePressed(final MouseEvent e) {
                if (e.isPopupTrigger()) {
                    menu(v).show(e.getComponent(), e.getX(), e.getY());
                } else if (SwingUtilities.isLeftMouseButton(e)) {
                    final int at = indexOf(v.id());
                    if (at >= 0) {
                        tabs.setSelectedIndex(at);
                    }
                }
            }

            @Override
            public void mouseReleased(final MouseEvent e) {
                if (e.isPopupTrigger()) {
                    menu(v).show(e.getComponent(), e.getX(), e.getY());
                }
            }
        };
        p.addMouseListener(clicks);
        title.addMouseListener(clicks);
        p.add(title);
        p.add(pin);
        p.add(close);
        return p;
    }

    private void dispatch(final Intent intent) {
        context.intents().dispatch(intent);
    }

    /** A {@link ToolButton} at the tab's 12px icon size, so the header keeps the tab's height. */
    private static JButton small(final String icon, final String tip, final Runnable r) {
        final JButton b = ToolButton.of(icon, tip, r);
        b.setIcon(Icons.get(icon, 12));
        return b;
    }

    static String icon(final String type) {
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
