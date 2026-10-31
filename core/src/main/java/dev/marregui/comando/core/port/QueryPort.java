package dev.marregui.comando.core.port;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import dev.marregui.comando.core.model.ConnectionSpec;

/** SQL on behalf of Claude: runs on the connection's lane like the editor's runs, returns capped text rows. */
public interface QueryPort {

    /**
     * Rows as text; a {@code null} cell is SQL NULL, so a reader can tell it from the text {@code "null"}.
     *
     * @param updateCount rows changed by a statement without rows, -1 otherwise
     * @param truncated   more rows existed than were returned
     */
    record Rows(List<String> columns, List<List<String>> rows, long updateCount, boolean truncated) {
        public Rows {
            columns = List.copyOf(columns);
            // not List.copyOf, which refuses the null that stands for SQL NULL
            rows = rows.stream().map(row -> Collections.unmodifiableList(new ArrayList<>(row))).toList();
        }
    }

    CompletableFuture<Rows> query(ConnectionSpec spec, String sql, int maxRows);

    /** Columns of {@code table}: name, type, nullable. */
    CompletableFuture<Rows> describe(ConnectionSpec spec, String table);
}
