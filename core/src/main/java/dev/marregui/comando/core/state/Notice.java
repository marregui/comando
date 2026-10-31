package dev.marregui.comando.core.state;

import java.util.Objects;

/**
 * A message for the status bar.
 *
 * @param subject what the notice is about, so a later event about the same thing can replace or clear it, and where a
 *                click on it goes: one of {@link #panel}, {@link #connection}, {@link #session}, {@link #folder},
 *                {@link #approval}, {@link #log}, or empty for a general notice
 */
public record Notice(Level level, String text, long atMillis, String subject) {

    public enum Level { INFO, WARN, ERROR }

    public static final Notice NONE = new Notice(Level.INFO, "", 0L, "");

    public Notice {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(text, "text");
        subject = subject == null ? "" : subject;
    }

    public Notice(final Level level, final String text, final long atMillis) {
        this(level, text, atMillis, "");
    }

    public boolean isEmpty() {
        return text.isEmpty();
    }

    /** About a panel's run: refusals, SQL errors, its results. */
    public static String panel(final String panelId) {
        return "panel:" + panelId;
    }

    /** About a connection: open, failure, loss, test. */
    public static String connection(final String name) {
        return "connection:" + name;
    }

    /** About a session: start, turn errors, exit. */
    public static String session(final String name) {
        return "session:" + name;
    }

    /** About a folder of the Files tab: saving it, reading it. */
    public static String folder(final String name) {
        return "folder:" + name;
    }

    /** About an approval card while it waits. */
    public static String approval(final long id) {
        return "approval:" + id;
    }

    /**
     * About the entry of comando's log written at {@code atMillis} or within a second after it: the time is read just
     * before the entry is logged, so the entry's own time is never earlier.
     */
    public static String log(final long atMillis) {
        return "log:" + atMillis;
    }

    /** The kind of the subject, the part before its first {@code :}; empty for a general notice. */
    public String kind() {
        final int colon = subject.indexOf(':');
        return colon < 0 ? "" : subject.substring(0, colon);
    }

    /** What the subject names within its kind, the part after its first {@code :}; empty for a general notice. */
    public String key() {
        final int colon = subject.indexOf(':');
        return colon < 0 ? "" : subject.substring(colon + 1);
    }
}
