package dev.marregui.comando.ui.widget;

import javax.swing.JComponent;

/**
 * The contract every widget implements: it renders a view value and sends intents. A widget holds no state
 * beyond what Swing needs, does not call adapters, threads or the store, and imports no other widget.
 *
 * @param <V> the value it renders
 */
public interface Widget<V> {

    JComponent component();

    /** Renders {@code view}; called on the EDT whenever the value or its version changes. */
    void render(V view);

    /** Releases listeners and timers; the component is about to leave the hierarchy. */
    default void unmount() {
    }
}
