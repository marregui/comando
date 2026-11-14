package dev.marregui.comando.cdb.store;

import java.util.List;

import org.junit.jupiter.api.Test;

import dev.marregui.comando.cdb.CdbException;
import dev.marregui.comando.cdb.CdbType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// The `_meta` text of [TableFiles]: format 2 keeps a SYMBOL's options, format 1 still reads with QuestDB's defaults,
/// and anything else is refused.
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

    private static void assertCorrupt(final String text, final String line) {
        assertThatThrownBy(() -> TableFiles.decodeMeta(text, "_meta"))
                .isInstanceOf(CdbException.class)
                .hasMessage("cannot read table file line [file=_meta, line=" + line + ']');
    }
}
