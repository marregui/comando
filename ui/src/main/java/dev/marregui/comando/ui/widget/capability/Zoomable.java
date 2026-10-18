package dev.marregui.comando.ui.widget.capability;

/** A widget that scales its content; positive steps enlarge. Text zooms between {@link #MIN_SIZE} and {@link #MAX_SIZE}. */
public interface Zoomable {

    float MIN_SIZE = 8f;
    float MAX_SIZE = 40f;

    void zoom(int steps);

    void resetZoom();

    /** {@code size}, in points, kept between {@link #MIN_SIZE} and {@link #MAX_SIZE}. */
    static float clampSize(float size) {
        return Math.max(MIN_SIZE, Math.min(MAX_SIZE, size));
    }
}
