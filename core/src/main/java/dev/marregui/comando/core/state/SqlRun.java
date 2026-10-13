package dev.marregui.comando.core.state;

import dev.marregui.comando.core.model.PanelId;

import java.util.Objects;

/**
 * One SQL statement run from a panel. Its rows live in the result registry under {@code requestId}.
 *
 * @param requestId   identity of the run; late events for another id are dropped
 * @param rowCount    rows fetched so far
 * @param updateCount rows changed by a statement that returns no rows, -1 otherwise
 * @param truncated   the fetch stopped at the row cap
 */
public record SqlRun(
        long requestId,
        PanelId panel,
        String connection,
        String sql,
        RunStatus status,
        int rowCount,
        long updateCount,
        boolean truncated,
        long execMillis,
        long fetchMillis,
        long totalMillis,
        String error) {

    public SqlRun {
        Objects.requireNonNull(panel, "panel");
        Objects.requireNonNull(connection, "connection");
        Objects.requireNonNull(sql, "sql");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(error, "error");
    }

    public static SqlRun queued(long requestId, PanelId panel, String connection, String sql) {
        return new SqlRun(requestId, panel, connection, sql, RunStatus.QUEUED, 0, -1L, false, 0L, 0L, 0L, "");
    }

    public SqlRun withStatus(RunStatus newStatus) {
        return new SqlRun(requestId, panel, connection, sql, newStatus, rowCount, updateCount, truncated, execMillis, fetchMillis, totalMillis, error);
    }

    public SqlRun withProgress(int rows, long exec, long fetch, long total) {
        return new SqlRun(requestId, panel, connection, sql, RunStatus.RUNNING, rows, updateCount, truncated, exec, fetch, total, error);
    }

    public SqlRun completed(int rows, long updates, boolean wasTruncated, long exec, long fetch, long total) {
        return new SqlRun(requestId, panel, connection, sql, RunStatus.DONE, rows, updates, wasTruncated, exec, fetch, total, error);
    }

    public SqlRun failed(String message, long total) {
        return new SqlRun(requestId, panel, connection, sql, RunStatus.FAILED, rowCount, updateCount, truncated, execMillis, fetchMillis, total, message);
    }
}
