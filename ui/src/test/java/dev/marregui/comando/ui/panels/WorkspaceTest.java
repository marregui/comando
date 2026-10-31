package dev.marregui.comando.ui.panels;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Container;
import java.awt.HeadlessException;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.awt.event.MouseMotionListener;
import java.awt.geom.Rectangle2D;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JLayeredPane;
import javax.swing.JList;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.JRootPane;
import javax.swing.JTabbedPane;
import javax.swing.JTextArea;
import javax.swing.JViewport;
import javax.swing.SwingUtilities;
import javax.swing.event.HyperlinkEvent;
import javax.swing.text.BadLocationException;
import javax.swing.text.PlainDocument;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;

import dev.marregui.comando.core.intent.Intent;
import dev.marregui.comando.core.model.Approval;
import dev.marregui.comando.core.model.ApprovalKind;
import dev.marregui.comando.core.model.ConnectionSpec;
import dev.marregui.comando.core.model.LinearTickets;
import dev.marregui.comando.core.model.PanelId;
import dev.marregui.comando.core.model.SessionSpec;
import dev.marregui.comando.core.model.Status;
import dev.marregui.comando.core.model.Topic;
import dev.marregui.comando.core.port.TopicPort;
import dev.marregui.comando.core.schema.SchemaRegistry;
import dev.marregui.comando.core.state.AppState;
import dev.marregui.comando.core.state.BackgroundTask;
import dev.marregui.comando.core.state.ConnectionState;
import dev.marregui.comando.core.state.PanelState;
import dev.marregui.comando.core.state.SessionState;
import dev.marregui.comando.core.transcript.Block;
import dev.marregui.comando.core.transcript.Transcript;
import dev.marregui.comando.core.transcript.TranscriptRegistry;
import dev.marregui.comando.ui.PanelTexts;
import dev.marregui.comando.ui.kit.format.KeyText;
import dev.marregui.comando.ui.kit.icon.Icons;
import dev.marregui.comando.ui.kit.icon.SpinnerIcon;
import dev.marregui.comando.ui.kit.text.TextLinks;
import dev.marregui.comando.ui.kit.theme.Theme;
import dev.marregui.comando.ui.kit.theme.Tint;

import static org.assertj.core.api.Assertions.assertThat;

/// [Workspace]: the panel tabs, their files kept in step with the disk, their status marks, menus and hover cards.
/// - an outside change reloads a panel unless it has unsaved edits; a growing log posts no notice
/// - a file deleted outside removes its panel, except a session's own panel or one edited since the report
/// - a tab follows its session's status, marks an outcome until looked at, and asks for attention once per need
/// - returned background work marks the tab finished only when no reading turn follows within the grace
/// - a tab's menu closes others or one side, keeping tabs that wait for the user or run a program
/// - a tab's or row's card shows the facts, the summary read on demand, linked tickets, sub-task links and closes when
///   left
/// - a connection's card shows its facts without a summary and follows its state
/// - a dragged tab moves its panel; a link to another document's heading opens or scrolls it there
/// - a log opened for an entry scrolls to its line, or waits for it to be written
class WorkspaceTest {

    private static final PanelId P = new PanelId("p");

    /** Panel text in memory that hands its outside-change listener to the test. */
    private static final class Texts implements PanelTexts {
        final AtomicInteger changes = new AtomicInteger();
        Consumer<Outside> listener;
        Consumer<Deleted> deletions;

        @Override
        public void onDeleted(final Consumer<Deleted> listener) {
            this.deletions = listener;
        }

        @Override
        public String load(final PanelId panel) {
            return "SELECT 1;";
        }

        @Override
        public void changed(final PanelId panel, final String text) {
            changes.incrementAndGet();
        }

        @Override
        public void forget(final PanelId panel) {
        }

        @Override
        public void onOutsideChange(final Consumer<Outside> listener) {
            this.listener = listener;
        }
    }

    /// A log panel reloaded from outside takes the new text without posting a notice.
    @Test
    void aLogFollowingItsGrowthPostsNoNotice() throws Exception {
        final Texts texts = new Texts();
        final List<Intent> intents = new CopyOnWriteArrayList<>();
        final Workspace[] w = new Workspace[1];
        SwingUtilities.invokeAndWait(() -> {
            w[0] = new Workspace(intents::add, texts, new SchemaRegistry());
            w[0].render(AppState.initial(List.of(new PanelState(P, "comando-0", "", "", true)), List.of(), List.of()));
            w[0].focused().file(Path.of("/home/.comando/logs/comando-0.log"), true);
        });
        SwingUtilities.invokeAndWait(() -> w[0].takeOutside(new PanelTexts.Outside(P, "comando-0.log", "one\ntwo\n", () -> true)));
        assertThat(w[0].focused().editor().text()).isEqualTo("one\ntwo\n");
        assertThat(intents).noneMatch(Intent.Notify.class::isInstance);
    }

    /// An outside change is refused while the panel has unsaved edits and taken otherwise, without counting as an edit
    /// to save, with one "Reloaded" notice.
    @Test
    void aFileChangedOutsideShowsInItsPanelUnlessThePanelHasUnsavedEdits() throws Exception {
        final Texts texts = new Texts();
        final List<Intent> intents = new CopyOnWriteArrayList<>();
        final Workspace[] w = new Workspace[1];
        SwingUtilities.invokeAndWait(() -> {
            w[0] = new Workspace(intents::add, texts, new SchemaRegistry());
            w[0].render(AppState.initial(List.of(new PanelState(P, "spec", "", "")), List.of(), List.of()));
        });
        assertThat(texts.listener).as("the workspace listens for outside changes").isNotNull();

        SwingUtilities.invokeAndWait(() -> w[0].takeOutside(new PanelTexts.Outside(P, "spec.md", "SELECT 2;", () -> false)));
        assertThat(w[0].focused().editor().text()).as("unsaved edits win; their save keeps the outside text aside").isEqualTo("SELECT 1;");

        SwingUtilities.invokeAndWait(() -> w[0].takeOutside(new PanelTexts.Outside(P, "spec.md", "SELECT 2;", () -> true)));
        assertThat(w[0].focused().editor().text()).isEqualTo("SELECT 2;");
        assertThat(texts.changes).as("a reload is not an edit to save").hasValue(0);
        assertThat(intents).filteredOn(Intent.Notify.class::isInstance).extracting(i -> ((Intent.Notify) i).text())
                .containsExactly("Reloaded spec.md: it changed outside comando");
    }

    /// A document deleted outside removes its panel once, with a notice, leaving nothing for ⌘⇧T to reopen; a closed
    /// panel's file deleted outside removes that panel too.
    @Test
    void aDeletedFileRemovesItsPanelWithNothingToReopen() throws Exception {
        final PanelId doc = new PanelId("d");
        final Texts texts = new Texts();
        final List<Intent> intents = new CopyOnWriteArrayList<>();
        final PanelState spec = new PanelState(P, "spec", "", "");
        final Workspace[] w = new Workspace[1];
        SwingUtilities.invokeAndWait(() -> {
            w[0] = new Workspace(intents::add, texts, new SchemaRegistry());
            w[0].render(AppState.initial(List.of(spec, new PanelState(doc, "ALPHA", "", "")), List.of(), List.of()));
        });
        assertThat(texts.deletions).as("the workspace listens for deleted documents").isNotNull();

        SwingUtilities.invokeAndWait(() -> w[0].closeDeleted(new PanelTexts.Deleted(doc, "ALPHA.md", () -> true)));
        assertThat(intents).containsExactly(new Intent.RemovePanel(doc));
        SwingUtilities.invokeAndWait(() -> w[0].render(AppState.initial(List.of(spec), List.of(), List.of())));
        assertThat(w[0].hasClosed()).as("⌘⇧T has nothing to reopen: the file is gone").isFalse();
        assertThat(intents).filteredOn(Intent.Notify.class::isInstance).extracting(i -> ((Intent.Notify) i).text())
                .containsExactly("Removed ALPHA: ALPHA.md was deleted outside comando");

        SwingUtilities.invokeAndWait(() -> w[0].closeDeleted(new PanelTexts.Deleted(doc, "ALPHA.md", () -> true)));
        assertThat(intents).as("a panel already gone is not removed twice").filteredOn(Intent.RemovePanel.class::isInstance).hasSize(1);

        final PanelId shut = new PanelId("c");
        SwingUtilities.invokeAndWait(() -> w[0].render(AppState.initial(List.of(spec), List.of(), List.of())
                .withClosed(List.of(new PanelState(shut, "old", "", "")))));
        SwingUtilities.invokeAndWait(() -> w[0].closeDeleted(new PanelTexts.Deleted(shut, "old.md", () -> true)));
        assertThat(intents).as("a closed panel's file deleted outside removes it too").contains(new Intent.RemovePanel(shut));
    }

    /// A session's own panel whose file is deleted is not removed, since that would stop the session; its text is
    /// written again and a notice says why.
    @Test
    void aSessionsOwnPanelWhoseFileWasDeletedKeepsItsSessionAndWritesItsTextAgain() throws Exception {
        final Texts texts = new Texts();
        final List<Intent> intents = new CopyOnWriteArrayList<>();
        final SessionState work = SessionState.closed(SessionSpec.fresh("work", "/w")).withStatus(Status.OPEN, "");
        final PanelState own = new PanelState(P, "work", "", "work");
        final Workspace[] w = new Workspace[1];
        SwingUtilities.invokeAndWait(() -> {
            w[0] = new Workspace(intents::add, texts, new SchemaRegistry());
            w[0].render(AppState.initial(List.of(own), List.of(), List.of(work)));
            w[0].panel(P).editor().area().setText("my notes");
        });
        final int before = texts.changes.get();
        SwingUtilities.invokeAndWait(() -> w[0].closeDeleted(new PanelTexts.Deleted(P, "work.md", () -> true)));
        assertThat(intents).as("removing it would remove and stop the session").doesNotHaveAnyElementsOfTypes(Intent.RemovePanel.class);
        assertThat(texts.changes.get()).as("its text is written again").isGreaterThan(before);
        assertThat(intents).filteredOn(Intent.Notify.class::isInstance).extracting(i -> ((Intent.Notify) i).text())
                .containsExactly("work.md was deleted outside comando; it is session work's own panel, so its text is written again");
    }

