package dev.marregui.comando.ui.kit.text;

import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rsyntaxtextarea.SyntaxScheme;
import org.fife.ui.rsyntaxtextarea.Theme;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;

/** The editors' colour theme, IntelliJ's Dark, loaded once from its XML: comando is dark throughout. */
final class EditorThemes {

    private static Theme dark;

    private EditorThemes() {
    }

    static void apply(RSyntaxTextArea area) {
        Theme theme = theme();
        theme.apply(area);
        // each area its own copy: a zoom rewrites its scheme's fonts, which would reach every other editor
        area.setSyntaxScheme((SyntaxScheme) theme.scheme.clone());
    }

    static SyntaxScheme scheme() {
        return theme().scheme;
    }

    private static synchronized Theme theme() {
        if (dark == null) {
            dark = load("editor-dark.xml");
        }
        return dark;
    }

    private static Theme load(String name) {
        try (InputStream in = EditorThemes.class.getResourceAsStream(name)) {
            return Theme.load(in);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot load the editor theme " + name, e);
        }
    }
}
