package dev.marregui.comando.ui.kit.text;

import java.nio.file.Path;
import java.util.List;
import javax.swing.SwingUtilities;

import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rsyntaxtextarea.SyntaxConstants;
import org.fife.ui.rsyntaxtextarea.TokenTypes;
import org.junit.jupiter.api.Test;

import dev.marregui.comando.ui.kit.theme.ContentPalette;
import dev.marregui.comando.ui.kit.theme.Theme;
import dev.marregui.comando.ui.kit.theme.Tint;

import static org.assertj.core.api.Assertions.assertThat;

/// [Editors] chooses an editor's token colours and syntax style.
/// - Token colours come from the content palette, keywords from the tint, and follow a tint change.
/// - A file takes the style of its extension in any case, or of its name; a fence takes the style of its language.
/// - An area it makes runs no caret timer until it is first focused, so an area never focused holds no timer.
class EditorsTest {

    /// Keywords are the tint's body, strings, numbers, annotations and identifiers the palette's, end-of-line comments
    /// muted; after a tint change keywords follow it and strings stay the palette's.
    @Test
    void theEditorsColoursAreTheContentPalettesAndFollowTheTint() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            try {
                final ContentPalette p = Theme.content();
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

    /// `.java`, `.PY` and `Makefile` map to Java, Python and Makefile, `.txt` to none, and the fence name `python` to
    /// Python.
    @Test
    void aFileTakesTheStyleOfItsExtensionOrItsName() {
        assertThat(Editors.styleFor(Path.of("/s/work/Red.java"))).isEqualTo(SyntaxConstants.SYNTAX_STYLE_JAVA);
        assertThat(Editors.styleFor(Path.of("/s/rig.PY"))).isEqualTo(SyntaxConstants.SYNTAX_STYLE_PYTHON);
        assertThat(Editors.styleFor(Path.of("/s/Makefile"))).isEqualTo(SyntaxConstants.SYNTAX_STYLE_MAKEFILE);
        assertThat(Editors.styleFor(Path.of("/s/notes.txt"))).isEqualTo(SyntaxConstants.SYNTAX_STYLE_NONE);
        assertThat(Editors.styleFor("python")).as("a code fence's language name").isEqualTo(SyntaxConstants.SYNTAX_STYLE_PYTHON);
    }

    /// A viewer and a command editor start with the caret hidden and its blink stopped: a running caret blink is a
    /// Swing timer, which keeps an area nobody shows reachable and the JVM from ending by itself. A focus gain shows
    /// the caret again (Swing's `DefaultCaret.focusGained`), so a focused editor still blinks.
    @Test
    void anAreaNeverFocusedRunsNoCaretTimer() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            for (final RSyntaxTextArea a : List.of(Editors.viewer("java"), Editors.command())) {
                assertThat(a.getCaret().isVisible()).as("caret of an unfocused %s", a.isEditable() ? "editor" : "viewer").isFalse();
                assertThat(a.getCaret().getBlinkRate()).as("the blink rate is kept for the first focus").isPositive();
            }
        });
    }
}
