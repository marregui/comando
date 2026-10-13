package dev.marregui.comando.core.state;

/** Life cycle of one SQL run. */
public enum RunStatus {
    QUEUED, RUNNING, DONE, FAILED, CANCELLED;

    public boolean isActive() {
        return this == QUEUED || this == RUNNING;
    }
}
