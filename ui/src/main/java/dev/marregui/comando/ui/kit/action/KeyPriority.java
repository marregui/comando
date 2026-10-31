package dev.marregui.comando.ui.kit.action;

import java.awt.Component;
import java.awt.KeyEventDispatcher;
import java.awt.KeyboardFocusManager;
import java.awt.Window;
import java.awt.event.KeyEvent;
import java.util.function.BiPredicate;
import java.util.function.Predicate;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;

/**
 * Gives the commands' keys priority over the focused component in one window. Swing offers a key to the focused
 * component's own bindings before menu accelerators and window-wide bindings, so a widget that binds the same
 * stroke takes it: RSyntaxTextArea binds ⌘⏎, ⌘J and ⌘⇧K, and the terminal consumes every key. The key typed event
 * that follows a claimed press is swallowed as well, so the stroke leaves no character behind. A component may keep
 * some keys for itself: the terminal keeps Ctrl with a letter for the shell, which is ⌘'s key on desktops other than
 * macOS.
 */
public final class KeyPriority implements KeyEventDispatcher, AutoCloseable {

    private final ActionRegistry registry;
    private final Predicate<Component> owns;
    private final BiPredicate<Component, KeyStroke> keeps;
    private boolean swallowTyped;

    private KeyPriority(final ActionRegistry registry, final Predicate<Component> owns, final BiPredicate<Component, KeyStroke> keeps) {
        this.registry = registry;
        this.owns = owns;
        this.keeps = keeps;
    }

    /**
     * Installs the priority for {@code window}, except for the keys {@code keeps} leaves to the component they are
     * pressed in; close the result to remove it.
     */
    public static KeyPriority install(final ActionRegistry registry, final Window window, final BiPredicate<Component, KeyStroke> keeps) {
        final KeyPriority p = new KeyPriority(registry, c -> c == window || SwingUtilities.getWindowAncestor(c) == window, keeps);
        KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(p);
        return p;
    }

    /** For tests: the dispatcher, not installed, claiming keys whose source {@code owns} accepts and does not keep. */
    static KeyPriority detached(final ActionRegistry registry, final Predicate<Component> owns, final BiPredicate<Component, KeyStroke> keeps) {
        return new KeyPriority(registry, owns, keeps);
    }

    @Override
    public boolean dispatchKeyEvent(final KeyEvent e) {
        switch (e.getID()) {
            case KeyEvent.KEY_PRESSED -> {
                swallowTyped = false;
                if (e.getComponent() == null || !owns.test(e.getComponent())) {
                    return false;
                }
                final KeyStroke key = KeyStroke.getKeyStrokeForEvent(e);
                if (keeps.test(e.getComponent(), key)) {
                    return false;
                }
                final Command c = registry.forKey(key);
                if (c == null || !c.enabled().getAsBoolean()) {
                    return false;
                }
                e.consume();
                swallowTyped = true;
                c.run().run();
                return true;
            }
            case KeyEvent.KEY_TYPED -> {
                if (swallowTyped) {
                    e.consume();
                    return true;
                }
                return false;
            }
            default -> {
                swallowTyped = false;
                return false;
            }
        }
    }

    @Override
    public void close() {
        KeyboardFocusManager.getCurrentKeyboardFocusManager().removeKeyEventDispatcher(this);
    }
}
