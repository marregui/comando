package dev.marregui.comando.cdb.exec;

import java.util.ArrayList;
import java.util.List;

/**
 * A {@link FrameSource}'s rows as one cursor, frame after frame in frame order: what an operator that streams its
 * input reads. Frames ahead of the one being read run on the pool, as one job of all the source's frames, so memory
 * stays bounded and the order is the serial scan's. Owns and closes its source.
 *
 * <p>A frame's partial is its pages; the job's window ({@link QueryPool}) lets the workers run at most the pool's
 * threads plus one frames past the one being read, so at most that many frames' pages are held. A close stops the
 * frames not started and waits for the ones running before it closes the source. The first failure of a frame closes
 * the job and the source and is thrown by this call and every later one. The pages handed out are the caller's
 * ({@link Page}).</p>
 */
public final class ExchangeCursor implements Cursor {

    private final FrameSource source;
    // submitted at the first next, so a cursor never read costs the pool nothing
    private Job<List<Page>> job;
    private int frame;
    private List<Page> pages;
    private int page;
    private boolean closed;
    private RuntimeException failedRuntime;
    private Error failedError;

    /** The rows of {@code source}; the cursor owns the source. */
    public ExchangeCursor(final FrameSource source) {
        this.source = source;
    }

    @Override
    public List<Field> fields() {
        return source.fields();
    }

    @Override
    public Page next() {
        if (failedRuntime != null) {
            throw failedRuntime;
        }
        if (failedError != null) {
            throw failedError;
        }
        try {
            while (!closed) {
                if (pages != null && page < pages.size()) {
                    // the list lets go of a page handed out, so the frame's pages are freed as they are read
                    return pages.set(page++, null);
                }
                pages = null;
                if (frame >= source.frames()) {
                    close();
                    return null;
                }
                source.context().check();
                if (job == null) {
                    job = source.context().pool().submit(source.frames(), this::pages, source.context());
                }
                pages = job.await(frame);
                page = 0;
                frame++;
            }
            return null;
        } catch (final RuntimeException e) {
            failedRuntime = e;
            release(e);
            throw e;
        } catch (final Error e) {
            failedError = e;
            release(e);
            throw e;
        }
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        pages = null;
        try {
            if (job != null) {
                job.close();
            }
        } finally {
            source.close();
        }
    }

    /** Frame {@code f}'s pages through the segment, on whichever thread runs it. */
    private List<Page> pages(final int f) {
        final List<Page> out = new ArrayList<>();
        try (final Cursor cursor = source.open(f)) {
            Page p;
            while ((p = cursor.next()) != null) {
                out.add(p);
                source.context().check();
            }
        }
        return out;
    }

    /** Closes the job and the source after {@code failure}, which carries any failure of the close. */
    private void release(final Throwable failure) {
        try {
            close();
        } catch (final RuntimeException | Error closeFailure) {
            failure.addSuppressed(closeFailure);
        }
    }
}
