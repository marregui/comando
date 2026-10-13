package dev.marregui.comando.ui.widget;

import dev.marregui.comando.core.view.ViewSpec;

import javax.swing.JComponent;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * View type to widget factory. A view placed by Claude or the user resolves here; adding a widget adds one
 * registration and one view type.
 */
public final class WidgetRegistry {

    private record Entry<V extends ViewSpec>(Class<V> type, Function<WidgetContext, Widget<V>> factory) {
        Widget<ViewSpec> create(WidgetContext ctx) {
            Widget<V> w = factory.apply(ctx);
            return new Widget<>() {
                @Override
                public JComponent component() {
                    return w.component();
                }

                @Override
                public void render(ViewSpec view) {
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

    public <V extends ViewSpec> void register(String viewType, Class<V> type, Function<WidgetContext, Widget<V>> factory) {
        if (entries.putIfAbsent(viewType, new Entry<>(type, factory)) != null) {
            throw new IllegalArgumentException("view type registered twice: " + viewType);
        }
    }

    public Widget<ViewSpec> create(String viewType, WidgetContext ctx) {
        Entry<?> e = entries.get(viewType);
        if (e == null) {
            throw new IllegalArgumentException("no widget for view type " + viewType);
        }
        return e.create(ctx);
    }

    public Set<String> types() {
        return entries.keySet();
    }
}
