package dev.marregui.comando.cdb;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntConsumer;

import org.junit.jupiter.api.Test;

import dev.marregui.comando.cdb.exec.Cursor;
import dev.marregui.comando.cdb.exec.Field;
import dev.marregui.comando.cdb.exec.Page;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// A result drained from an offset, across pages of any size, and a drain that gives up when asked to.
class ResultOffsetTest {

    /// Rows 0 to 11 in three pages of four: an offset of 6 skips one page and two rows of the next.
    @Test
    void offsetSkipsRowsAcrossPages() {
        final CdbResult result = CdbResult.drain(new Pages(4, 4, 4), 6L, 0, null);

        assertThat(values(result)).containsExactly(6L, 7L, 8L, 9L, 10L, 11L);
    }

    /// `maxRows` counts the rows after the skipped ones.
    @Test
    void maxRowsCountsAfterTheOffset() {
        final CdbResult result = CdbResult.drain(new Pages(4, 4, 4), 3L, 5, null);

        assertThat(values(result)).containsExactly(3L, 4L, 5L, 6L, 7L);
        assertThat(result.truncated()).isTrue();
    }

    /// An offset past the last row leaves an empty result with the cursor's columns.
    @Test
    void offsetPastTheEndLeavesNoRows() {
        final CdbResult result = CdbResult.drain(new Pages(4, 4), 100L, 0, null);

        assertThat(result.rowCount()).isZero();
        assertThat(result.columnCount()).isEqualTo(2);
    }

    /// A cancel set before the first page ends the drain with the cursor closed.
    @Test
    void aCancelledQueryThrowsAndClosesTheCursor() {
        final CdbCancel cancel = new CdbCancel();
        cancel.cancel();
        final Pages pages = new Pages(4, 4);

        assertThatThrownBy(() -> CdbResult.drain(pages, 0L, 0, cancel))
                .isInstanceOf(CdbException.class)
                .hasMessageContaining("query cancelled");
        assertThat(pages.closes.get()).isEqualTo(1);
    }

    /// A cancel set while the second page is produced stops the drain at that page.
    @Test
    void aCancelBetweenPagesStopsTheDrain() {
        final CdbCancel cancel = new CdbCancel();
        final Pages pages = new Pages(4, 4, 4);
        pages.onPage = (index) -> {
            if (index == 1) {
                cancel.cancel();
            }
        };

        assertThatThrownBy(() -> CdbResult.drain(pages, 0L, 0, cancel)).isInstanceOf(CdbException.class);
        assertThat(pages.served).isEqualTo(2);
    }

    /// Without a designated timestamp from the planner, the first TIMESTAMP column answers; with one, that column does.
    @Test
    void timestampIndexIsTheDesignatedColumn() {
        final CdbResult result = CdbResult.drain(new Pages(4), 0L, 0, null);

        assertThat(result.timestampIndex()).isEqualTo(1);
        assertThat(result.withTimestampIndex(0).timestampIndex()).isZero();
        assertThat(result.withTimestampIndex(-1).timestampIndex()).isEqualTo(-1);
    }

    private static List<Long> values(final CdbResult result) {
        final ArrayList<Long> values = new ArrayList<>();
        for (int row = 0; row < result.rowCount(); row++) {
            values.add(result.getLong(row, 0));
        }
        return values;
    }

    /// Pages of the given sizes over a LONG column counting from 0, and a TIMESTAMP column; closes are counted.
    private static final class Pages implements Cursor {

        final AtomicInteger closes = new AtomicInteger();
        IntConsumer onPage = _ -> { };
        int served;
        private final Queue<Integer> sizes = new ArrayDeque<>();
        private final List<Field> fields = List.of(new Field("v", CdbType.LONG), new Field("ts", CdbType.TIMESTAMP));
        private long next;

        Pages(final int... sizes) {
            for (final int size : sizes) {
                this.sizes.add(size);
            }
        }

        @Override
        public List<Field> fields() {
            return fields;
        }

        @Override
        public Page next() {
            final Integer size = sizes.poll();
            if (size == null) {
                return null;
            }
            onPage.accept(served++);
            final Page page = Page.allocate(fields);
            for (int i = 0; i < size; i++) {
                page.columns[0].setLong(i, next);
                page.columns[1].setLong(i, next);
                next++;
            }
            page.size = size;
            return page;
        }

        @Override
        public void close() {
            closes.incrementAndGet();
        }
    }
}
