package dev.marregui.comando.cdb.exec;

import java.util.ArrayList;
import java.util.List;
import java.util.function.LongPredicate;

import org.junit.jupiter.api.Test;

import dev.marregui.comando.cdb.CdbType;
import dev.marregui.comando.cdb.exec.expr.VectorFn;
import dev.marregui.comando.cdb.plan.Plan;
import dev.marregui.comando.cdb.store.PartitionReader;

import static dev.marregui.comando.cdb.exec.RowsFakes.field;
import static dev.marregui.comando.cdb.exec.RowsFakes.partition;
import static dev.marregui.comando.cdb.exec.RowsFakes.range;
import static dev.marregui.comando.cdb.exec.RowsFakes.table;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/// [Cursor#borrow] lends the rows [Cursor#next] gives, in the lender's own page. Under `-ea` a page kept past its loan
/// reads poison; with `Page`'s assertions off (`:cdb:testPageReuse`) the lender refills the one page (record 179).
class PageBorrowTest {

    private static final List<Field> X = List.of(field("x", CdbType.LONG));
    private static final List<PartitionReader> PARTS = List.of(partition(0, 700), partition(700, 0),
            partition(700, 1500), partition(2200, 800));

    /// The scan lends the rows and page sizes it gives, in time order and newest first.
    @Test
    void theScanLendsTheRowsItGives() {
        for (final boolean newestFirst : new boolean[] {false, true}) {
            final RowsFakes.Drained given = RowsFakes.drain(scan(newestFirst));
            final List<Integer> sizes = new ArrayList<>();
            assertThat(borrowAll(scan(newestFirst), sizes)).as("newestFirst=%s", newestFirst)
                    .isEqualTo(given.longs());
            assertThat(sizes).isEqualTo(given.sizes());
        }
        assertThat(RowsFakes.drain(scan(false)).longs()).isEqualTo(range(0, 3000));
    }

    /// Under `-ea`, a page the scan lent reads poison once the next one is borrowed, so an operator that kept it fails.
    @Test
    void aPageKeptPastItsLoanReadsPoison() {
        assumeTrue(Page.class.desiredAssertionStatus(), "poison is for -ea runs");
        try (ScanCursor cursor = scan(false)) {
            final Page first = cursor.borrow();
            assertThat(first.columns[0].getLong(0)).isZero();
            final Page second = cursor.borrow();
            assertThat(second).isNotSameAs(first);
            assertThat(second.columns[0].getLong(0)).isEqualTo(Page.CAPACITY);
            for (int i = 0; i < first.size; i++) {
                assertThat(first.columns[0].getLong(i)).isEqualTo(Vector.POISON_LONG);
            }
        }
    }

    /// With `Page`'s assertions off, the scan refills the page it lent: one page for the whole read.
    @Test
    void theScanRefillsOnePage() {
        assumeFalse(Page.class.desiredAssertionStatus(), "the refill runs with -da for Page");
        try (ScanCursor cursor = scan(false)) {
            final Page first = cursor.borrow();
            final Page second = cursor.borrow();
            assertThat(second).isSameAs(first);
            assertThat(second.columns[0].getLong(0)).isEqualTo(Page.CAPACITY);
        }
    }

    /// The filter over a borrowing scan lends the rows it gives: packed pages, and full pages passed through.
    @Test
    void theFilterLendsTheRowsItGives() {
        final List<LongPredicate> tests = List.of(v -> v % 3 == 0, v -> v >= 0, v -> v < 700 || v >= 2200);
        for (final LongPredicate test : tests) {
            final RowsFakes.Drained given = RowsFakes.drain(new FilterCursor(scan(false), where(test)));
            final List<Integer> sizes = new ArrayList<>();
            assertThat(borrowAll(new FilterCursor(scan(false), where(test)), sizes)).isEqualTo(given.longs());
            assertThat(sizes).isEqualTo(given.sizes());
        }
    }

    /// A `next` after a `borrow` gives a page the caller owns: the filter's following call does not rewrite it.
    @Test
    void aNextAfterABorrowGivesAnOwnedPage() {
        try (FilterCursor cursor = new FilterCursor(scan(false), where(v -> true))) {
            assertThat(cursor.borrow().columns[0].getLong(0)).isZero();
            final Page owned = cursor.next();
            assertThat(owned.size).isEqualTo(Page.CAPACITY);
            assertThat(owned.columns[0].getLong(0)).isEqualTo(Page.CAPACITY);
            assertThat(cursor.next().columns[0].getLong(0)).isEqualTo(2L * Page.CAPACITY);
            assertThat(owned.size).isEqualTo(Page.CAPACITY);
            for (int i = 0; i < owned.size; i++) {
                assertThat(owned.columns[0].getLong(i)).isEqualTo(Page.CAPACITY + i);
            }
        }
    }

    /// The projection of a computed value over a borrowing scan lends the values it gives.
    @Test
    void theProjectionLendsTheValuesItGives() {
        final Vector doubled = Vector.of(CdbType.LONG, Page.CAPACITY);
        final VectorFn twice = page -> {
            for (int i = 0; i < page.size; i++) {
                doubled.setLong(i, 2 * page.columns[0].getLong(i));
            }
            return doubled;
        };
        final List<Field> fields = List.of(field("y", CdbType.LONG));
        final RowsFakes.Drained given = RowsFakes.drain(new ProjectCursor(scan(false), List.of(twice), fields));
        final List<Integer> sizes = new ArrayList<>();
        assertThat(borrowAll(new ProjectCursor(scan(false), List.of(twice), fields), sizes))
                .isEqualTo(given.longs());
        assertThat(sizes).isEqualTo(given.sizes());
        assertThat(given.longs()).hasSize(3000).startsWith(0L, 2L, 4L);
    }

    // the values of column 0 of every page, read before the next call; each page's size goes to sizes
    private static List<Long> borrowAll(final Cursor cursor, final List<Integer> sizes) {
        final List<Long> out = new ArrayList<>();
        try (cursor) {
            Page page;
            while ((page = cursor.borrow()) != null) {
                assertThat(page.size).isPositive();
                sizes.add(page.size);
                for (int i = 0; i < page.size; i++) {
                    out.add(page.columns[0].getLong(i));
                }
            }
        }
        return out;
    }

    private static ScanCursor scan(final boolean newestFirst) {
        final Plan.Scan scan = new Plan.Scan("t", new int[] {1}, Long.MIN_VALUE, Long.MAX_VALUE, List.of(),
                newestFirst, X);
        return new ScanCursor(scan, new RowsFakes.FakeReader(table(), PARTS));
    }

    private static VectorFn where(final LongPredicate test) {
        final Vector mask = Vector.of(CdbType.BOOLEAN, Page.CAPACITY);
        return page -> {
            for (int i = 0; i < page.size; i++) {
                mask.setBoolean(i, test.test(page.columns[0].getLong(i)));
            }
            return mask;
        };
    }
}
