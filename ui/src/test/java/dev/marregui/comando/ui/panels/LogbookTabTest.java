package dev.marregui.comando.ui.panels;

import java.awt.Component;
import java.awt.Container;
import java.awt.Rectangle;
import java.awt.event.MouseEvent;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.swing.JComponent;
import javax.swing.JMenu;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.JTree;
import javax.swing.ToolTipManager;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.TreePath;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import dev.marregui.comando.core.intent.Intent;
import dev.marregui.comando.core.logbook.Logbook;
import dev.marregui.comando.core.logbook.LogbookTask;
import dev.marregui.comando.core.model.LinearTickets;
import dev.marregui.comando.core.model.SessionSpec;
import dev.marregui.comando.core.model.Status;
import dev.marregui.comando.core.state.AppState;
import dev.marregui.comando.core.state.SessionState;
import dev.marregui.comando.core.view.Placement;
import dev.marregui.comando.core.view.ViewSpec;
import dev.marregui.comando.testkit.States;
import dev.marregui.comando.ui.kit.async.OnEdt;
import dev.marregui.comando.ui.kit.desktop.Browser;
import dev.marregui.comando.ui.kit.desktop.Links;

import static org.assertj.core.api.Assertions.assertThat;

/// [LogbookTab]: the scratchpad's open tasks in the sidebar's tree.
/// - Needs you first, when a task waits on the user, then a group per topic in the index's order, each newest first;
///   a row is the task's name, its number and start day muted after it, and who asked when not the logbook's owner
/// - a logbook with a problem says it in one row
/// - a click shows the task's card of facts; a double click shows its page, with the day folder and the Linear site
/// - the menu opens each ticket in Linear once the workspace is known, sends the exact prompts to the running session
///   chosen, opens a document resolved against the index's folder; every entry wears an icon
/// - a new logbook keeps the selected task selected by its number and the closed groups closed
@ExtendWith(OnEdt.class)
class LogbookTabTest {

    private static final Path DAY = Path.of("/w/SCRATCHPAD/2026-11-10");
    private static final String INDEX = "/w/SCRATCHPAD/2026-11-10/INDEX.md";
    private static final LinearTickets.Site SITE = new LinearTickets.Site("acme", Set.of("EDG"));

    private final Recording cards = new Recording();
    private final List<Intent> intents = new ArrayList<>();
    private final List<Path> opened = new ArrayList<>();

    @BeforeAll
    static void headless() {
        System.setProperty("java.awt.headless", "true");
    }

    /// Needs you leads with its count, then the topics in the order the index first names them; within each group the
    /// newest task, the highest number, comes first, and a task that waits is listed under its topic too.
    @Test
    void needsYouLeadsThenEachTopicInTheIndexsOrderNewestFirst() {
        final ViewSpec.TreeNode root = LogbookTab.root(logbook());

        assertThat(root.children()).extracting(ViewSpec.TreeNode::label)
                .containsExactly("Needs you (2)", "harness", "release");
        assertThat(numbers(root.children().getFirst())).containsExactly(4, 1);
        assertThat(numbers(root.children().get(1))).containsExactly(3, 1);
        assertThat(numbers(root.children().getLast())).containsExactly(4, 2);
        assertThat(root.children().get(1).children().getFirst().label()).isEqualTo("Task 3");
        assertThat(LogbookTab.tip(logbook())).isEqualTo("the scratchpad's open tasks; 2 need you");
    }

    /// The tree shows its groups open, the root hidden, and leaves the rows' tooltips to the card: the tree is not
    /// registered for tooltips.
    @Test
    void theTreeShowsTheGroupsOpenAndNoTooltips() {
        final LogbookTab tab = tab();
        tab.render(state(logbook()));
        final JTree tree = tree(tab);

        assertThat(rows(tree)).containsExactly("Needs you (2)", "Task 4", "Logbook tab", "harness", "Task 3",
                "Logbook tab", "release", "Task 4", "Task 2");
        assertThat(tree.isRootVisible()).isFalse();
        assertThat(tree.getMouseListeners()).doesNotContain(ToolTipManager.sharedInstance());
    }

