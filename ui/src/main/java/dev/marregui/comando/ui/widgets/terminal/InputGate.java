package dev.marregui.comando.ui.widgets.terminal;

import java.util.ArrayDeque;
import java.util.function.Consumer;

/**
 * Holds text for a shell that has just started until it is ready: its first output followed by
 * {@link #SETTLE_NANOS} without output (the rc files ran and the prompt is drawn), or {@link #LIMIT_NANOS}
 * after the start, whichever comes first. Text is written in the order it was sent; nothing is dropped.
 * Not thread-safe: the terminal calls it on the EDT, and nothing in it waits.
 */
final class InputGate {

    static final long SETTLE_NANOS = 200_000_000L;
    static final long LIMIT_NANOS = 3_000_000_000L;
    /** The shell has not written yet. */
    static final long NO_OUTPUT = Long.MIN_VALUE;

    private final Consumer<String> writer;
    private final ArrayDeque<String> pending = new ArrayDeque<>();
    private long startedNanos;
    private boolean open = true;

    InputGate(Consumer<String> writer) {
        this.writer = writer;
    }

    /** A shell started at {@code nowNanos}: text waits until it is ready. */
    void started(long nowNanos) {
        startedNanos = nowNanos;
        open = false;
    }

    /**
     * Writes {@code text} now when the shell is ready and nothing waits before it, else queues it; {@code
     * lastOutputNanos} is when the shell last wrote, or {@link #NO_OUTPUT}. Returns whether text still waits, so the
     * caller polls again.
     */
    boolean send(String text, long nowNanos, long lastOutputNanos) {
        pending.addLast(text);
        return poll(nowNanos, lastOutputNanos);
    }

    /** Opens the gate when the shell is ready and writes what waits; returns whether text still waits. */
    boolean poll(long nowNanos, long lastOutputNanos) {
        if (!open && ((lastOutputNanos != NO_OUTPUT && nowNanos - lastOutputNanos >= SETTLE_NANOS) || nowNanos - startedNanos >= LIMIT_NANOS)) {
            open = true;
        }
        if (open) {
            while (!pending.isEmpty()) {
                writer.accept(pending.removeFirst());
            }
        }
        return !pending.isEmpty();
    }

    boolean isOpen() {
        return open;
    }

    /** Drops the text that waits: the shell it was meant for is gone, and a new one must never receive it. */
    void clear() {
        pending.clear();
    }

    /** How many texts wait. */
    int waiting() {
        return pending.size();
    }
}
