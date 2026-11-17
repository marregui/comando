package dev.marregui.comando.cdb.exec;

import com.sun.management.ThreadMXBean;
import java.lang.management.ManagementFactory;
import java.util.List;

import org.junit.jupiter.api.Test;

import dev.marregui.comando.cdb.CdbType;

import static dev.marregui.comando.cdb.exec.RowsFakes.field;
import static org.assertj.core.api.Assertions.assertThat;

/// A page a cursor lends is filled, so its text vectors are sized for every row at once: filling one allocates
/// nothing, where a vector grown from a few values copies its arrays as it fills (record 179).
class FullPageTest {

    private static final SymbolDictionary DICTIONARY = new SymbolDictionary() {
        @Override
        public String valueOf(final int key) {
            return "s" + key;
        }

        @Override
        public int size() {
            return 3;
        }

        @Override
        public int keyOf(final String value) {
            return -1;
        }
    };
    // bytes a full SYMBOL vector may allocate while it takes its 1,024 keys: none expected, room for a stray object
    private static final long FULL_FILL_BYTES = 1_024L;
    // a grown vector copies its three int arrays from 16 values to 1,024: well over one copy of all three
    private static final long GROWN_FILL_BYTES = 3L * Integer.BYTES * Page.CAPACITY;

    /// A lent page takes 1,024 SYMBOL keys without growing, the first and every later loan (a new page under `-ea`,
    /// the refilled one with `-da`); a page made by `Page.allocate` grows.
    @Test
    void aLentPageTakesEveryRowWithoutGrowing() {
        final List<Field> fields = List.of(field("s", CdbType.SYMBOL));
        // warm up both paths, so the measured fills run no class loading
        fill(Page.relend(null, fields, null).columns[0]);
        fill(Page.allocate(fields).columns[0]);
        final Page first = Page.relend(null, fields, null);
        assertThat(allocatedFilling(first.columns[0])).isLessThan(FULL_FILL_BYTES);
        first.size = Page.CAPACITY;
        assertThat(allocatedFilling(Page.relend(first, fields, null).columns[0])).isLessThan(FULL_FILL_BYTES);
        assertThat(allocatedFilling(Page.allocate(fields).columns[0])).isGreaterThan(GROWN_FILL_BYTES);
    }

    /// A column the filler leaves unwritten, as a scan's unread one, keeps the small start.
    @Test
    void anUnfilledColumnIsNotSizedFull() {
        final List<Field> fields = List.of(field("read", CdbType.SYMBOL), field("unread", CdbType.SYMBOL));
        final Page page = Page.relend(null, fields, new boolean[] {true, false});
        fill(page.columns[0]);
        assertThat(allocatedFilling(page.columns[0])).isLessThan(FULL_FILL_BYTES);
        assertThat(allocatedFilling(page.columns[1])).isGreaterThan(GROWN_FILL_BYTES);
    }

    /// A lent single vector, as a projection's copy, is full too.
    @Test
    void aLentVectorTakesEveryRowWithoutGrowing() {
        fill(Page.relend((Vector) null, CdbType.SYMBOL));
        assertThat(allocatedFilling(Page.relend((Vector) null, CdbType.SYMBOL))).isLessThan(FULL_FILL_BYTES);
    }

    private static long allocatedFilling(final Vector vector) {
        final ThreadMXBean threads = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        final long before = threads.getCurrentThreadAllocatedBytes();
        fill(vector);
        return threads.getCurrentThreadAllocatedBytes() - before;
    }

    private static void fill(final Vector vector) {
        for (int i = 0; i < Page.CAPACITY; i++) {
            vector.setSymbol(i, DICTIONARY, i % 3);
        }
    }
}