    /// The muted detail is the number and the start day, the number alone without a date, and adds who asked only when
    /// that is not the one who asked for most tasks.
    @Test
    void theDetailIsTheNumberTheDayAndWhoAskedWhenNotTheOwner() {
        final LogbookTask ana = dated(5, "2026-11-10", "Ana");
        final LogbookTask ben = dated(6, "2026-11-09", "Ben");
        final Logbook l = new Logbook(INDEX, List.of(ana, ben, dated(7, "", "Ana"), dated(8, "not a date", "")), "");

        assertThat(LogbookTab.owner(l)).isEqualTo("Ana");
        assertThat(LogbookTab.detail(ana, "Ana")).isEqualTo("5 · 10 Nov");
        assertThat(LogbookTab.detail(ben, "Ana")).isEqualTo("6 · 9 Nov · Ben");
        assertThat(LogbookTab.detail(dated(7, "", "Ana"), "Ana")).isEqualTo("7");
        assertThat(LogbookTab.detail(dated(8, "not a date", ""), "Ana")).isEqualTo("8");
        assertThat(LogbookTab.root(l).children().getFirst().children()).extracting(ViewSpec.TreeNode::detail)
                .containsExactly("8", "7", "6 · 9 Nov · Ben", "5 · 10 Nov");
        assertThat(LogbookTab.owner(new Logbook(INDEX, List.of(task(1, "t", "Open.")), ""))).isEmpty();
    }

    /// With no task waiting on the user there is no Needs you group: the first group is the first topic.
    @Test
    void noNeedsYouGroupWhenNoTaskWaits() {
        final Logbook l = new Logbook(INDEX, List.of(task(2, "release", "Open."), task(3, "harness", "Open.")), "");

        assertThat(LogbookTab.root(l).children()).extracting(ViewSpec.TreeNode::label)
                .containsExactly("release", "harness");
        assertThat(LogbookTab.tip(l)).isEqualTo("the scratchpad's open tasks; 0 need you");
    }

    /// A logbook that could not be read says why, in its one row; so does one with no day folder yet.
    @Test
    void aProblemIsShownAsOneRow() {
        final LogbookTab tab = tab();
        tab.render(state(new Logbook(INDEX, List.of(), "INDEX.md has no Open section")));

        assertThat(rows(tree(tab))).containsExactly("INDEX.md has no Open section");
        assertThat(rows(tree(tab()))).containsExactly("No scratchpad day folder yet");
    }

    /// A click on a task's row shows its card: Task N, the facts in their order, each only when it has a value, the
    /// task's name over its Ask, over the tree.
    @Test
    void aClickShowsTheTasksCardOfFacts() {
        final LogbookTask full = new LogbookTask(5, "Logbook tab", "harness", "Build the tab.",
                "In progress: it is being built. Then review.", "Review. Then merge.", List.of(),
                List.of("Gold or ivory?", "Which order?"), "", "2026-11-10", "Ben", "Slack", List.of("EDG-1", "EDG-2"));
        final LogbookTab tab = tab();
        tab.render(state(new Logbook(INDEX, List.of(full), "")));
        final JTree tree = tree(tab);
        click(tree, 1, 1);

        assertThat(cards.shown).hasSize(1);
        final Shown card = cards.shown.getFirst();
        assertThat(card.key()).isEqualTo("logbook:5");
        assertThat(card.title()).isEqualTo("Task 5");
        assertThat(card.facts()).containsExactly(Map.entry("Started", "10 Nov 2026"), Map.entry("By", "Ben"),
                Map.entry("From", "Slack"), Map.entry("Topic", "harness"), Map.entry("Ticket", "EDG-1, EDG-2"),
                Map.entry("State", "In progress: it is being built."), Map.entry("Next", "Review."),
                Map.entry("Needs you", "2"));
        assertThat(card.heading()).isEqualTo("Logbook tab");
        assertThat(card.text()).isEqualTo("Build the tab.");
        assertThat(card.over()).isSameAs(tree);
        assertThat(LogbookTab.facts(task(2, "", ""))).as("no value, no fact").isEmpty();
    }

    /// A double click on a task shows its page, titled Task N in the lower panel, made with the index's folder and the
    /// Linear site the window knows.
    @Test
    void aDoubleClickShowsTheTasksPageWithTheDayFolderAndTheSite() {
        final LogbookTab tab = tab();
        tab.render(state(logbook()));
        click(tree(tab), 2, 2);

        final LogbookTask one = logbook().tasks().getFirst();
        assertThat(intents).containsExactly(new Intent.ShowView("", "Task 1",
                new ViewSpec.Markdown(TaskPages.markdown(one, DAY, SITE)), Placement.LOWER));
        assertThat(cards.shown).as("a double click shows the page, not the card").isEmpty();
    }

