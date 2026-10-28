package dev.marregui.comando.ui.widgets.editor;

import org.fife.ui.rsyntaxtextarea.SyntaxConstants;
import org.junit.jupiter.api.Test;

import javax.swing.SwingUtilities;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/// [CommandEditor] never lets a run or a session's write take the user's text or caret.
/// - runs tint the block that ran and select nothing; an error position underlines its token until an edit
/// - a session's append or insert keeps the caret and selection, forward or backward, and leaves the user's line alone
/// - an insert lands as a block after the block that ran, or appends when nothing ran
/// - a session's write, a replace, a clear and a reload are each one undo step
/// - completion opens in SQL being typed with a database open, not in prose
/// - a source file is coloured as its language and marks no run targets
/// - zoom stays between 8 and 40 points
class CommandEditorTest {

    private static final String TEXT = "SELECT 1;\n\nExplain the query above.\n";

    /// Zooming far in stops at 40 pt and far out at 8 pt.
    @Test
    void zoomStaysBetween8And40Points() {
        onEdt(() -> {
            CommandEditor e = new CommandEditor();
            e.zoom(100);
            assertThat(e.area().getFont().getSize2D()).isEqualTo(40f);
            e.zoom(-100);
            assertThat(e.area().getFont().getSize2D()).isEqualTo(8f);
            e.dispose();
        });
    }

    /// Running with the caret inside a block returns that block, selects nothing and tints it from its start; the next edit
    /// clears the tint.
    @Test
    void runningABlockTintsItAndLeavesNothingSelected() {
        onEdt(() -> {
            CommandEditor e = new CommandEditor();
            e.load(TEXT);
            e.area().setCaretPosition(TEXT.indexOf("Explain") + 3);
            assertThat(e.runText()).isEqualTo("Explain the query above.");
            assertThat(e.area().getSelectedText()).isNull();
            assertThat(e.ranStart()).isEqualTo(TEXT.indexOf("Explain"));
            e.area().insert("x", 0);
            assertThat(e.ranStart()).as("the next edit clears the tint").isEqualTo(-1);
            e.dispose();
        });
    }

    /// A quiet append keeps the selection and adds the text after the existing text, followed by a blank line.
    @Test
    void aSessionsAppendKeepsTheCaretAndTheSelection() {
        onEdt(() -> {
            CommandEditor e = new CommandEditor();
            e.load(TEXT);
            e.area().select(0, 6);
            e.appendQuietly("SELECT 2;");
            assertThat(e.area().getSelectedText()).isEqualTo("SELECT");
            assertThat(e.text()).as("a blank line after it ends its block").endsWith("SELECT 2;\n\n");
            assertThat(e.text()).startsWith(TEXT);
            e.dispose();
        });
    }

    /// With nothing run and a selection, an insert appends after the text instead of replacing the selection, which stays.
    @Test
    void anInsertReplacesNothingAndLeavesTheSelectionAlone() {
        onEdt(() -> {
            CommandEditor e = new CommandEditor();
            e.load(TEXT);
            e.area().select(0, 9);
            e.insertAtCaret("\nSELECT 2;");
            // Nothing ran from the editor: the insert appends, it no longer lands after the selection
            assertThat(e.text()).startsWith(TEXT).endsWith("SELECT 2;\n\n");
            assertThat(e.area().getSelectedText()).isEqualTo("SELECT 1;");
            e.dispose();
        });
    }

    /// Replacing the whole text is undone by one undo.
    @Test
    void replacingTheWholeTextIsOneUndo() {
        onEdt(() -> {
            CommandEditor e = new CommandEditor();
            e.load(TEXT);
            e.replaceAll("new text");
            assertThat(e.text()).isEqualTo("new text");
            e.area().undoLastAction();
            assertThat(e.text()).isEqualTo(TEXT);
            e.dispose();
        });
    }

