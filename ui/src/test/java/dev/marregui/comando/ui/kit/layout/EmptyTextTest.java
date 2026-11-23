package dev.marregui.comando.ui.kit.layout;

import java.awt.Component;
import java.awt.Container;
import java.util.ArrayList;
import java.util.List;
import javax.swing.DefaultListModel;
import javax.swing.JLabel;
import javax.swing.JList;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import dev.marregui.comando.ui.kit.async.OnEdt;

import static org.assertj.core.api.Assertions.assertThat;

/// [EmptyText] makes a list say what to do while it is empty.
/// - The text shows while the model holds no row, hides with the first row and shows again with the last one gone.
/// - The text is shown as text: markup in it is escaped, never rendered, so an image it names is never loaded.
/// - A text wraps at the sidebar's width: the Machines window's one line stays one line, a long one takes more.
/// - The list answered is the one given.
@ExtendWith(OnEdt.class)
class EmptyTextTest {

    private static final String MACHINES = "none: no machine paired yet";

    private final DefaultListModel<String> model = new DefaultListModel<>();
    private final JList<String> list = new JList<>(model);

    /// Empty at first it shows its text; a row hides it, replacing the row keeps it hidden, removing the row shows it
    /// again, and clearing the model shows it after a row was added again.
    @Test
    void theTextShowsWhileTheListIsEmptyOnly() {
        assertThat(EmptyText.over(list, MACHINES)).as("the list given").isSameAs(list);
        assertThat(shown(list)).as("empty").hasSize(1);
        model.addElement("a");
        assertThat(shown(list)).as("one row").isEmpty();
        model.setElementAt("b", 0);
        assertThat(shown(list)).as("a row replaced").isEmpty();
        model.removeElementAt(0);
        assertThat(shown(list)).as("the last row gone").hasSize(1);
        model.addElement("a");
        model.clear();
        assertThat(shown(list)).as("cleared").hasSize(1);
    }

    /// A text that names an image shows as the characters it is made of: no tag reaches the label's HTML.
    @Test
    void markupInTheTextIsEscaped() {
        final String text = hint("<img src='http://example.invalid/x.png'>").getText();
        assertThat(text).doesNotContain("<img").contains("&lt;img");
    }

    /// The Machines window's text fits one line, as it did before the sidebar's wrapping was shared; a text five
    /// times as long takes more.
    @Test
    void aTextWrapsAtTheSidebarsWidth() {
        final int oneLine = hint("x").getPreferredSize().height;
        assertThat(hint(MACHINES).getPreferredSize().height).as("the Machines text").isEqualTo(oneLine);
        final JLabel longer = hint(MACHINES.repeat(5));
        assertThat(longer.getPreferredSize().height).as("a long text").isGreaterThan(oneLine);
        assertThat(longer.getPreferredSize().width).as("wrapped at the sidebar's width")
                .isLessThan(EmptyText.WIDTH_PX * 2);
    }

    /// The labels the list shows, visible now, outermost first.
    private static List<JLabel> shown(final Container c) {
        final List<JLabel> out = new ArrayList<>();
        for (final Component child : c.getComponents()) {
            if (child.isVisible() && child instanceof JLabel l) {
                out.add(l);
            } else if (child.isVisible() && child instanceof Container sub) {
                out.addAll(shown(sub));
            }
        }
        return out;
    }

    /// The label that says `text` over a list of its own.
    private static JLabel hint(final String text) {
        final JList<String> own = EmptyText.over(new JList<>(new DefaultListModel<>()), text);
        return shown(own).getFirst();
    }
}