    /// A deletion reported after the user typed in the panel keeps the panel, and the notice says the unsaved edits
    /// write the file again.
    @Test
    void aDeletedFileWhosePanelGotEditsSinceTheReportKeepsThePanel() throws Exception {
        final Texts texts = new Texts();
        final List<Intent> intents = new CopyOnWriteArrayList<>();
        final PanelState spec = new PanelState(P, "spec", "", "");
        final PanelId other = new PanelId("o");
        final Workspace[] w = new Workspace[1];
        SwingUtilities.invokeAndWait(() -> {
            w[0] = new Workspace(intents::add, texts, new SchemaRegistry());
            w[0].render(AppState.initial(List.of(spec, new PanelState(other, "other", "", "")), List.of(), List.of()));
        });
        // the store saw the file gone, then the user typed before the report reached the EDT
        SwingUtilities.invokeAndWait(() -> w[0].closeDeleted(new PanelTexts.Deleted(P, "spec.md", () -> false)));
        assertThat(intents).doesNotHaveAnyElementsOfTypes(Intent.RemovePanel.class);
        assertThat(intents).filteredOn(Intent.Notify.class::isInstance).extracting(i -> ((Intent.Notify) i).text())
                .containsExactly("spec.md was deleted outside comando; the panel's unsaved edits write it again");
    }

    /// A background tab shows the spinner icon and wants it turned while its turn runs, its timer waiting until the
    /// window shows; it gets the question mark and one attention request when the session asks, the check when the
    /// turn ends unseen, which clears once focused, and a failed turn asks for attention again until a new turn
    /// supersedes it. The icon alone carries the status: the title never changes.
    @Test
    void aTabFollowsItsSessionAndMarksAnOutcomeUntilLookedAt() throws Exception {
        final PanelId other = new PanelId("o");
        final PanelState work = new PanelState(P, "work", "", "s");
        final PanelState notes = new PanelState(other, "notes", "", "");
        final SessionState session = SessionState.closed(SessionSpec.fresh("s", "/tmp")).withStatus(Status.OPEN, "");
        final List<Intent> intents = new CopyOnWriteArrayList<>();
        final AtomicInteger attention = new AtomicInteger();
        final Workspace[] w = new Workspace[1];
        final AppState[] s = new AppState[1];
        SwingUtilities.invokeAndWait(() -> {
            w[0] = new Workspace(intents::add, new Texts(), new SchemaRegistry());
            w[0].onAttention(attention::incrementAndGet);
            s[0] = AppState.initial(List.of(work, notes), List.of(), List.of(session)).withFocused(other);
            w[0].render(s[0]);
        });
        final JTabbedPane tabs = (JTabbedPane) w[0].component();
        assertThat(w[0].statuses()).containsEntry(P, TabStatus.IDLE).containsEntry(other, TabStatus.NONE);
        assertThat(tabs.getTitleAt(0)).isEqualTo("work");

        // the turn runs while another tab is in front: the tab spins, its title stays
        SwingUtilities.invokeAndWait(() -> w[0].render(s[0] = s[0].withSession(session.withTurns(1, 1L))));
        assertThat(w[0].statuses()).containsEntry(P, TabStatus.WORKING);
        assertThat(tabs.getIconAt(0)).isInstanceOf(SpinnerIcon.class);
        assertThat(w[0].spinWanted()).as("a working tab wants the spinner").isTrue();
        assertThat(w[0].spinning()).as("never shown: the spinner's timer waits for the window").isFalse();
        assertThat(tabs.getTitleAt(0)).isEqualTo("work");

        // the session asks: the tab needs the user, and the desktop is asked for attention once
        SwingUtilities.invokeAndWait(() -> w[0].render(s[0] = s[0].withApprovals(List.of(
                new Approval(1L, "s", ApprovalKind.TOOL, "Bash", "{}", "ls", 1L)))));
        assertThat(w[0].statuses()).containsEntry(P, TabStatus.NEEDS_YOU);
        assertThat(tabs.getIconAt(0)).isSameAs(Icons.get("question"));
        assertThat(tabs.getTitleAt(0)).as("one icon, no mark beside it").isEqualTo("work");
        assertThat(attention).hasValue(1);
        SwingUtilities.invokeAndWait(() -> w[0].render(s[0]));
        assertThat(attention).as("the same need asks once").hasValue(1);

        // answered, the turn ends unseen: done, the check, no spinner, no attention
        SwingUtilities.invokeAndWait(() -> w[0].render(s[0] = s[0].withApprovals(List.of()).withSession(session.withTurns(0, 2L))));
        assertThat(w[0].statuses()).containsEntry(P, TabStatus.DONE);
        assertThat(tabs.getIconAt(0)).isSameAs(Icons.get("check"));
        assertThat(tabs.getTitleAt(0)).isEqualTo("work");
        assertThat(w[0].spinWanted()).isFalse();
        assertThat(attention).hasValue(1);

        // looking at it clears the check: the session's own icon is back
        SwingUtilities.invokeAndWait(() -> w[0].render(s[0] = s[0].withFocused(P)));
        assertThat(w[0].statuses()).containsEntry(P, TabStatus.IDLE);
        assertThat(tabs.getIconAt(0)).isSameAs(Icons.get("claude"));
        assertThat(tabs.getTitleAt(0)).isEqualTo("work");

        // a failed turn while away: an error, with attention; a new turn starting supersedes it
        SwingUtilities.invokeAndWait(() -> w[0].render(s[0] = s[0].withFocused(other).withSession(session.withTurns(1, 3L))));
        SwingUtilities.invokeAndWait(() -> w[0].render(s[0] = s[0].withSession(session.withTurns(1, 3L).withTurns(0, 4L)
                .withLastTurnError("rate limited"))));
        assertThat(w[0].statuses()).containsEntry(P, TabStatus.ERROR);
        assertThat(tabs.getIconAt(0)).isSameAs(Icons.get("error"));
        assertThat(tabs.getTitleAt(0)).isEqualTo("work");
        assertThat(attention).hasValue(2);
        SwingUtilities.invokeAndWait(() -> w[0].render(s[0] = s[0].withSession(session.withTurns(1, 5L))));
        assertThat(w[0].statuses()).containsEntry(P, TabStatus.WORKING);
        assertThat(tabs.getTitleAt(0)).isEqualTo("work");
    }

    /// Returned background work is not marked done before its reading turn; with no reading turn within
    /// [TabStatus#REPORT_GRACE_MILLIS], the tick marks it done without a state change.
    @Test
    void returnedBackgroundWorkMarksTheTabFinishedOnlyWhenNoReadingTurnFollows() throws Exception {
        final PanelId other = new PanelId("o");
        final SessionState open = SessionState.closed(SessionSpec.fresh("s", "/tmp")).withStatus(Status.OPEN, "");
        final BackgroundTask agent = new BackgroundTask("a1", "local_agent", "review agent", 0L);
        final SessionState waiting = open.withTurns(1, 1_000L).withTasks(List.of(agent), 2_000L).withTurns(0, 3_000L);
        final long[] now = {3_000L};
        final Workspace[] w = new Workspace[1];
        final AppState[] s = new AppState[1];
        SwingUtilities.invokeAndWait(() -> {
            w[0] = new Workspace(i -> { }, new Texts(), new SchemaRegistry(), () -> now[0]);
            w[0].onAttention(() -> { });
            s[0] = AppState.initial(List.of(new PanelState(P, "work", "", "s"), new PanelState(other, "notes", "", "")), List.of(), List.of(waiting))
                    .withFocused(other);
            w[0].render(s[0]);
        });
        assertThat(w[0].statuses()).containsEntry(P, TabStatus.WAITING);

        // the work returns; its reading turn has not started a second later
        now[0] = 61_000L;
        SwingUtilities.invokeAndWait(() -> w[0].render(s[0] = s[0].withSession(waiting.withTasks(List.of(), 60_000L))));
        assertThat(w[0].statuses()).as("not finished before the turn that reads it").doesNotContainEntry(P, TabStatus.DONE);

        // no reading turn within the grace: the tick marks it finished, with no state change to wait for
        now[0] = 60_000L + TabStatus.REPORT_GRACE_MILLIS;
        SwingUtilities.invokeAndWait(() -> w[0].tick());
        assertThat(w[0].statuses()).containsEntry(P, TabStatus.DONE);
    }

    /// A background tab whose hand-off turn failed shows `ERROR` while its background task still runs.
    @Test
    void aHandOffTurnThatFailsShowsItsErrorWhileItsWorkRunsOn() throws Exception {
        final PanelId other = new PanelId("o");
        final SessionState open = SessionState.closed(SessionSpec.fresh("s", "/tmp")).withStatus(Status.OPEN, "");
        final BackgroundTask agent = new BackgroundTask("a1", "local_agent", "review agent", 0L);
        final Workspace[] w = new Workspace[1];
        final AppState[] s = new AppState[1];
        SwingUtilities.invokeAndWait(() -> {
            w[0] = new Workspace(i -> { }, new Texts(), new SchemaRegistry());
            w[0].onAttention(() -> { });
            s[0] = AppState.initial(List.of(new PanelState(P, "work", "", "s"), new PanelState(other, "notes", "", "")), List.of(),
                    List.of(open.withTurns(1, 1_000L))).withFocused(other);
            w[0].render(s[0]);
            final SessionState failed = open.withTurns(1, 1_000L).withTasks(List.of(agent), 1_500L).withTurns(0, 2_000L).withLastTurnError("rate limited");
            w[0].render(s[0] = s[0].withSession(failed));
        });
        assertThat(w[0].statuses()).containsEntry(P, TabStatus.ERROR);
    }

