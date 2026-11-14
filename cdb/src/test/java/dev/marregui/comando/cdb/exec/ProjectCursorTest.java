package dev.marregui.comando.cdb.exec;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import dev.marregui.comando.cdb.CdbException;
import dev.marregui.comando.cdb.CdbType;
import dev.marregui.comando.cdb.exec.expr.VectorFn;

import static dev.marregui.comando.cdb.exec.RowsFakes.SIZES;
import static dev.marregui.comando.cdb.exec.RowsFakes.drain;
import static dev.marregui.comando.cdb.exec.RowsFakes.field;
import static dev.marregui.comando.cdb.exec.RowsFakes.longs;
import static dev.marregui.comando.cdb.exec.RowsFakes.range;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// [ProjectCursor] evaluates one column per expression; a column reference is handed on, a computed vector copied.
class ProjectCursorTest {

    /// `x * 10`, NULL staying NULL, in one vector reused on every call.
    private static VectorFn timesTen() {
        final Vector out = Vector.of(CdbType.LONG, Page.CAPACITY);
        return page -> {
            final Vector x = page.columns[0];
            for (int i = 0; i < page.size; i++) {
                out.longs[i] = x.isNull(i) ? Vector.NULL_LONG : x.longs[i] * 10;
            }
            return out;
        };
    }

    /// A computed column is copied out of its reused vector: pages read after the cursor went on keep their values.
    @Test
    void copiesComputedColumnsAtEverySize() {
        for (final int n : SIZES) {
            final RowsFakes.Drained out = drain(new ProjectCursor(longs(n, Page.CAPACITY),
                    List.of(page -> page.columns[0], timesTen()),
                    List.of(field("x", CdbType.LONG), field("y", CdbType.LONG))));
            final List<List<Object>> expected = new ArrayList<>();
            for (final long v : range(0, n)) {
                expected.add(List.of(v, v * 10));
            }
            assertThat(out.rows()).as("n=%d", n).isEqualTo(expected);
        }
    }

    /// A column reference hands on the input's own vector, uncopied.
    @Test
    void handsAColumnReferenceOn() {
        final RowsFakes.ListCursor child = longs(5, Page.CAPACITY);
        final Vector input = child.pages().getFirst().columns[0];
        final RowsFakes.Drained out = drain(new ProjectCursor(child, List.of(page -> page.columns[0]),
                List.of(field("x", CdbType.LONG))));
        assertThat(out.pages().getFirst().columns[0]).isSameAs(input);
    }

    /// NULL in a computed column is copied as NULL.
    @Test
    void copiesNulls() {
        final RowsFakes.ListCursor child = RowsFakes.cursor(List.of(field("x", CdbType.LONG)), 3, Page.CAPACITY,
                r -> new Object[] {r == 1 ? null : (long) r});
        final RowsFakes.Drained out = drain(new ProjectCursor(child, List.of(timesTen()),
                List.of(field("y", CdbType.LONG))));
        assertThat(out.rows()).isEqualTo(RowsFakes.rows(new Object[] {0L}, new Object[] {null}, new Object[] {20L}));
    }

    /// A computed vector stored unlike its field's type is refused.
    @Test
    void refusesAValueOfAnotherType() {
        final Vector text = Vector.of(CdbType.VARCHAR, Page.CAPACITY);
        final ProjectCursor cursor = new ProjectCursor(longs(3, Page.CAPACITY), List.of(_ -> text),
                List.of(field("y", CdbType.LONG)));
        assertThatThrownBy(cursor::next).isInstanceOf(CdbException.class).hasMessageContaining("value=VARCHAR");
        cursor.close();
    }
}
