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

    /**
     * Adds values {@code [from, to)} of {@code value} (null for {@code count()}) to {@code group}: the run of rows of
     * one group a time-ordered input gives; an accumulator overrides it with a loop free of per-row dispatch.
     */
    default void addRange(final int group, final Vector value, final int from, final int to) {
        for (int row = from; row < to; row++) {
            add(group, value, row);
        }
    }

    /**
     * Adds the state of group {@code fromGroup} of {@code from}, an accumulator of the same aggregate that saw a later
     * frame's rows, to {@code group}: the result is the one this accumulator would give had it seen both frames' rows
     * in frame order (first keeps its own value when it has one, last takes {@code from}'s when it has one). Call
     * {@link #ensure} first. {@code from} keeps its results and must not be this accumulator.
     */
    void merge(int group, Accumulator from, int fromGroup);

    /** Writes the result of {@code group} to value {@code row} of {@code out}, a vector of the aggregate's type. */
    void write(int group, Vector out, int row);
}