    /// "Close others" focuses the tab and closes the rest except those waiting for the user or running a program, with
    /// one notice once they are gone; a menu opened before tabs change closes from its tab's new position, and "Close"
    /// is the tab's own close.
    @Test
    void aTabsMenuClosesTheOthersOrThoseToOneSideAndKeepsTabsWaitingForTheUserOrRunningAProgram() throws Exception {
        final PanelId a = new PanelId("a");
        final PanelId b = new PanelId("b");
        final PanelId c = new PanelId("c");
        final PanelId d = new PanelId("d");
        final PanelId e = new PanelId("e");
        final SessionState session = SessionState.closed(SessionSpec.fresh("s", "/tmp")).withStatus(Status.OPEN, "");
        // a and b are plain; c's session waits for an approval; d's shell runs a program; e's session is mid-turn
        final AppState s = AppState.initial(List.of(new PanelState(a, "a", "", ""), new PanelState(b, "b", "", ""),
                        new PanelState(c, "c", "", "s"), new PanelState(d, "d", "", ""), new PanelState(e, "e", "", "busy")), List.of(),
                List.of(session, SessionState.closed(SessionSpec.fresh("busy", "/tmp")).withStatus(Status.OPEN, "").withTurns(1, 1L)))
                .withApprovals(List.of(new Approval(1L, "s", ApprovalKind.TOOL, "Bash", "{}", "ls", 1L)));
        final List<Intent> intents = new CopyOnWriteArrayList<>();
        final List<PanelId> closedOne = new CopyOnWriteArrayList<>();
        final Workspace[] w = new Workspace[1];
        SwingUtilities.invokeAndWait(() -> {
            w[0] = new Workspace(intents::add, new Texts(), new SchemaRegistry());
            w[0].onClose(closedOne::add);
            w[0].onRunsProgram(d::equals);
            w[0].render(s);
        });
        assertThat(w[0].statuses()).containsEntry(c, TabStatus.NEEDS_YOU).containsEntry(e, TabStatus.WORKING);

        final List<String> labels = new CopyOnWriteArrayList<>();
        final List<Boolean> enabled = new CopyOnWriteArrayList<>();
        SwingUtilities.invokeAndWait(() -> {
            for (final Component item : w[0].tabMenu(0).getComponents()) {
                if (item instanceof JMenuItem m) {
                    labels.add(m.getText());
                    enabled.add(m.isEnabled());
                }
            }
        });
        assertThat(labels).containsExactly("Close", "Close others", "Close to the right", "Close to the left");
        assertThat(enabled).as("nothing left of the first tab").containsExactly(true, true, true, false);

        SwingUtilities.invokeAndWait(() -> menuItem(w[0].tabMenu(1), "Close others").doClick());
        assertThat(intents).filteredOn(i -> i instanceof Intent.FocusPanel || i instanceof Intent.ClosePanel)
                .as("b in front, then the others but the one waiting and the one running a program")
                .containsExactly(new Intent.FocusPanel(b), new Intent.ClosePanel(a), new Intent.ClosePanel(e));
        assertThat(intents).as("no notice until the tabs are gone").noneMatch(Intent.Notify.class::isInstance);
        final AppState after = s.withPanels(s.panels().stream().filter(p -> !p.id().equals(a) && !p.id().equals(e)).toList(), b);
        SwingUtilities.invokeAndWait(() -> w[0].render(after));
        assertThat(intents).filteredOn(Intent.Notify.class::isInstance).extracting(i -> ((Intent.Notify) i).text())
                .as("one notice for the close, none per tab")
                .containsExactly(KeyText.of("Closed 2 tabs; ⌘⇧T or the Sessions tab reopens them; kept 2 tabs open: 1 waiting for you, 1 running a program in the shell"));

        // b c d now; a menu opened on d, then b closes elsewhere: the menu still closes only what was left of d
        intents.clear();
        final JPopupMenu[] menu = new JPopupMenu[1];
        SwingUtilities.invokeAndWait(() -> menu[0] = w[0].tabMenu(2));
        SwingUtilities.invokeAndWait(() -> w[0].render(after.withPanels(after.panels().stream().filter(p -> !p.id().equals(b)).toList(), c)));
        SwingUtilities.invokeAndWait(() -> menuItem(menu[0], "Close to the left").doClick());
        assertThat(intents).filteredOn(Intent.ClosePanel.class::isInstance).as("b is gone, c waits for the user: nothing closes").isEmpty();
        assertThat(intents).filteredOn(Intent.Notify.class::isInstance).extracting(i -> ((Intent.Notify) i).text())
                .containsExactly("Kept 1 tab open: 1 waiting for you");

        SwingUtilities.invokeAndWait(() -> menuItem(w[0].tabMenu(1), "Close").doClick());
        assertThat(closedOne).as("Close is the tab's own close, which asks when its shell runs a program").containsExactly(d);
    }

    /** A topic reader that keeps every ask for the test to answer. */
    private static final class Asks implements TopicPort {
        record Ask(String key, String source, boolean urgent, Consumer<Topic> done) {
        }

        final List<Ask> asks = new CopyOnWriteArrayList<>();

        @Override
        public void read(final String key, final String source, final boolean urgent, final Consumer<Topic> done) {
            asks.add(new Ask(key, source, urgent, done));
        }
    }

    /// A row's card lists the session's top-level sub-tasks with their state and tool count at any depth, including one
    /// running in the background until it ends; a double click on one sends `OpenSubagent`, a single click nothing.
    @Test
    void aSessionsCardOutlinesItsSubagentsAndADoubleClickOpensOnesTab() throws Exception {
        final TranscriptRegistry transcripts = new TranscriptRegistry();
        final Transcript t = transcripts.of("idle");
        t.append(new Block.UserPrompt("review it", "x", ""));
        t.append(new Block.ToolCall("a1", "Agent", "{\"description\":\"Review the diff\",\"prompt\":\"…\"}", "found 2", Block.ToolStatus.DONE, ""));
        t.append(new Block.ToolCall("r1", "Read", "{\"file_path\":\"/w/a\"}", "ok", Block.ToolStatus.DONE, "a1"));
        t.append(new Block.ToolCall("n1", "Agent", "{\"description\":\"nested\"}", "ok", Block.ToolStatus.DONE, "a1"));
        t.append(new Block.ToolCall("g1", "Grep", "{\"pattern\":\"x\"}", "ok", Block.ToolStatus.DONE, "n1"));
        t.append(new Block.ToolCall("a2", "Agent", "{\"description\":\"Run the tests\"}", "", Block.ToolStatus.RUNNING, ""));
        t.append(new Block.ToolCall("a3", "Agent", "{\"description\":\"Watch CI\"}", "Async agent launched successfully. agentId: bg7", Block.ToolStatus.DONE, ""));
        t.append(new Block.ToolCall("a4", "Agent", "{\"description\":\"Broken\"}", "boom", Block.ToolStatus.ERROR, ""));
        final SessionState idle = SessionState.closed(SessionSpec.fresh("idle", "/w/idle")).withStatus(Status.OPEN, "")
                .withTasks(List.of(new BackgroundTask("bg7", "local_agent", "Watch CI", 0L)), 0L);
        final AppState s = AppState.initial(List.of(new PanelState(P, "work", "", "")), List.of(), List.of(idle));
        final List<Intent> intents = new CopyOnWriteArrayList<>();
        final Workspace[] w = new Workspace[1];
        final JRootPane root = new JRootPane();
        final JList<String> rows = new JList<>(new String[]{"idle"});
        SwingUtilities.invokeAndWait(() -> {
            w[0] = new Workspace(intents::add, new Texts(), new SchemaRegistry());
            w[0].topics(new Asks(), transcripts::of);
            w[0].render(s);
            root.getContentPane().setLayout(new BorderLayout());
            root.getContentPane().add(rows, BorderLayout.WEST);
            root.getContentPane().add(w[0].component(), BorderLayout.CENTER);
            layOut(root, 900, 600);
            w[0].rowCards().show("idle", rows, rows.getCellBounds(0, 0), 200);
        });
        final TabCard card = w[0].card();
        final List<JLabel> links = card.agentLinks();
        assertThat(links).extracting(JLabel::getText).as("each own sub-agent, its state and the tool calls inside it at any depth")
                .containsExactly("✓ Review the diff · done · 3 tools", "▸ Run the tests · running",
                        "▸ Watch CI · running in background", "✗ Broken · failed");
        final JLabel first = links.getFirst();
        SwingUtilities.invokeAndWait(() -> {
            first.dispatchEvent(new MouseEvent(first, MouseEvent.MOUSE_CLICKED, 0, 0, 2, 2, 1, false, MouseEvent.BUTTON1));
        });
        assertThat(intents).as("a single click opens nothing").noneMatch(i -> i instanceof Intent.OpenSubagent);
        SwingUtilities.invokeAndWait(() -> {
            first.dispatchEvent(new MouseEvent(first, MouseEvent.MOUSE_CLICKED, 0, 0, 2, 2, 2, false, MouseEvent.BUTTON1));
        });
        assertThat(intents).contains(new Intent.OpenSubagent("idle", "a1"));

        // the background one ended: done; a connection's card has no outline
        SwingUtilities.invokeAndWait(() -> {
            w[0].render(s.withSessions(List.of(idle.withTasks(List.of(), 0L))));
            w[0].rowCards().show("idle", rows, rows.getCellBounds(0, 0), 200);
        });
        assertThat(card.agentLinks()).extracting(JLabel::getText).contains("✓ Watch CI · done");
    }

