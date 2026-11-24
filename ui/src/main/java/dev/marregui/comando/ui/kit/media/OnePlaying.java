package dev.marregui.comando.ui.kit.media;

/**
 * The videos of one window, of which one plays at a time: a video asked to play pauses the one that played before it.
 * EDT only.
 */
public final class OnePlaying {

    /** The video asked to play last and not closed since; null when none. */
    private VideoPlayer current;

    /** {@code player} was asked to play: the video that played before it pauses. */
    void started(final VideoPlayer player) {
        if (current != null && current != player) {
            current.pause();
        }
        current = player;
    }

    /** {@code player} was closed: it is no longer the one that plays. */
    void left(final VideoPlayer player) {
        if (current == player) {
            current = null;
        }
    }
}
