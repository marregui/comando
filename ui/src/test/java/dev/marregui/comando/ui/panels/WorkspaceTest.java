package dev.marregui.comando.ui.panels;

import dev.marregui.comando.core.intent.Intent;
import dev.marregui.comando.core.model.Approval;
import dev.marregui.comando.core.model.ApprovalKind;
import dev.marregui.comando.core.model.PanelId;
import dev.marregui.comando.core.model.SessionSpec;
import dev.marregui.comando.core.model.Status;
import dev.marregui.comando.core.schema.SchemaRegistry;
import dev.marregui.comando.core.state.AppState;
import dev.marregui.comando.core.state.BackgroundTask;
import dev.marregui.comando.core.state.PanelState;
import dev.marregui.comando.core.state.SessionState;
import dev.marregui.comando.ui.PanelTexts;
import dev.marregui.comando.ui.kit.icon.SpinnerIcon;
import org.junit.jupiter.api.Test;

import javax.swing.JTabbedPane;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.HeadlessException;
import java.awt.Rectangle;
import java.awt.event.MouseEvent;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

class WorkspaceTest {

    private static final PanelId P = new PanelId("p");

    /** Panel text in memory that hands its outside-change listener to the test. */
    private static final class Texts implements PanelTexts {
        final AtomicInteger changes = new AtomicInteger();
        Consumer<Outside> listener;
        Consumer<Deleted> deletions;

        @Override
        public void onDeleted(Consumer<Deleted> listener) {
            this.deletions = listener;
        }

        @Override
        public String load(PanelId panel) {
            return "SELECT 1;";
        }

        @Override
        public void changed(PanelId panel, String text) {
            changes.incrementAndGet();
        }

        @Override
        public void forget(PanelId panel) {
        }

        @Override
        public void onOutsideChange(Consumer<Outside> listener) {
            this.listener = listener;
        }
    }

