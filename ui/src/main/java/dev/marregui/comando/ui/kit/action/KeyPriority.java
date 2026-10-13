package dev.marregui.comando.ui.kit.action;

import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.KeyEventDispatcher;
import java.awt.KeyboardFocusManager;
import java.awt.Window;
import java.awt.event.KeyEvent;
import java.util.function.Predicate;

/**
 * Gives the commands' keys priority over the focused component in one window. Swing offers a key to the focused
 * component's own bindings before menu accelerators and window-wide bindings, so a widget that binds the same
 * stroke takes it: RSyntaxTextArea binds ⌘⏎, ⌘J and ⌘⇧K, and the terminal consumes every key. The key typed
 * event that follows a claimed press is swallowed as well, so the stroke leaves no character behind.
 */
public final class KeyPriority implements KeyEventDispatcher, AutoCloseable {

    private final ActionRegistry registry;
    private final Predicate<Component> owns;
    private boolean swallowTyped;

    private KeyPriority(ActionRegistry registry, Predicate<Component> owns) {
        this.registry = registry;
        this.owns = owns;
    }

    /** Installs the priority for {@code window}; close the result to remove it. */
    public static KeyPriority install(ActionRegistry registry, Window window) {
        KeyPriority p = new KeyPriority(registry, c -> c == window || SwingUtilities.getWindowAncestor(c) == window);
        KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(p);
        return p;
    }

    /** For tests: the dispatcher without installing it, claiming keys whose source {@code owns} accepts. */
    static KeyPriority detached(ActionRegistry registry, Predicate<Component> owns) {
        return new KeyPriority(registry, owns);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        switch (e.getID()) {
            case KeyEvent.KEY_PRESSED -> {
                swallowTyped = false;
                if (e.getComponent() == null || !owns.test(e.getComponent())) {
                    return false;
                }
                Command c = registry.forKey(KeyStroke.getKeyStrokeForEvent(e));
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
