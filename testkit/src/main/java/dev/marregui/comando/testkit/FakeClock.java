package dev.marregui.comando.testkit;

import dev.marregui.comando.core.port.Clock;

import java.util.concurrent.atomic.AtomicLong;

/** A clock tests set and advance. */
public final class FakeClock implements Clock {

    private final AtomicLong now;

    public FakeClock(long start) {
        now = new AtomicLong(start);
    }

    public FakeClock() {
        this(1_000_000L);
    }

    @Override
    public long millis() {
        return now.get();
    }

    public void advance(long millis) {
        now.addAndGet(millis);
    }
}