    /// Another instance's card (record 124) is its owner's: the window's renders, which keep its own cards current and
    /// close those whose panel or session is gone, neither close it nor read it as a panel's; its owner's update shows,
    /// and the last exchange shows under its heading.
    @Test
    void anotherInstancesCardOutlivesTheRendersAndTakesItsOwnersUpdates() throws Exception {
        final AppState s = AppState.initial(List.of(new PanelState(P, "work", "", "")), List.of(), List.of());
        final Workspace[] w = new Workspace[1];
        final JRootPane root = new JRootPane();
        final JList<String> rows = new JList<>(new String[]{"Comando Expert"});
        SwingUtilities.invokeAndWait(() -> {
            w[0] = new Workspace(i -> { }, new Texts(), new SchemaRegistry());
            w[0].render(s);
            root.getContentPane().setLayout(new BorderLayout());
            root.getContentPane().add(rows, BorderLayout.WEST);
            root.getContentPane().add(w[0].component(), BorderLayout.CENTER);
            layOut(root, 900, 600);
            w[0].rowCards().showFacts("instance:comando-expert", "Comando Expert", List.of(Map.entry("State", "idle")), "", "", rows,
                    rows.getCellBounds(0, 0), 200);
            w[0].render(s.withSessions(List.of(SessionState.closed(SessionSpec.fresh("other", "/w")))));
            w[0].render(AppState.initial(List.of(new PanelState(P, "work", "", "")), List.of(), List.of()));
        });
        final TabCard card = w[0].card();
        final SessionsSidebar.Cards cards = w[0].rowCards();
        assertThat(cards.showing("instance:comando-expert")).as("a render keeps it").isTrue();
        assertThat(List.of(card.statusText(), card.detailsText())).containsExactly("Comando Expert", "State: idle");

        SwingUtilities.invokeAndWait(() -> cards.updateFacts("instance:comando-expert/work", "work", List.of(Map.entry("State", "busy")), "Last exchange",
                "Asked: x"));
        assertThat(card.detailsText()).as("another row's update is not this card's").isEqualTo("State: idle");
        SwingUtilities.invokeAndWait(() -> cards.updateFacts("instance:comando-expert", "Comando Expert", List.of(Map.entry("State", "busy: 1 of 1")),
                "Last exchange", "Asked: x"));
        assertThat(List.of(card.detailsText(), card.topicText(), card.headingLabel().isVisible())).containsExactly("State: busy: 1 of 1", "Asked: x", true);
    }

    /// A session's row shows its panel's card, with each session fact once and no new read, placed beside the rows when
    /// there is room and under the row otherwise; a session without a panel gets its own card read urgently from the
    /// end of its conversation; the card closes on ✕, on leaving, after the grace, and when the session is removed.
    @Test
    void aSessionRowShowsItsPanelsCardWithTheSessionsDetailsAndAHeadlessSessionACardOfItsOwn() throws Exception {
        final TranscriptRegistry transcripts = new TranscriptRegistry();
        transcripts.of("idle").append(new Block.UserPrompt("tidy the imports", "x", ""));
        transcripts.of("idle").append(new Block.AssistantText("m1", 0, "Imports tidied.", false, ""));
        final Asks asks = new Asks();
        final SessionState work = SessionState.closed(SessionSpec.fresh("s", "/w/s").resumable("conv-1")).withStatus(Status.OPEN, "").withBranch("main");
        final SessionState idle = SessionState.closed(SessionSpec.fresh("idle", "/w/idle"));
        final PanelState sql = new PanelState(new PanelId("q"), "query", "", "");
        final AppState s = AppState.initial(List.of(new PanelState(P, "work", "", "s"), sql), List.of(), List.of(work, idle));
        final Workspace[] w = new Workspace[1];
        final JRootPane root = new JRootPane();
        final JList<String> rows = new JList<>(new String[]{"s", "idle"});
        SwingUtilities.invokeAndWait(() -> {
            w[0] = new Workspace(i -> { }, new Texts(), new SchemaRegistry(), () -> 100_000L);
            w[0].topics(asks, transcripts::of);
            w[0].render(s);
            root.getContentPane().setLayout(new BorderLayout());
            root.getContentPane().add(rows, BorderLayout.WEST);
            root.getContentPane().add(w[0].component(), BorderLayout.CENTER);
            layOut(root, 900, 600);
            w[0].showCard(0);
        });
        final TabCard card = w[0].card();
        final String details = card.detailsText();
        assertThat(card.statusText()).as("the panel's title").isEqualTo("work");
        assertThat(details).as("a session panel's card has the session's facts, each once")
                .startsWith("State: running\nModel: default · effort default\nFolder: /w/s · main\nConversation: conv-1\nSession: s")
                .doesNotContain("Status:", "Path:", "unknown");
        asks.asks.getFirst().done().accept(new Topic("Working on the tab card", 1L, ""));
        SwingUtilities.invokeAndWait(() -> { });
        final String status = card.statusText();

        // the sidebar hears a row's card close, and where the pointer left it for; a tab's card is not a row's
        final List<String> closed = new CopyOnWriteArrayList<>();
        SwingUtilities.invokeAndWait(() -> w[0].rowCards().onClose(at -> closed.add(at == null ? "closed" : "left for a point")));
        SwingUtilities.invokeAndWait(() -> card.closeButton().doClick());
        assertThat(closed).as("a tab's card").isEmpty();

        // the session's row: the same card, its panel's, with no new read
        SwingUtilities.invokeAndWait(() -> w[0].rowCards().show("s", rows, rows.getCellBounds(0, 0)));
        assertThat(w[0].cardShown()).isEqualTo(P);
        assertThat(card.component().getParent()).isSameAs(root.getLayeredPane());
        assertThat(List.of(card.statusText(), card.detailsText(), card.topicText())).containsExactly(status, details, "Working on the tab card");
        assertThat(asks.asks).hasSize(1);
        final int below = SwingUtilities.convertRectangle(rows, rows.getCellBounds(0, 0), root.getLayeredPane()).y + rows.getCellBounds(0, 0).height;
        assertThat(card.component().getY()).as("under the row").isGreaterThanOrEqualTo(below);
        SwingUtilities.invokeAndWait(() -> w[0].tick());
        assertThat(card.component().getY()).as("a tick leaves it under its row, not its tab").isGreaterThanOrEqualTo(below);

        // beside the rows when the window has room: its left edge where the sidebar says, its top at the row's
        final int beside = rows.getWidth() - 4;
        SwingUtilities.invokeAndWait(() -> w[0].rowCards().show("s", rows, rows.getCellBounds(0, 0), beside));
        final Point besideAt = SwingUtilities.convertPoint(rows, new Point(beside, rows.getCellBounds(0, 0).y), root.getLayeredPane());
        assertThat(card.component().getX()).as("beside the rows, over their last pixels").isEqualTo(besideAt.x);
        assertThat(card.component().getY()).as("its top at the row's, within the window's margin").isEqualTo(Math.max(8, besideAt.y));
        // no room beside them: under the row, as before
        SwingUtilities.invokeAndWait(() -> w[0].rowCards().show("s", rows, rows.getCellBounds(0, 0), 880));
        assertThat(card.component().getY()).as("under the row").isGreaterThanOrEqualTo(below);
        assertThat(card.component().getX()).isLessThan(880);

        // a headless session: a card of its own, read from the end of its conversation
        SwingUtilities.invokeAndWait(() -> w[0].rowCards().show("idle", rows, rows.getCellBounds(1, 1)));
        assertThat(w[0].cardShown()).as("no panel's").isNull();
        assertThat(card.statusText()).isEqualTo("idle");
        assertThat(card.detailsText()).startsWith("State: stopped · no panel\n").contains("Folder: /w/idle").doesNotContain("Session:");
        assertThat(card.topicText()).isEqualTo("Reading the summary…");
        assertThat(asks.asks).hasSize(2);
        assertThat(asks.asks.get(1).key()).isEqualTo(Workspace.SESSION_KEY + "idle");
        assertThat(asks.asks.get(1).urgent()).isTrue();
        assertThat(asks.asks.get(1).source()).startsWith("A coding session named \"idle\", working in /w/idle.").contains("Assistant: Imports tidied.");
        asks.asks.get(1).done().accept(new Topic("Tidying the imports", 1L, ""));
        SwingUtilities.invokeAndWait(() -> { });
        assertThat(card.topicText()).isEqualTo("Tidying the imports");
        SwingUtilities.invokeAndWait(() -> card.refreshButton().doClick());
        assertThat(asks.asks).as("↻ reads it again").hasSize(3);

        // its ✕ closes it; the pointer leaving it says where for
        SwingUtilities.invokeAndWait(() -> card.closeButton().doClick());
        assertThat(closed).containsExactly("closed");
        SwingUtilities.invokeAndWait(() -> w[0].rowCards().show("idle", rows, rows.getCellBounds(1, 1)));
        final JComponent c = card.component();
        SwingUtilities.invokeAndWait(() -> {
            c.dispatchEvent(new MouseEvent(c, MouseEvent.MOUSE_ENTERED, 0, 0, 5, 5, 0, false));
            c.dispatchEvent(new MouseEvent(c, MouseEvent.MOUSE_EXITED, 0, 0, -5, 5, 0, false));
        });
        assertThat(card.shown()).isNull();
        assertThat(closed).containsExactly("closed", "left for a point");

        // the pointer leaves the row and does not reach the card: it closes after the grace
        SwingUtilities.invokeAndWait(() -> w[0].rowCards().show("idle", rows, rows.getCellBounds(1, 1)));
        SwingUtilities.invokeAndWait(() -> w[0].rowCards().left());
        Awaitility.await("the grace over").atMost(Duration.ofSeconds(3)).until(() -> card.shown() == null);
        assertThat(closed).last().isEqualTo("closed");

        // a removed session's card closes
        SwingUtilities.invokeAndWait(() -> w[0].rowCards().show("idle", rows, rows.getCellBounds(1, 1)));
        SwingUtilities.invokeAndWait(() -> w[0].render(s.withSessions(List.of(work))));
        assertThat(card.shown()).isNull();

        // a panel without a session: no session's facts
        SwingUtilities.invokeAndWait(() -> w[0].showCard(1));
        assertThat(card.statusText()).isEqualTo("query");
        assertThat(card.detailsText()).doesNotContain("Model", "Folder", "State");
    }

