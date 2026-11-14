package dev.marregui.comando.cdb.catalog;

import java.util.List;

import org.junit.jupiter.api.Test;

import dev.marregui.comando.cdb.CdbException;
import dev.marregui.comando.cdb.exec.Cursor;
import dev.marregui.comando.cdb.exec.Page;
import dev.marregui.comando.cdb.plan.Plan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// The rows of `long_sequence(n)` and `one_row`, which read no table: their pages, their end and their close.
class CatalogSourcesTest {

    private static Cursor open(final String function, final String... args) {
        final List<String> argList = List.of(args);
        return Catalog.open(new Plan.Source(function, argList, Catalog.fields(function, argList)), null);
    }

    /// 2500 values come as pages of 1024, 1024 and 452 holding 1 to 2500 in order.
    @Test
    void longSequenceOf2500IsThreePagesFromOneTo2500() {
        final List<List<Object>> rows = Rows.drain(open("long_sequence", "2500"));
        assertThat(rows).hasSize(2500);
        for (int i = 0; i < rows.size(); i++) {
            assertThat(rows.get(i)).containsExactly((long) i + 1);
        }
        assertThat(Rows.pageSizes(open("long_sequence", "2500"))).containsExactly(1024, 1024, 452);
    }

    /// A count that fills pages exactly ends with a full page, and one more row starts another.
    @Test
    void longSequencePagesBreakAtThePageCapacity() {
        assertThat(Rows.pageSizes(open("long_sequence", "1024"))).containsExactly(1024);
        assertThat(Rows.pageSizes(open("long_sequence", "1025"))).containsExactly(1024, 1);
        assertThat(Rows.pageSizes(open("long_sequence", "1"))).containsExactly(1);
    }

    /// A zero or negative count has no page, as QuestDB returns no rows.
    @Test
    void longSequenceOfZeroOrNegativeHasNoRows() {
        assertThat(open("long_sequence", "0").next()).isNull();
        assertThat(open("long_sequence", "-5").next()).isNull();
    }

    /// The cursor reports its field and, once closed, no more pages.
    @Test
    void closedLongSequenceReturnsNoMorePages() {
        final Cursor cursor = open("long_sequence", "5000");
        assertThat(cursor.fields()).hasSize(1);
        assertThat(cursor.next()).isNotNull();
        cursor.close();
        cursor.close();
        assertThat(cursor.next()).isNull();
    }

    /// A page the cursor returned is not written again by the next.
    @Test
    void longSequencePagesAreNotReused() {
        final Cursor cursor = open("long_sequence", "2048");
        final Page first = cursor.next();
        final Page second = cursor.next();
        assertThat(first).isNotSameAs(second);
        assertThat(first.columns[0].longs[0]).isEqualTo(1);
        assertThat(second.columns[0].longs[0]).isEqualTo(1025);
    }

    /// A count that is not a number fails when the source is opened, not when its first page is read.
    @Test
    void longSequenceWithABadCountFailsAtOpen() {
        assertThatThrownBy(() -> Catalog.open(new Plan.Source("long_sequence", List.of("x"), List.of()), null))
                .isInstanceOf(CdbException.class);
    }

    /// `one_row` is one page of one row and no column, then the end.
    @Test
    void oneRowIsASingleRowWithNoColumn() {
        final Cursor cursor = open("one_row");
        assertThat(cursor.fields()).isEmpty();
        final Page page = cursor.next();
        assertThat(page.size).isEqualTo(1);
        assertThat(page.columns).isEmpty();
        assertThat(cursor.next()).isNull();
    }
}