    /// A 1-based error position inside the block that ran underlines the token at that place; an edit clears it, and a
    /// position for text changed since the run is refused.
    @Test
    void anErrorPositionUnderlinesTheTokenInTheBlockThatRan() {
        onEdt(() -> {
            String text = "-- note\n\nSELECT * FROM nope;\n";
            CommandEditor e = new CommandEditor();
            e.load(text);
            e.area().setCaretPosition(text.indexOf("nope"));
            String ran = e.runText();
            // The database counts from 1: "nope" starts at the 15th character of the statement
            assertThat(e.markError(ran, ran.indexOf("nope") + 1)).isTrue();
            assertThat(e.errorStart()).isEqualTo(text.indexOf("nope"));
            e.area().insert(" ", 0);
            assertThat(e.errorStart()).as("an edit clears the underline").isEqualTo(-1);
            assertThat(e.markError(ran, 3)).as("the text changed since the run").isFalse();
            e.dispose();
        });
    }

    /// With the caret on the line under a prompt that ran, an insert goes after that line, which stays the user's to type on.
    @Test
    void anInsertLeavesTheLineUnderTheRanBlockToTheUser() {
        onEdt(() -> {
            CommandEditor e = new CommandEditor();
            e.load("Write a query for trades\n");
            e.area().setCaretPosition(e.text().length());
            e.runText();
            e.insertAtCaret("SELECT 1;");
            type(e, "and explain it");
            assertThat(e.text()).startsWith("Write a query for trades\nand explain it\n\nSELECT 1;");
            e.dispose();
        });
    }

    /// An append to an empty editor leaves the first line empty for the user and puts the block after it.
    @Test
    void anAppendToAnEmptyEditorLeavesTheFirstLineToTheUser() {
        onEdt(() -> {
            CommandEditor e = new CommandEditor();
            e.load("");
            e.appendQuietly("SELECT 1;");
            type(e, "explain ");
            assertThat(e.text()).startsWith("explain \n\nSELECT 1;");
            e.dispose();
        });
    }

    private static void type(CommandEditor e, String s) {
        for (char c : s.toCharArray()) {
            e.area().replaceSelection(String.valueOf(c));
        }
    }

    /// One undo removes only the session's append; the user's typing before it is a separate step.
    @Test
    void oneUndoTakesBackOnlyTheSessionsAppend() {
        onEdt(() -> {
            CommandEditor e = new CommandEditor();
            e.load("\n\nselect * From trades;");
            e.area().setCaretPosition(0);
            type(e, "Write a query for the biggest trade");
            e.runText();
            e.appendQuietly("SELECT max(price) FROM trades;");
            assertThat(e.text()).endsWith("SELECT max(price) FROM trades;\n\n");
            e.area().undoLastAction();
            assertThat(e.text()).isEqualTo("Write a query for the biggest trade\n\nselect * From trades;");
            e.area().undoLastAction();
            assertThat(e.text()).as("the user's typing is a step of its own").isEqualTo("\n\nselect * From trades;");
            e.dispose();
        });
    }

    /// One undo removes only the session's insert, leaving the user's later typing.
    @Test
    void oneUndoTakesBackOnlyTheSessionsInsert() {
        onEdt(() -> {
            CommandEditor e = new CommandEditor();
            e.load("ask\n\nnotes");
            e.area().setCaretPosition(3);
            e.runText();
            e.area().setCaretPosition(e.text().length());
            type(e, " more");
            e.area().setCaretPosition(e.text().length() - 2);
            e.insertAtCaret("SELECT 1;");
            assertThat(e.text()).isEqualTo("ask\n\nSELECT 1;\n\nnotes more");
            e.area().undoLastAction();
            assertThat(e.text()).isEqualTo("ask\n\nnotes more");
            e.dispose();
        });
    }

    /// With the caret at the end, on a fresh line or at the end of the last line, an append goes after it and typing stays on
    /// the user's line.
    @Test
    void aSessionsAppendAtTheCaretLeavesTheUsersLineToTheUser() {
        onEdt(() -> {
            CommandEditor e = new CommandEditor();
            e.load("Write a query for trades\n");
            e.area().setCaretPosition(e.text().length());
            type(e, "\n");
            e.appendQuietly("SELECT * FROM trades;");
            type(e, "and explain it");
            assertThat(e.text()).isEqualTo("Write a query for trades\n\nand explain it\n\nSELECT * FROM trades;\n\n");

            CommandEditor f = new CommandEditor();
            f.load("Write a query for trades");
            f.area().setCaretPosition(f.text().length());
            f.appendQuietly("SELECT 1;");
            type(f, " today");
            assertThat(f.text()).isEqualTo("Write a query for trades today\n\nSELECT 1;\n\n");
            e.dispose();
            f.dispose();
        });
    }

