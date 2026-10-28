package dev.marregui.comando.ui.kit.overlay;

import dev.marregui.comando.ui.kit.theme.Theme;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.junit.jupiter.api.Test;

import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.text.Highlighter;
import java.awt.Color;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/// [Flash] draws an amber attention flash that fades out over [Flash#MILLIS].
/// - The amber fades linearly from its start alpha to nothing, keeping its colour.
/// - A new flash on the same component ends the one before; a flash ends on its own.
/// - A line's flash is a text highlight over the caret's line, removed when it ends.
class FlashTest {

    /// [Flash#at] is attention at the start alpha at 0, half the alpha at half time, and transparent at and past the
    /// end, with the RGB unchanged.
    @Test
    void theAmberFadesToNothingOverTwoSeconds() {
        assertThat(Flash.at(0)).isEqualTo(Theme.withAlpha(Theme.ATTENTION, Flash.START_ALPHA));
        assertThat(Flash.at(Flash.MILLIS / 2).getAlpha()).isEqualTo(Flash.START_ALPHA / 2);
        assertThat(Flash.at(Flash.MILLIS).getAlpha()).isZero();
        assertThat(Flash.at(Flash.MILLIS * 3).getAlpha()).isZero();
        Color mid = Flash.at(500);
        assertThat(new int[]{mid.getRed(), mid.getGreen(), mid.getBlue()})
                .containsExactly(Theme.ATTENTION.getRed(), Theme.ATTENTION.getGreen(), Theme.ATTENTION.getBlue());
    }

    /// A flash paints at once; a second start on the same owner runs the first one's end callback at once, and the
    /// second ends by itself within its duration and stops running.
    @Test
    void aNewFlashOnTheSameComponentEndsTheOneBefore() throws Exception {
        JPanel owner = new JPanel();
        List<String> done = new CopyOnWriteArrayList<>();
        List<Integer> alphas = new CopyOnWriteArrayList<>();
        CountDownLatch ended = new CountDownLatch(1);
        SwingUtilities.invokeAndWait(() -> {
            Flash.start(owner, c -> alphas.add(c.getAlpha()), () -> done.add("first"));
            assertThat(alphas).as("painted at once").containsExactly(Flash.START_ALPHA);
            Flash.start(owner, c -> {
            }, () -> {
                done.add("second");
                ended.countDown();
            });
            assertThat(done).containsExactly("first");
            assertThat(Flash.running(owner)).isTrue();
        });
        assertThat(ended.await(Flash.MILLIS + 3_000, TimeUnit.MILLISECONDS)).as("ends on its own").isTrue();
        SwingUtilities.invokeAndWait(() -> assertThat(Flash.running(owner)).isFalse());
        assertThat(done).containsExactly("first", "second");
    }

    /// [Flash#line] adds one highlight spanning the whole line of the offset, newline included, and a newer flash on
    /// the area removes it.
    @Test
    void aLinesFlashIsATextHighlightOverTheCaretsLineGoneWhenItEnds() throws Exception {
        RSyntaxTextArea area = new RSyntaxTextArea("one\ntwo\nthree\n");
        int[] before = new int[1];
        SwingUtilities.invokeAndWait(() -> {
            before[0] = area.getHighlighter().getHighlights().length;
            Flash.line(area, 4);
            Highlighter.Highlight[] now = area.getHighlighter().getHighlights();
            assertThat(now).hasSize(before[0] + 1);
            assertThat(now[now.length - 1].getStartOffset()).as("the line of the offset").isEqualTo(4);
            assertThat(now[now.length - 1].getEndOffset()).isEqualTo(8);
            Flash.start(area, c -> {
            }, () -> {
            });
            assertThat(area.getHighlighter().getHighlights()).as("ended by the newer flash").hasSize(before[0]);
        });
    }
}
