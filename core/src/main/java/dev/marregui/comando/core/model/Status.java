package dev.marregui.comando.core.model;

/** Life cycle of a live resource: a database connection or a Claude Code process. */
public enum Status {
    CLOSED, OPENING, OPEN, CLOSING, FAILED;

    public boolean isUsable() {
        return this == OPEN || this == OPENING;
    }
}