    /// A logbook that names no index gives the page no folder.
    @Test
    void aLogbookWithNoIndexGivesThePageNoFolder() {
        final LogbookTab tab = tab();
        final LogbookTask one = logbook().tasks().getFirst();
        tab.render(state(new Logbook("", List.of(one), "")));
        tab.page(one);

        assertThat(intents).containsExactly(new Intent.ShowView("", "Task 1",
                new ViewSpec.Markdown(TaskPages.markdown(one, null, SITE)), Placement.LOWER));
    }

    /// Each of a task's tickets opens in Linear, at its issue's address on the site's workspace, through the browser.
    @Test
    void eachTicketOpensInLinearAtItsIssue() {
        final List<String> browsed = new ArrayList<>();
        Links.browseWith(browsed::add);
        try {
            final LogbookTab tab = tab();
            tab.render(state(logbook()));
            final JPopupMenu menu = tab.menu(ticketed());

            assertThat(items(menu)).containsExactly("Show the task", "Open EDG-1 in Linear", "Open EDG-2 in Linear",
                    "Resume in");
            item(menu, "Open EDG-2 in Linear").doClick();

            assertThat(browsed).containsExactly("https://linear.app/acme/issue/EDG-2");
            assertThat(intents).isEmpty();
        } finally {
            Links.browseWith(Browser::open);
        }
    }

    /// Until the window knows Linear's workspace a task's tickets have no entry.
    @Test
    void noLinearEntryWithoutAWorkspace() {
        final LogbookTab tab = new LogbookTab(intents::add, opened::add, () -> SessionsSidebar.Cards.NONE);
        tab.render(state(logbook()));

        assertThat(items(tab.menu(ticketed()))).containsExactly("Show the task", "Resume in");
    }

    /// Show the task in the menu shows the page as a double click does.
    @Test
    void showTheTaskShowsItsPage() {
        final LogbookTab tab = tab();
        tab.render(state(logbook()));
        item(tab.menu(logbook().tasks().get(1)), "Show the task").doClick();

        assertThat(intents).containsExactly(new Intent.ShowView("", "Task 2",
                new ViewSpec.Markdown(TaskPages.markdown(logbook().tasks().get(1), DAY, SITE)), Placement.LOWER));
    }

    /// Every entry of the menu wears an icon, the submenus and the entries in them too.
    @Test
    void everyMenuEntryWearsAnIcon() {
        final LogbookTab tab = tab();
        tab.render(state(logbook()));
        final List<JMenuItem> all = new ArrayList<>();
        collect(tab.menu(logbook().tasks().getFirst()).getComponents(), all);
        collect(tab.menu(ticketed()).getComponents(), all);

        assertThat(all).extracting(JMenuItem::getText).contains("Show the task", "Open EDG-1 in Linear", "Resume in",
                "Ask me its decisions in", "Open document", "alpha", "topic/01-first.md");
        assertThat(all).allSatisfy(i -> assertThat(i.getIcon()).as(i.getText()).isNotNull());
    }

    /// Resume in lists the running sessions only, and sends the chosen one the resume prompt as the user's, its panel
    /// opened first.
    @Test
    void resumeSendsTheExactPromptToTheChosenRunningSession() {
        final LogbookTab tab = tab();
        tab.render(state(logbook()));
        final JMenu resume = submenu(tab.menu(logbook().tasks().get(1)), "Resume in");

        assertThat(texts(resume)).containsExactly("alpha");
        resume.getItem(0).doClick();

        assertThat(intents).hasSize(2);
        assertThat(intents.getFirst()).isInstanceOfSatisfying(Intent.OpenSession.class,
                o -> assertThat(o.session()).isEqualTo("alpha"));
        assertThat(intents.get(1))
                .isEqualTo(new Intent.PromptSession("alpha", "Resume task 2 from the scratchpad.", ""));
    }

    /// Ask me its decisions in is on a task that waits on the user and sends the decisions prompt; a task that does not
    /// wait has no such entry.
    @Test
    void askSendsTheDecisionsPromptOnlyForATaskThatWaits() {
        final LogbookTab tab = tab();
        tab.render(state(logbook()));
        final JMenu ask = submenu(tab.menu(logbook().tasks().getLast()), "Ask me its decisions in");
        ask.getItem(0).doClick();

        assertThat(intents.get(1)).isEqualTo(new Intent.PromptSession("alpha",
                "Task 4 holds decisions for me (its Needs you line or its State). Ask them now as question cards, "
                        + "recommended option first, then write the answers into the task.", ""));
        assertThat(submenu(tab.menu(logbook().tasks().get(1)), "Ask me its decisions in"))
                .as("task 2 waits on no one").isNull();
    }

