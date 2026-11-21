package dev.marregui.comando.app.lab;

import java.util.List;

import dev.marregui.comando.frontear.watch.NodeSnapshot;
import dev.marregui.comando.frontear.watch.Observation;

/**
 * Where the lab's drain reads this instance's node from (record 187): its watch's events and its snapshot. Called from
 * the drain's thread only.
 */
public interface LabSource {

    /**
     * The events after {@code after}, at most {@code max}, the watch turned on at the first call for a node. When the
     * node is not the one the last call read (it started again, or another took its place), the events are read from
     * its first and {@link Chunk#restarted} is set: the drain then reads on from {@link Chunk#last}. No events when no
     * node runs.
     */
    Chunk since(long after, int max);

    /** The node now; null when none runs. */
    NodeSnapshot snapshot();

    /**
     * What one {@link #since} read: {@code events} oldest first, {@code missed} those the watch overwrote before the
     * read, {@code last} the {@code after} of the next call, {@code restarted} whether they came from a node other than
     * the last call's.
     */
    record Chunk(List<Observation> events, long missed, long last, boolean restarted) {
    }
}
