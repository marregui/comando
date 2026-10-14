package dev.marregui.comando.ui.kit.action;

import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rtextarea.RTextArea;
import org.junit.jupiter.api.Test;

import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.KeyStroke;
import java.awt.Component;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

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

    @Test
    void theEditorBindsTheRunKeyItself() {
        Object bound = null;
        for (var map = editor.getInputMap(JComponent.WHEN_FOCUSED); map != null && bound == null; map = map.getParent()) {
            bound = map.get(RUN);
        }
        assertThat(bound).as("why the priority exists").isNotNull();
    }

    @Test
    void aCommandKeyRunsTheCommandBeforeTheFocusedEditor() {
        KeyPriority p = KeyPriority.detached(registry, c -> c == editor);
        KeyEvent press = pressed(editor, KeyEvent.VK_ENTER, CMD);

        assertThat(p.dispatchKeyEvent(press)).isTrue();
        assertThat(press.isConsumed()).isTrue();
        assertThat(runs).hasValue(1);
    }

    @Test
    void theCharacterTypedByAClaimedPressIsSwallowedOnce() {
        KeyPriority p = KeyPriority.detached(registry, c -> c == editor);
        p.dispatchKeyEvent(pressed(editor, KeyEvent.VK_ENTER, CMD));

        assertThat(p.dispatchKeyEvent(typed(editor, '\n'))).isTrue();
        assertThat(p.dispatchKeyEvent(released(editor, KeyEvent.VK_ENTER, CMD))).isFalse();
        assertThat(p.dispatchKeyEvent(typed(editor, 'x'))).isFalse();
    }

    @Test
    void otherKeysAndOtherWindowsAreLeftAlone() {
        JPanel elsewhere = new JPanel();
        KeyPriority p = KeyPriority.detached(registry, c -> c == editor);

        assertThat(p.dispatchKeyEvent(pressed(editor, KeyEvent.VK_J, InputEvent.ALT_DOWN_MASK))).isFalse();
        assertThat(p.dispatchKeyEvent(pressed(elsewhere, KeyEvent.VK_ENTER, CMD))).isFalse();
        assertThat(p.dispatchKeyEvent(typed(editor, 'a'))).isFalse();
        assertThat(runs).hasValue(0);
    }

    @Test
    void aDisabledCommandLeavesTheKeyToTheComponent() {
        enabled.set(false);
        KeyPriority p = KeyPriority.detached(registry, c -> c == editor);

        assertThat(p.dispatchKeyEvent(pressed(editor, KeyEvent.VK_ENTER, CMD))).isFalse();
        assertThat(runs).hasValue(0);
    }

    private static KeyEvent pressed(Component source, int code, int modifiers) {
        return new KeyEvent(source, KeyEvent.KEY_PRESSED, 0, modifiers, code, KeyEvent.CHAR_UNDEFINED);
    }

    private static KeyEvent released(Component source, int code, int modifiers) {
        return new KeyEvent(source, KeyEvent.KEY_RELEASED, 0, modifiers, code, KeyEvent.CHAR_UNDEFINED);
    }

    private static KeyEvent typed(Component source, char c) {
        return new KeyEvent(source, KeyEvent.KEY_TYPED, 0, 0, KeyEvent.VK_UNDEFINED, c);
    }
}