    /// With no session running, the session submenus are there but disabled.
    @Test
    void noRunningSessionDisablesTheSessionMenus() {
        final LogbookTab tab = tab();
        tab.render(States.twoPanels().withLogbook(logbook()));

        final JMenu resume = submenu(tab.menu(logbook().tasks().getFirst()), "Resume in");
        assertThat(resume.isEnabled()).isFalse();
        assertThat(resume.getItemCount()).isZero();
    }

    /// A task's document is opened at its path against the folder that holds the index, as the Files tab opens a file;
    /// a task with no documents has no Open document entry.
    @Test
    void aDocumentOpensResolvedAgainstTheIndexsFolder() {
        final LogbookTab tab = tab();
        tab.render(state(logbook()));
        final JMenu documents = submenu(tab.menu(logbook().tasks().getFirst()), "Open document");

        assertThat(texts(documents)).containsExactly("topic/01-first.md", "topic/work/first-spec.md");
        documents.getItem(1).doClick();

        assertThat(opened).containsExactly(DAY.resolve("topic/work/first-spec.md"));
        assertThat(intents).isEmpty();
        assertThat(submenu(tab.menu(logbook().tasks().get(1)), "Open document")).isNull();
        assertThat(LogbookTab.resolved(Logbook.NONE, "a.md")).as("no index, no folder").isEmpty();
    }

    /// A new logbook, Needs you's count changed, keeps the selected task selected by its number in its group, and the
    /// group the user closed closed.
    @Test
    void aNewLogbookKeepsTheSelectedTaskAndTheClosedGroups() {
        final LogbookTab tab = tab();
        tab.render(state(logbook()));
        final JTree tree = tree(tab);
        tree.setSelectionRow(4);
        tree.collapseRow(6);
        assertThat(label(tree.getSelectionPath())).isEqualTo("Task 3");

        final List<LogbookTask> more = new ArrayList<>(logbook().tasks());
        more.addFirst(task(5, "harness", "Waiting on Miguel: a newer one."));
        tab.render(state(new Logbook(INDEX, more, "")));

        assertThat(rows(tree)).containsExactly("Needs you (3)", "Task 5", "Task 4", "Logbook tab", "harness",
                "Task 5", "Task 3", "Logbook tab", "release");
        assertThat(tree.getSelectionRows()).as("task 3 under harness, moved down two rows").containsExactly(6);
    }

    /// The State's first sentence ends at the first full stop followed by a space.
    @Test
    void theFirstSentenceEndsAtAFullStopFollowedBySpace() {
        assertThat(LogbookTab.firstSentence("Open: v2.0.3 lands. Then the rest.")).isEqualTo("Open: v2.0.3 lands.");
        assertThat(LogbookTab.firstSentence("No full stop")).isEqualTo("No full stop");
        assertThat(LogbookTab.firstSentence("")).isEmpty();
    }

    /// Tasks 1 and 4 wait on the user (a Needs you line; a State beginning "Waiting on Miguel"), topics harness then
    /// release; task 1's section names a ticket of the site's team and one of its documents, so its page links both.
    private static Logbook logbook() {
        final LogbookTask one = new LogbookTask(1, "Logbook tab", "harness", "", "In progress: it is being built. Then "
                + "review.", "", List.of("topic/01-first.md", "topic/work/first-spec.md"),
                List.of("Gold or ivory icon?"), "#### 1. Logbook tab\n\n- **State:** see EDG-1 in `topic/01-first.md`",
                "", "", "", List.of());
        return new Logbook(INDEX, List.of(one, task(2, "release", "Open."), task(3, "harness", "Open."),
                task(4, "release", "Waiting on Miguel: the cut date.")), "");
    }

    private static LogbookTask task(final int number, final String topic, final String state) {
        return new LogbookTask(number, "Task " + number, topic, "", state, "", List.of(), List.of(), "", "", "", "",
                List.of());
    }

    private static LogbookTask dated(final int number, final String started, final String by) {
        return new LogbookTask(number, "Task " + number, "t", "", "Open.", "", List.of(), List.of(), "", started, by,
                "", List.of());
    }

    /// A task that waits on no one, with two tickets and no documents.
    private static LogbookTask ticketed() {
        return new LogbookTask(9, "Tickets", "release", "", "Open.", "", List.of(), List.of(), "", "", "", "",
                List.of("EDG-1", "EDG-2"));
    }

