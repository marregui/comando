package dev.marregui.comando.testkit;

import dev.marregui.comando.core.intent.Intent;
import dev.marregui.comando.core.port.IntentSink;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/** An intent sink that records what adapters send and lets a test wait for a matching intent. */
public final class RecordingSink implements IntentSink {

    private final List<Intent> intents = new ArrayList<>();

    @Override
    public synchronized void dispatch(Intent intent) {
        intents.add(intent);
        notifyAll();
    }

    public synchronized List<Intent> intents() {
        return List.copyOf(intents);
    }

    public synchronized <T extends Intent> List<T> of(Class<T> type) {
        return intents.stream().filter(type::isInstance).map(type::cast).toList();
    }

    /** Waits until an intent of {@code type} matching {@code match} arrives; fails after {@code timeout}. */
    public synchronized <T extends Intent> T await(Class<T> type, Predicate<T> match, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        int seen = 0;
        while (true) {
            for (; seen < intents.size(); seen++) {
                Intent i = intents.get(seen);
                if (type.isInstance(i) && match.test(type.cast(i))) {
                    return type.cast(i);
                }
            }
            long left = deadline - System.nanoTime();
            if (left <= 0) {
                throw new AssertionError("no " + type.getSimpleName() + " within " + timeout + "; got " + intents);
            }
            try {
                wait(Math.max(1, left / 1_000_000L));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("interrupted", e);
            }
        }
    }

    public <T extends Intent> T await(Class<T> type, Duration timeout) {
        return await(type, x -> true, timeout);
    }

    public synchronized void clear() {
        intents.clear();
    }
}
