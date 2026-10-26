package dev.marregui.comando.ui.kit.text;

import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rsyntaxtextarea.Style;
import org.fife.ui.rsyntaxtextarea.TokenTypes;
import org.junit.jupiter.api.Test;

import javax.swing.SwingUtilities;
import java.awt.Font;

import static org.assertj.core.api.Assertions.assertThat;

class EditorThemesTest {

    @Test
    void zoomingOneEditorLeavesTheOthersFontsAlone() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            RSyntaxTextArea zoomed = new RSyntaxTextArea();
            RSyntaxTextArea other = new RSyntaxTextArea();
            EditorThemes.apply(zoomed);
            EditorThemes.apply(other);
            Style comment = other.getSyntaxScheme().getStyle(TokenTypes.COMMENT_DOCUMENTATION);
            Font before = comment.font;

            Font base = zoomed.getFont();
            zoomed.setFont(base.deriveFont(base.getSize2D() + 6f));

            assertThat(before).as("the dark theme gives documentation comments a font of their own").isNotNull();
            assertThat(other.getSyntaxScheme().getStyle(TokenTypes.COMMENT_DOCUMENTATION).font).isEqualTo(before);
        });
    }
}
