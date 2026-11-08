package dev.marregui.comando.ui.widgets.text;

import java.awt.Component;
import java.awt.Container;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.AbstractButton;
import javax.swing.SwingUtilities;

import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.junit.jupiter.api.Test;

import dev.marregui.comando.core.view.ViewSpec;
import dev.marregui.comando.ui.kit.theme.Theme;

import static org.assertj.core.api.Assertions.assertThat;

/// [TextWidget]'s zoom and its way to a panel.
/// - zoom stays between 8 and 40 points and resets to the theme's mono size
/// - "Open in a panel" opens the file shown at its line in comando's editor; with no opener it is not offered
class TextWidgetTest {

    private static AbstractButton button(final Component c, final String name) {
        if (c instanceof AbstractButton b && name.equals(b.getAccessibleContext().getAccessibleName())) {
            return b;
        }
        if (c instanceof Container k) {
            for (final Component child : k.getComponents()) {
                final AbstractButton b = button(child, name);
                if (b != null) {
                    return b;
                }
            }
        }
        return null;
    }

    private static RSyntaxTextArea area(final Component c) {
        if (c instanceof RSyntaxTextArea a) {
            return a;
        }
        if (c instanceof Container k) {
            for (final Component child : k.getComponents()) {
                final RSyntaxTextArea a = area(child);
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
        final AtomicReference<Float> largest = new AtomicReference<>();
        final AtomicReference<Float> smallest = new AtomicReference<>();
        final AtomicReference<Float> reset = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            final TextWidget w = new TextWidget(null);
            final RSyntaxTextArea area = area(w.component());
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

    /// The button opens the file shown, at the line shown, through the opener given; without one it is hidden.
    @Test
    void openInAPanelOpensTheFileAtItsLine() throws Exception {
        final List<String> opened = new CopyOnWriteArrayList<>();
        final AtomicReference<Boolean> offered = new AtomicReference<>();
        final AtomicReference<Boolean> offeredWithout = new AtomicReference<>();
        final Path file = Path.of("/w/notes.txt").toAbsolutePath();
        SwingUtilities.invokeAndWait(() -> {
            final TextWidget w = new TextWidget((f, line) -> opened.add(f + ":" + line));
            w.render(new ViewSpec.Text(file.toString(), "one\ntwo\nthree\n", "", 2));
            final AbstractButton b = button(w.component(), "Open in a panel");
            offered.set(b.isVisible() && b.getParent().isVisible());
            b.doClick();
            final TextWidget without = new TextWidget(null);
            without.render(new ViewSpec.Text(file.toString(), "one\n", "", 1));
            offeredWithout.set(button(without.component(), "Open in a panel").isVisible());
        });
        assertThat(offered.get()).isTrue();
        assertThat(opened).containsExactly(file + ":2");
        assertThat(offeredWithout.get()).isFalse();
    }
}