    @Test
    void aFileChangedOutsideShowsInItsPanelUnlessThePanelHasUnsavedEdits() throws Exception {
        Texts texts = new Texts();
        List<Intent> intents = new CopyOnWriteArrayList<>();
        Workspace[] w = new Workspace[1];
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

    @Test
    void aDeletedFileRemovesItsPanelWithNothingToReopen() throws Exception {
        PanelId doc = new PanelId("d");
        Texts texts = new Texts();
        List<Intent> intents = new CopyOnWriteArrayList<>();
        PanelState spec = new PanelState(P, "spec", "", "");
        Workspace[] w = new Workspace[1];
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

        PanelId shut = new PanelId("c");
        SwingUtilities.invokeAndWait(() -> w[0].render(AppState.initial(List.of(spec), List.of(), List.of())
                .withClosed(List.of(new PanelState(shut, "old", "", "")))));
        SwingUtilities.invokeAndWait(() -> w[0].closeDeleted(new PanelTexts.Deleted(shut, "old.md", () -> true)));
        assertThat(intents).as("a closed panel's file deleted outside removes it too").contains(new Intent.RemovePanel(shut));
    }

    @Test
    void aDeletedFileWhosePanelGotEditsSinceTheReportKeepsThePanel() throws Exception {
        Texts texts = new Texts();
        List<Intent> intents = new CopyOnWriteArrayList<>();
        PanelState spec = new PanelState(P, "spec", "", "");
        PanelId other = new PanelId("o");
        Workspace[] w = new Workspace[1];
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

    @Test
    void aTabFollowsItsSessionAndMarksAnOutcomeUntilLookedAt() throws Exception {
        PanelId other = new PanelId("o");
        PanelState work = new PanelState(P, "work", "", "s");
        PanelState notes = new PanelState(other, "notes", "", "");
        SessionState session = SessionState.closed(SessionSpec.fresh("s", "/tmp")).withStatus(Status.OPEN, "");
        List<Intent> intents = new CopyOnWriteArrayList<>();
        AtomicInteger attention = new AtomicInteger();
        Workspace[] w = new Workspace[1];
        AppState[] s = new AppState[1];
        SwingUtilities.invokeAndWait(() -> {
            w[0] = new Workspace(intents::add, new Texts(), new SchemaRegistry());
            w[0].onAttention(attention::incrementAndGet);
            s[0] = AppState.initial(List.of(work, notes), List.of(), List.of(session)).withFocused(other);
            w[0].render(s[0]);
        });
        JTabbedPane tabs = (JTabbedPane) w[0].component();
        assertThat(w[0].statuses()).containsEntry(P, TabStatus.IDLE).containsEntry(other, TabStatus.NONE);
        assertThat(tabs.getTitleAt(0)).isEqualTo("work");

        // the turn runs while another tab is in front: the tab spins, its title stays
        SwingUtilities.invokeAndWait(() -> w[0].render(s[0] = s[0].withSession(session.withTurns(1, 1L))));
        assertThat(w[0].statuses()).containsEntry(P, TabStatus.WORKING);
        assertThat(tabs.getIconAt(0)).isInstanceOf(SpinnerIcon.class);
        assertThat(w[0].spinning()).isTrue();
        assertThat(tabs.getTitleAt(0)).isEqualTo("work");

        // the session asks: the tab needs the user, and the desktop is asked for attention once
        SwingUtilities.invokeAndWait(() -> w[0].render(s[0] = s[0].withApprovals(List.of(
                new Approval(1L, "s", ApprovalKind.TOOL, "Bash", "{}", "ls", 1L)))));
        assertThat(w[0].statuses()).containsEntry(P, TabStatus.NEEDS_YOU);
        assertThat(tabs.getTitleAt(0)).isEqualTo("● work");
        assertThat(attention).hasValue(1);
        SwingUtilities.invokeAndWait(() -> w[0].render(s[0]));
        assertThat(attention).as("the same need asks once").hasValue(1);

        // answered, the turn ends unseen: done, marked, no spinner, no attention
        SwingUtilities.invokeAndWait(() -> w[0].render(s[0] = s[0].withApprovals(List.of()).withSession(session.withTurns(0, 2L))));
        assertThat(w[0].statuses()).containsEntry(P, TabStatus.DONE);
        assertThat(tabs.getTitleAt(0)).isEqualTo("● work");
        assertThat(tabs.getIconAt(0)).isNotInstanceOf(SpinnerIcon.class);
        assertThat(w[0].spinning()).isFalse();
        assertThat(attention).hasValue(1);

        // looking at it clears the mark
        SwingUtilities.invokeAndWait(() -> w[0].render(s[0] = s[0].withFocused(P)));
        assertThat(w[0].statuses()).containsEntry(P, TabStatus.IDLE);
        assertThat(tabs.getTitleAt(0)).isEqualTo("work");

        // a failed turn while away: an error, with attention; a new turn starting supersedes it
        SwingUtilities.invokeAndWait(() -> w[0].render(s[0] = s[0].withFocused(other).withSession(session.withTurns(1, 3L))));
        SwingUtilities.invokeAndWait(() -> w[0].render(s[0] = s[0].withSession(session.withTurns(1, 3L).withTurns(0, 4L)
                .withLastTurnError("rate limited"))));
        assertThat(w[0].statuses()).containsEntry(P, TabStatus.ERROR);
        assertThat(attention).hasValue(2);
        SwingUtilities.invokeAndWait(() -> w[0].render(s[0] = s[0].withSession(session.withTurns(1, 5L))));
        assertThat(w[0].statuses()).containsEntry(P, TabStatus.WORKING);
        assertThat(tabs.getTitleAt(0)).isEqualTo("work");
    }

    @Test
    void returnedBackgroundWorkMarksTheTabFinishedOnlyWhenNoReadingTurnFollows() throws Exception {
        PanelId other = new PanelId("o");
        SessionState open = SessionState.closed(SessionSpec.fresh("s", "/tmp")).withStatus(Status.OPEN, "");
        BackgroundTask agent = new BackgroundTask("a1", "local_agent", "review agent", 0L);
        SessionState waiting = open.withTurns(1, 1_000L).withTasks(List.of(agent), 2_000L).withTurns(0, 3_000L);
        long[] now = {3_000L};
        Workspace[] w = new Workspace[1];
        AppState[] s = new AppState[1];
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

    @Test
    void aHandOffTurnThatFailsShowsItsErrorWhileItsWorkRunsOn() throws Exception {
        PanelId other = new PanelId("o");
        SessionState open = SessionState.closed(SessionSpec.fresh("s", "/tmp")).withStatus(Status.OPEN, "");
        BackgroundTask agent = new BackgroundTask("a1", "local_agent", "review agent", 0L);
        Workspace[] w = new Workspace[1];
        AppState[] s = new AppState[1];
        SwingUtilities.invokeAndWait(() -> {
            w[0] = new Workspace(i -> { }, new Texts(), new SchemaRegistry());
            w[0].onAttention(() -> { });
            s[0] = AppState.initial(List.of(new PanelState(P, "work", "", "s"), new PanelState(other, "notes", "", "")), List.of(),
                    List.of(open.withTurns(1, 1_000L))).withFocused(other);
            w[0].render(s[0]);
            SessionState failed = open.withTurns(1, 1_000L).withTasks(List.of(agent), 1_500L).withTurns(0, 2_000L).withLastTurnError("rate limited");
            w[0].render(s[0] = s[0].withSession(failed));
        });
        assertThat(w[0].statuses()).containsEntry(P, TabStatus.ERROR);
    }

    @Test
    void aTabDraggedAlongTheRowMovesItsPanelAndTheTabsFollowTheStatesOrder() throws Exception {
        PanelId b = new PanelId("b");
        PanelId c = new PanelId("c");
        List<Intent> intents = new CopyOnWriteArrayList<>();
        Workspace[] w = new Workspace[1];
        AppState[] s = new AppState[1];
        SwingUtilities.invokeAndWait(() -> {
            w[0] = new Workspace(intents::add, new Texts(), new SchemaRegistry());
            s[0] = AppState.initial(List.of(new PanelState(P, "one", "", ""), new PanelState(b, "two", "", ""),
                    new PanelState(c, "three", "", "")), List.of(), List.of()).withFocused(P);
            w[0].render(s[0]);
            JTabbedPane tabs = (JTabbedPane) w[0].component();
            tabs.setSize(900, 400);
            tabs.doLayout();
            Rectangle from = tabs.getBoundsAt(0);
            Rectangle last = tabs.getBoundsAt(2);
            int y = from.y + from.height / 2;
            try {
                tabs.dispatchEvent(mouse(tabs, MouseEvent.MOUSE_PRESSED, from.x + from.width / 2, y));
            } catch (HeadlessException e) {
                // the look's own press handler may ask the toolkit for more than headless has; the drag saw the press first
            }
            tabs.dispatchEvent(mouse(tabs, MouseEvent.MOUSE_DRAGGED, last.x + last.width + 4, y));
            tabs.dispatchEvent(mouse(tabs, MouseEvent.MOUSE_RELEASED, last.x + last.width + 4, y));
        });
        assertThat(intents).filteredOn(Intent.MovePanel.class::isInstance).containsExactly(new Intent.MovePanel(P, 2));

        SwingUtilities.invokeAndWait(() -> w[0].render(s[0].withPanels(List.of(s[0].panels().get(1), s[0].panels().get(2),
                s[0].panels().get(0)), s[0].focused())));
        JTabbedPane tabs = (JTabbedPane) w[0].component();
        assertThat(List.of(tabs.getTitleAt(0), tabs.getTitleAt(1), tabs.getTitleAt(2))).containsExactly("two", "three", "one");
        assertThat(w[0].focused().panel().id()).as("the moved panel stays in front").isEqualTo(P);
    }

    private static MouseEvent mouse(Component c, int id, int x, int y) {
        boolean button = id == MouseEvent.MOUSE_PRESSED || id == MouseEvent.MOUSE_RELEASED;
        return new MouseEvent(c, id, System.currentTimeMillis(), MouseEvent.BUTTON1_DOWN_MASK, x, y, button ? 1 : 0, false,
                button ? MouseEvent.BUTTON1 : MouseEvent.NOBUTTON);
    }
}
