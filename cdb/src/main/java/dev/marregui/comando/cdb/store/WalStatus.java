package dev.marregui.comando.cdb.store;

/**
 * One table's log and its application, as {@code wal_tables()} and {@code wal_apply_stats()} list them: a commit is
 * answered once its record is forced in the log; the store's applier then writes many commits at once into the column
 * files and publishes them. The batch counters move right after a batch is published, so a reader the publish woke
 * may see them a moment behind.
 *
 * @param table           the table's name
 * @param suspended       true when the table refuses commits until the store reopens: a failed force or apply
 * @param appliedTxn      the last transaction published to readers
 * @param loggedTxn       the last transaction answered, in the log
 * @param pendingTxns     commits logged and not yet applied
 * @param pendingRows     their rows
 * @param pendingBytes    their rows' estimated heap bytes
 * @param lagMillis       the age of the oldest commit not yet applied, 0 when none
 * @param intervalMillis  the applier's current interval for the table
 * @param applies         batches applied since the store opened
 * @param demandedApplies the batches of those a reader waited for
 * @param appliedTxns     commits applied by those batches
 * @param appliedRows     rows applied by those batches
 * @param lastApplyMicros the last batch's duration
 * @param applyMicros     every batch's duration, summed
 * @param checkpoints     checkpoints since the store opened
 * @param checkpointTxn   the transaction the durable {@code _txn} names
 * @param logBytes        the log file's length
 * @param memoryPressure  0 below half the store's queued budget, 1 above it, 2 from a commit's wait for the applier
 *                        until the queue falls under half the budget again
 * @param lastError       the last apply or checkpoint failure, null when none
 */
public record WalStatus(String table, boolean suspended, long appliedTxn, long loggedTxn, long pendingTxns,
        long pendingRows, long pendingBytes, long lagMillis, long intervalMillis, long applies, long demandedApplies,
        long appliedTxns, long appliedRows, long lastApplyMicros, long applyMicros, long checkpoints,
        long checkpointTxn, long logBytes, int memoryPressure, String lastError) {
}
