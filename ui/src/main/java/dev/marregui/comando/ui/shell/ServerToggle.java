package dev.marregui.comando.ui.shell;

/** A local database server comando can start and stop, such as the embedded QuestDB. */
public interface ServerToggle {

    String name();

    boolean isRunning();

    /** Starts or stops it off the EDT; the outcome comes back as a notice. */
    void toggle();
}
