package dev.marregui.comando.ui.widgets.search;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import dev.marregui.comando.core.model.SearchHit;
import dev.marregui.comando.core.port.SearchPort;
import dev.marregui.comando.ui.kit.text.HitMarks;

import static org.assertj.core.api.Assertions.assertThat;

/// [SearchHub]: where the Search view's hits are read (record 141).
/// - a file's hit shows in the preview tab, every hit of the file marked, the hit current, the keyboard as asked
/// - a name's hit marks nothing; a conversation's hit shows nothing there
/// - Open keeps the preview tab of the hit's file
/// - F7 and ⇧F7 step the view mounted, and nothing when none is
class SearchHubTest {

    private final List<String> calls = new ArrayList<>();
    private final List<HitMarks> shown = new ArrayList<>();
    private final SearchHub hub = new SearchHub(List::of, () -> SearchPort.NONE, new SearchHub.Previews() {
        @Override
        public void preview(final Path file, final HitMarks marks, final boolean takeKeyboard) {
            calls.add("preview " + file + (takeKeyboard ? " keyboard" : ""));
            shown.add(marks);
        }

        @Override
        public void pin(final Path file) {
            calls.add("pin " + file);
        }
    }, new SearchHub.Opener() {
        @Override
        public void open(final SearchHit hit, final String query) {
            calls.add("open " + hit.source());
        }

        @Override
        public boolean inSession(final String conversation) {
            return false;
        }

        @Override
        public void showInFiles(final SearchHit hit) {
            calls.add("files " + hit.source());
        }
    });

    private static SearchHit line(final String source, final int line, final String snippet, final int at) {
        return new SearchHit(SearchHit.Kind.FILE, source, "", "w", line, snippet, at, 3, 0L);
    }

    /// The hit selected is current among every hit of its file, each found again by its match, where its snippet
    /// puts it and the text before it there; the keyboard stays unless asked for.
    @Test
    void aFilesHitShowsInThePreviewTabWithEveryHitOfTheFile() {
        final SearchHit first = line("/w/notes.md", 2, "the foo here", 4);
        final SearchHit second = line("/w/notes.md", 5, "a FOO there", 2);

        hub.viewer().show(second, List.of(first, second), false);
        hub.viewer().show(first, List.of(first, second), true);

        assertThat(calls).containsExactly("preview " + Path.of("/w/notes.md"),
                "preview " + Path.of("/w/notes.md") + " keyboard");
        assertThat(shown.getFirst()).isEqualTo(new HitMarks(List.of(new HitMarks.Hit(2, "foo", 4, "the "),
                new HitMarks.Hit(5, "FOO", 2, "a ")), 1));
        assertThat(shown.getLast().current()).isZero();
    }

    /// A hit missing from its file's hits is still marked and current.
    @Test
    void aHitOutsideItsFilesHitsIsStillCurrent() {
        final SearchHit other = line("/w/notes.md", 2, "the foo here", 4);
        final SearchHit hit = line("/w/notes.md", 7, "foo", 0);

        hub.viewer().show(hit, List.of(other), false);

        final HitMarks marks = shown.getFirst();
        assertThat(marks.hits()).hasSize(2);
        assertThat(marks.hits().get(marks.current())).isEqualTo(new HitMarks.Hit(7, "foo", 0, ""));
    }

    /// A name's hit shows the file with nothing marked; a conversation's hit shows nothing in the preview tab.
    @Test
    void aNamesHitMarksNothingAndAConversationsShowsNothing() {
        final SearchHit name = new SearchHit(SearchHit.Kind.NAME, "/w/foo.md", "", "w", 0, "foo.md", 0, 3, 0L);
        final SearchHit talk = new SearchHit(SearchHit.Kind.CONVERSATION, "/t/c1.jsonl", "c1", "chat", 0, "a foo",
                2, 3, 0L, "a foo in a message", 2, "", "");

        hub.viewer().show(name, List.of(name), false);
        hub.viewer().show(talk, List.of(talk), true);

        assertThat(calls).containsExactly("preview " + Path.of("/w/foo.md"));
        assertThat(shown.getFirst()).isEqualTo(new HitMarks(List.of(new HitMarks.Hit(0, "", -1, "")), 0));
    }

    /// Open keeps the preview tab of the hit's file.
    @Test
    void openKeepsThePreviewTab() {
        hub.viewer().pin(line("/w/notes.md", 2, "the foo here", 4));

        assertThat(calls).containsExactly("pin " + Path.of("/w/notes.md"));
    }

    /// F7 and ⇧F7 reach the view mounted; with none mounted they do nothing.
    @Test
    void stepsReachTheViewMounted() {
        hub.step(1);
        final List<Integer> steps = new ArrayList<>();
        hub.viewer().onStep(steps::add);
        hub.step(1);
        hub.step(-1);
        hub.viewer().onStep(null);
        hub.step(1);

        assertThat(steps).containsExactly(1, -1);
    }
}
