package dev.marregui.comando.ui.widgets.plot;

import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import javax.swing.JComponent;

import dev.marregui.comando.core.view.ViewSpec;
import dev.marregui.comando.ui.kit.async.Edt;
import dev.marregui.comando.ui.kit.async.UiErrands;
import dev.marregui.comando.ui.kit.overlay.Overlay;
import dev.marregui.comando.ui.widget.Widget;
import dev.marregui.comando.ui.widget.capability.Capabilities;
import dev.marregui.comando.ui.widget.capability.Exportable;
import dev.marregui.comando.ui.widget.capability.Zoomable;

/** Series plotted over shared axes; drag to zoom, double-click to reset, export as PNG. */
public final class PlotWidget implements Widget<ViewSpec.Plot>, Zoomable, Exportable {

    /** Points prepared on the event thread at once (tens of milliseconds at most); more are prepared off it. */
    static final long PREPARE_HERE = 100_000;

    private final PlotCanvas canvas = new PlotCanvas();

    private final Overlay overlay = new Overlay(canvas);
    /** The latest render's number; a preparation that finishes after a newer render shows nothing. On the EDT. */
    private long generation;
    /** Whether a plot shows: a newer one is prepared behind it rather than behind the loading state. */
    private boolean shown;

    public PlotWidget() {
        Capabilities.mark(canvas, this);
        canvas.setToolTipText(null);
    }

    @Override
    public JComponent component() {
        return overlay;
    }

    @Override
    public void render(final ViewSpec.Plot view) {
        final long g = ++generation;
        final long points = view.series().stream().mapToLong(s -> s.x().length).sum();
        if (points == 0) {
            canvas.setData(PlotCanvas.prepare(view));
            overlay.showEmpty("No points to plot");
            shown = false;
        } else if (points <= PREPARE_HERE) {
            canvas.setData(PlotCanvas.prepare(view));
            overlay.showContent();
            shown = true;
        } else {
            if (!shown) {
                overlay.showLoading("Plotting " + points + " points…");
            }
            // sorting millions of points takes the event thread half a second or more; a live plot does it every period
            UiErrands.start("plot-prepare", () -> {
                final PlotCanvas.Prepared p = PlotCanvas.prepare(view);
                Edt.later(() -> {
                    if (g == generation) {
                        canvas.setData(p);
                        overlay.showContent();
                        shown = true;
                    }
                });
            });
        }
    }

    @Override
    public void zoom(final int steps) {
        canvas.zoom(steps);
    }

    @Override
    public void resetZoom() {
        canvas.reset();
    }

    @Override
    public String exportName() {
        return "plot.png";
    }

    @Override
    public Export prepare() {
        final BufferedImage image = canvas.snapshot();
        return file -> ImageIO.write(image, "png", file.toFile());
    }
}
