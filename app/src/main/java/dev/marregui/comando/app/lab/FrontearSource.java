package dev.marregui.comando.app.lab;

import java.util.List;
import java.util.function.Supplier;

import dev.marregui.comando.frontear.Frontear;
import dev.marregui.comando.frontear.watch.NodeSnapshot;
import dev.marregui.comando.frontear.watch.Watch;

/**
 * The node this instance hosts now, as {@code node} answers it (null when it hosts none), read through its watch and
 * its snapshot. The drain's thread only: the node it last read is its own.
 */
public final class FrontearSource implements LabSource {

    private final Supplier<Frontear> node;
    /** The node the last {@link #since} read, its watch turned on; null before the first. */
    private Frontear watched;

    public FrontearSource(final Supplier<Frontear> node) {
        this.node = node;
    }

    @Override
    public Chunk since(final long after, final int max) {
        final Frontear now = node.get();
        if (now == null) {
            return new Chunk(List.of(), 0L, after, false);
        }
        final boolean restarted = now != watched;
        if (restarted) {
            // the watch stays on once turned on: whoever opened the lab keeps watching this node
            now.watch().on(true);
            watched = now;
        }
        final Watch.Batch batch = now.watch().since(restarted ? 0L : after, max);
        return new Chunk(batch.events(), batch.missed(), batch.last(), restarted);
    }

    @Override
    public NodeSnapshot snapshot() {
        final Frontear now = node.get();
        return now == null ? null : now.snapshot();
    }
}
