package dev.marregui.comando.cdb.store;

import java.util.List;

import org.junit.jupiter.api.Test;

import dev.marregui.comando.cdb.CdbType;

import static org.assertj.core.api.Assertions.assertThat;

/// A [RowBlock] gives its segments back to its epoch on close, and a text segment it outgrew as it grows: a block
/// built on the reused memory reads none of the closed block's values, every row NULL or zero as in a fresh one, and
/// a block's byte count drops the outgrown segment.
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

    /// Block after block of the same columns, each filled in every row, its text grown, then closed: the next one
    /// reads every row as a fresh block does (zero, false and NULL), however much of its memory was the last one's.
    @Test
    void aBlockOnReusedMemoryReadsNoneOfTheClosedOnesValues() {
        final long liveBefore = RowBlock.liveBytes();
        for (int i = 0; i < BLOCKS; i++) {
            try (RowBlock block = new RowBlock("t", COLUMNS, ROWS)) {
                for (int r = 0; r < ROWS; r++) {
                    assertThat(block.getLong(TS, r)).as("block %d row %d", i, r).isZero();
                    assertThat(block.getLong(N, r)).as("block %d row %d", i, r).isZero();
                    assertThat(Double.doubleToRawLongBits(block.getDouble(D, r))).as("block %d row %d", i, r)
                            .isZero();
                    assertThat(block.getBoolean(B, r)).as("block %d row %d", i, r).isFalse();
                    assertThat(block.textAt(S, r)).as("block %d row %d", i, r).isNull();
                    assertThat(block.textLength(V, r)).as("block %d row %d", i, r).isEqualTo(-1);
                    assertThat(block.textChars(V, r)).as("block %d row %d", i, r).isZero();
                }
                for (int r = 0; r < ROWS; r++) {
                    block.setLong(TS, r, -1L);
                    block.setLong(N, r, i * 1_000L + r);
                    block.setDouble(D, r, Double.NaN);
                    block.setBoolean(B, r, true);
                    block.setText(S, r, "s" + r);
                    // past the text's first segment, which goes back to the epoch as the column grows
                    block.setText(V, r, "v".repeat(r + i % 17));
                }
                for (int r = 0; r < ROWS; r++) {
                    assertThat(block.textAt(V, r)).isEqualTo("v".repeat(r + i % 17));
                }
            }
        }
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
}
