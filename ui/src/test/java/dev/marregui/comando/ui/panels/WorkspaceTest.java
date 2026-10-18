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
import dev.marregui.comando.ui.kit.format.KeyText;
import dev.marregui.comando.ui.kit.icon.SpinnerIcon;
import org.junit.jupiter.api.Test;

import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.JTabbedPane;
import javax.swing.JViewport;
import javax.swing.SwingUtilities;
import javax.swing.event.HyperlinkEvent;
import javax.swing.text.BadLocationException;
import java.awt.Component;
import java.awt.Container;
import java.awt.HeadlessException;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.event.MouseEvent;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
    void aLogFollowingItsGrowthPostsNoNotice() throws Exception {
        Texts texts = new Texts();
        List<Intent> intents = new CopyOnWriteArrayList<>();
        Workspace[] w = new Workspace[1];
        SwingUtilities.invokeAndWait(() -> {
            w[0] = new Workspace(intents::add, texts, new SchemaRegistry());
            w[0].render(AppState.initial(List.of(new PanelState(P, "comando-0", "", "", true)), List.of(), List.of()));
            w[0].focused().file(Path.of("/home/.comando/logs/comando-0.log"), true);
        });
        SwingUtilities.invokeAndWait(() -> w[0].takeOutside(new PanelTexts.Outside(P, "comando-0.log", "one\ntwo\n", () -> true)));
        assertThat(w[0].focused().editor().text()).isEqualTo("one\ntwo\n");
        assertThat(intents).noneMatch(Intent.Notify.class::isInstance);
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
    void aTabsMenuClosesTheOthersOrThoseToOneSideAndKeepsTabsWaitingForTheUserOrRunningAProgram() throws Exception {
        PanelId a = new PanelId("a");
        PanelId b = new PanelId("b");
        PanelId c = new PanelId("c");
        PanelId d = new PanelId("d");
        PanelId e = new PanelId("e");
        SessionState session = SessionState.closed(SessionSpec.fresh("s", "/tmp")).withStatus(Status.OPEN, "");
        // a and b are plain; c's session waits for an approval; d's shell runs a program; e's session is mid-turn
        AppState s = AppState.initial(List.of(new PanelState(a, "a", "", ""), new PanelState(b, "b", "", ""),
                        new PanelState(c, "c", "", "s"), new PanelState(d, "d", "", ""), new PanelState(e, "e", "", "busy")), List.of(),
                List.of(session, SessionState.closed(SessionSpec.fresh("busy", "/tmp")).withStatus(Status.OPEN, "").withTurns(1, 1L)))
                .withApprovals(List.of(new Approval(1L, "s", ApprovalKind.TOOL, "Bash", "{}", "ls", 1L)));
        List<Intent> intents = new CopyOnWriteArrayList<>();
        List<PanelId> closedOne = new CopyOnWriteArrayList<>();
        Workspace[] w = new Workspace[1];
        SwingUtilities.invokeAndWait(() -> {
            w[0] = new Workspace(intents::add, new Texts(), new SchemaRegistry());
            w[0].onClose(closedOne::add);
            w[0].onRunsProgram(d::equals);
            w[0].render(s);
        });
        assertThat(w[0].statuses()).containsEntry(c, TabStatus.NEEDS_YOU).containsEntry(e, TabStatus.WORKING);

        List<String> labels = new CopyOnWriteArrayList<>();
        List<Boolean> enabled = new CopyOnWriteArrayList<>();
        SwingUtilities.invokeAndWait(() -> {
            for (Component item : w[0].tabMenu(0).getComponents()) {
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
        AppState after = s.withPanels(s.panels().stream().filter(p -> !p.id().equals(a) && !p.id().equals(e)).toList(), b);
        SwingUtilities.invokeAndWait(() -> w[0].render(after));
        assertThat(intents).filteredOn(Intent.Notify.class::isInstance).extracting(i -> ((Intent.Notify) i).text())
                .as("one notice for the close, none per tab")
                .containsExactly(KeyText.of("Closed 2 tabs; ⌘⇧T or the Panels tab reopens them; kept 2 tabs open: 1 waiting for you, 1 running a program in the shell"));

        // b c d now; a menu opened on d, then b closes elsewhere: the menu still closes only what was left of d
        intents.clear();
        JPopupMenu[] menu = new JPopupMenu[1];
        SwingUtilities.invokeAndWait(() -> menu[0] = w[0].tabMenu(2));
        SwingUtilities.invokeAndWait(() -> w[0].render(after.withPanels(after.panels().stream().filter(p -> !p.id().equals(b)).toList(), c)));
        SwingUtilities.invokeAndWait(() -> menuItem(menu[0], "Close to the left").doClick());
        assertThat(intents).filteredOn(Intent.ClosePanel.class::isInstance).as("b is gone, c waits for the user: nothing closes").isEmpty();
        assertThat(intents).filteredOn(Intent.Notify.class::isInstance).extracting(i -> ((Intent.Notify) i).text())
                .containsExactly("Kept 1 tab open: 1 waiting for you");

        SwingUtilities.invokeAndWait(() -> menuItem(w[0].tabMenu(1), "Close").doClick());
        assertThat(closedOne).as("Close is the tab's own close, which asks when its shell runs a program").containsExactly(d);
    }

    private static JMenuItem menuItem(JPopupMenu menu, String text) {
        for (Component item : menu.getComponents()) {
            if (item instanceof JMenuItem m && m.getText().equals(text)) {
                return m;
            }
        }
        throw new AssertionError("no item " + text);
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

    /** Two documents, each a file of its own. */
    private static final class Documents implements PanelTexts {
        private final Map<PanelId, Path> files;
        private final Map<PanelId, String> texts;

        Documents(Map<PanelId, Path> files, Map<PanelId, String> texts) {
            this.files = files;
            this.texts = texts;
        }

        @Override
        public String load(PanelId panel) {
            return texts.getOrDefault(panel, "");
        }

        @Override
        public Optional<Path> file(PanelId panel) {
            return Optional.ofNullable(files.get(panel));
        }

        @Override
        public boolean document(PanelId panel) {
            return files.containsKey(panel);
        }

        @Override
        public void changed(PanelId panel, String text) {
        }

        @Override
        public void forget(PanelId panel) {
        }
    }

    @Test
    void aLinkToAHeadingOfAnotherDocumentOpensItThereOrScrollsItsOpenPanelThere() throws Exception {
        PanelId setupId = new PanelId("setup");
        StringBuilder setup = new StringBuilder("# Setup\n\n");
        for (int i = 0; i < 80; i++) {
            setup.append("Step ").append(i).append(" of the preparation.\n\n");
        }
        setup.append("## 2. Install\n\nRun the installer.\n\n");
        for (int i = 0; i < 40; i++) {
            setup.append("Check ").append(i).append(" after the install.\n\n");
        }
        Documents texts = new Documents(Map.of(P, Path.of("/docs/guide.md"), setupId, Path.of("/docs/setup.md")),
                Map.of(P, "# Guide\n\nSee [the install](setup.md#2-install).\n", setupId, setup.toString()));
        List<Path> opened = new CopyOnWriteArrayList<>();
        Workspace[] w = new Workspace[1];
        AppState[] s = new AppState[1];
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
        EditorPanel setupPanel = w[0].focused();
        assertThat(setupPanel.previewing()).isTrue();
        int install = headingY(setupPanel, "2. Install");
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

    private static HyperlinkEvent link(EditorPanel p, String href) {
        MouseEvent click = new MouseEvent(p.rendered(), MouseEvent.MOUSE_CLICKED, System.currentTimeMillis(), 0, 10, 10, 2, false,
                MouseEvent.BUTTON1);
        return new HyperlinkEvent(p.rendered(), HyperlinkEvent.EventType.ACTIVATED, null, href, null, click);
    }

    /** Sizes and lays out a tree never shown, where validate() lays out nothing. */
    private static void layOut(Container c, int width, int height) {
        c.setSize(width, height);
        layOut(c);
    }

    private static void layOut(Container c) {
        c.doLayout();
        for (Component child : c.getComponents()) {
            if (child instanceof Container inner) {
                layOut(inner);
            }
        }
    }

    /** The preview's top, once the events the layout posted have run. */
    private static int top(EditorPanel p) throws Exception {
        int[] y = new int[1];
        for (int i = 0; i < 4; i++) {
            SwingUtilities.invokeAndWait(() -> {
            });
        }
        SwingUtilities.invokeAndWait(() -> y[0] = ((JViewport) p.rendered().getParent()).getViewPosition().y);
        return y[0];
    }

    private static int headingY(EditorPanel p, String text) throws Exception {
        int[] y = new int[1];
        SwingUtilities.invokeAndWait(() -> {
            try {
                String all = p.rendered().getDocument().getText(0, p.rendered().getDocument().getLength());
                y[0] = (int) p.rendered().modelToView2D(all.indexOf(text)).getY();
            } catch (BadLocationException e) {
                throw new IllegalStateException(e);
            }
        });
        return y[0];
    }

    private static MouseEvent mouse(Component c, int id, int x, int y) {
        boolean button = id == MouseEvent.MOUSE_PRESSED || id == MouseEvent.MOUSE_RELEASED;
        return new MouseEvent(c, id, System.currentTimeMillis(), MouseEvent.BUTTON1_DOWN_MASK, x, y, button ? 1 : 0, false,
                button ? MouseEvent.BUTTON1 : MouseEvent.NOBUTTON);
    }
}
