package dev.marregui.comando.ui.widget;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import javax.swing.JComponent;

import dev.marregui.comando.core.view.ViewSpec;

/**
 * View type to widget factory. A view placed by Claude or the user resolves here; adding a widget adds one
 * registration and one view type.
 */
public final class WidgetRegistry {

    private record Entry<V extends ViewSpec>(Class<V> type, Function<WidgetContext, Widget<V>> factory) {
        Widget<ViewSpec> create(final WidgetContext ctx) {
            final Widget<V> w = factory.apply(ctx);
            return new Widget<>() {
                @Override
                public JComponent component() {
                    return w.component();
                }

                @Override
                public void render(final ViewSpec view) {
                    w.render(type.cast(view));
                }

                @Override
                public void unmount() {
                    w.unmount();
                }
            };
        }
    }

    private final Map<String, Entry<?>> entries = new LinkedHashMap<>();

    public <V extends ViewSpec> void register(final String viewType, final Class<V> type, final Function<WidgetContext, Widget<V>> factory) {
        if (entries.putIfAbsent(viewType, new Entry<>(type, factory)) != null) {
            throw new IllegalArgumentException("view type registered twice: " + viewType);
        }
    }

    public Widget<ViewSpec> create(final String viewType, final WidgetContext ctx) {
        final Entry<?> e = entries.get(viewType);
        if (e == null) {
            throw new IllegalArgumentException("no widget for view type " + viewType);
        }
        return e.create(ctx);
    }

    public Set<String> types() {
        return entries.keySet();
    }
}
