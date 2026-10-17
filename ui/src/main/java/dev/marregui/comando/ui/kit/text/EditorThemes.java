package dev.marregui.comando.ui.kit.text;

import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rsyntaxtextarea.SyntaxScheme;
import org.fife.ui.rsyntaxtextarea.Theme;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;

/** The editors' two colour themes, IntelliJ's Dark and Light, each loaded once from its XML. */
final class EditorThemes {

    private static Theme dark;
    private static Theme light;

    private EditorThemes() {
    }

    static void apply(RSyntaxTextArea area, boolean light) {
        theme(light).apply(area);
    }

    static SyntaxScheme scheme(boolean light) {
        return theme(light).scheme;
    }

    private static synchronized Theme theme(boolean isLight) {
        if (isLight) {
            if (light == null) {
                light = load("editor-light.xml");
            }
            return light;
        }
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
