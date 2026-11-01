package dev.marregui.comando.ui.widgets.transcript.render;

import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.List;
import javax.swing.SwingUtilities;

import org.junit.jupiter.api.Test;

import dev.marregui.comando.core.transcript.Block;

import static org.assertj.core.api.Assertions.assertThat;

/// [Finder] finds the find's text in the texts the transcript's boxes show.
/// - a match's offsets index the box's own text, also after a char whose lowercase is longer than itself
/// - case is folded as `equalsIgnoreCase` folds it, so a final sigma and a dotted capital I match their other forms
class FinderTest {

    /// In `"İstanbul: find me"` a find of "find" matches at 10, and the highlighted range is `find`: `İ` (U+0130)
    /// lowercases to two chars in a whole-string `toLowerCase`, which would move the match one char late.
    @Test
    void aMatchIndexesTheBoxTextAfterACharWhoseLowercaseIsLonger() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            final TextBox box = new TextBox(new Rich(null, null).append("İstanbul: find me"));
            final Fixed host = new Fixed(List.of(new Boxes(List.of(box))));
            final Finder finder = new Finder(host);
            assertThat(finder.find("find", true)).isEqualTo(1);
            host.mark(finder);
            assertThat(finder.matches()).containsExactly(new int[]{0, 0, 10});
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
            final Fixed host = new Fixed(List.of(new Boxes(List.of(greek, turkish))));
            final Finder finder = new Finder(host);
            assertThat(finder.find("οδος", true)).isEqualTo(1);
            host.mark(finder);
            assertThat(finder.finds(greek)).hasSize(1);
            assertThat(finder.find("istanbul", true)).isEqualTo(1);
            host.mark(finder);
            assertThat(finder.finds(turkish).getFirst()).containsExactly(0, 8);
        });
    }

    /** An item that shows fixed boxes, one under another, and opens and closes nothing. */
    private static final class Boxes extends Item {
        private final List<TextBox> boxes;

        Boxes(final List<TextBox> boxes) {
            super(new Block.Note(Block.NoteKind.INFO, "", ""));
            this.boxes = boxes;
        }

        @Override
        int doLayout(final int w, final Style s) {
            return 0;
        }

        @Override
        void paint(final Graphics2D g, final int x, final int y, final Rectangle clip, final Marks marks) {
        }

        @Override
        void collect(final int x, final int y, final List<Placed> out) {
            for (int i = 0; i < boxes.size(); i++) {
                out.add(new Placed(boxes.get(i), x, y + 20 * i, this));
            }
        }

        @Override
        void findTexts(final List<String> out) {
            boxes.forEach(b -> out.add(b.text()));
        }

        @Override
        String text() {
            return String.join("\n", boxes.stream().map(TextBox::text).toList());
        }
    }

    /** A host of fixed items that never changes. */
    private record Fixed(List<Item> items) implements Finder.Host {

        /** Places every item, as painting the canvas does, so the finder marks their boxes. */
        void mark(final Finder finder) {
            finder.startPlacing();
            for (int i = 0; i < items.size(); i++) {
                finder.placed(i, place(i));
            }
        }

        @Override
        public Item parentOf(final Item item) {
            return null;
        }

        @Override
        public void foldChanged(final Item item) {
        }

        @Override
        public void structureChanged() {
        }

        @Override
        public List<Placed> place(final int index) {
            final List<Placed> out = new ArrayList<>();
            items.get(index).collect(0, 0, out);
            return out;
        }

        @Override
        public long version() {
            return 0;
        }

        @Override
        public void scrollTo(final int y) {
        }

        @Override
        public void repaint() {
        }
    }
}
