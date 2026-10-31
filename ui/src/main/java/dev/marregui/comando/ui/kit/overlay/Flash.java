package dev.marregui.comando.ui.kit.overlay;

import java.awt.Color;
import java.awt.geom.Rectangle2D;
import java.util.function.Consumer;
import javax.swing.JComponent;
import javax.swing.Timer;
import javax.swing.text.BadLocationException;
import javax.swing.text.Highlighter;

import org.fife.ui.rtextarea.RTextArea;

import dev.marregui.comando.ui.kit.theme.Theme;

/**
 * The hint on what a click brought into view (a notice's line in the log, a row, a block): the amber of a warning,
 * fading from {@link #START_ALPHA} to nothing over {@link #MILLIS}. One flash at a time on a component: a new one ends
 * the one before.
 */
public final class Flash {

    public static final long MILLIS = 2_000;
    static final int STEP_MILLIS = 40;
    static final int START_ALPHA = 140;
    private static final String RUNNING = "comando.flash";

    private Flash() {
    }

    /** The colour {@code elapsedMillis} into a flash: the warning amber, its alpha falling to 0 at {@link #MILLIS}. */
    public static Color at(final long elapsedMillis) {
        final long left = Math.max(0, MILLIS - elapsedMillis);
        return Theme.withAlpha(Theme.ATTENTION, (int) (START_ALPHA * left / MILLIS));
    }

    /**
     * Flashes on {@code owner}: {@code paint} receives the colour at once and then every step, {@code done} runs once
     * the flash ends or a newer one on the owner ends it. Called on the EDT.
     */
    public static void start(final JComponent owner, final Consumer<Color> paint, final Runnable done) {
        if (owner.getClientProperty(RUNNING) instanceof Running before) {
            before.end();
        }
        final long start = System.nanoTime();
        final Running running = new Running(owner, done);
        running.timer = new Timer(STEP_MILLIS, e -> {
            final long elapsed = (System.nanoTime() - start) / 1_000_000;
            if (elapsed >= MILLIS) {
                running.end();
            } else {
                paint.accept(at(elapsed));
            }
        });
        owner.putClientProperty(RUNNING, running);
        paint.accept(at(0));
        running.timer.start();
    }

    /**
     * Flashes the whole width of the line that holds {@code offset} in {@code area}, over the caret line's highlight
     * and under the text. Called on the EDT.
     */
    public static void line(final RTextArea area, final int offset) {
        int start;
        int end;
        try {
            final int line = area.getLineOfOffset(offset);
            start = area.getLineStartOffset(line);
            end = Math.max(start + 1, area.getLineEndOffset(line));
        } catch (final BadLocationException e) {
            return;
        }
        final Color[] now = {at(0)};
        // a text highlight, not a line highlight: the caret's line paints its own over the line highlights
        final Highlighter.HighlightPainter painter = (g, p0, p1, bounds, c) -> {
            try {
                final Rectangle2D r = c.modelToView2D(p0);
                if (r != null) {
                    g.setColor(now[0]);
                    g.fillRect(0, (int) r.getY(), c.getWidth(), (int) Math.ceil(r.getHeight()));
                }
            } catch (final BadLocationException e) {
                // the text changed under the flash: nothing left to mark
            }
        };
        Object tag;
        try {
            tag = area.getHighlighter().addHighlight(start, Math.min(end, area.getDocument().getLength()), painter);
        } catch (final BadLocationException e) {
            return;
        }
        start(area, color -> {
            now[0] = color;
            repaintLine(area, start);
        }, () -> {
            area.getHighlighter().removeHighlight(tag);
            repaintLine(area, start);
        });
    }

    private static void repaintLine(final RTextArea area, final int offset) {
        try {
            // null while the area is not laid out, when nothing of it is drawn yet
            final Rectangle2D r = area.modelToView2D(Math.min(offset, area.getDocument().getLength()));
            if (r != null) {
                area.repaint(0, (int) r.getY(), area.getWidth(), (int) Math.ceil(r.getHeight()));
            }
        } catch (final BadLocationException e) {
            area.repaint();
        }
    }

    /** Whether a flash runs on {@code owner} now; for tests. */
    static boolean running(final JComponent owner) {
        return owner.getClientProperty(RUNNING) != null;
    }

    private static final class Running {
        private final JComponent owner;
        private final Runnable done;
        private Timer timer;
        private boolean ended;

        Running(final JComponent owner, final Runnable done) {
            this.owner = owner;
            this.done = done;
        }

        void end() {
            if (ended) {
                return;
            }
            ended = true;
            timer.stop();
            if (owner.getClientProperty(RUNNING) == this) {
                owner.putClientProperty(RUNNING, null);
            }
            done.run();
        }
    }
}
