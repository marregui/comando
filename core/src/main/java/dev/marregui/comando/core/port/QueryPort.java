package dev.marregui.comando.core.port;

import dev.marregui.comando.core.model.ConnectionSpec;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/** SQL on behalf of Claude: runs on the connection's lane like the editor's runs, returns capped text rows. */
public interface QueryPort {

    /**
     * Rows as text.
     *
     * @param updateCount rows changed by a statement without rows, -1 otherwise
     * @param truncated   more rows existed than were returned
     */
    record Rows(List<String> columns, List<List<String>> rows, long updateCount, boolean truncated) {
        public Rows {
            columns = List.copyOf(columns);
            rows = rows.stream().map(List::copyOf).toList();
        }
    }

    CompletableFuture<Rows> query(ConnectionSpec spec, String sql, int maxRows);

    /** Columns of {@code table}: name, type, nullable. */
    CompletableFuture<Rows> describe(ConnectionSpec spec, String table);
}
