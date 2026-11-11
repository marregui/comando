package dev.marregui.comando.ui.widgets.approval;

import java.awt.Component;
import java.awt.Container;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;
import javax.swing.JLabel;
import javax.swing.text.JTextComponent;

import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rsyntaxtextarea.SyntaxConstants;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import dev.marregui.comando.core.intent.Intent;
import dev.marregui.comando.core.model.Approval;
import dev.marregui.comando.core.model.ApprovalKind;
import dev.marregui.comando.core.model.PermissionRules;
import dev.marregui.comando.ui.kit.action.ActionRegistry;
import dev.marregui.comando.ui.kit.async.OnEdt;
import dev.marregui.comando.ui.widget.WidgetContext;

import static org.assertj.core.api.Assertions.assertThat;

/// [ApprovalInbox]: what a tool card says, in the words a person reads.
/// - a Write shows the file's text as the transcript does, with its line count; an edit keeps its diff
/// - the head says what the call wants once, with a path under the home folder as `~/…`
/// - the scope line, Always included, says what a rule allows; a shape with no words prints the rule
@ExtendWith(OnEdt.class)
class ApprovalInboxWordsTest {

    private static final String HOME = System.getProperty("user.home");
    private static final long T0 = 1_000_000_000L;

    private final AtomicLong now = new AtomicLong(T0);
    private final List<ApprovalInbox> opened = new ArrayList<>();
    private final List<Intent> sent = new ArrayList<>();

    @AfterEach
    void unmount() {
        opened.forEach(ApprovalInbox::unmount);
    }

    // ---- what a Write shows

    /// The text starts at its first line with no diff marks, stops at the transcript's limit and counts all its lines.
    @Test
    void aWriteShowsTheFilesTextClippedWithItsLineCount() {
        final String content = "first\\n" + "line\\n".repeat(400);
        final Component card = card(write("/a/new.txt", content));
        final String shown = viewer(card).getText();
        assertThat(shown).startsWith("first\nline\n").endsWith("\n… 101 more lines")
                .doesNotContain("+first").doesNotContain("+++");
        assertThat(shown.lines().count()).isEqualTo(301);
        assertThat(((JLabel) named(card, "lines")).getText()).isEqualTo("401 lines");
    }

    /// One line reads as one line, and a Write of nothing still says so.
    @Test
    void aWriteCountsItsLinesInTheSingular() {
        final Component one = card(write("/a", "x"));
        assertThat(((JLabel) named(one, "lines")).getText()).isEqualTo("1 line");
        final Component none = card(write("/a", ""));
        assertThat(((JLabel) named(none, "lines")).getText()).isEqualTo("0 lines");
    }

    /// The text is coloured by the file's extension, with none and with a folder that has a dot.
    @Test
    void aWriteColoursItsTextByTheFile() {
        assertThat(style("/a/B.java")).isEqualTo(SyntaxConstants.SYNTAX_STYLE_JAVA);
        assertThat(style("/a.d/notes")).isEqualTo(SyntaxConstants.SYNTAX_STYLE_NONE);
        assertThat(style("")).isEqualTo(SyntaxConstants.SYNTAX_STYLE_NONE);
    }

    /// An Edit replaces text, so it keeps the diff and has no line count.
    @Test
    void anEditKeepsItsDiff() {
        final String input = "{\"file_path\":\"/a\",\"old_string\":\"x\",\"new_string\":\"y\"}";
        final Component card = card(tool("Edit", input, "Edit /a"));
        assertThat(viewer(card).getText()).contains("-x").contains("+y");
        assertThat(named(card, "lines")).isNull();
    }

    // ---- the head

    /// A file tool names its path once, under the home folder as `~/…`, outside it whole.
    @Test
    void theHeadOfAFileToolNamesItsPathOnce() {
        final String path = HOME + "/a/B.java";
        final String file = "{\"file_path\":\"" + path + "\"}";
        assertThat(title("Write", file, "Write " + path)).isEqualTo("wants to write ~/a/B.java");
        assertThat(title("Edit", file, "Edit " + path)).isEqualTo("wants to edit ~/a/B.java");
        assertThat(title("MultiEdit", file, "MultiEdit " + path)).isEqualTo("wants to edit ~/a/B.java");
        assertThat(title("NotebookEdit", "{\"notebook_path\":\"" + path + "\"}", "NotebookEdit " + path))
                .isEqualTo("wants to edit ~/a/B.java");
        assertThat(title("Write", "{\"file_path\":\"/opt/a\"}", "Write /opt/a")).isEqualTo("wants to write /opt/a");
    }

    /// A command reads as itself, whatever the agent's description says of it: the description could be wrong.
    @Test
    void theHeadOfACommandReadsByItsDescription() {
        assertThat(title("Bash", "{\"command\":\"ls\",\"description\":\"List\"}", "ls"))
                .isEqualTo("wants to run: ls");
        assertThat(title("Bash", "{\"command\":\"ls\",\"description\":\" one\\ntwo \"}", "ls"))
                .isEqualTo("wants to run: ls");
        assertThat(title("Bash", "{\"command\":\"ls -la\",\"description\":\" \"}", "ls -la"))
                .isEqualTo("wants to run: ls -la");
        assertThat(title("Bash", "{\"command\":\"ls -la\"}", "ls -la")).isEqualTo("wants to run: ls -la");
    }

