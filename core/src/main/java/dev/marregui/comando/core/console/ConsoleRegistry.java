package dev.marregui.comando.core.console;

import java.util.concurrent.ConcurrentHashMap;

/** Consoles by run id; a console view refers to its output by that id. */
public final class ConsoleRegistry {

    private final ConcurrentHashMap<Long, Console> consoles = new ConcurrentHashMap<>();

    public Console of(final long runId) {
        return consoles.computeIfAbsent(runId, k -> new Console());
    }

    public void forget(final long runId) {
        consoles.remove(runId);
    }

    /** Consoles held; a closed run's console is released, so this does not grow with every run. */
    public int size() {
        return consoles.size();
    }
}
