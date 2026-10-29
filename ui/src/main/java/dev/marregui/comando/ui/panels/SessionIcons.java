package dev.marregui.comando.ui.panels;

import dev.marregui.comando.core.model.Status;

/**
 * The icon that says whether a session is connected, wherever a session shows (its row in the sidebar, its panel's tab
 * at rest): Claude's orange while it runs, grey while stopped, faint while it starts or stops, red when it failed; one
 * glyph, Claude's, filled at one size in every state (record 90).
 */
final class SessionIcons {

    private SessionIcons() {
    }

    static String of(Status status) {
        return switch (status) {
            case OPEN -> "claude";
            case OPENING, CLOSING -> "claude-starting";
            case FAILED -> "claude-failed";
            case CLOSED -> "claude-off";
        };
    }
}
