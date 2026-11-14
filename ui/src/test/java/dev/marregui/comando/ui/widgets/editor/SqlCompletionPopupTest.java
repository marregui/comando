package dev.marregui.comando.ui.widgets.editor;

import java.awt.event.ActionEvent;
import java.awt.event.FocusEvent;
import java.awt.event.FocusListener;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import javax.swing.Action;
import javax.swing.InputMap;
import javax.swing.JComponent;
import javax.swing.JList;
import javax.swing.KeyStroke;
import javax.swing.UIManager;

import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import dev.marregui.comando.core.schema.Schema;
import dev.marregui.comando.ui.kit.async.OnEdt;

import static org.assertj.core.api.Assertions.assertThat;

/// [SqlCompletion]'s list on the editor's text area, driven through the area's key bindings as the shell forwards them.
/// - ⌃Space lists the words the prefix starts, ignoring case, the first selected; a single word is not put in unasked
/// - ⌃Space with no word lists every word
/// - the list follows the prefix as it is typed, and closes on a space, on no match and when the caret leaves the word
/// - Enter, Tab and a double-click put the selected word in place of the prefix
/// - Up and Down wrap, Page Up and Page Down move by the visible rows and stop at the ends, Home and End go there
/// - Escape and a focus loss close it and give the area its keys back
/// - a letter typed in SQL starts the pause before the list opens on its own; prose, a paste or a digit do not
/// - uninstalling gives ⌃Space back, removes the global listener and may be repeated
@ExtendWith(OnEdt.class)
class SqlCompletionPopupTest {

    private static final Schema SCHEMA = new Schema(List.of(
            new Schema.Table("trades",
                    List.of(new Schema.Column("ts", "TIMESTAMP"), new Schema.Column("price", "DOUBLE"))),
            new Schema.Table("trips", List.of(new Schema.Column("ts", "TIMESTAMP")))));
    private static final KeyStroke CTRL_SPACE = KeyStroke.getKeyStroke(KeyEvent.VK_SPACE, InputEvent.CTRL_DOWN_MASK);
    /// The keys the list takes from the area while it shows.
    private static final List<Integer> LIST_KEYS = List.of(KeyEvent.VK_ESCAPE, KeyEvent.VK_UP, KeyEvent.VK_DOWN,
            KeyEvent.VK_PAGE_UP, KeyEvent.VK_PAGE_DOWN, KeyEvent.VK_HOME, KeyEvent.VK_END, KeyEvent.VK_ENTER,
            KeyEvent.VK_TAB);

    /// `sel` lists `SELECT` alone, selected, and leaves the text as typed; `SEL` and `Sel` list the same.
    @Test
    void ctrlSpaceListsTheWordsThePrefixStartsIgnoringCase() {
        for (final String typed : List.of("sel", "SEL", "Sel")) {
            final Fixture f = new Fixture(typed);
            f.ctrlSpace();
            assertThat(f.popup().isOpen()).as(typed).isTrue();
            assertThat(f.shown()).as(typed).containsExactly("SELECT");
            assertThat(f.list().getSelectedIndex()).isZero();
            assertThat(f.area.getText()).isEqualTo(typed);
        }
    }

    /// With the caret after a space, ⌃Space lists every keyword, table and column.
    @Test
    void ctrlSpaceWithNoWordListsEveryWord() {
        final Fixture f = new Fixture("select ");
        f.ctrlSpace();
        assertThat(f.popup().isOpen()).isTrue();
        assertThat(f.list().getModel().getSize()).isEqualTo(f.completion.words().size());
    }

    /// `tr` lists both tables and `TRUNCATE TABLE`, ignoring case; `a` narrows it to `trades`; a space closes it;
    /// `trz` matches nothing and closes it.
    @Test
    void theListFollowsThePrefixAndClosesOnASpaceOrNoMatch() {
        final Fixture f = new Fixture("select * from tr");
        f.ctrlSpace();
        assertThat(f.shown()).containsExactly("trades", "trips", "TRUNCATE TABLE");
        f.type("a");
        assertThat(f.shown()).containsExactly("trades");
        f.type(" ");
        assertThat(f.popup().isOpen()).isFalse();

        final Fixture g = new Fixture("select * from tr");
        g.ctrlSpace();
        g.type("z");
        assertThat(g.popup().isOpen()).isFalse();
    }

