package dev.marregui.comando.cdb.exec;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.IntFunction;

import dev.marregui.comando.cdb.CdbException;

/**
 * The process's query workers: platform daemon threads named {@code cdb-query-<n>}, by default one fewer than the
 * machine's cores, shared by every query of every store. Each submitted {@link Job} is split into its frames; a worker
 * claims one frame of the oldest job with frames left, so concurrent queries share the cores. The pool owns its
 * threads: they start at the first submit and end at {@link #close()}.
 *
 * <p>At most {@link #MAX_JOBS} jobs wait for workers. A job submitted to a full queue, to a pool of no threads or to a
 * closed pool is not queued: its consumer runs every frame itself, at its await. A frame never waits for the pool, so
 * a frame that submits and awaits a job of its own cannot deadlock it.</p>
 */
public final class QueryPool implements AutoCloseable {

    // the jobs waiting for workers: a query has one per parallel operator and the HTTP server admits 64 requests in
    // flight, so four per request; a job that finds the queue full is not offered again and runs its frames on its
    // consumer; not measured
    static final int MAX_JOBS = 256;
    // the longest an idle worker parks: a bound, not a poll, as every submit and the close signal it
    private static final long IDLE_WAIT_NANOS = TimeUnit.SECONDS.toNanos(1L);
    // the close waits this long for a frame in flight on a worker: as the store's applier
    private static final long JOIN_NANOS = TimeUnit.SECONDS.toNanos(10L);
    private static final System.Logger LOG = System.getLogger(QueryPool.class.getName());
    private static final Object SHARED_LOCK = new Object();
    // the n of cdb-query-<n>, over every pool of the process, so a thread dump tells the pools' threads apart
    private static final AtomicInteger THREAD_IDS = new AtomicInteger();
    // guarded by SHARED_LOCK
    private static QueryPool shared;
    // guarded by SHARED_LOCK; -1 is the default size, one fewer than the cores
    private static int configured = -1;

    private final boolean isShared;
    // guarded by lock: the queued jobs, oldest first; a job leaves once it has no frame left to claim
    private final ArrayDeque<PoolJob<?>> jobs = new ArrayDeque<>();
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition available = lock.newCondition();
    private final int threads;
    // guarded by lock: the workers started so far
    private final Thread[] workers;
    // guarded by lock: the workers are started once, at the first submit
    private boolean started;
    // guarded by lock: written by close before it signals the workers, read by them before each frame they take
    private boolean stopped;

    private QueryPool(final int threads, final boolean isShared) {
        this.threads = threads;
        this.isShared = isShared;
        this.workers = new Thread[threads];
    }

    /**
     * Sets the shared pool's size before its first use: {@code threads} workers, 0 for none (every frame runs on its
     * awaiting thread). Fails once the shared pool exists with another size.
     */
    public static void configure(final int threads) {
        if (threads < 0) {
            throw new IllegalArgumentException("refuse negative query threads [threads=" + threads + ']');
        }
        synchronized (SHARED_LOCK) {
            if (shared != null && shared.threads != threads) {
                throw new IllegalStateException("refuse resize of a started query pool [threads=" + shared.threads
                        + ", asked=" + threads + ']');
            }
            configured = threads;
        }
    }

    /** A pool of its own with {@code threads} workers, for tests and embedders; the caller closes it. */
    public static QueryPool of(final int threads) {
        if (threads < 0) {
            throw new IllegalArgumentException("refuse negative query threads [threads=" + threads + ']');
        }
        return new QueryPool(threads, false);
    }

    /** The process's pool, made at the first call with the configured size; its close does nothing. */
    public static QueryPool shared() {
        synchronized (SHARED_LOCK) {
            if (shared == null) {
                shared = new QueryPool(configured >= 0 ? configured : defaultThreads(), true);
            }
            return shared;
        }
    }

    /** The number of workers. */
    public int threads() {
        return threads;
    }

    /**
     * A job of {@code frames} frames, frame {@code i}'s partial result being {@code work.apply(i)}; {@code ctx} is
     * checked before each frame starts. The caller closes the job.
     */
    public <P> Job<P> submit(final int frames, final IntFunction<P> work, final QueryContext ctx) {
        if (frames < 0) {
            throw new IllegalArgumentException("refuse negative frame count [frames=" + frames + ']');
        }
        final PoolJob<P> job = new PoolJob<>(this, frames, work, ctx);
        if (threads > 0 && frames > 0) {
            offer(job);
        }
        return job;
    }