    /// A connection's card lists its state, database, user, TLS and panels, has no summary, reads nothing, is narrower
    /// than a summary card, follows the connection's state and closes when the connection is removed.
    @Test
    void aConnectionsRowShowsACardOfItsFactsWithoutASummaryThatFollowsItsState() throws Exception {
        final Asks asks = new Asks();
        final ConnectionState local = ConnectionState.closed(ConnectionSpec.questDbDefaults("local"));
        final AppState s = AppState.initial(List.of(new PanelState(P, "work", "local", ""), new PanelState(new PanelId("q"), "query", "", "")),
                List.of(local), List.of());
        final Workspace[] w = new Workspace[1];
        final JRootPane root = new JRootPane();
        final JList<String> rows = new JList<>(new String[]{"local"});
        SwingUtilities.invokeAndWait(() -> {
            w[0] = new Workspace(i -> { }, new Texts(), new SchemaRegistry(), () -> 100_000L);
            w[0].topics(asks, name -> null);
            w[0].render(s);
            root.getContentPane().setLayout(new BorderLayout());
            root.getContentPane().add(rows, BorderLayout.WEST);
            root.getContentPane().add(w[0].component(), BorderLayout.CENTER);
            layOut(root, 900, 600);
            w[0].rowCards().showConnection("local", rows, rows.getCellBounds(0, 0));
        });
        final TabCard card = w[0].card();
        assertThat(card.shown()).isEqualTo(Workspace.CONNECTION_KEY + "local");
        assertThat(w[0].cardShown()).as("no panel's").isNull();
        assertThat(card.statusText()).isEqualTo("local");
        assertThat(card.detailsText()).isEqualTo("State: disconnected\nDatabase: qdb on localhost:8812\nUser: admin\nTLS: prefer\nPanels: work");
        assertThat(card.summarised()).as("no summary").isFalse();
        assertThat(List.of(card.headingLabel().isVisible(), card.topicArea().isVisible(), card.refreshButton().getParent().isVisible()))
                .containsOnly(false);
        assertThat(asks.asks).as("nothing is read").isEmpty();
        assertThat(card.component().getWidth()).as("as wide as its facts, not a summary").isLessThan(TabCard.SUMMARY_WIDTH);

        SwingUtilities.invokeAndWait(() -> w[0].render(s.withConnections(List.of(local.with(Status.OPEN, "")))));
        assertThat(card.detailsText()).as("it follows the connection").startsWith("State: connected\n");

        // a session's card after it has its summary back
        SwingUtilities.invokeAndWait(() -> w[0].showCard(0));
        assertThat(card.summarised()).isTrue();
        assertThat(card.headingLabel().isVisible()).isTrue();

        // a removed connection's card closes
        SwingUtilities.invokeAndWait(() -> w[0].rowCards().showConnection("local", rows, rows.getCellBounds(0, 0)));
        SwingUtilities.invokeAndWait(() -> w[0].render(s.withConnections(List.of())));
        assertThat(card.shown()).isNull();
    }

    /// A spec change in the same conversation keeps the card's topic; a new conversation id drops it and the next show
    /// reads it again.
    @Test
    void aNewConversationDropsTheTopicOfItsSessionsPanelsAndTheNextShowReadsItAgain() throws Exception {
        final TranscriptRegistry transcripts = new TranscriptRegistry();
        final Asks asks = new Asks();
        final SessionState open = SessionState.closed(SessionSpec.fresh("s", "/tmp").resumable("conv-1")).withStatus(Status.OPEN, "");
        final AppState s = AppState.initial(List.of(new PanelState(P, "work", "", "s")), List.of(), List.of(open));
        final Workspace[] w = new Workspace[1];
        final JRootPane root = new JRootPane();
        SwingUtilities.invokeAndWait(() -> {
            w[0] = new Workspace(i -> { }, new Texts(), new SchemaRegistry(), () -> 100_000L);
            w[0].topics(asks, transcripts::of);
            w[0].render(s);
            root.getContentPane().add(w[0].component());
            layOut(root, 900, 600);
            w[0].showCard(0);
        });
        asks.asks.getFirst().done().accept(new Topic("Reflecting on the session", 1L, ""));
        SwingUtilities.invokeAndWait(() -> { });
        final TabCard card = w[0].card();
        assertThat(card.topicText()).isEqualTo("Reflecting on the session");

        SwingUtilities.invokeAndWait(() -> w[0].render(s.withSession(open.withSpec(open.spec().withModel("opus")))));
        assertThat(card.topicText()).as("the same conversation keeps its topic").isEqualTo("Reflecting on the session");

        // a checkpoint's /clear: the session reports a new conversation
        SwingUtilities.invokeAndWait(() -> w[0].render(s.withSession(open.withSpec(open.spec().resumable("conv-2")))));
        assertThat(card.topicText()).as("the old topic is gone with its conversation").isEqualTo("Nothing to summarise yet");
        SwingUtilities.invokeAndWait(() -> w[0].showCard(0));
        assertThat(asks.asks).as("read again at the next show, from the same text").hasSize(2);
    }

    /// A tab's card sits in the popup layer instead of a tooltip and reads its summary from the panel's text, urgently;
    /// it is read again only when the session answered, the text changed or ↻ is pressed, never by a timer; a failed
    /// read says why, a long summary grows the card up to [TabCard#MAX_WIDTH], and the card closes when left, after the
    /// grace, on a press or on ✕.
    @Test
    void aTabShowsACardWithItsTopicReadOnceAndAgainOnlyOnAskOrWhenThePanelsTextChangedAndItClosesWhenLeft() throws Exception {
        final TranscriptRegistry transcripts = new TranscriptRegistry();
        final Transcript t = transcripts.of("s");
        t.append(new Block.UserPrompt("make the tab card sticky", "p", ""));
        final Asks asks = new Asks();
        final long[] now = {100_000L};
        final SessionState open = SessionState.closed(SessionSpec.fresh("s", "/tmp")).withStatus(Status.OPEN, "");
        final AppState s = AppState.initial(List.of(new PanelState(P, "work", "", "s")), List.of(), List.of(open));
        final Workspace[] w = new Workspace[1];
        final JRootPane root = new JRootPane();
        SwingUtilities.invokeAndWait(() -> {
            w[0] = new Workspace(i -> { }, new Texts(), new SchemaRegistry(), () -> now[0]);
            w[0].topics(asks, transcripts::of);
            w[0].render(s);
            root.getContentPane().add(w[0].component());
            layOut(root, 900, 600);
            w[0].showCard(0);
        });
        final TabCard card = w[0].card();
        assertThat(w[0].cardShown()).isEqualTo(P);
        assertThat(card.component().getParent()).isSameAs(root.getLayeredPane());
        assertThat(JLayeredPane.getLayer(card.component())).isEqualTo(JLayeredPane.POPUP_LAYER.intValue());
        assertThat(((JTabbedPane) w[0].component()).getToolTipTextAt(0)).as("the card, not a tooltip").isNull();
        assertThat(card.statusText()).isEqualTo("work");
        assertThat(card.detailsText()).contains("State: running", "Session: s");
        assertThat(card.topicText()).isEqualTo("Reading the summary…");
        assertThat(card.refreshButton().isEnabled()).isFalse();
        assertThat(asks.asks).singleElement().satisfies(a -> {
            assertThat(a.key()).isEqualTo("p");
            assertThat(a.urgent()).as("the card shown is read first").isTrue();
            assertThat(a.source()).as("the panel's own text, not the conversation").contains("which the summary is about:\nSELECT 1;")
                    .doesNotContain("make the tab card sticky");
        });

        asks.asks.getFirst().done().accept(new Topic("Making the tab card sticky", 1L, ""));
        SwingUtilities.invokeAndWait(() -> { });
        assertThat(card.topicText()).isEqualTo("Making the tab card sticky");
        assertThat(card.topicArea().getForeground()).as("the summary reads as a report's text").isEqualTo(Theme.TRANSCRIPT_TEXT);
        assertThat(card.headingLabel().getText()).isEqualTo("Summary");
        assertThat(card.headingLabel().getForeground()).as("its heading is in the tint").isEqualTo(Theme.tint().body);
        assertThat(card.headingLabel().getFont().getFamily()).as("in the headings' serif").isEqualTo(Theme.serif().getFamily());
        try {
            SwingUtilities.invokeAndWait(() -> Theme.tint(Tint.CHAMPAGNE));
            assertThat(card.headingLabel().getForeground()).as("another tint chosen, the card shown takes it").isEqualTo(Tint.CHAMPAGNE.body);
        } finally {
            SwingUtilities.invokeAndWait(() -> Theme.tint(Tint.TREASURE));
        }
        assertThat(card.refreshButton().isEnabled()).isTrue();
        // the buttons sit at the bottom left, the refresh first, the close beside it; a card never shown lays out by
        // hand
        SwingUtilities.invokeAndWait(() -> layOut(card.component()));
        final Rectangle cardBounds = card.component().getBounds();
        final Point refreshAt = SwingUtilities.convertPoint(card.refreshButton().getParent(), card.refreshButton().getLocation(), card.component());
        final Point closeAt = SwingUtilities.convertPoint(card.closeButton().getParent(), card.closeButton().getLocation(), card.component());
        final Point summaryAt = SwingUtilities.convertPoint(card.topicArea().getParent(), card.topicArea().getLocation(), card.component());
        assertThat(refreshAt.y).as("below the summary").isGreaterThan(summaryAt.y);
        assertThat(refreshAt.x).as("at the left").isLessThan(cardBounds.width / 4);
        assertThat(closeAt.x).as("next to the refresh").isGreaterThan(refreshAt.x).isLessThan(refreshAt.x + 3 * card.refreshButton().getWidth());
        assertThat(closeAt.y).isEqualTo(refreshAt.y);

        // no timer reads it again, however long it shows and whatever the session does
        now[0] += 3_600_000;
        t.append(new Block.AssistantText("m1", 0, "The card sits in the popup layer.", false, ""));
        SwingUtilities.invokeAndWait(() -> w[0].tick());
        assertThat(asks.asks).hasSize(1);
        // shown again after the session answered: read again, the answer with the panel's last paragraph
        SwingUtilities.invokeAndWait(() -> w[0].showCard(0));
        assertThat(asks.asks).hasSize(2);
        assertThat(asks.asks.get(1).source()).contains("which the summary is about:\nSELECT 1;\n\nThe session's last response, to read with that command:\n"
                + "The card sits in the popup layer.");
        assertThat(card.topicText()).as("the last summary stays while it is read again").isEqualTo("Making the tab card sticky");
        asks.asks.get(1).done().accept(new Topic("Making the tab card sticky", 1L, ""));
        // shown again with the panel's text and the answer as they were: nothing is asked
        SwingUtilities.invokeAndWait(() -> w[0].showCard(0));
        assertThat(asks.asks).as("its summary stands").hasSize(2);
        // the panel's text changed: shown again, it is read from the text, about its last paragraph
        SwingUtilities.invokeAndWait(() -> w[0].panel(P).editor().area().setText("old notes on the card\n\nnow make the card close when left"));
        SwingUtilities.invokeAndWait(() -> w[0].showCard(0));
        assertThat(asks.asks).hasSize(3);
        assertThat(asks.asks.get(2).source()).contains("which the summary is about:\nnow make the card close when left")
                .contains("for context, oldest first:\nold notes on the card");

        // the button reads it again, the same source too, ahead of the others
        asks.asks.get(2).done().accept(new Topic("Placing the card", 2L, ""));
        SwingUtilities.invokeAndWait(() -> card.refreshButton().doClick());
        assertThat(asks.asks).hasSize(4);
        assertThat(asks.asks.get(3).urgent()).isTrue();
        assertThat(asks.asks.get(3).source()).isEqualTo(asks.asks.get(2).source());

        // a failed read says why
        asks.asks.get(3).done().accept(Topic.failed("Not logged in", 3L));
        SwingUtilities.invokeAndWait(() -> { });
        assertThat(card.topicText()).isEqualTo("No summary: Not logged in");

        // a long summary wraps to a few lines, and the card grows to hold them
        final int oneLine = card.component().getHeight();
        asks.asks.get(3).done().accept(new Topic("In comando, the tab card: " + "a summary of several clauses, ".repeat(8), 4L, ""));
        SwingUtilities.invokeAndWait(() -> { });
        assertThat(card.component().getHeight()).as("taller for the wrapped summary").isGreaterThan(oneLine);
        assertThat(card.component().getWidth()).isLessThanOrEqualTo(TabCard.MAX_WIDTH);

        // the pointer leaving the tabs and going on into the card keeps it; leaving the card closes it
        SwingUtilities.invokeAndWait(() -> {
            for (final MouseListener l : w[0].component().getMouseListeners()) {
                l.mouseExited(mouse(w[0].component(), MouseEvent.MOUSE_EXITED, 1, 1));
            }
            card.component().dispatchEvent(mouse(card.component(), MouseEvent.MOUSE_ENTERED, 5, 5));
            card.closeIfLeft();
        });
        assertThat(w[0].cardShown()).as("the pointer reached it").isEqualTo(P);
        SwingUtilities.invokeAndWait(() -> card.topicArea().dispatchEvent(mouse(card.topicArea(), MouseEvent.MOUSE_EXITED, 3, 3)));
        assertThat(w[0].cardShown()).as("from a part onto the card's own ground is still on the card").isEqualTo(P);
        SwingUtilities.invokeAndWait(() -> card.component().dispatchEvent(mouse(card.component(), MouseEvent.MOUSE_EXITED, -4, 5)));
        assertThat(w[0].cardShown()).as("off the card, it closes").isNull();
        // leaving the tab without reaching the card closes it once the grace is over
        SwingUtilities.invokeAndWait(() -> {
            w[0].showCard(0);
            for (final MouseListener l : w[0].component().getMouseListeners()) {
                l.mouseExited(mouse(w[0].component(), MouseEvent.MOUSE_EXITED, 1, 1));
            }
        });
        assertThat(w[0].cardShown()).as("within the grace").isEqualTo(P);
        Awaitility.await("the grace over").atMost(Duration.ofSeconds(3)).until(() -> w[0].cardShown() == null);

        // a click on it closes it
        SwingUtilities.invokeAndWait(() -> w[0].showCard(0));
        SwingUtilities.invokeAndWait(() -> card.component().dispatchEvent(mouse(card.component(), MouseEvent.MOUSE_PRESSED, 5, 5)));
        assertThat(w[0].cardShown()).isNull();
        assertThat(card.component().getParent()).isNull();

        // the close button closes it too
        SwingUtilities.invokeAndWait(() -> w[0].showCard(0));
        assertThat(w[0].cardShown()).isEqualTo(P);
        SwingUtilities.invokeAndWait(() -> card.closeButton().doClick());
        assertThat(w[0].cardShown()).isNull();
    }

