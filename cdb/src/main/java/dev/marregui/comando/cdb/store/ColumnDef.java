package dev.marregui.comando.cdb.store;

import dev.marregui.comando.cdb.CdbType;

/**
 * One column of a table: its name, as written, its type and, for a SYMBOL, the options QuestDB keeps for it: the symbol
 * capacity, whether the dictionary is cached, whether the column is indexed and the index's value block capacity. They
 * are 0 and false for the other types.
 */
public record ColumnDef(
        String name,
        CdbType type,
        int symbolCapacity,
        boolean symbolCached,
        boolean indexed,
        int indexBlockCapacity
) {

    /** QuestDB's {@code cairo.default.symbol.capacity}, its default when CREATE TABLE names none. */
    public static final int DEFAULT_SYMBOL_CAPACITY = 256;

    /**
     * QuestDB's {@code cairo.index.value.block.size}: the index block capacity it reports for every SYMBOL column, an
     * indexed one created without {@code INDEX CAPACITY} and one never indexed alike.
     */
    public static final int DEFAULT_INDEX_BLOCK_CAPACITY = 256;

    /** A column created without options: QuestDB's defaults for a SYMBOL (cached, not indexed), none for the others. */
    public ColumnDef(final String name, final CdbType type) {
        this(name, type, type == CdbType.SYMBOL ? DEFAULT_SYMBOL_CAPACITY : 0, type == CdbType.SYMBOL, false,
                type == CdbType.SYMBOL ? DEFAULT_INDEX_BLOCK_CAPACITY : 0);
    }
}
