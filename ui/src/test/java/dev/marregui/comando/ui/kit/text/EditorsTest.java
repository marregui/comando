package dev.marregui.comando.ui.kit.text;

import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rsyntaxtextarea.SyntaxConstants;
import org.fife.ui.rsyntaxtextarea.TokenTypes;
import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class EditorsTest {

    @Test
    void theLightThemeIsIntellijsLightOnWarmPaperAndTheDarkComesBackWhole() {
        RSyntaxTextArea area = Editors.viewer("java");
        Color dark = area.getBackground();
        Editors.theme(area, true);
        assertThat(area.getBackground()).as("the preview's paper, not white").isEqualTo(HtmlPane.PAPER);
        assertThat(Editors.colorOf(TokenTypes.RESERVED_WORD, true)).isEqualTo(new Color(0x0033B3));
        Editors.theme(area, false);
        assertThat(area.getBackground()).isEqualTo(dark);
    }

    @Test
    void theThemeHasIntellijsDarkColours() {
        assertThat(Editors.colorOf(TokenTypes.RESERVED_WORD)).isEqualTo(new Color(0xCF8E6D));
        assertThat(Editors.colorOf(TokenTypes.LITERAL_STRING_DOUBLE_QUOTE)).isEqualTo(new Color(0x6AAB73));
        assertThat(Editors.colorOf(TokenTypes.LITERAL_NUMBER_DECIMAL_INT)).isEqualTo(new Color(0x2AACB8));
        assertThat(Editors.colorOf(TokenTypes.COMMENT_EOL)).isEqualTo(new Color(0x7A7E85));
        assertThat(Editors.colorOf(TokenTypes.ANNOTATION)).isEqualTo(new Color(0xB3AE60));
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
