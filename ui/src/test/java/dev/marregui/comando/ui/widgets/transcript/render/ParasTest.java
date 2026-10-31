package dev.marregui.comando.ui.widgets.transcript.render;

import java.util.ArrayList;
import java.util.List;
import javax.swing.SwingUtilities;

import org.junit.jupiter.api.Test;

import dev.marregui.comando.ui.widgets.transcript.doc.Para;

import static org.assertj.core.api.Assertions.assertThat;

/// [Paras] reuses the views of paragraphs that did not change while text streams.
/// - a streamed code block keeps the layouts of its whole lines and lays out the new ones
class ParasTest {

    /// When a code block grows from a partial third line to four whole lines, the first two lines keep their layout
    /// objects, the third is laid out again, and the fourth is added with its text.
    @Test
    void aStreamedCodeBlockKeepsTheLayoutsOfItsWholeLinesAndLaysOutTheNewOnes() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            final Style s = Style.of(0);
            final Para.Code before = new Para.Code("java", "int a = 1;\nint b = 2;\nint c", 0);
            final Para.Code after = new Para.Code("java", "int a = 1;\nint b = 2;\nint c = 3;\nint d = 4;", 0);
            final List<Paras.View> first = Paras.reuse(List.of(before), List.of());
            first.getFirst().layout(800, s);
            final List<Paras.View> grown = Paras.reuse(List.of(after), first);
            grown.getFirst().layout(800, s);

            final List<TextBox.Line> old = box(first.getFirst()).lines();
            final List<TextBox.Line> now = box(grown.getFirst()).lines();
            assertThat(now.get(0).layout()).as("a whole line of the old text: its layout kept").isSameAs(old.get(0).layout());
            assertThat(now.get(1).layout()).isSameAs(old.get(1).layout());
            assertThat(now.get(2).layout()).as("the line that was still arriving: laid out again").isNotSameAs(old.get(2).layout());
            assertThat(now).hasSize(4);
            assertThat(box(grown.getFirst()).rich().text().substring(now.get(3).start(), now.get(3).end())).isEqualTo("int d = 4;");
        });
    }

    private static TextBox box(final Paras.View v) {
        final List<Placed> out = new ArrayList<>();
        v.collect(0, 0, null, out);
        return out.getFirst().box();
    }
}
