package dev.marregui.comando.ui.kit.text;

import dev.marregui.comando.ui.kit.theme.ContentPalette;
import dev.marregui.comando.ui.kit.theme.Theme;
import dev.marregui.comando.ui.kit.theme.Tint;
import org.fife.ui.rsyntaxtextarea.SyntaxConstants;
import org.fife.ui.rsyntaxtextarea.TokenTypes;
import org.junit.jupiter.api.Test;

import javax.swing.SwingUtilities;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class EditorsTest {

    @Test
    void theEditorsColoursAreTheContentPalettesAndFollowTheTint() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            try {
                ContentPalette p = Theme.content();
                assertThat(Editors.colorOf(TokenTypes.RESERVED_WORD)).as("keywords in the tint").isEqualTo(Theme.tint().body);
                assertThat(Editors.colorOf(TokenTypes.LITERAL_STRING_DOUBLE_QUOTE)).isEqualTo(p.string);
                assertThat(Editors.colorOf(TokenTypes.LITERAL_NUMBER_DECIMAL_INT)).isEqualTo(p.number);
                assertThat(Editors.colorOf(TokenTypes.COMMENT_EOL)).isEqualTo(Theme.MUTED);
                assertThat(Editors.colorOf(TokenTypes.ANNOTATION)).isEqualTo(p.annotation);
                assertThat(Editors.colorOf(TokenTypes.IDENTIFIER)).as("identifiers in the ink").isEqualTo(p.ink);

                Theme.tint(Tint.ELECTRIC_BLUE);
                assertThat(Editors.colorOf(TokenTypes.RESERVED_WORD)).isEqualTo(Tint.ELECTRIC_BLUE.body);
                assertThat(Editors.colorOf(TokenTypes.LITERAL_STRING_DOUBLE_QUOTE)).isEqualTo(Theme.content().string);
            } finally {
                Theme.tint(Tint.TREASURE);
            }
        });
    }

    @Test
    void aFileTakesTheStyleOfItsExtensionOrItsName() {
        assertThat(Editors.styleFor(Path.of("/s/work/Red.java"))).isEqualTo(SyntaxConstants.SYNTAX_STYLE_JAVA);
        assertThat(Editors.styleFor(Path.of("/s/rig.PY"))).isEqualTo(SyntaxConstants.SYNTAX_STYLE_PYTHON);
        assertThat(Editors.styleFor(Path.of("/s/Makefile"))).isEqualTo(SyntaxConstants.SYNTAX_STYLE_MAKEFILE);
        assertThat(Editors.styleFor(Path.of("/s/notes.txt"))).isEqualTo(SyntaxConstants.SYNTAX_STYLE_NONE);
        assertThat(Editors.styleFor("python")).as("a code fence's language name").isEqualTo(SyntaxConstants.SYNTAX_STYLE_PYTHON);
    }
}