    /// Any other tool keeps its sentence; the word the summary repeats from the tool's name goes.
    @Test
    void theHeadOfAnyOtherToolRepeatsNoWord() {
        assertThat(title("Read", "{\"file_path\":\"" + HOME + "/a\"}", "Read " + HOME + "/a"))
                .isEqualTo("wants to use Read: ~/a");
        assertThat(title("WebFetch", "{\"url\":\"https://a.org\"}", "Fetch https://a.org"))
                .isEqualTo("wants to use WebFetch: https://a.org");
        assertThat(title("WebSearch", "{\"query\":\"q\"}", "Search q")).isEqualTo("wants to use WebSearch: q");
        assertThat(title("mcp__x__y", "{\"a\":1}", "mcp__x__y a=1")).isEqualTo("wants to use mcp__x__y: a=1");
        assertThat(title("mcp__x__y", "{}", "mcp__x__y")).isEqualTo("wants to use mcp__x__y");
        assertThat(title("mcp__x__delete_all", "{}", "delete all"))
                .isEqualTo("wants to use mcp__x__delete_all: delete all");
    }

    /// With no path in its input a file tool has nothing to name and keeps the sentence.
    @Test
    void aFileToolWithNoPathKeepsTheSentence() {
        assertThat(title("Write", "{}", "Write")).isEqualTo("wants to use Write");
    }

    // ---- the scope line

    /// Files under a folder, a command's start, a site: each in words, with the rule behind them unchanged.
    @Test
    void theScopeLineSaysWhatARuleAllows() {
        final String measure = "/" + HOME + "/.claude/tools/measure/**";
        assertThat(scope(false, rule("Edit", measure)))
                .isEqualTo("For session: any edit under ~/.claude/tools/measure/");
        assertThat(scope(false, rule("Read", measure)))
                .isEqualTo("For session: any read under ~/.claude/tools/measure/");
        assertThat(scope(true, rule("Edit", "./src/**"))).isEqualTo("Always: any edit under ./src/");
        assertThat(scope(false, rule("Edit", "//etc/**"))).isEqualTo("For session: any edit under /etc/");
        assertThat(scope(false, rule("Read", "//**"))).isEqualTo("For session: any read under /");
        assertThat(scope(false, rule("Edit", ""))).isEqualTo("For session: any edit");
        assertThat(scope(false, rule("Read", ""))).isEqualTo("For session: any read");
        assertThat(scope(true, rule("Bash", "git status:*"))).isEqualTo("Always: commands starting \"git status\"");
        assertThat(scope(true, rule("Bash", "git status"))).isEqualTo("Always: the command \"git status\"");
        assertThat(scope(false, rule("Bash", ""))).isEqualTo("For session: any command");
        assertThat(scope(true, rule("WebFetch", "domain:a.org"))).isEqualTo("Always: any fetch from a.org");
        assertThat(scope(false, rule("WebFetch", ""))).isEqualTo("For session: any fetch");
    }

    /// A rule with no words prints as it is written, and several rules join.
    @Test
    void aShapeWithNoWordsPrintsTheRule() {
        assertThat(scope(false, rule("Edit", "./one.txt"))).isEqualTo("For session: Edit(./one.txt)");
        assertThat(scope(false, rule("Grep", ""))).isEqualTo("For session: Grep");
        assertThat(scope(false, rule("WebFetch", "a.org"))).isEqualTo("For session: WebFetch(a.org)");
        assertThat(scope(false, rule("mcp__x__y", ""))).isEqualTo("For session: mcp__x__y");
        assertThat(scope(true, rule("Bash", "git status"), rule("Edit", "./one.txt")))
                .isEqualTo("Always: the command \"git status\", Edit(./one.txt)");
    }

    private static PermissionRules.Rule rule(final String tool, final String content) {
        return new PermissionRules.Rule(tool, content);
    }

    private static String title(final String tool, final String input, final String summary) {
        return ApprovalInbox.title(tool(tool, input, summary));
    }

    private static Approval write(final String path, final String content) {
        return tool("Write", "{\"file_path\":\"" + path + "\",\"content\":\"" + content + "\"}", "Write " + path);
    }

    private static Approval tool(final String name, final String input, final String summary) {
        return new Approval(1, "work", ApprovalKind.TOOL, name, input, summary, T0);
    }

    private static JTextComponent viewer(final Component card) {
        return (JTextComponent) find(card, c -> c instanceof JTextComponent t && !t.isEditable() && t.isFocusable());
    }

    private static Component named(final Component root, final String name) {
        return find(root, c -> name.equals(c.getName()));
    }

    private static Component find(final Component root, final Predicate<Component> p) {
        if (p.test(root)) {
            return root;
        }
        if (root instanceof Container k) {
            for (final Component c : k.getComponents()) {
                final Component f = find(c, p);
                if (f != null) {
                    return f;
                }
            }
        }
        return null;
    }

    private WidgetContext context() {
        return new WidgetContext(sent::add, new ActionRegistry());
    }

    private Component card(final Approval a) {
        return card(a, new ApprovalInbox(context(), now::get, ApprovalInbox.Focus.WINDOW));
    }

    private Component card(final Approval a, final ApprovalInbox inbox) {
        opened.add(inbox);
        inbox.render(new ApprovalInbox.Queue(List.of(a), ""));
        return find(inbox.component(), c -> "rule".equals(c.getName())).getParent().getParent();
    }

    private String style(final String path) {
        return ((RSyntaxTextArea) viewer(card(write(path, "x")))).getSyntaxEditingStyle();
    }

    private String scope(final boolean persistable, final PermissionRules.Rule... rules) {
        final ApprovalInbox inbox = new ApprovalInbox(context(), now::get, ApprovalInbox.Focus.WINDOW,
                (tool, field) -> new PermissionRules.Rules(List.of(rules), persistable));
        return ((JTextComponent) named(card(tool("Bash", "{\"command\":\"x\"}", "x"), inbox), "rule")).getText();
    }
}