    /// The card links at most [Workspace#MAX_TICKETS] ticket ids of known teams from the panel's text: the last
    /// paragraph's in the order written, then earlier ones from the newest back, so `ENG-7` is cut and `UTF-8` is no
    /// team; a team prefix is learnt from a ticket URL in the conversation; a double click opens the ticket and keeps
    /// the card.
    @Test
    void aCardLinksTheLinearTicketsThePanelNamesNewestFirstLearningTheirAddressFromLinearsLinks() throws Exception {
        final TranscriptRegistry transcripts = new TranscriptRegistry();
        transcripts.of("s").append(new Block.AssistantText("m", 0, "Opened https://linear.app/acme/issue/OPS-3/x for it.", false, ""));
        final SessionState open = SessionState.closed(SessionSpec.fresh("s", "/tmp")).withStatus(Status.OPEN, "");
        final AppState s = AppState.initial(List.of(new PanelState(P, "work", "", "s")), List.of(), List.of(open));
        final Workspace[] w = new Workspace[1];
        final List<LinearTickets.Site> kept = new CopyOnWriteArrayList<>();
        final List<String> opened = new CopyOnWriteArrayList<>();
        final JRootPane root = new JRootPane();
        SwingUtilities.invokeAndWait(() -> {
            w[0] = new Workspace(i -> { }, new Texts(), new SchemaRegistry(), () -> 0L);
            w[0].topics(new Asks(), transcripts::of);
            w[0].linear(new LinearTickets.Site("acme", Set.of("ENG")), kept::add);
            w[0].card().browseWith(opened::add);
            w[0].render(s);
            root.getContentPane().add(w[0].component());
            layOut(root, 900, 600);
            w[0].panel(P).editor().area().setText("ENG-7 and OPS-3 and UTF-8 earlier\n\nnow fix ENG-1125 then ENG-8");
            w[0].showCard(0);
        });
        final TabCard card = w[0].card();
        assertThat(card.ticketLinks()).extracting(JLabel::getText).containsExactly("ENG-1125", "ENG-8", "OPS-3");
        assertThat(kept).as("OPS learnt from the session's conversation, and kept")
                .containsExactly(new LinearTickets.Site("acme", Set.of("ENG", "OPS")));
        final JLabel first = card.ticketLinks().getFirst();
        SwingUtilities.invokeAndWait(() -> first.dispatchEvent(new MouseEvent(first, MouseEvent.MOUSE_CLICKED, 0, 0, 2, 2, 1, false, MouseEvent.BUTTON1)));
        assertThat(opened).as("one click opens nothing").isEmpty();
        SwingUtilities.invokeAndWait(() -> first.dispatchEvent(new MouseEvent(first, MouseEvent.MOUSE_CLICKED, 0, 0, 2, 2, 2, false, MouseEvent.BUTTON1)));
        assertThat(opened).containsExactly("https://linear.app/acme/issue/ENG-1125");
        assertThat(w[0].cardShown()).as("the card stays for the next link").isEqualTo(P);

        SwingUtilities.invokeAndWait(() -> {
            w[0].panel(P).editor().area().setText("no tickets here, UTF-8 only");
            w[0].showCard(0);
        });
        assertThat(card.ticketLinks()).isEmpty();
    }

