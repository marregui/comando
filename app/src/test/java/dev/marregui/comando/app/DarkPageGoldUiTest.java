package dev.marregui.comando.app;

import dev.marregui.comando.ui.kit.text.HtmlPane;
import dev.marregui.comando.ui.kit.theme.Gold;
import dev.marregui.comando.ui.kit.theme.Theme;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import javax.swing.JFrame;
import javax.swing.SwingUtilities;
import javax.swing.text.Element;
import javax.swing.text.ElementIterator;
import javax.swing.text.StyleConstants;
import javax.swing.text.html.HTML;
import javax.swing.text.html.HTMLDocument;
import java.awt.Color;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A rendered Markdown page in a window takes another gold at once, as the transcript does: it listens only while it is
 * in a window, which needs a display, so this runs in {@code ./gradlew :app:uiTest}.
 */
@EnabledIfSystemProperty(named = "comando.uiTests", matches = "true")
class DarkPageGoldUiTest {

    @Test
    void aShownPageIsDrawnAgainInTheGoldChosenAndOneTakenOutOfItsWindowNoMore() throws Exception {
        AtomicReference<JFrame> frame = new AtomicReference<>();
        HtmlPane pane = new HtmlPane();
        try {
            SwingUtilities.invokeAndWait(() -> {
                pane.setMarkdown("# Result\n\nDone.");
                JFrame f = new JFrame("dark page");
                f.setUndecorated(true);
                f.setAutoRequestFocus(false);
                f.add(pane);
                f.setBounds(-5_000, 0, 400, 200);
                f.setVisible(true);
                frame.set(f);
            });
            assertThat(headingColour(pane)).isEqualTo(Gold.TREASURE.body);
            SwingUtilities.invokeAndWait(() -> Theme.gold(Gold.CHAMPAGNE));
            assertThat(headingColour(pane)).as("drawn again at once").isEqualTo(Gold.CHAMPAGNE.body);

            SwingUtilities.invokeAndWait(() -> frame.get().remove(pane));
            SwingUtilities.invokeAndWait(() -> Theme.gold(Gold.YELLOW));
            assertThat(headingColour(pane)).as("out of its window, it no longer listens").isEqualTo(Gold.CHAMPAGNE.body);
        } finally {
            SwingUtilities.invokeAndWait(() -> {
                Theme.gold(Gold.TREASURE);
                if (frame.get() != null) {
                    frame.get().dispose();
                }
            });
        }
    }

    /** The colour the page's first heading is drawn in, as its style sheet resolves it. */
    private static Color headingColour(HtmlPane pane) throws Exception {
        AtomicReference<Color> out = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            HTMLDocument doc = (HTMLDocument) pane.getDocument();
            ElementIterator it = new ElementIterator(doc);
            for (Element e = it.next(); e != null; e = it.next()) {
                if (e.getAttributes().getAttribute(StyleConstants.NameAttribute) == HTML.Tag.H1) {
                    out.set(doc.getStyleSheet().getForeground(doc.getStyleSheet().getRule(HTML.Tag.H1, e)));
                    return;
                }
            }
        });
        return out.get();
    }
}