    /// After the user edits above the block that ran, an insert lands as a block right after that block, a second insert
    /// after the first, and a forward selection keeps its mark and dot.
    @Test
    void anInsertLandsAsABlockAfterTheBlockThatRanAndKeepsTheSelection() {
        onEdt(() -> {
            CommandEditor e = new CommandEditor();
            e.load("Write a query for trades\n\nnotes below");
            e.area().setCaretPosition(5);
            e.runText();
            e.area().insert("top\n\n", 0); // The user edits above the block that ran
            int from = e.text().indexOf("trades");
            e.area().setCaretPosition(from);
            e.area().moveCaretPosition(from + 6); // "trades" selected forwards, the caret at the block's end
            e.insertAtCaret("SELECT 1;");
            assertThat(e.text()).isEqualTo("top\n\nWrite a query for trades\n\nSELECT 1;\n\nnotes below");
            assertThat(e.area().getCaret().getMark()).isEqualTo(from);
            assertThat(e.area().getCaret().getDot()).isEqualTo(from + 6);
            assertThat(e.area().getSelectedText()).isEqualTo("trades");
            e.insertAtCaret("SELECT 2;");
            assertThat(e.text()).as("a second insert follows the first")
                    .isEqualTo("top\n\nWrite a query for trades\n\nSELECT 1;\n\nSELECT 2;\n\nnotes below");
            assertThat(e.area().getSelectedText()).isEqualTo("trades");
            e.dispose();
        });
    }

    /// An insert after a block that ends the text is separated by a blank line, leaves the caret at the block's end, and a
    /// second insert follows the first.
    @Test
    void anInsertAfterTheLastBlockIsABlockOfItsOwn() {
        onEdt(() -> {
            CommandEditor e = new CommandEditor();
            e.load("Write a query for trades");
            e.area().setCaretPosition(e.text().length());
            e.runText();
            e.insertAtCaret("SELECT 1;");
            assertThat(e.text()).isEqualTo("Write a query for trades\n\nSELECT 1;\n\n");
            assertThat(e.area().getCaretPosition()).isEqualTo("Write a query for trades".length());
            e.insertAtCaret("SELECT 2;");
            assertThat(e.text()).isEqualTo("Write a query for trades\n\nSELECT 1;\n\nSELECT 2;\n\n");
            e.dispose();
        });
    }

    /// With nothing run, an insert appends as a block of its own and the selection stays.
    @Test
    void anInsertWithNothingRunAppends() {
        onEdt(() -> {
            CommandEditor e = new CommandEditor();
            e.load("abc def ghi");
            e.area().setCaretPosition(4);
            e.area().moveCaretPosition(7);
            e.insertAtCaret("XYZ");
            assertThat(e.text()).isEqualTo("abc def ghi\n\nXYZ\n\n");
            assertThat(e.area().getSelectedText()).isEqualTo("def");
            e.dispose();
        });
    }

    /// A backward selection keeps its mark and dot when the session appends.
    @Test
    void aSessionsWriteKeepsABackwardSelection() {
        onEdt(() -> {
            CommandEditor e = new CommandEditor();
            e.load(TEXT);
            e.area().setCaretPosition(20);
            e.area().moveCaretPosition(15);
            e.appendQuietly("SELECT 2;");
            assertThat(e.area().getCaret().getMark()).isEqualTo(20);
            assertThat(e.area().getCaret().getDot()).isEqualTo(15);
            e.dispose();
        });
    }

    /// A position reported for a block of several statements counts from the failed statement, so nothing is underlined.
    @Test
    void anErrorInABlockOfSeveralStatementsIsNotUnderlined() {
        onEdt(() -> {
            String block = "insert into t1 values (99); select * From nope";
            CommandEditor e = new CommandEditor();
            e.load(block);
            e.area().setCaretPosition(3);
            String ran = e.runText();
            assertThat(e.markError(ran, 16)).isFalse();
            assertThat(e.errorStart()).isEqualTo(-1);
            e.dispose();
        });
    }

