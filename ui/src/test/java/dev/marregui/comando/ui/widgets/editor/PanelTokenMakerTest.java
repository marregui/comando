package dev.marregui.comando.ui.widgets.editor;

import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import javax.swing.SwingUtilities;
import javax.swing.text.BadLocationException;

import org.fife.ui.rsyntaxtextarea.RSyntaxDocument;
import org.fife.ui.rsyntaxtextarea.SyntaxConstants;
import org.fife.ui.rsyntaxtextarea.Token;
import org.fife.ui.rsyntaxtextarea.TokenTypes;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// The panel style of [CommandEditor]: SQL and prompts in one text, each block coloured by what it is.
/// - SQL is coloured as SQL only while the database is open; a prompt never is
/// - a slash command and an `@` marker are marked, and the marker makes its block a prompt
/// - an edit below recolours the block's first line
/// - a comment runs across the lines of a block, not across a blank line
/// - a file keeps its own language
class PanelTokenMakerTest {

    private static final String TEXT = """
            select price from trades where sym = 'BTC'

            Explain the plan above, step by step.

            /reflect now

            -- @claude
            select the rows that matter
            """;

    /// A statement has no reserved-word tokens until the database is live; a prose line never has them.
    @Test
    void sqlIsColouredAsSqlOnlyWhileTheDatabaseIsOpen() {
        withEditor(TEXT, e -> {
            assertThat(e.area().getSyntaxEditingStyle()).isEqualTo(CommandEditor.PANEL_STYLE);
            assertThat(types(e, 0)).as("no database open: a statement reads as text").doesNotContain(TokenTypes.RESERVED_WORD);
            e.sqlLive(true);
            assertThat(types(e, 0)).as("database open").contains(TokenTypes.RESERVED_WORD);
            assertThat(types(e, 2)).as("a prompt is never SQL").doesNotContain(TokenTypes.RESERVED_WORD);
        });
    }

    /// A `/reflect` command is a preprocessor token, a `-- @claude` line a comment, and the block after the marker has
    /// no reserved words.
    @Test
    void aSlashCommandAndAMarkerAreMarked() {
        withEditor(TEXT, e -> {
            e.sqlLive(true);
            assertThat(texts(e, 4, TokenTypes.PREPROCESSOR)).containsExactly("/reflect");
            assertThat(texts(e, 6, TokenTypes.COMMENT_EOL)).containsExactly("-- @claude");
            assertThat(types(e, 7)).as("the marker made the block a prompt").doesNotContain(TokenTypes.RESERVED_WORD);
        });
    }

    /// Typing prose on a block's second line turns the first line from SQL into prompt text.
    @Test
    void anEditBelowRecoloursTheBlocksFirstLine() {
        withEditor("select price\nfrom trades\n", e -> {
            e.sqlLive(true);
            assertThat(types(e, 0)).contains(TokenTypes.RESERVED_WORD);
            insert(e, e.text().length() - 1, " where the price rose?");
            assertThat(types(e, 0)).as("a prose word on line 2 makes the whole block a prompt").doesNotContain(TokenTypes.RESERVED_WORD);
        });
    }

    /// A block comment continues onto the block's next line, but a blank line ends the block and an unclosed comment
    /// with it.
    @Test
    void aCommentRunsAcrossLinesOfABlockButNotAcrossABlankLine() {
        withEditor("select 1 /* one\ntwo */ from t\n\n/* three\n\nfour */\n", e -> {
            e.sqlLive(true);
            assertThat(first(e, 1)).as("inside the comment opened above").isEqualTo(TokenTypes.COMMENT_MULTILINE);
            assertThat(types(e, 5)).as("a blank line ends a block; the next starts afresh").doesNotContain(TokenTypes.COMMENT_MULTILINE);
        });
    }

    /// A Java syntax style colours Java keywords, and the panel style can be set back.
    @Test
    void aFileKeepsItsOwnLanguage() {
        withEditor("class A {}\n", e -> {
            e.syntax(SyntaxConstants.SYNTAX_STYLE_JAVA);
            assertThat(types(e, 0)).contains(TokenTypes.RESERVED_WORD);
            e.syntax(CommandEditor.PANEL_STYLE);
            assertThat(e.area().getSyntaxEditingStyle()).isEqualTo(CommandEditor.PANEL_STYLE);
        });
    }

    private static List<Integer> types(final CommandEditor e, final int line) {
        final List<Integer> out = new ArrayList<>();
        for (Token t = tokens(e, line); t != null && t.isPaintable(); t = t.getNextToken()) {
            out.add(t.getType());
        }
        return out;
    }

    private static int first(final CommandEditor e, final int line) {
        return tokens(e, line).getType();
    }

    private static List<String> texts(final CommandEditor e, final int line, final int type) {
        final List<String> out = new ArrayList<>();
        for (Token t = tokens(e, line); t != null && t.isPaintable(); t = t.getNextToken()) {
            if (t.getType() == type) {
                out.add(t.getLexeme());
            }
        }
        return out;
    }

    private static Token tokens(final CommandEditor e, final int line) {
        return ((RSyntaxDocument) e.area().getDocument()).getTokenListForLine(line);
    }

    private static void insert(final CommandEditor e, final int at, final String text) {
        try {
            e.area().getDocument().insertString(at, text, null);
        } catch (final BadLocationException x) {
            throw new IllegalStateException(x);
        }
    }

    private static void withEditor(final String text, final Consumer<CommandEditor> test) {
        try {
            SwingUtilities.invokeAndWait(() -> {
                final CommandEditor e = new CommandEditor();
                try {
                    e.load(text);
                    test.accept(e);
                } finally {
                    e.unmount();
                }
            });
        } catch (final InvocationTargetException x) {
            if (x.getCause() instanceof RuntimeException r) {
                throw r;
            }
            if (x.getCause() instanceof Error r) {
                throw r;
            }
            throw new IllegalStateException(x.getCause());
        } catch (final InterruptedException x) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(x);
        }
    }
}
