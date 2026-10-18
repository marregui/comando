package dev.marregui.comando.ui.widgets.transcript.render;

import dev.marregui.comando.ui.widgets.transcript.doc.Para;
import org.junit.jupiter.api.Test;

import javax.swing.SwingUtilities;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ParasTest {

    @Test
    void aStreamedCodeBlockKeepsTheLayoutsOfItsWholeLinesAndLaysOutTheNewOnes() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            Style s = Style.of(0);
            Para.Code before = new Para.Code("java", "int a = 1;\nint b = 2;\nint c", 0);
            Para.Code after = new Para.Code("java", "int a = 1;\nint b = 2;\nint c = 3;\nint d = 4;", 0);
            List<Paras.View> first = Paras.reuse(List.of(before), List.of());
            first.getFirst().layout(800, s);
            List<Paras.View> grown = Paras.reuse(List.of(after), first);
            grown.getFirst().layout(800, s);

            List<TextBox.Line> old = box(first.getFirst()).lines();
            List<TextBox.Line> now = box(grown.getFirst()).lines();
            assertThat(now.get(0).layout()).as("a whole line of the old text: its layout kept").isSameAs(old.get(0).layout());
            assertThat(now.get(1).layout()).isSameAs(old.get(1).layout());
            assertThat(now.get(2).layout()).as("the line that was still arriving: laid out again").isNotSameAs(old.get(2).layout());
            assertThat(now).hasSize(4);
            assertThat(box(grown.getFirst()).rich().text().substring(now.get(3).start(), now.get(3).end())).isEqualTo("int d = 4;");
        });
    }

    private static TextBox box(Paras.View v) {
        List<Placed> out = new ArrayList<>();
        v.collect(0, 0, null, out);
        return out.getFirst().box();
    }
}
