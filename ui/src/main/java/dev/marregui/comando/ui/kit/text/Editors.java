package dev.marregui.comando.ui.kit.text;

import dev.marregui.comando.ui.kit.theme.Theme;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rsyntaxtextarea.SyntaxConstants;
import org.fife.ui.rtextarea.RTextScrollPane;

import java.awt.Color;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Locale;
import java.util.Map;

/** The one place text areas are configured: theme, font, syntax by file type. */
public final class Editors {

    private static final Map<String, String> STYLES = Map.ofEntries(
            Map.entry("sql", SyntaxConstants.SYNTAX_STYLE_SQL),
            Map.entry("java", SyntaxConstants.SYNTAX_STYLE_JAVA),
            Map.entry("kt", SyntaxConstants.SYNTAX_STYLE_KOTLIN),
            Map.entry("kts", SyntaxConstants.SYNTAX_STYLE_KOTLIN),
            Map.entry("gradle", SyntaxConstants.SYNTAX_STYLE_GROOVY),
            Map.entry("groovy", SyntaxConstants.SYNTAX_STYLE_GROOVY),
            Map.entry("json", SyntaxConstants.SYNTAX_STYLE_JSON_WITH_COMMENTS),
            Map.entry("jsonl", SyntaxConstants.SYNTAX_STYLE_JSON),
            Map.entry("xml", SyntaxConstants.SYNTAX_STYLE_XML),
            Map.entry("yaml", SyntaxConstants.SYNTAX_STYLE_YAML),
            Map.entry("yml", SyntaxConstants.SYNTAX_STYLE_YAML),
            Map.entry("md", SyntaxConstants.SYNTAX_STYLE_MARKDOWN),
            Map.entry("py", SyntaxConstants.SYNTAX_STYLE_PYTHON),
            Map.entry("sh", SyntaxConstants.SYNTAX_STYLE_UNIX_SHELL),
            Map.entry("bash", SyntaxConstants.SYNTAX_STYLE_UNIX_SHELL),
            Map.entry("zsh", SyntaxConstants.SYNTAX_STYLE_UNIX_SHELL),
            Map.entry("js", SyntaxConstants.SYNTAX_STYLE_JAVASCRIPT),
            Map.entry("ts", SyntaxConstants.SYNTAX_STYLE_TYPESCRIPT),
            Map.entry("html", SyntaxConstants.SYNTAX_STYLE_HTML),
            Map.entry("css", SyntaxConstants.SYNTAX_STYLE_CSS),
            Map.entry("go", SyntaxConstants.SYNTAX_STYLE_GO),
            Map.entry("rs", SyntaxConstants.SYNTAX_STYLE_RUST),
            Map.entry("c", SyntaxConstants.SYNTAX_STYLE_C),
            Map.entry("h", SyntaxConstants.SYNTAX_STYLE_C),
            Map.entry("cpp", SyntaxConstants.SYNTAX_STYLE_CPLUSPLUS),
            Map.entry("clj", SyntaxConstants.SYNTAX_STYLE_CLOJURE),
            Map.entry("properties", SyntaxConstants.SYNTAX_STYLE_PROPERTIES_FILE),
            Map.entry("dockerfile", SyntaxConstants.SYNTAX_STYLE_DOCKERFILE));

    private static org.fife.ui.rsyntaxtextarea.Theme dark;

    private Editors() {
    }

    /** The command editor: SQL highlighting, current line, matching brackets, marked occurrences. */
    public static RSyntaxTextArea command() {
        RSyntaxTextArea a = base(SyntaxConstants.SYNTAX_STYLE_SQL);
        a.setEditable(true);
        a.setHighlightCurrentLine(true);
        a.setMarkOccurrences(true);
        a.setBracketMatchingEnabled(true);
        a.setCloseCurlyBraces(true);
        a.setTabsEmulated(true);
        a.setTabSize(4);
        return a;
    }

    /** A read-only viewer for {@code syntax}, a file extension or a style constant. */
    public static RSyntaxTextArea viewer(String syntax) {
        RSyntaxTextArea a = base(styleFor(syntax));
        a.setEditable(false);
        a.setHighlightCurrentLine(false);
        return a;
    }

    public static RTextScrollPane scroll(RSyntaxTextArea area, boolean lineNumbers) {
        RTextScrollPane sp = new RTextScrollPane(area, lineNumbers);
        sp.setFoldIndicatorEnabled(false);
        sp.setBorder(null);
        sp.getGutter().setLineNumberFont(Theme.mono(Theme.mono().getSize2D() - 1f));
        return sp;
    }

    public static String styleFor(String syntax) {
        if (syntax == null || syntax.isEmpty()) {
            return SyntaxConstants.SYNTAX_STYLE_NONE;
        }
        if (syntax.startsWith("text/")) {
            return syntax;
        }
        return STYLES.getOrDefault(syntax.toLowerCase(Locale.ROOT), SyntaxConstants.SYNTAX_STYLE_NONE);
    }

    private static RSyntaxTextArea base(String style) {
        RSyntaxTextArea a = new RSyntaxTextArea();
        a.setSyntaxEditingStyle(style);
        a.setCodeFoldingEnabled(false);
        a.setAntiAliasingEnabled(true);
        darkTheme().apply(a);
        a.setFont(Theme.mono());
        return a;
    }

    /** The editor theme's foreground for token type {@code type}, null for the default. */
    public static Color colorOf(int type) {
        var scheme = darkTheme().scheme;
        if (type < 0 || type >= scheme.getStyleCount() || scheme.getStyle(type) == null) {
            return null;
        }
        return scheme.getStyle(type).foreground;
    }

    private static synchronized org.fife.ui.rsyntaxtextarea.Theme darkTheme() {
        if (dark == null) {
            try (InputStream in = RSyntaxTextArea.class.getResourceAsStream("/org/fife/ui/rsyntaxtextarea/themes/dark.xml")) {
                dark = org.fife.ui.rsyntaxtextarea.Theme.load(in);
            } catch (IOException e) {
                throw new UncheckedIOException("cannot load the editor theme", e);
            }
        }
        return dark;
    }
}
