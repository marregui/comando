package dev.marregui.comando.ui.kit.async;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// [Waiters] lets callers for one key share one run of its work and tells each callback once when it ends.
class WaitersTest {

    /// Two callers for one key: only the first starts the work, and finishing tells each callback once.
    @Test
    void twoCallersForOneKeyStartOneRunAndEachIsToldOnce() {
        final Waiters<String> waiters = new Waiters<>();
        final AtomicInteger a = new AtomicInteger();
        final AtomicInteger b = new AtomicInteger();
        final Runnable tellA = a::incrementAndGet;
        final Runnable tellB = b::incrementAndGet;

        assertThat(waiters.join("k", tellA)).as("the first caller starts the work").isTrue();
        assertThat(waiters.join("k", tellB)).as("the second joins it").isFalse();
        assertThat(waiters.join("other", tellB)).as("another key is other work").isTrue();
        waiters.finish("k");
        waiters.finish("k");

        assertThat(a).hasValue(1);
        assertThat(b).hasValue(1);
        assertThat(waiters.join("k", tellA)).as("after it ends, the next caller starts it again").isTrue();
    }

    /// The same callback handed in twice while it waits, as a preview rendered again does, is told once.
    @Test
    void theSameCallbackTwiceIsToldOnce() {
        final Waiters<String> waiters = new Waiters<>();
        final AtomicInteger told = new AtomicInteger();
        final Runnable tell = told::incrementAndGet;

        waiters.join("k", tell);
        waiters.join("k", tell);
        waiters.finish("k");

        assertThat(told).hasValue(1);
    }

    /// Abandoned work tells no one, and the next caller starts it afresh.
    @Test
    void abandonedWorkTellsNoOne() {
        final Waiters<String> waiters = new Waiters<>();
        final AtomicInteger told = new AtomicInteger();

        waiters.join("k", told::incrementAndGet);
        waiters.abandon("k");
        waiters.finish("k");

        assertThat(told).hasValue(0);
        assertThat(waiters.join("k", told::incrementAndGet)).isTrue();
    }
}
