package dev.marregui.comando.frontear.alexander;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import dev.marregui.comando.frontear.octopus.Direction;
import dev.marregui.comando.frontear.octopus.LinkSpec;
import dev.marregui.comando.frontear.octopus.LinkState;
import dev.marregui.comando.frontear.octopus.Octopus;

/// An octopus that records what it was asked, for a test to read from any thread. A put adds or replaces the link's
/// state (disconnected, all counters 0) and a remove drops it; a test may also set the state outright, make every call
/// fail, make each put throw an `Error`, or hold each put until it releases `hold`.
final class FakeOctopus implements Octopus {

    /// How long a held put waits for its release at most, so a failed test cannot hang the strand.
    private static final Duration HOLD_MAX = Duration.ofSeconds(10);

    final List<LinkSpec> puts = new CopyOnWriteArrayList<>();
    final List<String> removes = new CopyOnWriteArrayList<>();
    final List<Filters> filters = new CopyOnWriteArrayList<>();
    /// The links' state, replaced whole; written by the test and the caller of the octopus, read by both.
    volatile List<LinkState> state = List.of();
    /// Thrown by every call when not null; written by the test, read by the caller of the octopus.
    volatile RuntimeException failure;
    /// Each put waits for it when not null; written by the test, read by the caller of the octopus.
    volatile CountDownLatch hold;
    /// Thrown by each put when not null; written by the test, read by the caller of the octopus.
    volatile Error putError;

    @Override
    public void put(final LinkSpec spec) {
        fail();
        final Error error = putError;
        if (error != null) {
            throw error;
        }
        final CountDownLatch latch = hold;
        if (latch != null) {
            try {
                if (!latch.await(HOLD_MAX.toMillis(), TimeUnit.MILLISECONDS)) {
                    throw new IllegalStateException("put held past its bound [id=" + spec.id() + ']');
                }
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("put interrupted [id=" + spec.id() + ']', e);
            }
        }
        puts.add(spec);
        final List<LinkState> next = new ArrayList<>();
        for (final LinkState link : state) {
            if (!link.id().equals(spec.id())) {
                next.add(link);
            }
        }
        next.add(new LinkState(spec.id(), false, 0, 0, 0, 0, 0));
        state = List.copyOf(next);
    }

    @Override
    public void remove(final String id) {
        fail();
        removes.add(id);
        final List<LinkState> next = new ArrayList<>();
        for (final LinkState link : state) {
            if (!link.id().equals(id)) {
                next.add(link);
            }
        }
        state = List.copyOf(next);
    }

    @Override
    public void filters(final String id, final Direction direction, final Set<String> filters) {
        fail();
        this.filters.add(new Filters(id, direction, filters));
    }

    @Override
    public List<LinkState> state() {
        fail();
        return state;
    }

    private void fail() {
        final RuntimeException e = failure;
        if (e != null) {
            throw e;
        }
    }

    /// One `filters` call.
    record Filters(String id, Direction direction, Set<String> filters) {
    }
}
