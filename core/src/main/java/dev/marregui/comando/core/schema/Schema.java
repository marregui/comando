package dev.marregui.comando.core.schema;

import java.util.List;
import java.util.Objects;

/** The tables and columns of one connection's database, as last read. */
public record Schema(List<Table> tables) {

    public static final Schema EMPTY = new Schema(List.of());

    public Schema {
        tables = List.copyOf(tables);
    }

    public record Table(String name, List<Column> columns) {
        public Table {
            Objects.requireNonNull(name, "name");
            columns = List.copyOf(columns);
        }
    }

    public record Column(String name, String type) {
        public Column {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(type, "type");
        }
    }
}
