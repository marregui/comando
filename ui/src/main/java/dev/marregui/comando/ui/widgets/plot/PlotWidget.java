package dev.marregui.comando.ui.widgets.plot;

import dev.marregui.comando.core.view.ViewSpec;
import dev.marregui.comando.ui.kit.overlay.Overlay;
import dev.marregui.comando.ui.widget.Widget;
import dev.marregui.comando.ui.widget.capability.Capabilities;
import dev.marregui.comando.ui.widget.capability.Exportable;
import dev.marregui.comando.ui.widget.capability.Zoomable;

import javax.imageio.ImageIO;
import javax.swing.JComponent;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;

/** Series plotted over shared axes; drag to zoom, double-click to reset, export as PNG. */
public final class PlotWidget implements Widget<ViewSpec.Plot>, Zoomable, Exportable {

    private final PlotCanvas canvas = new PlotCanvas();

    private final Overlay overlay = new Overlay(canvas);

    public PlotWidget() {
        Capabilities.mark(canvas, this);
        canvas.setToolTipText(null);
    }

    @Override
    public JComponent component() {
        return overlay;
    }

    @Override
    public void render(ViewSpec.Plot view) {
        canvas.setData(view);
        boolean anyPoint = view.series().stream().anyMatch(s -> s.x().length > 0);
        if (anyPoint) {
            overlay.showContent();
        } else {
            overlay.showEmpty("No points to plot");
        }
    }

    @Override
    public void zoom(int steps) {
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
        BufferedImage image = canvas.snapshot();
        return file -> ImageIO.write(image, "png", file.toFile());
    }
}
