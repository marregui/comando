package io.questdb.desktop.ui;

import org.jetbrains.annotations.NotNull;

import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.awt.event.MouseMotionListener;

/**
 * Extends {@link MouseListener} and {@link MouseMotionListener} overriding all
 * the methods with default behaviour to do nothing.
 */
public interface NoopMouseListener extends MouseListener, MouseMotionListener {

    @Override
    default void mouseClicked(final @NotNull MouseEvent e) {
        // nothing
    }

    @Override
    default void mousePressed(final @NotNull MouseEvent e) {
        // nothing
    }

    @Override
    default void mouseReleased(final @NotNull MouseEvent e) {
        // nothing
    }

    @Override
    default void mouseEntered(final @NotNull MouseEvent e) {
        // nothing
    }

    @Override
    default void mouseExited(final @NotNull MouseEvent e) {
        // nothing
    }

    @Override
    default void mouseDragged(final @NotNull MouseEvent e) {
        // nothing
    }

    @Override
    default void mouseMoved(final @NotNull MouseEvent e) {
        // nothing
    }
}
