package dev.marregui.comando.cdb.store;

/**
 * One partition of a {@link TableReader}'s snapshot: its rows are in designated-timestamp order and do not change for
 * the life of the reader. A column added after the partition was written reads as NULL. Reads copy {@code n} values
 * from row {@code from} into {@code dst} from index 0; {@code from + n} is at most {@link #rowCount()}.
 */
public interface PartitionReader {

    /** The inclusive start of the partition's time range, microseconds (its floor by the table's partitioning). */
    long lowerBound();

    /** The smallest designated timestamp in the partition. */
    long minTimestamp();

    /** The largest designated timestamp in the partition. */
    long maxTimestamp();

    long rowCount();

    /** LONG and TIMESTAMP columns. */
    void longs(int column, long from, int n, long[] dst);

    /** DOUBLE columns. */
    void doubles(int column, long from, int n, double[] dst);

    /** BOOLEAN columns. */
    void booleans(int column, long from, int n, boolean[] dst);

    /** SYMBOL (resolved through the table's dictionary) and VARCHAR columns; NULL as a null reference. */
    void texts(int column, long from, int n, String[] dst);
}