    /// Moving back inside the word refilters on the shorter prefix; moving to another word on the line, to another
    /// line or before the word's start closes the list.
    @Test
    void theListClosesWhenTheCaretLeavesTheWord() {
        final Fixture f = new Fixture("select ts\nfrom trad");
        f.ctrlSpace();
        assertThat(f.shown()).containsExactly("trades");
        f.area.setCaretPosition(f.area.getText().length() - 2);
        assertThat(f.popup().isOpen()).isTrue();
        assertThat(f.shown()).containsExactly("trades", "trips", "TRUNCATE TABLE");
        f.area.setCaretPosition("select ts".length());
        assertThat(f.popup().isOpen()).isFalse();

        final Fixture g = new Fixture("sel tr");
        g.ctrlSpace();
        g.area.setCaretPosition("sel".length());
        assertThat(g.popup().isOpen()).isFalse();

        final Fixture h = new Fixture("from tr");
        h.ctrlSpace();
        h.area.setCaretPosition("from ".length());
        assertThat(h.popup().isOpen()).isFalse();
    }

    /// Enter puts the selected word in place of the prefix and closes the list; Tab does the same; neither inserts
    /// its own character.
    @Test
    void enterOrTabPutsTheSelectedWordInPlaceOfThePrefix() {
        final Fixture f = new Fixture("select * from tr");
        f.ctrlSpace();
        f.press(KeyEvent.VK_DOWN);
        f.press(KeyEvent.VK_ENTER);
        assertThat(f.area.getText()).isEqualTo("select * from trips");
        assertThat(f.popup().isOpen()).isFalse();

        final Fixture g = new Fixture("SELECT pr");
        g.ctrlSpace();
        g.press(KeyEvent.VK_TAB);
        assertThat(g.area.getText()).isEqualTo("SELECT price");
        assertThat(g.area.getCaretPosition()).isEqualTo("SELECT price".length());
    }

    /// A double-click on the list puts the selected word in; a single click does not.
    @Test
    void aDoubleClickPutsTheWordIn() {
        final Fixture f = new Fixture("from tr");
        f.ctrlSpace();
        f.click(1);
        assertThat(f.area.getText()).isEqualTo("from tr");
        assertThat(f.popup().isOpen()).isTrue();
        f.click(2);
        assertThat(f.area.getText()).isEqualTo("from trades");
        assertThat(f.popup().isOpen()).isFalse();
    }

    /// Down from the last word goes to the first and Up from the first to the last; Page Down and Page Up move by
    /// the list's visible row count and stop at the ends; End and Home go to them.
    @Test
    void theKeysMoveTheSelection() {
        final Fixture f = new Fixture("");
        f.ctrlSpace();
        final JList<Completion> list = f.list();
        final int last = list.getModel().getSize() - 1;
        final int rows = list.getVisibleRowCount();
        assertThat(last).isGreaterThan(2 * rows);
        f.press(KeyEvent.VK_UP);
        assertThat(list.getSelectedIndex()).isEqualTo(last);
        f.press(KeyEvent.VK_DOWN);
        assertThat(list.getSelectedIndex()).isZero();
        f.press(KeyEvent.VK_DOWN);
        assertThat(list.getSelectedIndex()).isEqualTo(1);
        f.press(KeyEvent.VK_PAGE_DOWN);
        assertThat(list.getSelectedIndex()).isEqualTo(1 + rows);
        f.press(KeyEvent.VK_PAGE_UP);
        f.press(KeyEvent.VK_PAGE_UP);
        assertThat(list.getSelectedIndex()).isZero();
        f.press(KeyEvent.VK_END);
        assertThat(list.getSelectedIndex()).isEqualTo(last);
        f.press(KeyEvent.VK_PAGE_DOWN);
        assertThat(list.getSelectedIndex()).isEqualTo(last);
        f.press(KeyEvent.VK_HOME);
        assertThat(list.getSelectedIndex()).isZero();
        assertThat(f.area.getText()).isEmpty();
    }

    /// While the list shows it holds Enter, Escape and the moving keys; Escape closes it, and the area's own bindings
    /// of those keys, inherited or its own, are what they were before it opened. A focus loss closes it the same way.
    @Test
    void escapeOrAFocusLossClosesAndGivesTheKeysBack() {
        final Fixture f = new Fixture("sel");
        final InputMap map = f.area.getInputMap(JComponent.WHEN_FOCUSED);
        final Map<Integer, Object> before = f.bindings();
        final Set<KeyStroke> own = own(map);
        f.ctrlSpace();
        assertThat(f.bindings()).isNotEqualTo(before);
        f.press(KeyEvent.VK_ESCAPE);
        assertThat(f.popup().isOpen()).isFalse();
        assertThat(f.area.getText()).isEqualTo("sel");
        assertThat(f.bindings()).isEqualTo(before);
        assertThat(own(map)).isEqualTo(own);

        f.ctrlSpace();
        assertThat(f.popup().isOpen()).isTrue();
        for (final FocusListener l : f.area.getFocusListeners()) {
            l.focusLost(new FocusEvent(f.area, FocusEvent.FOCUS_LOST));
        }
        assertThat(f.popup().isOpen()).isFalse();
        assertThat(f.bindings()).isEqualTo(before);
        assertThat(own(map)).isEqualTo(own);
    }

