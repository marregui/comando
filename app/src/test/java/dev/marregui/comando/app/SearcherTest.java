package dev.marregui.comando.app;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import dev.marregui.comando.core.loop.Threads;
import dev.marregui.comando.core.model.SearchHit;
import dev.marregui.comando.core.model.SearchQuery;
import dev.marregui.comando.core.port.SearchPort;

import static org.assertj.core.api.Assertions.assertThat;

/// The searcher that runs a search's sources and hands the hits to the window.
/// - sources run in turn, and every hit reaches the sink before the search's end
/// - a second search and a cancel silence the search before them, its end included
/// - a search stops at 1,000 hits and says it was cut; what could not be read is summed, a failed source counted as one
/// - hits reach the sink while a source goes on reading, not only at the end
/// - closing ends a running search and leaves no thread
class SearcherTest {

    private static final Duration WAIT = Duration.ofSeconds(10);
    private static final SearchQuery QUERY = new SearchQuery("plan", List.of(), true);

    private final List<Searcher> searchers = new ArrayList<>();

    @AfterEach
    void closeSearchers() {
        searchers.forEach(Searcher::close);
    }

    /// What a sink heard, in order.
    private static final class Heard implements SearchPort.Sink {
        final List<SearchHit> hits = new CopyOnWriteArrayList<>();
        final List<Integer> batches = new CopyOnWriteArrayList<>();
        final List<String> ends = new CopyOnWriteArrayList<>();
        /// Written by the searcher's thread in the sink; read by the test thread once the search ended.
        volatile boolean hitAfterEnd;

        @Override
        public void hits(final List<SearchHit> found) {
            hitAfterEnd |= !ends.isEmpty();
            hits.addAll(found);
            batches.add(found.size());
        }

        @Override
        public void done(final boolean cut, final int failed) {
            ends.add(cut + "/" + failed);
        }

        void awaitEnd() {
            Awaitility.await("the end").atMost(WAIT).until(() -> !ends.isEmpty());
        }
    }

    private Searcher searcher(final SearchPort.Source... sources) {
        final Searcher s = new Searcher(List.of(sources));
        searchers.add(s);
        return s;
    }

    private static SearchHit hit(final String source) {
        return new SearchHit(SearchHit.Kind.FILE, source, "", "Notes", 1, "plan", 0, 4, 0L);
    }

    /// A source that hands `count` hits named `name` and reports `failed` things not read.
    private static SearchPort.Source giving(final String name, final int count, final int failed) {
        return (query, found, ended) -> {
            for (int i = 0; i < count && found.test(hit(name)); i++) {
                assertThat(ended.getAsBoolean()).isFalse();
            }
            return failed;
        };
    }

    /// A source that gives one hit, then waits to be ended or released; it counts down `reading` once it is in.
    private static final class Waiting implements SearchPort.Source {
        final CountDownLatch reading = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final AtomicBoolean sawTheEnd = new AtomicBoolean();

        @Override
        public int scan(final SearchQuery query, final Predicate<SearchHit> found, final BooleanSupplier ended) {
            found.test(hit("first"));
            reading.countDown();
            try {
                while (!ended.getAsBoolean() && !release.await(5L, TimeUnit.MILLISECONDS)) {
                    Thread.onSpinWait();
                }
            } catch (final InterruptedException _) {
                Thread.currentThread().interrupt();
            }
            sawTheEnd.set(ended.getAsBoolean());
            // a source slow to look at `ended` hands on: its later hits must go nowhere
            found.test(hit("late"));
            return 3;
        }
    }

    /// The sources run in the order given, and the sink hears every hit in that order, then the failures summed.
    @Test
    void sourcesRunInTurnAndTheEndFollowsEveryHit() {
        final Heard heard = new Heard();

        searcher(giving("files", 3, 1), giving("conversations", 2, 2)).search(QUERY, heard);

        heard.awaitEnd();
        assertThat(heard.hits).extracting(SearchHit::source)
                .containsExactly("files", "files", "files", "conversations", "conversations");
        assertThat(heard.ends).containsExactly("false/3");
        assertThat(heard.hitAfterEnd).isFalse();
    }

    /// A second search silences the first: once it is asked for, the first's sink hears no hit and no end, though its
    /// source goes on handing hits, while the second is answered in full.
    @Test
    void aSecondSearchSilencesTheFirst() throws Exception {
        final Waiting first = new Waiting();
        final Searcher searcher = searcher((query, found, ended) -> query.text().equals("plan")
                ? first.scan(query, found, ended) : giving("second", 2, 0).scan(query, found, ended));
        final Heard silenced = new Heard();
        final Heard answered = new Heard();
        searcher.search(QUERY, silenced);
        assertThat(first.reading.await(WAIT.toMillis(), TimeUnit.MILLISECONDS)).isTrue();
        Awaitility.await("the batch").atMost(WAIT).until(() -> !silenced.hits.isEmpty());

        searcher.search(new SearchQuery("budget", List.of(), true), answered);

        answered.awaitEnd();
        assertThat(first.sawTheEnd).as("the first source sees it ended").isTrue();
        assertThat(answered.hits).extracting(SearchHit::source).containsExactly("second", "second");
        assertThat(answered.ends).containsExactly("false/0");
        // the next batch would be out by now: two periods after the second search ended
        TimeUnit.MILLISECONDS.sleep(2L * Searcher.BATCH_MILLIS);
        assertThat(silenced.hits).extracting(SearchHit::source).containsExactly("first");
        assertThat(silenced.ends).isEmpty();
    }

