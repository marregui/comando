package dev.marregui.comando.cdb.serve;

import dev.marregui.comando.cdb.CdbException;

/** The read lag a client asks for, over HTTP ({@code max_lag_ms}) or the PG wire ({@code cdb.max_lag_ms}). */
final class ReadLag {

    private ReadLag() {
    }

    /**
     * The milliseconds in {@code text}, boxed once for the query it binds; null when {@code text} is null (none asked).
     * Refused, naming {@code name}, when it is no whole number or is negative.
     */
    static Long parse(final String name, final String text) {
        if (text == null) {
            return null;
        }
        final long millis;
        try {
            millis = Long.parseLong(text.strip());
        } catch (final NumberFormatException _) {
            throw invalid(name, text);
        }
        if (millis < 0L) {
            throw invalid(name, text);
        }
        return millis;
    }

    private static CdbException invalid(final String name, final String text) {
        return new CdbException("invalid " + name + " [" + name + '=' + text + ']', -1);
    }
}
