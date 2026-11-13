package dev.marregui.comando.ui.widgets.search;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.IntConsumer;
import java.util.function.Supplier;

import dev.marregui.comando.core.model.FolderRoot;
import dev.marregui.comando.core.model.SearchHit;
import dev.marregui.comando.core.port.SearchPort;
import dev.marregui.comando.ui.kit.text.HitMarks;
import dev.marregui.comando.ui.kit.text.Previews;

/**
 * What the Search view (record 139) shares with the rest of comando, and keeps while comando runs: the text, the
 * toggles, where a file's hit is read (the preview tab, record 141), and what opens a hit. The view's widget comes and
 * goes with the view; this stays, so the view opened again shows the search it showed. EDT only.
 */
public final class SearchHub {

    private final Supplier<List<FolderRoot>> folders;
    private final Opener opener;
    private final Supplier<SearchPort> port;
    private final Viewer viewer;
    private boolean conversations = true;
    private boolean files = true;
    private Listener listener;
    private String text = "";

    public SearchHub(
            final Supplier<List<FolderRoot>> folders,
            final Supplier<SearchPort> port,
            final Previews previews,
            final Opener opener
    ) {
        this.folders = Objects.requireNonNull(folders, "folders");
        this.port = Objects.requireNonNull(port, "port");
        this.viewer = new Viewer(Objects.requireNonNull(previews, "previews"));
        this.opener = Objects.requireNonNull(opener, "opener");
    }

    /**
     * Searches {@code selection} when it is not blank, else the text last searched, in the view mounted; a view not
     * mounted reads it as it mounts.
     */
    public void ask(final String selection) {
        if (!selection.isBlank()) {
            text = selection;
        }
        if (listener != null) {
            listener.ask(text);
        }
    }

    /** F7 (+1) and ⇧F7 (-1): the view mounted selects the next or the previous hit, which the preview tab shows. */
    public void step(final int by) {
        viewer.step(by);
    }

    Viewer viewer() {
        return viewer;
    }

    Supplier<List<FolderRoot>> folders() {
        return folders;
    }

    Supplier<SearchPort> port() {
        return port;
    }

    Opener opener() {
        return opener;
    }

    String text() {
        return text;
    }

    void text(final String next) {
        text = next;
    }

    boolean files() {
        return files;
    }

    void files(final boolean on) {
        files = on;
    }

    boolean conversations() {
        return conversations;
    }

    void conversations(final boolean on) {
        conversations = on;
    }

    /** The view mounted last: it hears what comando asks, and a view mounted before it no longer does. */
    void listener(final Listener next) {
        listener = next;
    }

    /** Whether {@code view} is the one mounted last; it lets go as it unmounts, another view leaves it be. */
    boolean holds(final Listener view) {
        return listener == view;
    }

    /** Opens what a hit points at in comando. */
    public interface Opener {

        /** Opens {@code hit}, found by {@code query}: a file in a tab that stays, a conversation in its session. */
        void open(SearchHit hit, String query);

        /** Whether a session of this instance holds {@code conversation}. */
        boolean inSession(String conversation);

        /** Shows the file of {@code hit} in the folder tab that holds it. */
        void showInFiles(SearchHit hit);
    }

    /** The view mounted: what it does when comando asks it to search. */
    interface Listener {

        /** Searches {@code text}, with the keyboard in the field. */
        void ask(String text);
    }

    /** Where the view's hits are read: a file's in the preview tab, every hit of the file marked. */
    static final class Viewer {

        private final Previews previews;
        private IntConsumer step;

        private Viewer(final Previews previews) {
            this.previews = previews;
        }

        /**
         * Shows the file of {@code hit} in the preview tab, every hit of {@code sameSource} marked and {@code hit}
         * current; {@code takeKeyboard} gives the tab the keyboard. A conversation's hit has no text to show there:
         * its row's tooltip reads it.
         */
        void show(final SearchHit hit, final List<SearchHit> sameSource, final boolean takeKeyboard) {
            if (hit.kind() == SearchHit.Kind.CONVERSATION) {
                return;
            }
            final List<HitMarks.Hit> marks = new ArrayList<>(sameSource.size() + 1);
            int current = -1;
            for (final SearchHit h : sameSource) {
                if (h.equals(hit)) {
                    current = marks.size();
                }
                marks.add(mark(h));
            }
            if (current < 0) {
                current = marks.size();
                marks.add(mark(hit));
            }
            previews.preview(Path.of(hit.source()), new HitMarks(marks, current), takeKeyboard);
        }

        /** Keeps the preview tab of the file of {@code hit}. */
        void pin(final SearchHit hit) {
            previews.pin(Path.of(hit.source()));
        }

        /** What {@link SearchHub#step} does: the view mounted moves its selection; null for nothing. */
        void onStep(final IntConsumer next) {
            step = next;
        }

        /** {@code h} as the preview tab marks it: a name's hit marks nothing, a line's hit its match. */
        private static HitMarks.Hit mark(final SearchHit h) {
            if (h.kind() == SearchHit.Kind.NAME) {
                return new HitMarks.Hit(0, "", -1, "");
            }
            final int start = Math.min(Math.max(h.matchStart(), 0), h.snippet().length());
            return new HitMarks.Hit(h.line(), h.match(), start, h.snippet().substring(0, start));
        }

        private void step(final int by) {
            if (step != null) {
                step.accept(by);
            }
        }
    }
}
