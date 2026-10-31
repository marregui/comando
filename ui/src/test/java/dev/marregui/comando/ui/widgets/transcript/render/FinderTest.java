package dev.marregui.comando.ui.widgets.transcript.render;

import org.junit.jupiter.api.Test;

import javax.swing.SwingUtilities;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/// [Finder] finds the find's text in the transcript's boxes.
/// - a match's offsets index the box's own text, also after a char whose lowercase is longer than itself
/// - case is folded as `equalsIgnoreCase` folds it, so a final sigma and a dotted capital I match their other forms
class FinderTest {

    /// In `"İstanbul: find me"` a find of "find" matches at 10, and the highlighted range is `find`: `İ` (U+0130)
    /// lowercases to two chars in a whole-string `toLowerCase`, which would move the match one char late.
    @Test
    void aMatchIndexesTheBoxTextAfterACharWhoseLowercaseIsLonger() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            final TextBox box = new TextBox(new Rich(null, null).append("İstanbul: find me"));
            final Finder finder = new Finder(new Fixed(List.of(new Placed(box, 0, 0, null))));
            assertThat(finder.find("find", true)).isEqualTo(1);
            final List<int[]> finds = finder.finds(box);
            assertThat(finds).hasSize(1);
            assertThat(finds.getFirst()).containsExactly(10, 14);
            assertThat(box.text().substring(finds.getFirst()[0], finds.getFirst()[1])).isEqualTo("find");
        });
    }

    /// `"ΟΔΟΣ"` is found by `"οδος"`, typed with a final sigma, and `"İstanbul"` by `"istanbul"`: each char is folded
    /// through upper case, then lower, so both forms of a letter meet.
    @Test
    void aFindMatchesEveryCaseFormOfALetter() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            final TextBox greek = new TextBox(new Rich(null, null).append("ΟΔΟΣ"));
            final TextBox turkish = new TextBox(new Rich(null, null).append("İstanbul"));
            final Finder finder = new Finder(
                    new Fixed(List.of(new Placed(greek, 0, 0, null), new Placed(turkish, 0, 20, null))));
            assertThat(finder.find("οδος", true)).isEqualTo(1);
            assertThat(finder.finds(greek)).hasSize(1);
            assertThat(finder.find("istanbul", true)).isEqualTo(1);
            assertThat(finder.finds(turkish).getFirst()).containsExactly(0, 8);
        });
    }

    /** A host whose boxes are fixed and which has no items to open. */
    private record Fixed(List<Placed> placed) implements Finder.Host {

        @Override
        public List<Item> items() {
            return List.of();
        }

        @Override
        public Item parentOf(final Item item) {
            return null;
        }

        @Override
        public List<Placed> placedExactly() {
            return placed;
        }

        @Override
        public void foldChanged(final Item item) {
        }

        @Override
        public void relayout() {
        }

        @Override
        public void structureChanged() {
        }

        @Override
        public void scrollTo(final int y) {
        }

        @Override
        public void repaint() {
        }
    }
}
