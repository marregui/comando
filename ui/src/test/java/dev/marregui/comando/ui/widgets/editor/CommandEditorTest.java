package dev.marregui.comando.ui.widgets.editor;

import org.fife.ui.rsyntaxtextarea.SyntaxConstants;
import org.junit.jupiter.api.Test;
import javax.swing.SwingUtilities;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The editor never lets a run or a session's write take the user's text or caret. */
class CommandEditorTest {

    private static final String TEXT = "SELECT 1;\n\nExplain the query above.\n";

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

    /** The caret on the line under the prompt that ran: that line stays the user's, the inserted block goes after it. */
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

    /** In an empty editor the caret's line stays empty for the user; the session's block goes after it. */
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

    /** The user typed above other text, ran it, the session appended; one ⌘Z takes back the session's block only. */
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

    /** An insert and a replace are their own undo steps too. */
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

    /** With the caret at the end on a fresh line, the session's block goes after it and typing stays the user's. */
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

    /**
     * An insert is a block of its own after the block that ran, wherever that block is now, and the
     * user's selection stays exactly the user's.
     */
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

    /** With the caret at the end of the block that ran, the insert is not glued to it. */
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

    /** With nothing run from the editor, an insert appends. */
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

    /** A backward selection keeps its anchor and its direction when the session writes. */
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

    /** In a block of several statements the position counts from the one that failed; nothing is underlined. */
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

    /** Completion opens on its own in SQL being typed, not on the first word of a prompt. */
    @Test
    void completionOpensInSqlBeingTypedNotInProse() {
        onEdt(() -> {
            for (String sql : new String[] {"UPDATE tr", "update tr", "WITH x", "select * From trades t where t.", "SELECT"}) {
                assertThat(inSql(sql)).as(sql).isTrue();
            }
            for (String prose : new String[] {"Show", "Explain", "Update", "Explain why the", "show me the log"}) {
                assertThat(inSql(prose)).as(prose).isFalse();
            }
        });
    }

    private static boolean inSql(String text) {
        CommandEditor c = new CommandEditor();
        c.load(text);
        c.area().setCaretPosition(text.length());
        boolean in = c.caretInSql();
        c.dispose();
        return in;
    }

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
            e.syntax(SyntaxConstants.SYNTAX_STYLE_SQL);
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
