package dev.marregui.comando.cdb.exec;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;

import dev.marregui.comando.cdb.CdbType;

/** SELECT DISTINCT: each distinct row once, in first-seen order; streams, emitting the new rows of each input page. */
final class DistinctCursor implements Cursor {

    private final Cursor child;
    private final List<Field> fields;
    private final AggKeys seen;
    private boolean closed;

    DistinctCursor(final Cursor child) {
        this.child = child;
        try {
            fields = child.fields();
            final List<CdbType> types = new ArrayList<>();
            for (final Field field : fields) {
                types.add(field.type());
            }
            seen = new AggKeys(types);
        } catch (final RuntimeException e) {
            child.close();
            throw e;
        }
    }

    /**
     * DISTINCT over {@code source}'s frames: each frame's distinct rows, deduplicated again in frame order. The answer
     * is the serial operator's, the same bits whatever the pool's size: a row's first occurrence is the first of its
     * frame, and the frames are read in order. Streams: a frame's new rows go out once the frames before it are read.
     * Owns and closes the source.
     */
    static Cursor parallel(final FrameSource source) {
        return new DistinctCursor(FramePartials.of(source, DistinctCursor::partial));
    }

    /** A frame's partial: its distinct rows in first-seen order. Closes {@code frame}. */
    static List<Page> partial(final Cursor frame) {
        return FramePartials.drain(new DistinctCursor(frame));
    }

    @Override
    public List<Field> fields() {
        return fields;
    }

    @Override
    public Page next() {
        Page page;
        while ((page = child.next()) != null) {
            final Page out = Page.allocate(fields);
            for (int i = 0; i < page.size; i++) {
                final int before = seen.size();
                if (seen.groupOf(page.columns, i) == before) {
                    for (int c = 0; c < out.columns.length; c++) {
                        out.columns[c].copy(page.columns[c], i, out.size);
                    }
                    out.size++;
                }
            }
            if (out.size > 0) {
                return out;
            }
        }
        return null;
    }

    @Override
    public void close() {
        if (!closed) {
            closed = true;
            child.close();
        }
    }

    /**
     * The pages of per-frame partial results, frame after frame in frame order, never completion order: the first
     * {@link #next} starts the job, and a frame's partial is awaited only once the pages of the frames before it are
     * handed out, so the consumer goes on while later frames still run. Owns the job and the frames: closing it closes
     * the job, then runs the release.
     */
    static final class FramePartials implements Cursor {

        private final List<Field> fields;
        private final Runnable release;
        private final Supplier<Job<List<Page>>> start;
        private boolean closed;
        private int frame;
        private Job<List<Page>> job;
        private List<Page> pages;
        private int pos;

        /** {@code start} submits the job of partials; {@code release} frees the frames after the job is closed. */
        FramePartials(final List<Field> fields, final Supplier<Job<List<Page>>> start, final Runnable release) {
            this.fields = fields;
            this.start = start;
            this.release = release;
        }

        /** The partials {@code step} makes of each of {@code source}'s frames, on the source's pool. */
        static FramePartials of(final FrameSource source, final Function<Cursor, List<Page>> step) {
            return new FramePartials(source.fields(), () -> source.submit(frame -> step.apply(source.open(frame))),
                    source::close);
        }

        /** Every page of {@code cursor}, then closes it. */
        static List<Page> drain(final Cursor cursor) {
            final List<Page> out = new ArrayList<>();
            try (cursor) {
                Page page;
                while ((page = cursor.next()) != null) {
                    out.add(page);
                }
            }
            return out;
        }

        @Override
        public List<Field> fields() {
            return fields;
        }

        @Override
        public Page next() {
            while (!closed) {
                if (pages != null && pos < pages.size()) {
                    return pages.get(pos++);
                }
                pages = null;
                if (job == null) {
                    job = start.get();
                }
                if (frame >= job.frames()) {
                    return null;
                }
                pages = job.await(frame++);
                pos = 0;
            }
            return null;
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            final Job<List<Page>> running = job;
            job = null;
            pages = null;
            try {
                if (running != null) {
                    running.close();
                }
            } finally {
                release.run();
            }
        }
    }
}
