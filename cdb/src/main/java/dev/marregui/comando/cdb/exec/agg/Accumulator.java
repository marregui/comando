package dev.marregui.comando.cdb.exec.agg;

import dev.marregui.comando.cdb.exec.Vector;

/**
 * The state of one aggregate for every group of an operator, groups numbered from 0. A group's state starts empty:
 * its result is then the aggregate's value over no rows (0 for the counts, NULL for the others).
 */
public interface Accumulator {

    /** Makes room for groups {@code [0, groups)}; call before adding to a new group. */
    void ensure(int groups);

    /** Adds value {@code row} of {@code value} (null for {@code count()}, which reads no value) to {@code group}. */
    void add(int group, Vector value, int row);

    /** Adds value {@code i} of {@code value} to group {@code groups[i]}, for {@code i} in {@code [0, size)}. */
    void addAll(int[] groups, Vector value, int size);

    /** Writes the result of {@code group} to value {@code row} of {@code out}, a vector of the aggregate's type. */
    void write(int group, Vector out, int row);
}
