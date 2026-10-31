package dev.marregui.comando.ui.panels;

import java.awt.Color;
import java.awt.Point;
import java.awt.Rectangle;
import java.util.Optional;

import dev.marregui.comando.core.model.ConnectionSpec;
import dev.marregui.comando.core.model.Status;
import dev.marregui.comando.core.state.ConnectionState;
import dev.marregui.comando.ui.kit.icon.Icons;
import dev.marregui.comando.ui.kit.text.MarkdownHtml;
import dev.marregui.comando.ui.kit.theme.Theme;

/**
 * One connection in the sidebar's list, one line, as a session's (record 92): its state icon, which a click connects or
 * disconnects (the database in blue while connected, grey while not, faint while it connects or closes, red when it
 * failed, one glyph at one size), and its name, in the accent colour while the focused panel uses it, with a red "prod"
 * for a production database. Everything else is in its menu, and its host, database, user and the rest are on the card
 * its ⓘ shows, which has no summary.
 */
final class ConnectionRow extends IconRow {

    /** Shows {@code c}: the name in the accent colour when the focused panel uses it ({@code focused}). */
    ConnectionRow show(final ConnectionState c, final boolean focused, final boolean selected, final Color background, final Color foreground) {
        setOpaque(true);
        setBackground(background);
        title.setForeground(foreground);
        title.setText(titleHtml(c, focused));
        power.setIcon(Icons.get(icon(c.status())));
        power.setToolTipText(powerTip(c));
        return this;
    }

    /** Whether {@code p}, in the cell this row was laid out to, is on the state icon. */
    boolean hit(final Rectangle cell, final Point p) {
        return onIcon(cell, p);
    }

    /** The tooltip of the state icon or the ⓘ at {@code p}; empty over the name. */
    Optional<String> tip(final Rectangle cell, final Point p) {
        return iconTip(cell, p);
    }

    /** The state icon, the same glyph filled in at one size in every state. */
    static String icon(final Status status) {
        return switch (status) {
            case OPEN -> "database";
            case OPENING, CLOSING -> "database-starting";
            case FAILED -> "database-failed";
            case CLOSED -> "database-off";
        };
    }

    /** What the state icon says and does. */
    static String powerTip(final ConnectionState c) {
        return switch (c.status()) {
            case OPEN -> "Connected; click to disconnect";
            case OPENING -> "Connecting";
            case CLOSING -> "Disconnecting";
            case FAILED -> "Failed" + (c.error().isEmpty() ? "" : ": " + c.error()) + "; click to connect";
            case CLOSED -> "Disconnected; click to connect";
        };
    }

    /** "connected", "connecting", "disconnecting", "disconnected", or "failed: ‹error›". */
    static String stateText(final ConnectionState c) {
        return switch (c.status()) {
            case OPEN -> "connected";
            case OPENING -> "connecting";
            case CLOSING -> "disconnecting";
            case FAILED -> c.error().isEmpty() ? "failed" : "failed: " + c.error();
            case CLOSED -> "disconnected";
        };
    }

    /** What the menu's Copy details and ⌘C put on the clipboard, one fact a line; never the password. */
    static String details(final ConnectionState c) {
        final ConnectionSpec s = c.spec();
        final StringBuilder sb = new StringBuilder();
        sb.append("Connection: ").append(s.name()).append('\n');
        sb.append("Host: ").append(s.host()).append(':').append(s.port()).append('\n');
        sb.append("Database: ").append(s.database()).append('\n');
        if (!s.user().isEmpty()) {
            sb.append("User: ").append(s.user()).append('\n');
        }
        sb.append("TLS: ").append(s.sslMode()).append('\n');
        sb.append("Status: ").append(stateText(c)).append('\n');
        if (s.prod()) {
            sb.append("Production: Claude's writes are refused\n");
        }
        return sb.toString();
    }

    /** The name, in the accent colour while the focused panel uses it, and "prod" in red. Never wraps. */
    static String titleHtml(final ConnectionState c, final boolean focused) {
        final String prod = c.spec().prod() ? "&nbsp;<span style='color:" + Theme.hex(Theme.DANGER) + "'>prod</span>" : "";
        final String name = focused ? "<b style='color:" + Theme.hex(Theme.tint().body) + "'>" : "<b>";
        return "<html><nobr>" + name + MarkdownHtml.escape(c.spec().name()) + "</b>" + prod + "</nobr></html>";
    }
}