    /// A press on a tab, a click or the menu's, cancels the pending hover card and closes a shown one, and the tab
    /// shows none until the pointer leaves it; the close button's tooltip shows no card, a card follows its tab when an
    /// earlier tab closes, and a summary that looks like HTML is shown as plain text.
    @Test
    void aPressOrTheCloseButtonShowsNoCardAndACardFollowsItsTabAndShowsHtmlAsText() throws Exception {
        final PanelId a = new PanelId("a");
        final PanelId b = new PanelId("b");
        final Asks asks = new Asks();
        final Workspace[] w = new Workspace[1];
        final JRootPane root = new JRootPane();
        SwingUtilities.invokeAndWait(() -> {
            w[0] = new Workspace(i -> { }, new Texts(), new SchemaRegistry());
            w[0].topics(asks, name -> null);
            w[0].render(AppState.initial(List.of(new PanelState(a, "first panel", "", ""), new PanelState(b, "second", "", "")), List.of(), List.of()));
            root.getContentPane().add(w[0].component());
            layOut(root, 900, 600);
        });
        final JTabbedPane tabs = (JTabbedPane) w[0].component();
        final Rectangle first = tabs.getUI().getTabBounds(tabs, 0);
        final Rectangle second = tabs.getUI().getTabBounds(tabs, 1);

        // a press selects the tab: the pointer resting there afterwards shows no card
        SwingUtilities.invokeAndWait(() -> {
            mouseMoved(tabs, first.x + 4, first.y + 4);
            assertThat(w[0].hoverWaiting()).isTrue();
            for (final MouseListener l : tabs.getMouseListeners()) {
                l.mousePressed(mouse(tabs, MouseEvent.MOUSE_PRESSED, first.x + 4, first.y + 4));
            }
        });
        assertThat(w[0].hoverWaiting()).isFalse();
        SwingUtilities.invokeAndWait(() -> mouseMoved(tabs, first.x + 6, first.y + 4));
        assertThat(w[0].hoverWaiting()).as("moving on the same tab does not start it again").isFalse();
        SwingUtilities.invokeAndWait(() -> w[0].showCard(0));
        assertThat(w[0].cardShown()).as("the pressed tab's wait, firing late, shows nothing").isNull();

        // a card shown when the menu's press comes closes
        SwingUtilities.invokeAndWait(() -> {
            mouseMoved(tabs, second.x + 4, second.y + 4);
            w[0].showCard(1);
        });
        assertThat(w[0].cardShown()).isEqualTo(b);
        SwingUtilities.invokeAndWait(() -> {
            for (final MouseListener l : tabs.getMouseListeners()) {
                l.mousePressed(mouse(tabs, MouseEvent.MOUSE_PRESSED, second.x + 4, second.y + 4));
            }
        });
        assertThat(w[0].cardShown()).as("a press closes the tab's card").isNull();

        // off the tabs and back: resting shows the card again
        SwingUtilities.invokeAndWait(() -> {
            mouseMoved(tabs, first.x + 4, first.y + first.height + 40);
            mouseMoved(tabs, first.x + 4, first.y + 4);
        });
        assertThat(w[0].hoverWaiting()).isTrue();

        // over the close button the look puts its tooltip on the tab: no card, and the next move waits again
        SwingUtilities.invokeAndWait(() -> {
            tabs.setToolTipTextAt(0, "Close the panel");
            w[0].showCard(0);
            tabs.setToolTipTextAt(0, null);
            mouseMoved(tabs, first.x + 8, first.y + 4);
        });
        assertThat(w[0].cardShown()).isNull();
        assertThat(w[0].hoverWaiting()).isTrue();

        // the second tab's card moves left with it when the first tab closes
        SwingUtilities.invokeAndWait(() -> w[0].showCard(1));
        final int before = w[0].card().component().getX();
        SwingUtilities.invokeAndWait(() -> {
            w[0].render(AppState.initial(List.of(new PanelState(b, "second", "", "")), List.of(), List.of()));
            layOut(root, 900, 600);
            w[0].tick();
        });
        assertThat(w[0].cardShown()).isEqualTo(b);
        assertThat(w[0].card().component().getX()).isLessThan(before).isLessThanOrEqualTo(Math.max(8, first.x));
        assertThat(second.x).isGreaterThan(first.x);

        // the summary's line is text, never HTML
        asks.asks.getLast().done().accept(new Topic("<html><img src='http://example.com/x.png'>", 1L, ""));
        SwingUtilities.invokeAndWait(() -> { });
        // a text area, which never renders HTML: the summary shows as typed
        assertThat(w[0].card().topicText()).isEqualTo("<html><img src='http://example.com/x.png'>");
        assertThat(w[0].card().topicArea().getDocument()).isInstanceOf(PlainDocument.class);
    }

    /// A URL in the summary opens at a double click and keeps the card; a press elsewhere on the summary closes it.
    @Test
    void aLinkInTheCardsSummaryOpensAtADoubleClickAndKeepsTheCard() throws Exception {
        final PanelId a = new PanelId("a");
        final Asks asks = new Asks();
        final Workspace[] w = new Workspace[1];
        final List<String> opened = new CopyOnWriteArrayList<>();
        final JRootPane root = new JRootPane();
        SwingUtilities.invokeAndWait(() -> {
            w[0] = new Workspace(i -> { }, new Texts(), new SchemaRegistry());
            w[0].topics(asks, name -> null);
            w[0].card().browseWith(opened::add);
            w[0].render(AppState.initial(List.of(new PanelState(a, "first panel", "", "")), List.of(), List.of()));
            root.getContentPane().add(w[0].component());
            layOut(root, 900, 600);
            w[0].showCard(0);
        });
        asks.asks.getLast().done().accept(new Topic("Reviewing https://example.org/pr/7 against the plan.", 1L, ""));
        SwingUtilities.invokeAndWait(() -> layOut(w[0].card().component()));
        final TabCard card = w[0].card();
        assertThat(card.topicLinks()).extracting(TextLinks.Ref::href).containsExactly("https://example.org/pr/7");
        final JTextArea topic = card.topicArea();
        final int at = card.topicLinks().getFirst().start() + 3;
        final Point[] p = new Point[1];
        SwingUtilities.invokeAndWait(() -> {
            try {
                final Rectangle2D r = topic.modelToView2D(at);
                p[0] = new Point((int) r.getCenterX(), (int) r.getCenterY());
            } catch (final BadLocationException e) {
                throw new IllegalStateException(e);
            }
        });
        SwingUtilities.invokeAndWait(() -> {
            for (int clicks = 1; clicks <= 2; clicks++) {
                topic.dispatchEvent(new MouseEvent(topic, MouseEvent.MOUSE_PRESSED, 0, 0, p[0].x, p[0].y, clicks, false, MouseEvent.BUTTON1));
                topic.dispatchEvent(new MouseEvent(topic, MouseEvent.MOUSE_RELEASED, 0, 0, p[0].x, p[0].y, clicks, false, MouseEvent.BUTTON1));
                topic.dispatchEvent(new MouseEvent(topic, MouseEvent.MOUSE_CLICKED, 0, 0, p[0].x, p[0].y, clicks, false, MouseEvent.BUTTON1));
            }
        });
        assertThat(opened).containsExactly("https://example.org/pr/7");
        assertThat(w[0].cardShown()).as("a press on a link keeps the card").isEqualTo(a);
        SwingUtilities.invokeAndWait(() -> topic.dispatchEvent(new MouseEvent(topic, MouseEvent.MOUSE_PRESSED, 0, 0, 1, 1, 1, false, MouseEvent.BUTTON1)));
        assertThat(w[0].cardShown()).as("a press elsewhere on the summary closes it").isNull();
    }

    private static void mouseMoved(final Component c, final int x, final int y) {
        final MouseEvent e = mouse(c, MouseEvent.MOUSE_MOVED, x, y);
        for (final MouseMotionListener l : c.getMouseMotionListeners()) {
            l.mouseMoved(e);
        }
    }

    /// A tab without a session asks for a topic from the start of its text; showing another tab's card replaces the
    /// first, and closing a tab closes its card, also when a late summary arrives.
    @Test
    void aTabWithoutASessionTakesItsTopicFromItsTextAndAClosedTabTakesItsCardWithIt() throws Exception {
        final PanelId other = new PanelId("o");
        final Asks asks = new Asks();
        final AppState s = AppState.initial(List.of(new PanelState(P, "query", "lab", ""), new PanelState(other, "notes", "", "")), List.of(), List.of());
        final Workspace[] w = new Workspace[1];
        final JRootPane root = new JRootPane();
        SwingUtilities.invokeAndWait(() -> {
            w[0] = new Workspace(i -> { }, new Texts(), new SchemaRegistry());
            w[0].topics(asks, name -> null);
            w[0].render(s);
            root.getContentPane().add(w[0].component());
            layOut(root, 900, 600);
            w[0].showCard(0);
        });
        assertThat(asks.asks).singleElement().satisfies(a -> assertThat(a.source())
                .isEqualTo("A document in a panel titled \"query\". Its text begins:\nSELECT 1;"));

        // a second card replaces the first; the other tab's topic is read on its own
        SwingUtilities.invokeAndWait(() -> w[0].showCard(1));
        assertThat(w[0].cardShown()).isEqualTo(other);
        assertThat(asks.asks).hasSize(2);
        SwingUtilities.invokeAndWait(() -> w[0].render(AppState.initial(List.of(new PanelState(P, "query", "lab", "")), List.of(), List.of())));
        assertThat(w[0].cardShown()).as("closing the tab closes its card").isNull();
        asks.asks.get(1).done().accept(new Topic("late", 1L, ""));
        SwingUtilities.invokeAndWait(() -> { });
        assertThat(w[0].cardShown()).isNull();
    }

    private static JMenuItem menuItem(final JPopupMenu menu, final String text) {
        for (final Component item : menu.getComponents()) {
            if (item instanceof JMenuItem m && m.getText().equals(text)) {
                return m;
            }
        }
        throw new AssertionError("no item " + text);
    }

    /// Dragging the first tab past the last sends `MovePanel` to index 2; the tabs then follow the state's order and
    /// the moved panel stays in front.
    @Test
    void aTabDraggedAlongTheRowMovesItsPanelAndTheTabsFollowTheStatesOrder() throws Exception {
        final PanelId b = new PanelId("b");
        final PanelId c = new PanelId("c");
        final List<Intent> intents = new CopyOnWriteArrayList<>();
        final Workspace[] w = new Workspace[1];
        final AppState[] s = new AppState[1];
        SwingUtilities.invokeAndWait(() -> {
            w[0] = new Workspace(intents::add, new Texts(), new SchemaRegistry());
            s[0] = AppState.initial(List.of(new PanelState(P, "one", "", ""), new PanelState(b, "two", "", ""),
                    new PanelState(c, "three", "", "")), List.of(), List.of()).withFocused(P);
            w[0].render(s[0]);
            final JTabbedPane tabs = (JTabbedPane) w[0].component();
            tabs.setSize(900, 400);
            tabs.doLayout();
            final Rectangle from = tabs.getBoundsAt(0);
            final Rectangle last = tabs.getBoundsAt(2);
            final int y = from.y + from.height / 2;
            try {
                tabs.dispatchEvent(mouse(tabs, MouseEvent.MOUSE_PRESSED, from.x + from.width / 2, y));
            } catch (final HeadlessException e) {
                // the look's own press handler may ask the toolkit for more than headless has; the drag saw the press
                // first
            }
            tabs.dispatchEvent(mouse(tabs, MouseEvent.MOUSE_DRAGGED, last.x + last.width + 4, y));
            tabs.dispatchEvent(mouse(tabs, MouseEvent.MOUSE_RELEASED, last.x + last.width + 4, y));
        });
        assertThat(intents).filteredOn(Intent.MovePanel.class::isInstance).containsExactly(new Intent.MovePanel(P, 2));

        SwingUtilities.invokeAndWait(() -> w[0].render(s[0].withPanels(List.of(s[0].panels().get(1), s[0].panels().get(2),
                s[0].panels().get(0)), s[0].focused())));
        final JTabbedPane tabs = (JTabbedPane) w[0].component();
        assertThat(List.of(tabs.getTitleAt(0), tabs.getTitleAt(1), tabs.getTitleAt(2))).containsExactly("two", "three", "one");
        assertThat(w[0].focused().panel().id()).as("the moved panel stays in front").isEqualTo(P);
    }

