package dev.marregui.comando.app;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import dev.marregui.comando.core.loop.ExecutorStop;
import dev.marregui.comando.core.loop.Threads;
import dev.marregui.comando.core.model.SearchHit;
import dev.marregui.comando.core.model.SearchQuery;
import dev.marregui.comando.core.port.SearchPort;

/**
 * Runs the searches of the search window (record 139), one at a time: a strand runs a search's sources in turn, and a
 * second strand hands its hits to the sink every {@value #BATCH_MILLIS} ms, so a source that goes on reading without a
 * new hit does not hold back the ones it found. A search belongs to a generation: a new search or a cancel moves the
 * generation on, the sources see {@code ended} turn true, and from then on the sink is not called. A call that passed
 * that check as the generation moved may still be finishing, since no lock is held across a sink; a window that must
 * not see it checks again on its own thread.
 */
public final class Searcher implements SearchPort, AutoCloseable {

    /**
     * The hits one search lists at most, set by record 139: more than anyone reads, few enough to list again on each
     * batch.
     */
    public static final int MAX_HITS = 1_000;
    /**
     * Files read at once: half the cores, so the rest stay for the window and the sessions while a search runs.
     * Unmeasured as a split; the 15 MB search took 27.6 ms with it (record 139), and timing 1 GB with a quarter and
     * all of them would settle it.
     */
    public static final int SCANNERS = Math.max(1, Runtime.getRuntime().availableProcessors() / 2);

    /** How often the hits found go to the sink, set by record 139; under what the eye reads as a pause. Unmeasured. */
    static final long BATCH_MILLIS = 100L;

    private static final System.Logger LOG = System.getLogger(Searcher.class.getName());

    private final List<Source> sources;
    private final ExecutorService scanning = Threads.strand("search-scan");
    private final ScheduledExecutorService delivering = Threads.timedStrand("search-hits");
    private final AtomicLong generation = new AtomicLong();

    public Searcher(final List<Source> sources) {
        this.sources = List.copyOf(sources);
    }

    @Override
    public void search(final SearchQuery query, final Sink sink) {
        final Run run = new Run(generation.incrementAndGet(), query, sink);
        try {
            scanning.execute(run);
        } catch (final RejectedExecutionException _) {
            LOG.log(System.Logger.Level.DEBUG, "dropped search after close");
        }
    }

    @Override
    public void cancel() {
        generation.incrementAndGet();
    }

    /** Ends the running search and waits for its threads, bounded. */
    @Override
    public void close() {
        cancel();
        ExecutorStop.now("searcher", scanning, delivering);
    }

    /** One search: what it found and has not handed over, held under the run's lock, never across a sink. */
    private final class Run implements Runnable {

        private final long mine;
        private final SearchQuery query;
        private final Sink sink;
        private final Object lock = new Object();
        private List<SearchHit> batch = new ArrayList<>();
        private int count;
        private boolean cut;

        Run(final long mine, final SearchQuery query, final Sink sink) {
            this.mine = mine;
            this.query = query;
            this.sink = sink;
        }

        private boolean ended() {
            return generation.get() != mine;
        }

        @Override
        public void run() {
            if (ended()) {
                return;
            }
            try {
                final ScheduledFuture<?> batches = delivering.scheduleWithFixedDelay(
                        this::handOver, BATCH_MILLIS, BATCH_MILLIS, TimeUnit.MILLISECONDS);
                int failed = 0;
                try {
                    for (final Source source : sources) {
                        if (ended() || wasCut()) {
                            break;
                        }
                        failed += scan(source);
                    }
                } finally {
                    batches.cancel(false);
                }
                final int unread = failed;
                delivering.execute(() -> finish(unread));
            } catch (final RejectedExecutionException _) {
                LOG.log(System.Logger.Level.DEBUG, "ended search on close");
            }
        }

        /** What {@code source} could not read; a source that fails as a whole counts as one. */
        private int scan(final Source source) {
            try {
                return source.scan(query, this::found, this::ended);
            } catch (final RuntimeException e) {
                LOG.log(System.Logger.Level.WARNING,
                        "cannot search source [source=" + source.getClass().getSimpleName() + ']', e);
                return 1;
            }
        }

        private boolean found(final SearchHit hit) {
            if (ended()) {
                return false;
            }
            synchronized (lock) {
                if (count == MAX_HITS) {
                    cut = true;
                    return false;
                }
                count++;
                batch.add(hit);
                return true;
            }
        }

        private boolean wasCut() {
            synchronized (lock) {
                return cut;
            }
        }

        private void handOver() {
            final List<SearchHit> hits;
            synchronized (lock) {
                hits = batch;
                batch = new ArrayList<>();
            }
            if (!hits.isEmpty() && !ended()) {
                sink.hits(hits);
            }
        }

        private void finish(final int failed) {
            handOver();
            if (!ended()) {
                sink.done(wasCut(), failed);
            }
        }
    }
}