    /// Session alpha runs; beta is closed.
    private static AppState state(final Logbook l) {
        final SessionState alpha = SessionState.closed(SessionSpec.fresh("alpha", "/w/alpha"))
                .withStatus(Status.OPEN, "");
        final SessionState beta = SessionState.closed(SessionSpec.fresh("beta", "/w/beta"));
        return States.twoPanels().withSessions(List.of(alpha, beta)).withLogbook(l);
    }

    private static List<Integer> numbers(final ViewSpec.TreeNode group) {
        return group.children().stream().map(n -> Integer.parseInt(n.link().substring(LogbookTab.TASK_LINK.length())))
                .toList();
    }

    private static JTree tree(final LogbookTab tab) {
        final JTree tree = find(tab.component(), JTree.class);
        tree.setSize(300, 400);
        return tree;
    }

    /// The labels of the rows shown, top to bottom.
    private static List<String> rows(final JTree tree) {
        final List<String> labels = new ArrayList<>();
        for (int i = 0; i < tree.getRowCount(); i++) {
            labels.add(label(tree.getPathForRow(i)));
        }
        return labels;
    }

    private static String label(final TreePath path) {
        return ((ViewSpec.TreeNode) ((DefaultMutableTreeNode) path.getLastPathComponent()).getUserObject()).label();
    }

    /// `clicks` left clicks on row `row`, a few pixels into its text.
    private static void click(final JTree tree, final int row, final int clicks) {
        final Rectangle r = tree.getRowBounds(row);
        tree.dispatchEvent(new MouseEvent(tree, MouseEvent.MOUSE_CLICKED, System.currentTimeMillis(), 0, r.x + 4,
                r.y + r.height / 2, clicks, false, MouseEvent.BUTTON1));
    }

    private static List<String> items(final JPopupMenu menu) {
        return Arrays.stream(menu.getComponents()).filter(JMenuItem.class::isInstance)
                .map(c -> ((JMenuItem) c).getText()).toList();
    }

    private static JMenuItem item(final JPopupMenu menu, final String text) {
        return Arrays.stream(menu.getComponents()).filter(JMenuItem.class::isInstance).map(JMenuItem.class::cast)
                .filter(i -> text.equals(i.getText())).findFirst().orElseThrow();
    }

    private static void collect(final Component[] components, final List<JMenuItem> into) {
        for (final Component c : components) {
            if (c instanceof JMenuItem i) {
                into.add(i);
            }
            if (c instanceof JMenu m) {
                collect(m.getMenuComponents(), into);
            }
        }
    }

    private static JMenu submenu(final JPopupMenu menu, final String text) {
        for (final Component c : menu.getComponents()) {
            if (c instanceof JMenu m && text.equals(m.getText())) {
                return m;
            }
        }
        return null;
    }

    private static List<String> texts(final JMenu menu) {
        return Arrays.stream(menu.getMenuComponents()).map(c -> ((JMenuItem) c).getText()).toList();
    }

    private static <T extends Component> T find(final Component c, final Class<T> type) {
        if (type.isInstance(c)) {
            return type.cast(c);
        }
        if (c instanceof Container k) {
            for (final Component child : k.getComponents()) {
                final T found = find(child, type);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private LogbookTab tab() {
        return new LogbookTab(intents::add, opened::add, () -> cards);
    }

    /// A card as [SessionsSidebar.Cards#showFacts] was asked for it.
    private record Shown(String key, String title, List<Map.Entry<String, String>> facts, String heading, String text,
                         JComponent over) {
    }

    /// The sidebar's cards on the site `acme`, recording each card of facts asked for; a card shows once asked for.
    private static final class Recording implements SessionsSidebar.Cards {
        private final List<Shown> shown = new ArrayList<>();

        @Override
        public void show(final String session, final JComponent over, final Rectangle row) {
        }

        @Override
        public LinearTickets.Site linearSite() {
            return SITE;
        }

        @Override
        public void showFacts(final String key, final String title, final List<Map.Entry<String, String>> facts,
                              final String heading, final String text, final JComponent over, final Rectangle row,
                              final int beside) {
            shown.add(new Shown(key, title, facts, heading, text, over));
        }

        @Override
        public boolean showing(final String key) {
            return shown.stream().anyMatch(s -> s.key().equals(key));
        }

        @Override
        public void left() {
        }
    }
}
