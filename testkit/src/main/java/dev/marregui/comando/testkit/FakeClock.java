package dev.marregui.comando.testkit;

import java.util.concurrent.atomic.AtomicLong;

import dev.marregui.comando.core.port.Clock;

/** A clock tests set and advance. */
public final class FakeClock implements Clock {

    private final AtomicLong now;

    public FakeClock(final long start) {
        now = new AtomicLong(start);
    }

    public FakeClock() {
        this(1_000_000L);
    }

    @Override
    public long millis() {
        return now.get();
    }

    public void advance(final long millis) {
        now.addAndGet(millis);
    }
}
