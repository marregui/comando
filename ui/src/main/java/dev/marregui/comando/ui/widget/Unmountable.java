package dev.marregui.comando.ui.widget;

/** A component that holds listeners or timers beyond its own lifetime in the hierarchy, released in one call. */
public interface Unmountable {

    /** Releases listeners and timers; the component is about to leave the hierarchy. */
    void unmount();
}
