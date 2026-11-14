package dev.marregui.comando.cdb.store;

import dev.marregui.comando.cdb.CdbType;

/** One column of a table: its name, as written, and its type. */
public record ColumnDef(String name, CdbType type) {
}
