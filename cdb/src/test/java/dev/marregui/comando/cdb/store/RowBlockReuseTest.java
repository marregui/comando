package dev.marregui.comando.cdb.store;

import java.util.List;

import org.junit.jupiter.api.Test;

import dev.marregui.comando.cdb.CdbType;

import static org.assertj.core.api.Assertions.assertThat;

/// A [RowBlock] gives its segments back to its epoch on close, and a text segment it outgrew as it grows: a block
/// built on the reused memory, which is not zeroed again, reads exactly the values written into it, NULLs included,
/// and every text row it has not set as NULL; a block's byte count drops the outgrown segment.
class RowBlockReuseTest {

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
    private static final int ROWS = 64;
    // blocks built one after another: most land in the epoch of the block before and take its segments
    private static final int BLOCKS = 500;

    /// Block after block of the same columns, each written in every row with values unlike the last block's (a NULL
    /// where the last one held a value, and the other way round), its text grown, then closed: each reads back
    /// exactly what it was given, and its text rows read NULL before they are set, though the LONG column of a block
    /// on reused memory, not zeroed again, holds a closed block's bytes until written.
    @Test
    void aBlockOnReusedMemoryReadsExactlyWhatWasWritten() {
        final long liveBefore = RowBlock.liveBytes();
        int unzeroed = 0;
        for (int i = 0; i < BLOCKS; i++) {
            try (RowBlock block = new RowBlock("t", COLUMNS, ROWS)) {
                boolean stale = false;
                for (int r = 0; r < ROWS; r++) {
                    stale |= block.getLong(N, r) != 0L;
                    assertThat(block.textAt(S, r)).as("block %d row %d", i, r).isNull();
                    assertThat(block.textLength(V, r)).as("block %d row %d", i, r).isEqualTo(-1);
                }
                unzeroed += stale ? 1 : 0;
                for (int r = 0; r < ROWS; r++) {
                    block.setLong(TS, r, ts(i, r));
                    block.setLong(N, r, n(i, r));
                    block.setDouble(D, r, d(i, r));
                    block.setBoolean(B, r, (i + r) % 2 == 0);
                    block.setText(S, r, s(i, r));
                    // past the text's first segment, which goes back to the epoch as the column grows
                    block.setText(V, r, v(i, r));
                }
                for (int r = 0; r < ROWS; r++) {
                    assertThat(block.getLong(TS, r)).as("block %d row %d", i, r).isEqualTo(ts(i, r));
                    assertThat(block.getLong(N, r)).as("block %d row %d", i, r).isEqualTo(n(i, r));
                    assertThat(Double.doubleToRawLongBits(block.getDouble(D, r))).as("block %d row %d", i, r)
                            .isEqualTo(Double.doubleToRawLongBits(d(i, r)));
                    assertThat(block.getBoolean(B, r)).as("block %d row %d", i, r).isEqualTo((i + r) % 2 == 0);
                    assertThat(block.textAt(S, r)).as("block %d row %d", i, r).isEqualTo(s(i, r));
                    assertThat(block.textAt(V, r)).as("block %d row %d", i, r).isEqualTo(v(i, r));
                    assertThat(block.textChars(V, r)).as("block %d row %d", i, r)
                            .isEqualTo(v(i, r) == null ? 0 : v(i, r).length());
                }
            }
        }
        assertThat(unzeroed).as("blocks whose LONG column held a closed block's bytes").isPositive();
        assertThat(RowBlock.liveBytes()).isEqualTo(liveBefore);
    }

    /// A text column outgrowing its segment gives the old one back: the block's bytes grow by the new segment less
    /// the old one, 8 bytes per row of capacity.
    @Test
    void aGrownTextSegmentLeavesTheBlocksBytes() {
        final long liveBefore = RowBlock.liveBytes();
        try (RowBlock block = new RowBlock("t", COLUMNS, 4)) {
            final long before = block.bytes();
            block.setText(V, 0, "x".repeat(100));
            assertThat(block.bytes()).isEqualTo(before - 4L * Long.BYTES + 100L);
            assertThat(RowBlock.liveBytes() - liveBefore).isEqualTo(block.bytes());
            assertThat(block.textAt(V, 0)).isEqualTo("x".repeat(100));
        }
        assertThat(RowBlock.liveBytes()).isEqualTo(liveBefore);
    }

    private static long ts(final int block, final int row) {
        return block * 1_000_000L + row;
    }

    // a NULL on every third row, shifted by one each block
    private static long n(final int block, final int row) {
        return (block + row) % 3 == 0 ? Long.MIN_VALUE : -(block * 1_000L + row);
    }

    private static double d(final int block, final int row) {
        return (block + row) % 3 == 1 ? Double.NaN : block + row / 8.0;
    }

    private static String s(final int block, final int row) {
        return (block + row) % 2 == 0 ? null : "s" + block % 7 + row;
    }

    private static String v(final int block, final int row) {
        return (block + row) % 5 == 0 ? null : (block % 2 == 0 ? "v" : "é").repeat(row + block % 17);
    }
}
