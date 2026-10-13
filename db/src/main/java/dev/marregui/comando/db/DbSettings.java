package dev.marregui.comando.db;

/**
 * Tunables of the database adapter.
 *
 * @param queryTimeoutSeconds driver-side statement timeout; 0 means none
 * @param maxRows             rows kept per run; the fetch stops there and the run is marked truncated
 * @param fetchSize           rows per round trip when streaming a result
 * @param healthPeriodSeconds period of the validity check of idle open connections
 * @param abortAfterMillis    a cancelled statement still running after this long has its connection aborted
 */
public record DbSettings(int queryTimeoutSeconds, int maxRows, int fetchSize, int healthPeriodSeconds, long abortAfterMillis) {

    public static final DbSettings DEFAULTS = new DbSettings(0, 5_000_000, 5_000, 60, 2_000L);

    public DbSettings {
        if (queryTimeoutSeconds < 0 || maxRows <= 0 || fetchSize <= 0 || healthPeriodSeconds <= 0 || abortAfterMillis <= 0) {
            throw new IllegalArgumentException("invalid database settings " + this);
        }
    }
}
