package dev.marregui.comando.core.port;

/**
 * A local server an agent starts and stops as the user's toggle does (record 121): the comando Edge or the embedded
 * QuestDB. Unlike the toggle, the caller names the direction, so a stale "running" never turns a start into a stop.
 */
public interface ServerSwitch {

    /** As the toggle's tooltip names it: {@code Edge}, {@code QuestDB}. */
    String name();

    boolean isRunning();

    /**
     * Starts the server when {@code up}, stops it otherwise, off the caller's thread; the outcome comes back as a
     * notice. Returns at once with what was done: a start or stop under way, nothing to do, or a refusal that begins
     * with {@code refused}.
     */
    String want(boolean up);

    /** A start or stop under way, or who runs it when another instance does; empty when there is nothing to add. */
    default String status() {
        return "";
    }
}