    /** Two documents, each a file of its own. */
    private static final class Documents implements PanelTexts {
        private final Map<PanelId, Path> files;
        private final Map<PanelId, String> texts;

        Documents(final Map<PanelId, Path> files, final Map<PanelId, String> texts) {
            this.files = files;
            this.texts = texts;
        }

        @Override
        public String load(final PanelId panel) {
            return texts.getOrDefault(panel, "");
        }

        @Override
        public Optional<Path> file(final PanelId panel) {
            return Optional.ofNullable(files.get(panel));
        }

        @Override
        public boolean document(final PanelId panel) {
            return files.containsKey(panel);
        }

        @Override
        public void changed(final PanelId panel, final String text) {
        }

        @Override
        public void forget(final PanelId panel) {
        }
    }

    /// A link to a heading in another document opens that file and, once its panel arrives, scrolls the preview to the
    /// heading; with the panel already open and scrolled away, the link scrolls it back.
    @Test
    void aLinkToAHeadingOfAnotherDocumentOpensItThereOrScrollsItsOpenPanelThere() throws Exception {
        final PanelId setupId = new PanelId("setup");
        final StringBuilder setup = new StringBuilder("# Setup\n\n");
        for (int i = 0; i < 80; i++) {
            setup.append("Step ").append(i).append(" of the preparation.\n\n");
        }
        setup.append("## 2. Install\n\nRun the installer.\n\n");
        for (int i = 0; i < 40; i++) {
            setup.append("Check ").append(i).append(" after the install.\n\n");
        }
        final Documents texts = new Documents(Map.of(P, Path.of("/docs/guide.md"), setupId, Path.of("/docs/setup.md")),
                Map.of(P, "# Guide\n\nSee [the install](setup.md#2-install).\n", setupId, setup.toString()));
        final List<Path> opened = new CopyOnWriteArrayList<>();
        final Workspace[] w = new Workspace[1];
        final AppState[] s = new AppState[1];
        SwingUtilities.invokeAndWait(() -> {
            w[0] = new Workspace(intent -> {
            }, texts, new SchemaRegistry());
            w[0].onOpenFile(opened::add);
            s[0] = AppState.initial(List.of(new PanelState(P, "guide", "", "")), List.of(), List.of()).withFocused(P);
            w[0].render(s[0]);
            w[0].focused().rendered().fireHyperlinkUpdate(link(w[0].focused(), "setup.md#2-install"));
        });
        assertThat(opened).as("the shell opens the file").containsExactly(Path.of("/docs/setup.md"));

        // the shell's panel for it arrives; it is laid out after it is made
        SwingUtilities.invokeAndWait(() -> {
            s[0] = s[0].withPanels(List.of(s[0].panels().get(0), new PanelState(setupId, "setup", "", "")), setupId);
            w[0].render(s[0]);
            layOut(w[0].component(), 900, 500);
        });
        final EditorPanel setupPanel = w[0].focused();
        assertThat(setupPanel.previewing()).isTrue();
        final int install = headingY(setupPanel, "2. Install");
        assertThat(install).as("well below the first screen").isGreaterThan(1000);
        assertThat(top(setupPanel)).as("opened at the heading").isEqualTo(install);
        assertThat(install + ((JViewport) setupPanel.rendered().getParent()).getHeight()).as("not the end of the page")
                .isLessThanOrEqualTo(setupPanel.rendered().getHeight());

        // already open, scrolled elsewhere: the link brings it there again
        SwingUtilities.invokeAndWait(() -> {
            ((JViewport) setupPanel.rendered().getParent()).setViewPosition(new Point(0, 0));
            w[0].render(s[0].withFocused(P));
            w[0].focused().rendered().fireHyperlinkUpdate(link(w[0].focused(), "setup.md#2-install"));
        });
        assertThat(top(setupPanel)).as("its open panel scrolled to the heading").isEqualTo(install);
    }

    /// A log opened for a time a few milliseconds before an entry puts the caret at that entry, in the upper part of
    /// the view; for an open log the entry not yet written is reached on the reload that brings it, instead of the end.
    @Test
    void aLogOpenedForAnEntryScrollsToItsLineAndAnOpenOneWaitsForTheEntryToBeWritten() throws Exception {
        final Path file = Path.of("/home/.comando/logs/comando-0.log");
        final long base = Instant.parse("2026-10-20T10:00:00Z").toEpochMilli();
        final StringBuilder log = new StringBuilder();
        for (int i = 0; i < 300; i++) {
            log.append(Instant.ofEpochMilli(base + i * 1_000L)).append(" INFO StateLoop line ").append(i).append('\n');
            if (i == 120) {
                log.append("\tat dev.marregui.comando.Hang.here(Hang.java:7)\n");
            }
        }
        final long hang = base + 120_000 - 3; // read just before the entry was written
        final Documents texts = new Documents(Map.of(P, file), Map.of(P, log.toString()));
        final Workspace[] w = new Workspace[1];
        SwingUtilities.invokeAndWait(() -> {
            w[0] = new Workspace(intent -> {
            }, texts, new SchemaRegistry());
            w[0].entryWhenOpen(file, hang);
            w[0].render(AppState.initial(List.of(new PanelState(P, "comando-0.log", "", "", true)), List.of(), List.of()));
            layOut(w[0].component(), 900, 500);
        });
        final EditorPanel panel = w[0].focused();
        final int entry = log.indexOf(Instant.ofEpochMilli(base + 120_000L) + " INFO");
        assertThat(caret(panel)).as("at the entry's line, not the log's end").isEqualTo(entry);
        assertThat(lineShown(panel, entry)).as("in the upper part of the view").isBetween(0, 250);

        // open, following its end: an entry not written yet is shown once the file is read again with it
        final long later = base + 400_000;
        SwingUtilities.invokeAndWait(() -> {
            panel.editor().area().setCaretPosition(panel.editor().area().getDocument().getLength());
            w[0].entryWhenOpen(file, later);
        });
        assertThat(caret(panel)).as("still at the end").isEqualTo(log.length());
        final String grown = log.toString() + Instant.ofEpochMilli(later + 2) + " WARNING ComandoUi the EDT has not answered\n"
                + Instant.ofEpochMilli(later + 900) + " INFO StateLoop after\n";
        SwingUtilities.invokeAndWait(() -> panel.reload(grown));
        assertThat(caret(panel)).as("at the new entry, which starts where the old text ended; not following to the end")
                .isEqualTo(log.length()).isLessThan(grown.length());
    }

    private static int caret(final EditorPanel p) throws Exception {
        final int[] at = new int[1];
        SwingUtilities.invokeAndWait(() -> at[0] = p.editor().area().getCaretPosition());
        return at[0];
    }

    /** How far below the top of the view the line at {@code offset} is drawn. */
    private static int lineShown(final EditorPanel p, final int offset) throws Exception {
        final int[] y = new int[1];
        SwingUtilities.invokeAndWait(() -> {
            try {
                final JViewport port = (JViewport) p.editor().area().getParent();
                y[0] = (int) p.editor().area().modelToView2D(offset).getY() - port.getViewPosition().y;
            } catch (final BadLocationException e) {
                throw new IllegalStateException(e);
            }
        });
        return y[0];
    }

    private static HyperlinkEvent link(final EditorPanel p, final String href) {
        final MouseEvent click = new MouseEvent(p.rendered(), MouseEvent.MOUSE_CLICKED, System.currentTimeMillis(), 0, 10, 10, 2, false,
                MouseEvent.BUTTON1);
        return new HyperlinkEvent(p.rendered(), HyperlinkEvent.EventType.ACTIVATED, null, href, null, click);
    }

    /** Sizes and lays out a tree never shown, where validate() lays out nothing. */
    private static void layOut(final Container c, final int width, final int height) {
        c.setSize(width, height);
        layOut(c);
    }

    private static void layOut(final Container c) {
        c.doLayout();
        for (final Component child : c.getComponents()) {
            if (child instanceof Container inner) {
                layOut(inner);
            }
        }
    }

    /** The preview's top, once the events the layout posted have run. */
    private static int top(final EditorPanel p) throws Exception {
        final int[] y = new int[1];
        for (int i = 0; i < 4; i++) {
            SwingUtilities.invokeAndWait(() -> {
            });
        }
        SwingUtilities.invokeAndWait(() -> y[0] = ((JViewport) p.rendered().getParent()).getViewPosition().y);
        return y[0];
    }

    private static int headingY(final EditorPanel p, final String text) throws Exception {
        final int[] y = new int[1];
        SwingUtilities.invokeAndWait(() -> {
            try {
                final String all = p.rendered().getDocument().getText(0, p.rendered().getDocument().getLength());
                y[0] = (int) p.rendered().modelToView2D(all.indexOf(text)).getY();
            } catch (final BadLocationException e) {
                throw new IllegalStateException(e);
            }
        });
        return y[0];
    }

    private static MouseEvent mouse(final Component c, final int id, final int x, final int y) {
        final boolean button = id == MouseEvent.MOUSE_PRESSED || id == MouseEvent.MOUSE_RELEASED;
        return new MouseEvent(c, id, System.currentTimeMillis(), MouseEvent.BUTTON1_DOWN_MASK, x, y, button ? 1 : 0, false,
                button ? MouseEvent.BUTTON1 : MouseEvent.NOBUTTON);
    }
}