    /// A letter typed alone in a block that reads as SQL starts the pause after which the list opens on its own; in
    /// prose it does not, nor does a digit, a word pasted whole or a space after the letter.
    @Test
    void aLetterTypedInSqlStartsThePause() {
        final Fixture sql = new Fixture("");
        sql.type("s");
        assertThat(sql.popup().pending()).isTrue();
        sql.type(" ");
        assertThat(sql.popup().pending()).isFalse();
        sql.type("1");
        assertThat(sql.popup().pending()).isFalse();
        sql.area.replaceSelection("select");
        assertThat(sql.popup().pending()).isFalse();
        assertThat(sql.popup().isOpen()).isFalse();

        final Fixture prose = new Fixture("", false);
        prose.type("s");
        assertThat(prose.popup().pending()).isFalse();
    }

    /// The area's own ⌃Space binding, and its lack of one, come back on uninstall, with the look and feel listener
    /// removed; a second uninstall changes nothing.
    @Test
    void uninstallGivesCtrlSpaceBackAndMayBeRepeated() {
        final int listeners = UIManager.getPropertyChangeListeners().length;
        final RSyntaxTextArea area = new RSyntaxTextArea();
        final InputMap map = area.getInputMap(JComponent.WHEN_FOCUSED);
        map.put(CTRL_SPACE, "mine");
        final SqlCompletion c = new SqlCompletion();
        c.install(area, () -> true);
        assertThat(map.get(CTRL_SPACE)).isNotEqualTo("mine");
        assertThat(action(area, CTRL_SPACE).isEnabled()).isTrue();
        assertThat(UIManager.getPropertyChangeListeners().length).isEqualTo(listeners + 1);
        c.uninstall();
        c.uninstall();
        assertThat(map.get(CTRL_SPACE)).isEqualTo("mine");
        assertThat(UIManager.getPropertyChangeListeners().length).isEqualTo(listeners);

        final RSyntaxTextArea plain = new RSyntaxTextArea();
        final Set<KeyStroke> own = own(plain.getInputMap(JComponent.WHEN_FOCUSED));
        c.install(plain, () -> true);
        c.uninstall();
        assertThat(own(plain.getInputMap(JComponent.WHEN_FOCUSED))).isEqualTo(own);
    }

    private static Set<KeyStroke> own(final InputMap map) {
        return map.keys() == null ? Set.of() : Arrays.stream(map.keys()).collect(Collectors.toSet());
    }

    /// The action the area binds to `key`, found the way the shell forwards a key to the focused editor.
    private static Action action(final JComponent area, final KeyStroke key) {
        final Object name = area.getInputMap(JComponent.WHEN_FOCUSED).get(key);
        return area.getActionMap().get(name);
    }

    /// An editor's text area holding `text`, the caret at its end, with completion on [#SCHEMA].
    private static final class Fixture {

        private final RSyntaxTextArea area = new RSyntaxTextArea();
        private final SqlCompletion completion = new SqlCompletion();

        Fixture(final String text) {
            this(text, true);
        }

        Fixture(final String text, final boolean inSql) {
            completion.setSchema(SCHEMA);
            completion.install(area, () -> inSql);
            area.setText(text);
            area.setCaretPosition(text.length());
        }

        CompletionPopup popup() {
            return completion.popup();
        }

        JList<Completion> list() {
            return completion.popup().list();
        }

        List<String> shown() {
            final JList<Completion> list = list();
            return IntStream.range(0, list.getModel().getSize())
                    .mapToObj(i -> list.getModel().getElementAt(i).text()).toList();
        }

        void ctrlSpace() {
            fire(CTRL_SPACE);
        }

        void press(final int keyCode) {
            fire(KeyStroke.getKeyStroke(keyCode, 0));
        }

        /// Types `text` one character at a time at the caret.
        void type(final String text) {
            for (final char ch : text.toCharArray()) {
                area.replaceSelection(String.valueOf(ch));
            }
        }

        void click(final int count) {
            final MouseEvent e = new MouseEvent(
                    list(), MouseEvent.MOUSE_CLICKED, 0L, 0, 1, 1, count, false, MouseEvent.BUTTON1);
            for (final MouseListener l : list().getMouseListeners()) {
                l.mouseClicked(e);
            }
        }

        /// What the area binds each of the list's keys to, inherited bindings included.
        Map<Integer, Object> bindings() {
            final InputMap map = area.getInputMap(JComponent.WHEN_FOCUSED);
            final Map<Integer, Object> out = new HashMap<>();
            for (final int key : LIST_KEYS) {
                out.put(key, map.get(KeyStroke.getKeyStroke(key, 0)));
            }
            return out;
        }

        private void fire(final KeyStroke key) {
            final Action a = action(area, key);
            assertThat(a).as("bound: %s", key).isNotNull();
            a.actionPerformed(new ActionEvent(area, ActionEvent.ACTION_PERFORMED, ""));
        }
    }
}