    /// A hit found but not yet handed over when a second search starts is dropped with the first search: without the
    /// generation's check at the hand-over it would reach the first's sink in the next batch.
    @Test
    void aSecondSearchDropsTheFirstsHitsNotYetHandedOver() throws Exception {
        final CountDownLatch holding = new CountDownLatch(1);
        final CountDownLatch go = new CountDownLatch(1);
        final Searcher searcher = searcher((query, found, ended) -> {
            if (query.text().equals("plan")) {
                found.test(hit("unsent"));
                holding.countDown();
                try {
                    go.await(WAIT.toMillis(), TimeUnit.MILLISECONDS);
                } catch (final InterruptedException _) {
                    Thread.currentThread().interrupt();
                }
            }
            return 0;
        });
        final Heard silenced = new Heard();
        final Heard answered = new Heard();

        searcher.search(QUERY, silenced);
        assertThat(holding.await(WAIT.toMillis(), TimeUnit.MILLISECONDS)).isTrue();
        searcher.search(new SearchQuery("budget", List.of(), true), answered);
        go.countDown();

        answered.awaitEnd();
        TimeUnit.MILLISECONDS.sleep(2L * Searcher.BATCH_MILLIS);
        assertThat(silenced.hits).isEmpty();
        assertThat(silenced.ends).isEmpty();
    }

    /// A cancel silences the running search as a new search does, and the searcher answers the next one.
    @Test
    void aCancelSilencesTheRunningSearch() throws Exception {
        final Waiting source = new Waiting();
        final Searcher searcher = searcher(source);
        final Heard silenced = new Heard();
        searcher.search(QUERY, silenced);
        assertThat(source.reading.await(WAIT.toMillis(), TimeUnit.MILLISECONDS)).isTrue();

        searcher.cancel();

        Awaitility.await("the source ended").atMost(WAIT).untilTrue(source.sawTheEnd);
        TimeUnit.MILLISECONDS.sleep(2L * Searcher.BATCH_MILLIS);
        assertThat(silenced.ends).isEmpty();
    }

    /// The hit past the limit cuts the search: 1,000 reach the sink, the source is told to stop, the next source does
    /// not run, and the end says so.
    @Test
    void aSearchStopsAtTheLimitAndSaysItWasCut() {
        final AtomicBoolean ran = new AtomicBoolean();
        final Heard heard = new Heard();

        searcher(giving("files", Searcher.MAX_HITS + 500, 0), (query, found, ended) -> {
            ran.set(true);
            return 0;
        }).search(QUERY, heard);

        heard.awaitEnd();
        assertThat(heard.hits).hasSize(Searcher.MAX_HITS);
        assertThat(heard.ends).containsExactly("true/0");
        assertThat(ran).as("the next source").isFalse();
    }

    /// Exactly as many hits as the limit is not a cut search.
    @Test
    void hitsUpToTheLimitAreNotCut() {
        final Heard heard = new Heard();

        searcher(giving("files", Searcher.MAX_HITS, 0)).search(QUERY, heard);

        heard.awaitEnd();
        assertThat(heard.hits).hasSize(Searcher.MAX_HITS);
        assertThat(heard.ends).containsExactly("false/0");
    }

    /// A hit found reaches the sink within a batch's time while its source goes on reading without another.
    @Test
    void hitsReachTheSinkWhileTheSourceReads() throws Exception {
        final Waiting source = new Waiting();
        final Heard heard = new Heard();

        searcher(source).search(QUERY, heard);

        assertThat(source.reading.await(WAIT.toMillis(), TimeUnit.MILLISECONDS)).isTrue();
        Awaitility.await("a batch before the end").atMost(WAIT).until(() -> !heard.hits.isEmpty());
        assertThat(heard.ends).isEmpty();
        source.release.countDown();
        heard.awaitEnd();
        assertThat(heard.ends).containsExactly("false/3");
    }

    /// Many hits found at once go to the sink as few batches, not one call each.
    @Test
    void hitsGoToTheSinkInBatches() {
        final Heard heard = new Heard();

        searcher(giving("files", 500, 0)).search(QUERY, heard);

        heard.awaitEnd();
        assertThat(heard.hits).hasSize(500);
        assertThat(heard.batches).hasSizeLessThan(10);
    }

    /// A source that throws counts as one not read, and the next source still runs.
    @Test
    void aSourceThatFailsCountsAsOneAndTheNextRuns() {
        final Heard heard = new Heard();

        searcher((query, found, ended) -> {
            throw new IllegalStateException("no such store");
        }, giving("conversations", 1, 0)).search(QUERY, heard);

        heard.awaitEnd();
        assertThat(heard.hits).extracting(SearchHit::source).containsExactly("conversations");
        assertThat(heard.ends).containsExactly("false/1");
    }

    /// With no source a search ends at once with nothing, and after closing a search is dropped without a word.
    @Test
    void aClosedSearcherDropsASearch() {
        final Searcher searcher = searcher();
        final Heard heard = new Heard();
        searcher.search(QUERY, heard);
        heard.awaitEnd();
        assertThat(heard.ends).containsExactly("false/0");

        searcher.close();
        final Heard late = new Heard();
        searcher.search(QUERY, late);

        assertThat(late.ends).isEmpty();
    }

    /// Closing ends a running search: its sink hears nothing more, and no thread of the searcher is alive afterwards.
    @Test
    void closingEndsARunningSearchAndLeavesNoThread() throws Exception {
        final Waiting source = new Waiting();
        final Searcher searcher = searcher(source);
        final Heard heard = new Heard();
        searcher.search(QUERY, heard);
        assertThat(source.reading.await(WAIT.toMillis(), TimeUnit.MILLISECONDS)).isTrue();

        searcher.close();

        Awaitility.await("no thread left").atMost(WAIT)
                .until(() -> Threads.alive("search-scan").isEmpty() && Threads.alive("search-hits").isEmpty());
        assertThat(heard.ends).isEmpty();
    }
}
