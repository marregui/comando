package dev.marregui.comando.ui.kit.action;

import java.awt.Component;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.KeyStroke;

import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rtextarea.RTextArea;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// [KeyPriority] runs a registered command's key before the focused component gets it.
/// - The editor binds the run key itself, which is why the priority exists.
/// - A command key in a priority component runs the command and consumes the press.
/// - The character typed by a claimed press is swallowed once, and only that one.
/// - Other keys, other windows and disabled commands are left to the component.
/// - A key the focused component keeps for itself is left to it.
class KeyPriorityTest {

    /** The shortcut modifier the editor binds its keys with: ⌘ on macOS, Ctrl elsewhere, as comando's own keys. */
    private static final int CMD = RTextArea.getDefaultModifier();
    private static final KeyStroke RUN = KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, CMD);

    private final RSyntaxTextArea editor = new RSyntaxTextArea();
    private final AtomicInteger runs = new AtomicInteger();
    private final AtomicBoolean enabled = new AtomicBoolean(true);
    private final ActionRegistry registry = new ActionRegistry();

    KeyPriorityTest() {
        registry.register(new Command("run", "Run", null, RUN, "Run", runs::incrementAndGet, enabled::get));
    }

    /// [RSyntaxTextArea] binds the run key in its own focused input map chain, so without the priority the
    /// editor would take it.
    @Test
    void theEditorBindsTheRunKeyItself() {
        Object bound = null;
        for (var map = editor.getInputMap(JComponent.WHEN_FOCUSED); map != null && bound == null; map = map.getParent()) {
            bound = map.get(RUN);
        }
        assertThat(bound).as("why the priority exists").isNotNull();
    }

    /// A press of an enabled command's key in a priority component runs the command once, returns true and
    /// consumes the event.
    @Test
    void aCommandKeyRunsTheCommandBeforeTheFocusedEditor() {
        final KeyPriority p = KeyPriority.detached(registry, c -> c == editor, (c, k) -> false);
        final KeyEvent press = pressed(editor, KeyEvent.VK_ENTER, CMD);

        assertThat(p.dispatchKeyEvent(press)).isTrue();
        assertThat(press.isConsumed()).isTrue();
        assertThat(runs).hasValue(1);
    }

    /// After a claimed press, the next typed character is swallowed; the release and any later typed character
    /// pass through.
    @Test
    void theCharacterTypedByAClaimedPressIsSwallowedOnce() {
        final KeyPriority p = KeyPriority.detached(registry, c -> c == editor, (c, k) -> false);
        p.dispatchKeyEvent(pressed(editor, KeyEvent.VK_ENTER, CMD));

        assertThat(p.dispatchKeyEvent(typed(editor, '\n'))).isTrue();
        assertThat(p.dispatchKeyEvent(released(editor, KeyEvent.VK_ENTER, CMD))).isFalse();
        assertThat(p.dispatchKeyEvent(typed(editor, 'x'))).isFalse();
    }

    /// A key no command owns, a command key in a component outside the priority set, and a plain typed character
    /// all pass through without running anything.
    @Test
    void otherKeysAndOtherWindowsAreLeftAlone() {
        final JPanel elsewhere = new JPanel();
        final KeyPriority p = KeyPriority.detached(registry, c -> c == editor, (c, k) -> false);

        assertThat(p.dispatchKeyEvent(pressed(editor, KeyEvent.VK_J, InputEvent.ALT_DOWN_MASK))).isFalse();
        assertThat(p.dispatchKeyEvent(pressed(elsewhere, KeyEvent.VK_ENTER, CMD))).isFalse();
        assertThat(p.dispatchKeyEvent(typed(editor, 'a'))).isFalse();
        assertThat(runs).hasValue(0);
    }

    /// A command whose enabled supplier says false does not claim its key.
    @Test
    void aDisabledCommandLeavesTheKeyToTheComponent() {
        enabled.set(false);
        final KeyPriority p = KeyPriority.detached(registry, c -> c == editor, (c, k) -> false);

        assertThat(p.dispatchKeyEvent(pressed(editor, KeyEvent.VK_ENTER, CMD))).isFalse();
        assertThat(runs).hasValue(0);
    }

    /// The terminal keeps Ctrl with a letter for its shell; on desktops other than macOS that is ⌘'s key. A key
    /// the keep predicate claims for the focused component passes to it, and the same key elsewhere runs the
    /// command.
    @Test
    void aKeyTheFocusedComponentKeepsIsLeftToIt() {
        final KeyStroke close = KeyStroke.getKeyStroke(KeyEvent.VK_W, InputEvent.CTRL_DOWN_MASK);
        final AtomicInteger closes = new AtomicInteger();
        registry.register(new Command("close", "Close", null, close, "Panels", closes::incrementAndGet, () -> true));
        final JPanel shell = new JPanel();
        final KeyPriority p = KeyPriority.detached(registry, c -> c == editor || c == shell, (c, k) -> c == shell && k.equals(close));

        assertThat(p.dispatchKeyEvent(pressed(shell, KeyEvent.VK_W, InputEvent.CTRL_DOWN_MASK))).as("the shell's ^W").isFalse();
        assertThat(closes).hasValue(0);
        assertThat(p.dispatchKeyEvent(pressed(editor, KeyEvent.VK_W, InputEvent.CTRL_DOWN_MASK))).as("elsewhere a command").isTrue();
        assertThat(closes).hasValue(1);
    }

    private static KeyEvent pressed(final Component source, final int code, final int modifiers) {
        return new KeyEvent(source, KeyEvent.KEY_PRESSED, 0, modifiers, code, KeyEvent.CHAR_UNDEFINED);
    }

    private static KeyEvent released(final Component source, final int code, final int modifiers) {
        return new KeyEvent(source, KeyEvent.KEY_RELEASED, 0, modifiers, code, KeyEvent.CHAR_UNDEFINED);
    }

    private static KeyEvent typed(final Component source, final char c) {
        return new KeyEvent(source, KeyEvent.KEY_TYPED, 0, 0, KeyEvent.VK_UNDEFINED, c);
    }
}
