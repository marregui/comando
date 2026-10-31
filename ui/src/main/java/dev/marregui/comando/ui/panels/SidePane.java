package dev.marregui.comando.ui.panels;

import java.awt.Component;
import java.util.HashMap;
import java.util.Map;
import javax.swing.JComponent;
import javax.swing.JTabbedPane;
import javax.swing.SwingUtilities;

import dev.marregui.comando.core.state.AppState;
import dev.marregui.comando.core.state.ViewState;
import dev.marregui.comando.core.view.Placement;
import dev.marregui.comando.ui.widget.WidgetContext;
import dev.marregui.comando.ui.widget.WidgetRegistry;

/** Views placed beside the editor: files Claude opened, diffs, plots, the Changes view. Hidden when it holds none. */
public final class SidePane {

    private final JTabbedPane tabs = named(new JTabbedPane(), "side");
    private final ViewTabs views;
    /** Each side view's version at the last render, so a view shown again comes forward. */
    private Map<Long, Long> versions = Map.of();

    public SidePane(final WidgetContext context, final WidgetRegistry registry) {
        tabs.putClientProperty("JTabbedPane.tabType", "underlined");
        tabs.setTabLayoutPolicy(JTabbedPane.SCROLL_TAB_LAYOUT);
        views = new ViewTabs(tabs, 0, Placement.SIDE, registry, context);
    }

    public JComponent component() {
        return tabs;
    }

    /** The view whose tab holds {@code c}, or -1. */
    public long viewHolding(final Component c) {
        for (int i = 0; i < tabs.getTabCount(); i++) {
            if (SwingUtilities.isDescendingFrom(c, tabs.getComponentAt(i))) {
                return views.viewAt(i);
            }
        }
        return -1;
    }

    public boolean hasViews() {
        return views.size() > 0;
    }

    /** Shows the side views of {@code s}, the one new or shown again in front; returns whether the pane holds any. */
    public boolean render(final AppState s) {
        final boolean changed = views.render(s.views());
        if (changed) {
            // the side view new or shown again is brought forward, the newest of them when several
            ViewState shown = null;
            final Map<Long, Long> now = new HashMap<>();
            for (final ViewState v : s.views()) {
                if (v.placement() != Placement.SIDE) {
                    continue;
                }
                now.put(v.id(), v.version());
                final Long before = versions.get(v.id());
                if ((before == null || before < v.version()) && (shown == null || v.id() > shown.id())) {
                    shown = v;
                }
            }
            versions = now;
            if (shown != null) {
                final int i = views.indexOf(shown.id());
                if (i >= 0) {
                    tabs.setSelectedIndex(i);
                }
            }
        }
        return views.size() > 0;
    }

    private static JTabbedPane named(final JTabbedPane tabs, final String name) {
        tabs.setName(name);
        return tabs;
    }
}
