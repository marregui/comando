package dev.marregui.comando.cdb.store;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.Test;

import dev.marregui.comando.cdb.CdbType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// A row block holds its columns in native memory: every type reads back what was set, NULL text included, text grows
/// past its first segment, close frees every byte once, and a read after close throws.
class RowBlockTest {

    private static final List<ColumnDef> COLUMNS = List.of(
            new ColumnDef("ts", CdbType.TIMESTAMP),
            new ColumnDef("n", CdbType.LONG),
            new ColumnDef("d", CdbType.DOUBLE),
            new ColumnDef("b", CdbType.BOOLEAN),
            new ColumnDef("s", CdbType.SYMBOL),
            new ColumnDef("v", CdbType.VARCHAR));
    private static final int TS = 0;
    private static final int N = 1;
    private static final int D = 2;
    private static final int B = 3;
    private static final int S = 4;
    private static final int V = 5;

    /// Every type reads back the value set, NULL sentinels and NaN bits included; a text row never set reads NULL.
    @Test
    void everyTypeReadsBackWhatWasSet() {
        try (RowBlock block = new RowBlock("t", COLUMNS, 4)) {
            block.setLong(TS, 0, 1_700_000_000_000_000L);
            block.setLong(N, 0, Long.MIN_VALUE);
            block.setLong(N, 1, -7L);
            block.setDouble(D, 0, Double.NaN);
            block.setDouble(D, 1, -0.0);
            block.setBoolean(B, 0, true);
            block.setBoolean(B, 1, false);
            block.setText(S, 0, "sensor-1");
            block.setText(S, 1, null);
            block.setText(V, 0, "");
            block.setText(V, 1, "plain");
            assertThat(block.getLong(TS, 0)).isEqualTo(1_700_000_000_000_000L);
            assertThat(block.getLong(N, 0)).isEqualTo(Long.MIN_VALUE);
            assertThat(block.getLong(N, 1)).isEqualTo(-7L);
            assertThat(Double.doubleToRawLongBits(block.getDouble(D, 0)))
                    .isEqualTo(Double.doubleToRawLongBits(Double.NaN));
            assertThat(Double.doubleToRawLongBits(block.getDouble(D, 1))).isEqualTo(Double.doubleToRawLongBits(-0.0));
            assertThat(block.getBoolean(B, 0)).isTrue();
            assertThat(block.getBoolean(B, 1)).isFalse();
            assertThat(block.textAt(S, 0)).isEqualTo("sensor-1");
            assertThat(block.textAt(S, 1)).isNull();
            assertThat(block.textLength(S, 1)).isEqualTo(-1);
            assertThat(block.textAt(V, 0)).isEmpty();
            assertThat(block.textLength(V, 0)).isZero();
            assertThat(block.textAt(V, 1)).isEqualTo("plain");
            assertThat(block.textAt(V, 3)).isNull();
        }
    }

    /// Non-ASCII text, a supplementary character and a char of U+E000..U+FFFF among them, reads back equal, with its
    /// UTF-8 and UTF-16 lengths; equal values compare equal and hash alike, others do not compare equal.
    @Test
    void nonAsciiTextReadsBackWithItsLengths() {
        final String[] values = {"é", "日本語", "😀 smile",
                String.valueOf((char) 0xE000), "a" + (char) 0x2028 + "b"};
        try (RowBlock block = new RowBlock("t", COLUMNS, values.length + 1)) {
            for (int r = 0; r < values.length; r++) {
                block.setText(V, r, values[r]);
            }
            block.setText(V, values.length, "日本語");
            for (int r = 0; r < values.length; r++) {
                assertThat(block.textAt(V, r)).isEqualTo(values[r]);
                assertThat(block.textChars(V, r)).isEqualTo(values[r].length());
                assertThat(block.textLength(V, r)).isEqualTo(values[r].getBytes(StandardCharsets.UTF_8).length);
            }
            assertThat(block.textEquals(V, 1, values.length)).isTrue();
            assertThat(block.textHash(V, 1)).isEqualTo(block.textHash(V, values.length));
            assertThat(block.textEquals(V, 0, 1)).isFalse();
        }
    }

    /// Text past the first segment grows it by doubling: ten thousand values of growing length all read back, and the
    /// block's byte count grows with them.
    @Test
    void textGrowsPastItsFirstSegment() {
        final int rows = 10_000;
        try (RowBlock block = new RowBlock("t", COLUMNS, rows)) {
            final long initial = block.bytes();
            for (int r = 0; r < rows; r++) {
                block.setText(V, r, "value-" + r + "-" + "x".repeat(r % 97));
            }
            for (int r = 0; r < rows; r++) {
                assertThat(block.textAt(V, r)).isEqualTo("value-" + r + "-" + "x".repeat(r % 97));
            }
            assertThat(block.bytes()).isGreaterThan(initial);
        }
    }

    /// Rows copied from another block, of fewer columns, read as in the source; the column the source lacks stays as
    /// it was.
    @Test
    void copyRowsCopiesTheSourceColumns() {
        final List<ColumnDef> fewer = COLUMNS.subList(0, V);
        try (RowBlock source = new RowBlock("t", fewer, 3); RowBlock target = new RowBlock("t", COLUMNS, 8)) {
            for (int r = 0; r < 3; r++) {
                source.setLong(TS, r, r);
                source.setLong(N, r, 10L * r);
                source.setDouble(D, r, r / 2.0);
                source.setBoolean(B, r, r % 2 == 0);
                source.setText(S, r, r == 1 ? null : "s" + r);
            }
            target.copyRows(source, 0, 5, 3);
            for (int r = 0; r < 3; r++) {
                assertThat(target.getLong(TS, 5 + r)).isEqualTo(r);
                assertThat(target.getLong(N, 5 + r)).isEqualTo(10L * r);
                assertThat(target.getDouble(D, 5 + r)).isEqualTo(r / 2.0);
                assertThat(target.getBoolean(B, 5 + r)).isEqualTo(r % 2 == 0);
                assertThat(target.textAt(S, 5 + r)).isEqualTo(r == 1 ? null : "s" + r);
                assertThat(target.textAt(V, 5 + r)).isNull();
            }
        }
    }

    /// Close frees the block's bytes from the live count once; a second close changes nothing.
    @Test
    void closeTwiceFreesOnce() {
        final long before = RowBlock.liveBytes();
        final RowBlock block = new RowBlock("t", COLUMNS, 1024);
        final long held = block.bytes();
        assertThat(held).isPositive();
        assertThat(RowBlock.liveBytes()).isEqualTo(before + held);
        block.close();
        assertThat(RowBlock.liveBytes()).isEqualTo(before);
        assertThat(block.bytes()).isZero();
        block.close();
        assertThat(RowBlock.liveBytes()).isEqualTo(before);
    }

    /// Every read and write after close throws IllegalStateException instead of touching freed memory.
    @Test
    void readAfterCloseThrows() {
        final RowBlock block = new RowBlock("t", COLUMNS, 4);
        block.setLong(N, 0, 1L);
        block.setText(V, 0, "kept");
        block.close();
        assertThatThrownBy(() -> block.getLong(N, 0)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> block.getDouble(D, 0)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> block.getBoolean(B, 0)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> block.textAt(V, 0)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> block.setText(V, 1, "late")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> block.setLong(N, 1, 2L)).isInstanceOf(IllegalStateException.class);
    }
}
