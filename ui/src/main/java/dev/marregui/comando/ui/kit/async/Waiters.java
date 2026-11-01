package dev.marregui.comando.ui.kit.async;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Who waits for each piece of background work, by key, so callers asking for the same key share one run of it.
 * The first caller for a key starts the work; every caller's callback is told once when it ends, however often the
 * same callback is handed in meanwhile.
 *
 * <p>Thread-safe. Invariant: a key is held from its first {@link #join} until {@link #finish} or {@link #abandon}
 * removes it, and its list of callbacks is changed only inside the map's atomic {@code compute} for that key; a join
 * after the removal starts a new list and is the first again, so a callback is never added to a list already
 * handed out and never lost. Callbacks run on the thread that finishes, outside any lock.
 *
 * @param <K> the key of one piece of work
 */
public final class Waiters<K> {

    private final Map<K, List<Runnable>> waiting = new ConcurrentHashMap<>();

    /**
     * Adds {@code whenReady} to those waiting for {@code key}, unless it is there already; true when no one waited, so
     * the caller starts the work.
     */
    public boolean join(final K key, final Runnable whenReady) {
        final boolean[] first = new boolean[1];
        waiting.compute(key, (k, list) -> {
            final List<Runnable> l = list == null ? new ArrayList<>() : list;
            first[0] = list == null;
            // a preview rendered again while it waits hands in the same callback: told once, not once per render
            if (!l.contains(whenReady)) {
                l.add(whenReady);
            }
            return l;
        });
        return first[0];
    }

    /** Tells each caller waiting for {@code key} that it is done, once, and forgets them. */
    public void finish(final K key) {
        final List<Runnable> callbacks = waiting.remove(key);
        if (callbacks != null) {
            callbacks.forEach(Runnable::run);
        }
    }

    /** Forgets the callers waiting for {@code key} without telling them: the work was refused or abandoned. */
    public void abandon(final K key) {
        waiting.remove(key);
    }
}
