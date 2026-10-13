package dev.marregui.comando.core.console;

import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The output of one command run: text appended by one writer thread and read by the EDT. Readers take
 * {@link #text(int)} from the length they already have, so a repaint copies only what is new. Output past
 * {@link #MAX_CHARS} is held back except its last {@link #TAIL_CHARS}, which {@link #seal} appends when the
 * run ends: a build's failure summary and exit line are at its end.
 */
public final class Console {

    public static final int MAX_CHARS = 4 * 1024 * 1024;
    public static final int TAIL_CHARS = 64 * 1024;

    private final StringBuilder text = new StringBuilder();
    private final CopyOnWriteArrayList<Runnable> listeners = new CopyOnWriteArrayList<>();
    private boolean truncated;
    private final StringBuilder tail = new StringBuilder();
    private long dropped;
    /** After {@link #seal}: the run's last lines go in whatever the length. */
    private boolean sealed;

    /** Appends output; writer thread only. */
    public void append(CharSequence chunk) {
        synchronized (text) {
            if (truncated) {
                keepTail(chunk);
                return;
            }
            if (!sealed && text.length() + chunk.length() > MAX_CHARS) {
                text.append("\n… output past ").append(MAX_CHARS / (1024 * 1024)).append(" MB held back; its last ")
                        .append(TAIL_CHARS / 1024).append(" kB follow when the run ends\n");
                truncated = true;
                keepTail(chunk);
            } else {
                text.append(chunk);
            }
        }
        for (Runnable l : listeners) {
            l.run();
        }
    }

    private void keepTail(CharSequence chunk) {
        tail.append(chunk);
        dropped += chunk.length();
        if (tail.length() > 2 * TAIL_CHARS) {
            tail.delete(0, tail.length() - TAIL_CHARS);
        }
    }

    /** The run ended: appends the end of any held-back output, so the last lines are always shown. */
    public void seal() {
        synchronized (text) {
            sealed = true;
            if (!truncated) {
                return;
            }
            int keep = Math.min(tail.length(), TAIL_CHARS);
            text.append("\n… ").append(dropped - keep).append(" characters not shown …\n").append(tail, tail.length() - keep, tail.length());
            tail.setLength(0);
            truncated = false;
        }
        for (Runnable l : listeners) {
            l.run();
        }
    }

    public int length() {
        synchronized (text) {
            return text.length();
        }
    }

    /** The text from {@code from} to the current end. */
    public String text(int from) {
        synchronized (text) {
            return text.substring(Math.min(from, text.length()));
        }
    }

    public void addListener(Runnable listener) {
        listeners.add(listener);
    }

    public void removeListener(Runnable listener) {
        listeners.remove(listener);
    }
}
