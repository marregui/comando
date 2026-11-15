package dev.marregui.comando.cdb.store;

import java.util.List;

import org.junit.jupiter.api.Test;

import dev.marregui.comando.cdb.CdbException;
import dev.marregui.comando.cdb.CdbType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// The `_meta` text of [TableFiles]: format 2 keeps a SYMBOL's options, format 1 still reads with QuestDB's defaults,
/// and anything else is refused; `_txn` reads in both its formats, with and without a checkpoint's `@` lines.
class TableFilesTest {

    private static final String FORMAT_ONE = "format=1\nname=t\npartitionBy=DAY\ntimestamp=0\nttl=0\ndedup=\n"
            + "col=ts:TIMESTAMP\ncol=s:SYMBOL\n";

    /// A table written in the current format reads back equal, every SYMBOL option included.
    @Test
    void currentFormatKeepsSymbolOptions() {
        final TableDef def = new TableDef("t", List.of(
                new ColumnDef("ts", CdbType.TIMESTAMP),
                new ColumnDef("plain", CdbType.SYMBOL),
                new ColumnDef("tuned", CdbType.SYMBOL, 4096, false, true, 1024),
                new ColumnDef("cachedIndex", CdbType.SYMBOL, 65536, true, true, 256),
                new ColumnDef("v", CdbType.VARCHAR)
        ), 0, PartitionBy.MONTH, List.of("ts", "plain"), 3_600_000_000L);
        final String text = TableFiles.encodeMeta(def);
        assertThat(text).startsWith("format=2\n");
        assertThat(TableFiles.decodeMeta(text, "_meta")).isEqualTo(def);
    }

    /// A format 1 definition, written before columns had options, reads with QuestDB's defaults.
    @Test
    void previousFormatReadsWithDefaults() {
        final TableDef def = TableFiles.decodeMeta(FORMAT_ONE, "_meta");
        assertThat(def.columns()).containsExactly(
                new ColumnDef("ts", CdbType.TIMESTAMP, 0, false, false, 0),
                new ColumnDef("s", CdbType.SYMBOL, 256, true, false, 256)
        );
    }

    /// A format newer than this build is refused, naming the formats it reads.
    @Test
    void unknownFormatIsRefused() {
        assertThatThrownBy(() -> TableFiles.decodeMeta(FORMAT_ONE.replace("format=1", "format=3"), "_meta"))
                .isInstanceOf(CdbException.class)
                .hasMessage("refuse table of unknown format [file=_meta, format=3, supported=1..2]");
    }

    /// Column options are refused in format 1, on a type other than SYMBOL, and when unknown.
    @Test
    void misplacedColumnOptionsAreRefused() {
        assertCorrupt(FORMAT_ONE.replace("col=s:SYMBOL", "col=s:SYMBOL:nocache"), "col=s:SYMBOL:nocache");
        final String formatTwo = FORMAT_ONE.replace("format=1", "format=2");
        assertCorrupt(formatTwo.replace("col=ts:TIMESTAMP", "col=ts:TIMESTAMP:capacity=8"),
                "col=ts:TIMESTAMP:capacity=8");
        assertCorrupt(formatTwo.replace("col=s:SYMBOL", "col=s:SYMBOL:posting"), "col=s:SYMBOL:posting");
    }

    /// A `_txn` written before the log, partitions only, reads back as not checkpointed, naming no definition.
    @Test
    void txnBeforeTheLogRoundTrips() {
        final List<TableFiles.Entry> partitions = List.of(new TableFiles.Entry("2026-11-14.3", 10L),
                new TableFiles.Entry("2026-11-15.7", 4L));
        final TableFiles.Txn txn = TableFiles.decodeTxn(TableFiles.encodeTxn(7L, partitions), "_txn");
        assertThat(txn.number()).isEqualTo(7L);
        assertThat(txn.partitions()).isEqualTo(partitions);
        assertThat(txn.checkpointed()).isFalse();
        assertThat(txn.columns()).isEqualTo(-1);
        assertThat(txn.symbolBytes()).isEmpty();
    }

    /// A checkpoint's `_txn` keeps every `@` line: the column count, the TTL and each SYMBOL column's file length,
    /// -1 for the columns that are not SYMBOL; a table with no partitions and no SYMBOL column too.
    @Test
    void checkpointedTxnRoundTripsEveryAtLine() {
        final List<TableFiles.Entry> partitions = List.of(new TableFiles.Entry("default.12", 3L));
        final TableFiles.Txn txn = new TableFiles.Txn(12L, partitions, 4, 3_600_000_000L,
                new long[] {-1L, 0L, -1L, 12_345L});
        final String text = TableFiles.encodeTxn(txn);
        assertThat(text).isEqualTo("12\ndefault.12\t3\n@columns=4\n@ttl=3600000000\n@sym=1:0\n@sym=3:12345\n");
        final TableFiles.Txn read = TableFiles.decodeTxn(text, "_txn");
        assertThat(read.number()).isEqualTo(12L);
        assertThat(read.partitions()).isEqualTo(partitions);
        assertThat(read.checkpointed()).isTrue();
        assertThat(read.columns()).isEqualTo(4);
        assertThat(read.ttlMicros()).isEqualTo(3_600_000_000L);
        assertThat(read.symbolBytes()).containsExactly(-1L, 0L, -1L, 12_345L);

        final TableFiles.Txn bare = TableFiles.decodeTxn(TableFiles.encodeTxn(new TableFiles.Txn(1L, List.of(), 2, 0L,
                new long[] {-1L, -1L})), "_txn");
        assertThat(bare.partitions()).isEmpty();
        assertThat(bare.checkpointed()).isTrue();
        assertThat(bare.ttlMicros()).isZero();
        assertThat(bare.symbolBytes()).containsExactly(-1L, -1L);
    }

    /// An `@` line this build does not know is refused, not skipped: it may name state the table needs.
    @Test
    void unknownAtLineInTxnIsRefused() {
        assertThatThrownBy(() -> TableFiles.decodeTxn("3\n@columns=1\n@ttl=0\n@wal=9\n", "_txn"))
                .isInstanceOf(CdbException.class)
                .hasMessage("cannot read table file line [file=_txn, line=@wal=9]");
    }

    /// A `@sym` line for a column at or past `@columns`, or with no `@columns` at all, is refused.
    @Test
    void symPastTheColumnsIsRefused() {
        assertThatThrownBy(() -> TableFiles.decodeTxn("3\n@columns=2\n@ttl=0\n@sym=2:10\n", "_txn"))
                .isInstanceOf(CdbException.class)
                .hasMessage("cannot read table file line [file=_txn, line=@sym=2:10]");
        assertThatThrownBy(() -> TableFiles.decodeTxn("3\n@sym=0:10\n", "_txn"))
                .isInstanceOf(CdbException.class)
                .hasMessage("cannot read table file line [file=_txn, line=@sym without @columns]");
    }

    private static void assertCorrupt(final String text, final String line) {
        assertThatThrownBy(() -> TableFiles.decodeMeta(text, "_meta"))
                .isInstanceOf(CdbException.class)
                .hasMessage("cannot read table file line [file=_meta, line=" + line + ']');
    }
}
