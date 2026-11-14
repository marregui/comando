package dev.marregui.comando.cdb.serve;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import dev.marregui.comando.cdb.CdbAppender;
import dev.marregui.comando.cdb.CdbException;

/// An appender that records each call as text, `table(t)`, `long(n=1)`, `at(5)`, and moves the pending calls to
/// `flushed` on `flush()` and on `close()`. Refuses the row whose `at` is the `refuseAt`-th, counted from 1 (0: none).
/// One thread writes; the lists and the close count are safe to read from another.
final class RecordingAppender implements CdbAppender {

    final List<String> flushed = Collections.synchronizedList(new ArrayList<>());
    final List<String> pending = Collections.synchronizedList(new ArrayList<>());
    private final AtomicInteger closes = new AtomicInteger();
    private final int refuseAt;
    private final Runnable onClose;
    private int rows;

    RecordingAppender() {
        this(0);
    }

    RecordingAppender(final int refuseAt) {
        this(refuseAt, () -> { });
    }

    /// As above, `onClose` run on every `close()`, after the flush.
    RecordingAppender(final int refuseAt, final Runnable onClose) {
        this.refuseAt = refuseAt;
        this.onClose = onClose;
    }

    /// How many times `close()` ran.
    int closes() {
        return closes.get();
    }

    @Override
    public CdbAppender table(final String table) {
        pending.add("table(" + table + ')');
        return this;
    }

    @Override
    public CdbAppender symbol(final String column, final String value) {
        pending.add("symbol(" + column + '=' + value + ')');
        return this;
    }

    @Override
    public CdbAppender stringColumn(final String column, final String value) {
        pending.add("string(" + column + '=' + value + ')');
        return this;
    }

    @Override
    public CdbAppender longColumn(final String column, final long value) {
        pending.add("long(" + column + '=' + value + ')');
        return this;
    }

    @Override
    public CdbAppender doubleColumn(final String column, final double value) {
        pending.add("double(" + column + '=' + value + ')');
        return this;
    }

    @Override
    public CdbAppender booleanColumn(final String column, final boolean value) {
        pending.add("boolean(" + column + '=' + value + ')');
        return this;
    }

    @Override
    public CdbAppender timestampColumn(final String column, final long micros) {
        pending.add("timestamp(" + column + '=' + micros + ')');
        return this;
    }

    @Override
    public void at(final long micros) {
        rows++;
        if (rows == refuseAt) {
            throw new CdbException("refuse row [row=" + rows + ']', -1);
        }
        pending.add("at(" + micros + ')');
    }

    @Override
    public void flush() {
        flushed.addAll(pending);
        pending.clear();
    }

    @Override
    public void close() {
        closes.incrementAndGet();
        flush();
        onClose.run();
    }
}
