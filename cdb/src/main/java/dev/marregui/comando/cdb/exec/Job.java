package dev.marregui.comando.cdb.exec;

/**
 * The partial results of one operator's work over a {@link FrameSource}'s frames, one per frame, run on a
 * {@link QueryPool}. The consumer reads them with {@link #await(int)} in frame order, never completion order, so the
 * merged answer is the same bits whatever the pool's size. A frame the pool has not started when it is awaited runs on
 * the awaiting thread, so a query makes progress with an empty or busy pool and a frame never waits on the pool.
 * Used by one consumer thread.
 *
 * @param <P> a frame's partial result
 */
public interface Job<P> extends AutoCloseable {

    /** The number of frames. */
    int frames();

    /**
     * Frame {@code frame}'s partial result, waiting for it or running it here. A frame's failure ({@code Error}
     * included) stops the frames after it that have not started, while the frames before it still run and are
     * answered; awaited in frame order, the failure thrown is the lowest failed frame's, as in a serial run, and once
     * thrown every later await throws it. A cancelled query's await throws the context's "query cancelled" failure,
     * and an await after {@link #close()} is refused. Each frame is awaited once: the job lets go of a frame's result
     * when it hands it out, so the partials an operator has merged are freed while later frames run.
     */
    P await(int frame);

    /**
     * Stops starting frames and returns once no frame of this job runs, the frames not yet started skipped;
     * idempotent. Never throws.
     */
    @Override
    void close();
}
