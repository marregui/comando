package dev.marregui.comando.ui.kit.text;

import java.awt.Font;
import javax.swing.SwingUtilities;

import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rsyntaxtextarea.Style;
import org.fife.ui.rsyntaxtextarea.TokenTypes;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// [EditorThemes] gives an [RSyntaxTextArea] the editors' colours, from the content palette.
/// - Zooming one editor leaves the other editors' token fonts alone.
class EditorThemesTest {

    /// The theme gives documentation comments a font of their own, and changing one editor's font size leaves that
    /// style's font in another themed editor unchanged, so the schemes are not shared.
    @Test
    void zoomingOneEditorLeavesTheOthersFontsAlone() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            final RSyntaxTextArea zoomed = new RSyntaxTextArea();
            final RSyntaxTextArea other = new RSyntaxTextArea();
            EditorThemes.apply(zoomed);
            EditorThemes.apply(other);
            final Style comment = other.getSyntaxScheme().getStyle(TokenTypes.COMMENT_DOCUMENTATION);
            final Font before = comment.font;

            final Font base = zoomed.getFont();
            zoomed.setFont(base.deriveFont(base.getSize2D() + 6f));

            assertThat(before).as("the dark theme gives documentation comments a font of their own").isNotNull();
            assertThat(other.getSyntaxScheme().getStyle(TokenTypes.COMMENT_DOCUMENTATION).font).isEqualTo(before);
        });
    }
}
