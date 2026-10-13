package dev.marregui.comando.core.state;

import java.util.Objects;

/** The last message for the status bar. */
public record Notice(Level level, String text, long atMillis) {

    public enum Level { INFO, WARN, ERROR }

    public static final Notice NONE = new Notice(Level.INFO, "", 0L);

    public Notice {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(text, "text");
    }
}
