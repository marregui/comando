package dev.marregui.comando.core.state;

import java.util.Objects;

/**
 * A message for the status bar.
 *
 * @param subject what the notice is about, so a later event about the same thing can replace or clear it: one of
 *                {@link #panel}, {@link #connection}, {@link #session}, {@link #approval}, or empty for a general notice
 */
public record Notice(Level level, String text, long atMillis, String subject) {

    public enum Level { INFO, WARN, ERROR }

    public static final Notice NONE = new Notice(Level.INFO, "", 0L, "");

    public Notice {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(text, "text");
        subject = subject == null ? "" : subject;
    }

    public Notice(Level level, String text, long atMillis) {
        this(level, text, atMillis, "");
    }

    public boolean isEmpty() {
        return text.isEmpty();
    }

    /** About a panel's run: refusals, SQL errors, its results. */
    public static String panel(String panelId) {
        return "panel:" + panelId;
    }

    /** About a connection: open, failure, loss, test. */
    public static String connection(String name) {
        return "connection:" + name;
    }

    /** About a session: start, turn errors, exit. */
    public static String session(String name) {
        return "session:" + name;
    }

    /** About an approval card while it waits. */
    public static String approval(long id) {
        return "approval:" + id;
    }
}
