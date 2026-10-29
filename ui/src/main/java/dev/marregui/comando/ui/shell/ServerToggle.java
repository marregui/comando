package dev.marregui.comando.ui.shell;

import java.nio.file.Path;

/** A local server comando can start and stop at the top of the workspace: the embedded QuestDB, or the comando Edge. */
public interface ServerToggle {

    String name();

    boolean isRunning();

    /** Starts or stops it off the EDT; the outcome comes back as a notice. */
    void toggle();

    /** A start or stop is under way: its button is disabled until it ends. */
    default boolean busy() {
        return false;
    }

    /**
     * What its button's tooltip adds now, read once a second: a start or stop under way, or that another comando runs it
     * and only that one stops it; empty when there is nothing to add.
     */
    default String status() {
        return "";
    }

    /** The log it writes now, in this instance's data root, for Help to show; null when it keeps none. */
    default Path log() {
        return null;
    }
}
