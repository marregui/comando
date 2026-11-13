package dev.marregui.comando.core.port;

import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

import dev.marregui.comando.core.model.SearchHit;
import dev.marregui.comando.core.model.SearchQuery;

/**
 * Searches what the user reads: the folder tabs' folders (Kit, Brain, Scratchpad) and the stored conversations (record
 * 139). The answer is derived data, so it goes from the searcher to the window that asked and never through the state.
 */
public interface SearchPort {

    /** Takes a search's answer, on the searcher's thread, in batches. */
    interface Sink {

        /** More hits, in the order they were found. */
        void hits(List<SearchHit> hits);

        /** The search ended: {@code cut} when it stopped at the hit limit, {@code failed} sources were not read. */
        void done(boolean cut, int failed);
    }

    /** One place to look: a searcher runs its sources in turn, on its own threads. */
    @FunctionalInterface
    interface Source {

        /**
         * Looks for {@code query}, handing each hit to {@code found} until it answers {@code false}, which ends the
         * scan.
         * {@code ended} turns true when the search was cancelled or replaced; a source checks it between files and
         * every
         * few thousand lines.
         *
         * @return how many files or conversations could not be read
         */
        int scan(SearchQuery query, Predicate<SearchHit> found, BooleanSupplier ended);
    }

    /**
     * Starts {@code query} and returns at once. A new search ends the one running: that one's sink hears nothing more,
     * not even its end.
     */
    void search(SearchQuery query, Sink sink);

    /** Ends the running search, if any; its sink hears nothing more. */
    void cancel();

    /** A searcher with nowhere to look: every search ends at once with no hits. */
    SearchPort NONE = new SearchPort() {
        @Override
        public void search(final SearchQuery query, final Sink sink) {
            sink.done(false, 0);
        }

        @Override
        public void cancel() {
            // nothing runs
        }
    };
}
