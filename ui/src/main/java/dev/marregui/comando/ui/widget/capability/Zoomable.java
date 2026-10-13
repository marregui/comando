package dev.marregui.comando.ui.widget.capability;

/** A widget that scales its content; positive steps enlarge. */
public interface Zoomable {

    void zoom(int steps);

    void resetZoom();
}
