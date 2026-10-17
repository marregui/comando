package dev.marregui.comando.ui.kit.text;

import dev.marregui.comando.ui.kit.desktop.Platform;
import dev.marregui.comando.ui.kit.theme.Theme;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rsyntaxtextarea.SyntaxConstants;
import org.fife.ui.rtextarea.RTextScrollPane;

import javax.swing.AbstractAction;
import javax.swing.Action;
import javax.swing.JTextArea;
import javax.swing.KeyStroke;
import javax.swing.text.DefaultEditorKit;
import javax.swing.undo.UndoManager;
import java.awt.Color;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;

/** The one place text areas are configured: theme (IntelliJ's Dark colours), font, syntax by file type. */
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
            Map.entry("dockerfile", SyntaxConstants.SYNTAX_STYLE_DOCKERFILE),
            Map.entry("markdown", SyntaxConstants.SYNTAX_STYLE_MARKDOWN),
            Map.entry("jsx", SyntaxConstants.SYNTAX_STYLE_JAVASCRIPT),
            Map.entry("mjs", SyntaxConstants.SYNTAX_STYLE_JAVASCRIPT),
            Map.entry("tsx", SyntaxConstants.SYNTAX_STYLE_TYPESCRIPT),
            Map.entry("htm", SyntaxConstants.SYNTAX_STYLE_HTML),
            Map.entry("hpp", SyntaxConstants.SYNTAX_STYLE_CPLUSPLUS),
            Map.entry("cc", SyntaxConstants.SYNTAX_STYLE_CPLUSPLUS),
            Map.entry("cs", SyntaxConstants.SYNTAX_STYLE_CSHARP),
            Map.entry("scala", SyntaxConstants.SYNTAX_STYLE_SCALA),
            Map.entry("rb", SyntaxConstants.SYNTAX_STYLE_RUBY),
            Map.entry("php", SyntaxConstants.SYNTAX_STYLE_PHP),
            Map.entry("lua", SyntaxConstants.SYNTAX_STYLE_LUA),
            Map.entry("pl", SyntaxConstants.SYNTAX_STYLE_PERL),
            Map.entry("cljs", SyntaxConstants.SYNTAX_STYLE_CLOJURE),
            Map.entry("cljc", SyntaxConstants.SYNTAX_STYLE_CLOJURE),
            Map.entry("edn", SyntaxConstants.SYNTAX_STYLE_CLOJURE),
            Map.entry("ini", SyntaxConstants.SYNTAX_STYLE_INI),
            Map.entry("toml", SyntaxConstants.SYNTAX_STYLE_INI),
            Map.entry("csv", SyntaxConstants.SYNTAX_STYLE_CSV),
            Map.entry("makefile", SyntaxConstants.SYNTAX_STYLE_MAKEFILE),
            Map.entry("mk", SyntaxConstants.SYNTAX_STYLE_MAKEFILE),
            // the names a Markdown code fence gives its language
            Map.entry("python", SyntaxConstants.SYNTAX_STYLE_PYTHON),
            Map.entry("javascript", SyntaxConstants.SYNTAX_STYLE_JAVASCRIPT),
            Map.entry("typescript", SyntaxConstants.SYNTAX_STYLE_TYPESCRIPT),
            Map.entry("kotlin", SyntaxConstants.SYNTAX_STYLE_KOTLIN),
            Map.entry("shell", SyntaxConstants.SYNTAX_STYLE_UNIX_SHELL),
            Map.entry("console", SyntaxConstants.SYNTAX_STYLE_UNIX_SHELL),
            Map.entry("rust", SyntaxConstants.SYNTAX_STYLE_RUST),
            Map.entry("golang", SyntaxConstants.SYNTAX_STYLE_GO),
            Map.entry("clojure", SyntaxConstants.SYNTAX_STYLE_CLOJURE),
            Map.entry("c++", SyntaxConstants.SYNTAX_STYLE_CPLUSPLUS),
            Map.entry("csharp", SyntaxConstants.SYNTAX_STYLE_CSHARP),
            Map.entry("ruby", SyntaxConstants.SYNTAX_STYLE_RUBY),
            Map.entry("perl", SyntaxConstants.SYNTAX_STYLE_PERL));

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

    /**
     * A plain text box in the editors' font, {@code rows} by {@code columns} characters, wrapped at words: cut, copy,
     * paste and select all on the menu key, and an undo (menu key Z) and redo (with Shift) of its own, which a plain
     * text area lacks. No syntax, no current line: text to read back, not to run.
     */
    public static JTextArea plain(int rows, int columns) {
        JTextArea a = new JTextArea(rows, columns);
        a.setFont(Theme.mono());
        a.setLineWrap(true);
        a.setWrapStyleWord(true);
        UndoManager undo = new UndoManager();
        a.getDocument().addUndoableEditListener(undo);
        int menu = Platform.menuShortcutMask();
        // on the menu key whatever the look's own map binds, as the Edit menu forwards them
        a.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_X, menu), DefaultEditorKit.cutAction);
        a.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_C, menu), DefaultEditorKit.copyAction);
        a.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_V, menu), DefaultEditorKit.pasteAction);
        a.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_A, menu), DefaultEditorKit.selectAllAction);
        a.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_Z, menu), "plain-undo");
        a.getActionMap().put("plain-undo", action(() -> {
            if (undo.canUndo()) {
                undo.undo();
            }
        }));
        a.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_Z, menu | InputEvent.SHIFT_DOWN_MASK), "plain-redo");
        a.getActionMap().put("plain-redo", action(() -> {
            if (undo.canRedo()) {
                undo.redo();
            }
        }));
        return a;
    }

    private static Action action(Runnable r) {
        return new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                r.run();
            }
        };
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

    /** The style of a file: by its extension, or by its whole name for files such as {@code Makefile}. */
    public static String styleFor(Path file) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        int dot = name.lastIndexOf('.');
        return styleFor(dot < 0 ? name : name.substring(dot + 1));
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
            try (InputStream in = Editors.class.getResourceAsStream("editor-dark.xml")) {
                dark = org.fife.ui.rsyntaxtextarea.Theme.load(in);
            } catch (IOException e) {
                throw new UncheckedIOException("cannot load the editor theme", e);
            }
        }
        return dark;
    }
}
