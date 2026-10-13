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
 * @param startedMillis when the statement started executing, 0 before; the running overlay counts from it
 * @param note        what the statements after the first one did, empty when the block held one statement
 * @param noteFailed  a statement after the first one failed
 * @param background  nobody typed this run (a live plot's refresh): its outcome is not announced
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
        String error,
        long startedMillis,
        String note,
        boolean noteFailed,
        boolean background) {

    public SqlRun {
        Objects.requireNonNull(panel, "panel");
        Objects.requireNonNull(connection, "connection");
        Objects.requireNonNull(sql, "sql");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(error, "error");
        note = note == null ? "" : note;
    }

    public static SqlRun queued(long requestId, PanelId panel, String connection, String sql) {
        return queued(requestId, panel, connection, sql, false);
    }

    public static SqlRun queued(long requestId, PanelId panel, String connection, String sql, boolean background) {
        return new SqlRun(requestId, panel, connection, sql, RunStatus.QUEUED, 0, -1L, false, 0L, 0L, 0L, "", 0L, "", false, background);
    }

    public SqlRun withStatus(RunStatus newStatus) {
        return new SqlRun(requestId, panel, connection, sql, newStatus, rowCount, updateCount, truncated, execMillis, fetchMillis, totalMillis, error, startedMillis, note, noteFailed, background);
    }

    public SqlRun withProgress(int rows, long exec, long fetch, long total) {
        return new SqlRun(requestId, panel, connection, sql, RunStatus.RUNNING, rows, updateCount, truncated, exec, fetch, total, error, startedMillis, note, noteFailed, background);
    }

    public SqlRun completed(int rows, long updates, boolean wasTruncated, long exec, long fetch, long total) {
        return new SqlRun(requestId, panel, connection, sql, RunStatus.DONE, rows, updates, wasTruncated, exec, fetch, total, error, startedMillis, note, noteFailed, background);
    }

    public SqlRun started(long at) {
        return new SqlRun(requestId, panel, connection, sql, RunStatus.RUNNING, rowCount, updateCount, truncated, execMillis, fetchMillis, totalMillis, error, at, note, noteFailed, background);
    }

    public SqlRun withNote(String text, boolean failed) {
        return new SqlRun(requestId, panel, connection, sql, status, rowCount, updateCount, truncated, execMillis, fetchMillis, totalMillis, error, startedMillis, text, failed, background);
    }

    public SqlRun failed(String message, long total) {
        return new SqlRun(requestId, panel, connection, sql, RunStatus.FAILED, rowCount, updateCount, truncated, execMillis, fetchMillis, total, message, startedMillis, note, noteFailed, background);
    }
}