    /// [CommandEditor#caretInSql] is true at the end of a SQL statement being typed only while a database is open, and false
    /// after prose, including words that are also SQL keywords.
    @Test
    void completionOpensInSqlBeingTypedNotInProse() {
        onEdt(() -> {
            for (String sql : new String[] {"UPDATE tr", "update tr", "WITH x", "select * From trades t where t.", "SELECT"}) {
                assertThat(inSql(sql, true)).as(sql).isTrue();
                assertThat(inSql(sql, false)).as(sql + ", no database open").isFalse();
            }
            for (String prose : new String[] {"Show", "Explain", "Update", "Explain why the", "show me the log"}) {
                assertThat(inSql(prose, true)).as(prose).isFalse();
            }
        });
    }

    /// Clear empties the text, reports the empty text to be saved, and one undo brings it back.
    @Test
    void clearEmptiesTheTextInOneStepThatUndoBringsBack() {
        onEdt(() -> {
            CommandEditor e = new CommandEditor();
            e.load(TEXT);
            List<String> changes = new ArrayList<>();
            e.onChange(changes::add);
            e.clear();
            assertThat(e.text()).isEmpty();
            assertThat(changes).as("the panel's file is saved empty").last().isEqualTo("");
            e.area().undoLastAction();
            assertThat(e.text()).isEqualTo(TEXT);
            e.dispose();
        });
    }

    private static boolean inSql(String text, boolean live) {
        CommandEditor c = new CommandEditor();
        c.sqlLive(live);
        c.load(text);
        c.area().setCaretPosition(text.length());
        boolean in = c.caretInSql();
        c.dispose();
        return in;
    }

    /// A reload keeps the caret, clamped to the new length, reports no change, and one undo restores the previous text and
    /// is reported as the user's edit.
    @Test
    void aReloadFromTheFileKeepsTheCaretReportsNoChangeAndUndoesInOneStep() {
        onEdt(() -> {
            CommandEditor e = new CommandEditor();
            e.load(TEXT);
            List<String> changes = new ArrayList<>();
            e.onChange(changes::add);
            e.area().setCaretPosition(5);
            e.reload("SELECT 2;\n");
            assertThat(e.text()).isEqualTo("SELECT 2;\n");
            assertThat(e.area().getCaretPosition()).isEqualTo(5);
            assertThat(changes).as("the file already holds the text").isEmpty();
            e.reload("x");
            assertThat(e.area().getCaretPosition()).as("within the new text").isEqualTo(1);
            e.area().undoLastAction();
            assertThat(e.text()).as("one step back").isEqualTo("SELECT 2;\n");
            assertThat(changes).as("an undo is the user's edit, and is saved").isNotEmpty().last().isEqualTo("SELECT 2;\n");
            e.dispose();
        });
    }

    /// The panel style marks each block with where it runs; a Java style marks none and opens no SQL completion; the panel
    /// style marks them again.
    @Test
    void aSourceFileIsColouredAsItsLanguageAndMarksNoTargets() {
        onEdt(() -> {
            CommandEditor e = new CommandEditor();
            e.load("select x from t;\n\nclass A {}\n");
            assertThat(e.marked()).as("a panel's own text: each block marked with where it runs").isEqualTo(2);
            e.syntax(SyntaxConstants.SYNTAX_STYLE_JAVA);
            assertThat(e.area().getSyntaxEditingStyle()).isEqualTo(SyntaxConstants.SYNTAX_STYLE_JAVA);
            assertThat(e.marked()).as("the gutter marks no targets in source code").isZero();
            e.area().setCaretPosition(2);
            assertThat(e.caretInSql()).as("no SQL completion pops up in Java").isFalse();
            e.syntax(CommandEditor.PANEL_STYLE);
            assertThat(e.marked()).isEqualTo(2);
            e.dispose();
        });
    }

    private static void onEdt(Runnable r) {
        try {
            SwingUtilities.invokeAndWait(r);
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof RuntimeException x) {
                throw x;
            }
            if (e.getCause() instanceof Error x) {
                throw x;
            }
            throw new IllegalStateException(e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