    /**
     * Ends the workers once their frames end, waiting for them up to 10 s; idempotent. The jobs still queued run their
     * frames on their consumers. The shared pool's close does nothing: its daemon threads end with the process.
     */
    @Override
    public void close() {
        if (isShared) {
            return;
        }
        final Thread[] toJoin;
        lock.lock();
        try {
            if (stopped) {
                return;
            }
            // the flag before the wake-up, both under the lock the workers read it under
            stopped = true;
            jobs.clear();
            available.signalAll();
            toJoin = workers.clone();
        } finally {
            lock.unlock();
        }
        join(toJoin);
    }

    /** One fewer than the cores the JVM sees, at least 0. */
    static int defaultThreads() {
        return Math.max(0, Runtime.getRuntime().availableProcessors() - 1);
    }

    // waits for the workers up to JOIN_NANOS in all; an interrupt ends the wait, its status kept
    private static void join(final Thread[] toJoin) {
        final long deadlineNanos = System.nanoTime() + JOIN_NANOS;
        for (final Thread worker : toJoin) {
            if (worker == null || worker == Thread.currentThread()) {
                continue;
            }
            try {
                final long leftNanos = deadlineNanos - System.nanoTime();
                if (leftNanos <= 0L || !worker.join(Duration.ofNanos(leftNanos))) {
                    LOG.log(System.Logger.Level.WARNING, "close query pool, a worker still runs a frame [thread="
                            + worker.getName() + ", waitMillis=" + TimeUnit.NANOSECONDS.toMillis(JOIN_NANOS) + ']');
                }
            } catch (final InterruptedException _) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    // queues the job for the workers, starting them at the first one; a full queue leaves it to its consumer
    private void offer(final PoolJob<?> job) {
        lock.lock();
        try {
            if (stopped) {
                return;
            }
            if (!started) {
                started = true;
                for (int i = 0; i < threads; i++) {
                    final Thread worker = Thread.ofPlatform().daemon()
                            .name("cdb-query-" + THREAD_IDS.getAndIncrement()).unstarted(this::work);
                    workers[i] = worker;
                    worker.start();
                }
            }
            jobs.removeIf(queued -> !queued.isClaimable());
            if (jobs.size() >= MAX_JOBS) {
                return;
            }
            jobs.addLast(job);
            for (int i = Math.min(threads, job.frames()); i > 0; i--) {
                available.signal();
            }
        } finally {
            lock.unlock();
        }
    }

    // the consumer awaited further: workers parked for want of a claimable frame look again
    private void advanced() {
        lock.lock();
        try {
            available.signalAll();
        } finally {
            lock.unlock();
        }
    }

    // a job closed by its consumer leaves the queue at once, so it holds no place there
    private void dequeue(final PoolJob<?> job) {
        lock.lock();
        try {
            jobs.remove(job);
        } finally {
            lock.unlock();
        }
    }

    // a worker's loop: one frame of the oldest job with frames left, until the pool closes
    private void work() {
        PoolJob<?> job;
        while ((job = take()) != null) {
            final int frame = job.claimNext();
            if (frame >= 0) {
                job.run(frame);
            }
        }
    }

    // the oldest queued job with frames left, dropping the ones without; null once the pool closes
    private PoolJob<?> take() {
        lock.lock();
        try {
            while (!stopped) {
                final Iterator<PoolJob<?>> it = jobs.iterator();
                while (it.hasNext()) {
                    final PoolJob<?> job = it.next();
                    if (job.isClaimable()) {
                        return job;
                    }
                    it.remove();
                }
                available.awaitNanos(IDLE_WAIT_NANOS);
            }
            return null;
        } catch (final InterruptedException _) {
            // nothing of cdb's interrupts a worker: an interrupt is taken as the stop signal of this one
            Thread.currentThread().interrupt();
            return null;
        } finally {
            lock.unlock();
        }
    }

    /**
     * One submit's frames. A frame is claimed once, by a worker or by the consumer, through a compare-and-set of its
     * state; workers take the frames in order through the job's claim index, the consumer the frame it awaits. A
     * frame's failure stops the frames after it that have not started, and the frames before it still run, so the
     * consumer, reading in frame order, meets the failure of the lowest failed frame, as a serial run would; a
     * failure of the consumer's own (its cancel, an interrupt) stops every frame.
     */
    private static final class PoolJob<P> implements Job<P> {

        private static final int UNCLAIMED = 0;
        private static final int CLAIMED = 1;
        private static final int DONE = 2;
        private static final VarHandle FAILURE;
        private static final VarHandle NEXT;
        private static final VarHandle RUNNING;
        private static final VarHandle STATE = MethodHandles.arrayElementVarHandle(int[].class);
        // how long the consumer parks before it looks at its cancel again: a cancel wakes no one, so this bounds how
        // late a waiting consumer sees it (the cancel's answer within 50 ms, ParallelLifecycleTest); a frame's end
        // wakes the consumer at once, so the park costs a wake-up per 10 ms of a frame's run, not measured
        private static final long WAIT_NANOS = TimeUnit.MILLISECONDS.toNanos(10L);
        // the frame of a failure of the consumer's own, its cancel or an interrupt: before every frame
        private static final int CONSUMER = -1;

        private final QueryContext ctx;
        private final int frames;
        private final QueryPool pool;
        // results[f] is written by frame f's runner before its DONE state; read by the consumer after it read DONE
        private final Object[] results;
        // per frame UNCLAIMED, CLAIMED or DONE, through STATE: compare-and-set by the claimers (workers, the consumer),
        // DONE set by the frame's runner, read by every one of them
        private final int[] states;
        private final IntFunction<P> work;
        // written by the consumer's close, read by the claimers before a frame starts
        private volatile boolean closed;
        // the failure of the lowest failed frame, or the consumer's own (frame -1), lowered through FAILURE by the
        // runners and the consumer; read by every claimer and the consumer
        private volatile Failure failure;
        // the consumer's: an await threw the failure, so every later await throws it
        private boolean thrown;
        // the claim index: the next frame a worker tries, raised through NEXT by the workers; read by the queue
        private volatile int next;
        // the frame after the last one the consumer awaited: written by the consumer, read by the workers, which claim
        // only frames below it plus the window, so the partials held ahead of the merge stay bounded
        private volatile int awaited;
        // how many frames workers may run past the last one awaited: one per worker and one more, so every worker
        // has a frame while the consumer merges; it bounds the partials held ahead of the merge, an exchange's pages
        // included; set by the design, not measured
        private final int window;
        // the frames entered and not yet left, through RUNNING by their runners; read by close
        private volatile int running;
        // the consumer while it parks for a frame or for the close, else null: written by the consumer, read by the
        // runners after a frame ends
        private volatile Thread waiter;

        static {
            try {
                final MethodHandles.Lookup lookup = MethodHandles.lookup();
                FAILURE = lookup.findVarHandle(PoolJob.class, "failure", Failure.class);
                NEXT = lookup.findVarHandle(PoolJob.class, "next", int.class);
                RUNNING = lookup.findVarHandle(PoolJob.class, "running", int.class);
            } catch (final ReflectiveOperationException e) {
                throw new ExceptionInInitializerError(e);
            }
        }

        PoolJob(final QueryPool pool, final int frames, final IntFunction<P> work, final QueryContext ctx) {
            this.pool = pool;
            this.frames = frames;
            this.work = work;
            this.ctx = ctx;
            this.results = new Object[frames];
            this.states = new int[frames];
            this.window = pool.threads + 1;
        }

        @Override
        public P await(final int frame) {
            if (frame < 0 || frame >= frames) {
                throw new IndexOutOfBoundsException("frame out of range [frame=" + frame + ", frames=" + frames + ']');
            }
            throwIfFailed(frame);
            if (closed) {
                throw new IllegalStateException("refuse await of a closed job [frame=" + frame + ']');
            }
            try {
                ctx.check();
            } catch (final RuntimeException e) {
                fail(CONSUMER, e);
                throwIfFailed(frame);
            }
            if (frame + 1 > awaited) {
                awaited = frame + 1;
                pool.advanced();
            }
            if ((int) STATE.getVolatile(states, frame) == UNCLAIMED && enter(frame)) {
                if (STATE.compareAndSet(states, frame, UNCLAIMED, CLAIMED)) {
                    run(frame);
                } else {
                    leave();
                }
            }
            waitFor(frame);
            throwIfFailed(frame);
            return result(frame);
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            pool.dequeue(this);
            // closed was written before running is read, and a claimer raises running before it reads closed: both
            // volatile, so either the close sees the frame or the frame sees the close
            boolean interrupted = false;
            waiter = Thread.currentThread();
            try {
                while (running > 0) {
                    LockSupport.parkNanos(this, WAIT_NANOS);
                    // a park returns at once while the thread is interrupted: cleared here, restored after the wait
                    interrupted |= Thread.interrupted();
                }
            } finally {
                waiter = null;
                if (interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
        }

        @Override
        public int frames() {
            return frames;
        }

        // a worker's claim of the next unclaimed frame, entered; -1 when none is left or the job stopped
        int claimNext() {
            while (true) {
                final int frame = next;
                if (frame >= frames || frame >= awaited + window || frame >= stopAt()) {
                    return -1;
                }
                if (NEXT.compareAndSet(this, frame, frame + 1)) {
                    if (!enter(frame)) {
                        return -1;
                    }
                    if (STATE.compareAndSet(states, frame, UNCLAIMED, CLAIMED)) {
                        return frame;
                    }
                    // the consumer claimed it first
                    leave();
                }
            }
        }

        // a worker's claim: true when frames are left below the window and the failure, and the job is open; read under
        // the pool's lock
        boolean isClaimable() {
            final int n = next;
            return !closed && n < frames && n < awaited + window && n < stopAt();
        }

        // runs a frame its caller entered and claimed; a failure, Error included, is kept and stops the job
        void run(final int frame) {
            try {
                ctx.check();
                results[frame] = work.apply(frame);
                // the result before the state: whoever reads DONE reads the result
                STATE.setVolatile(states, frame, DONE);
            } catch (final Throwable t) {
                fail(frame, t);
            } finally {
                leave();
            }
        }

        // the first frame that does not start: the failed frame's, 0 after a failure of the consumer's
        private int stopAt() {
            final Failure f = failure;
            return f == null ? Integer.MAX_VALUE : Math.max(0, f.frame);
        }

        // counts a frame in before it starts; false, and counted out, once the job closed or a frame before it failed
        private boolean enter(final int frame) {
            RUNNING.getAndAdd(this, 1);
            if (closed || frame >= stopAt()) {
                leave();
                return false;
            }
            return true;
        }

        // counts a frame out and wakes the consumer; the count and the frame's state are written before waiter is
        // read, and the consumer writes waiter before it reads them: both volatile, so no wake-up is lost
        private void leave() {
            RUNNING.getAndAdd(this, -1);
            final Thread consumer = waiter;
            if (consumer != null) {
                LockSupport.unpark(consumer);
            }
        }

        // parks until the frame is done or the job failed, failing it on a cancel or an interrupt
        private void waitFor(final int frame) {
            waiter = Thread.currentThread();
            try {
                while ((int) STATE.getVolatile(states, frame) != DONE && !failedAt(frame)) {
                    if (Thread.currentThread().isInterrupted()) {
                        fail(CONSUMER, new CdbException("cannot read query frame, interrupted waiting for it [frame="
                                + frame + ']', -1));
                        return;
                    }
                    ctx.check();
                    LockSupport.parkNanos(this, WAIT_NANOS);
                }
            } catch (final RuntimeException e) {
                fail(CONSUMER, e);
            } finally {
                waiter = null;
            }
        }

        // a failure at frame or before it, the consumer's included
        private boolean failedAt(final int frame) {
            final Failure f = failure;
            return f != null && f.frame <= frame;
        }

        // keeps the failure of the lowest failed frame: a later frame's failure, recorded first, gives way to it
        private void fail(final int frame, final Throwable t) {
            final Failure failed = new Failure(frame, t);
            while (true) {
                final Failure current = failure;
                if (current != null && current.frame <= frame) {
                    return;
                }
                if (FAILURE.compareAndSet(this, current, failed)) {
                    return;
                }
            }
        }

        // throws the failure that reaches frame: its own or an earlier frame's, the consumer's, or one already thrown
        private void throwIfFailed(final int frame) {
            final Failure f = failure;
            if (f == null || !thrown && f.frame > frame) {
                return;
            }
            thrown = true;
            final Throwable t = f.cause;
            if (t instanceof final RuntimeException e) {
                throw e;
            }
            if (t instanceof final Error e) {
                throw e;
            }
            // work is an IntFunction and ctx.check throws unchecked only: no checked failure reaches here
            throw new IllegalStateException("fail query frame [failure=" + t + ']', t);
        }

        /** Frame {@code frame}'s result, released by the job: a frame is awaited once, so a merged partial is freed. */
        @SuppressWarnings("unchecked") // results[frame] holds work.apply(frame), a P
        private P result(final int frame) {
            final P result = (P) results[frame];
            results[frame] = null;
            return result;
        }

        // a failure and the frame it came from, CONSUMER for the consumer's own
        private record Failure(int frame, Throwable cause) {
        }
    }
}
