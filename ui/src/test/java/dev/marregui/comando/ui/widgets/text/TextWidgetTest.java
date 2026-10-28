package dev.marregui.comando.ui.widgets.text;

import dev.marregui.comando.ui.kit.theme.Theme;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.junit.jupiter.api.Test;

import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/// [TextWidget]'s zoom.
/// - zoom stays between 8 and 40 points and resets to the theme's mono size
class TextWidgetTest {

    private static RSyntaxTextArea area(Component c) {
        if (c instanceof RSyntaxTextArea a) {
            return a;
        }
        if (c instanceof Container k) {
            for (Component child : k.getComponents()) {
                RSyntaxTextArea a = area(child);
                if (a != null) {
                    return a;
                }
            }
        }
        return null;
    }

    /// Sixty steps in stop at 40 pt, a large step out stops at 8 pt, and reset returns to [Theme#mono]'s size.
    @Test
    void zoomStaysBetween8And40PointsAndResets() throws Exception {
        AtomicReference<Float> largest = new AtomicReference<>();
        AtomicReference<Float> smallest = new AtomicReference<>();
        AtomicReference<Float> reset = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            TextWidget w = new TextWidget();
            RSyntaxTextArea area = area(w.component());
            for (int i = 0; i < 60; i++) {
                w.zoom(1);
            }
            largest.set(area.getFont().getSize2D());
            w.zoom(-100);
            smallest.set(area.getFont().getSize2D());
            w.resetZoom();
            reset.set(area.getFont().getSize2D());
        });
        assertThat(largest.get()).isEqualTo(40f);
        assertThat(smallest.get()).isEqualTo(8f);
        assertThat(reset.get()).isEqualTo(Theme.mono().getSize2D());
    }
}
