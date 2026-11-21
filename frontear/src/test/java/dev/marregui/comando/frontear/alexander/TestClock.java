package dev.marregui.comando.frontear.alexander;

import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.concurrent.atomic.AtomicLong;

/// A clock the test moves by hand, read by the code under test from any thread.
final class TestClock implements InstantSource {

    private final AtomicLong nowMillis;

    TestClock(final long startMillis) {
        this.nowMillis = new AtomicLong(startMillis);
    }

    @Override
    public Instant instant() {
        return Instant.ofEpochMilli(nowMillis.get());
    }

    @Override
    public long millis() {
        return nowMillis.get();
    }

    void advance(final Duration by) {
        nowMillis.addAndGet(by.toMillis());
    }

    void set(final long epochMillis) {
        nowMillis.set(epochMillis);
    }
}
