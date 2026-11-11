package dev.marregui.comando.ui.panels;

import java.awt.Rectangle;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;
import javax.swing.Icon;
import javax.swing.JComponent;
import javax.swing.JMenu;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JTree;
import javax.swing.SwingUtilities;
import javax.swing.ToolTipManager;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.TreePath;
import javax.swing.tree.TreeSelectionModel;

import dev.marregui.comando.core.intent.Intent;
import dev.marregui.comando.core.logbook.Logbook;
import dev.marregui.comando.core.logbook.LogbookTask;
import dev.marregui.comando.core.model.LinearTickets;
import dev.marregui.comando.core.model.PanelId;
import dev.marregui.comando.core.port.IntentSink;
import dev.marregui.comando.core.state.AppState;
import dev.marregui.comando.core.state.SessionState;
import dev.marregui.comando.core.view.Placement;
import dev.marregui.comando.core.view.ViewSpec;
import dev.marregui.comando.ui.kit.desktop.Links;
import dev.marregui.comando.ui.kit.icon.Icons;
import dev.marregui.comando.ui.kit.text.Plain;
import dev.marregui.comando.ui.kit.theme.Theme;
import dev.marregui.comando.ui.kit.tree.TreeView;

/**
 * The sidebar's Logbook tab: the scratchpad's open tasks, as {@link AppState#logbook()} read them from the newest day
 * folder's {@code INDEX.md}, which comando only reads, in the tree every widget uses with the Files tab's look. The
 * tasks that wait on the user come first, under Needs you, then every open task under its topic in the index's order,
 * so a task that waits on the user is listed twice; within a group the newest task, the highest number, leads. A row
 * is the task's name, its number and start day muted after it, and who asked for it when that is not the logbook's
 * owner. A click shows the task's card, which then follows the pointer, as the Instances tab's rows do; a double
 * click or ⏎ shows its page; its menu opens its tickets in Linear, has a running session resume it or ask the user
 * its decisions, the prompt going in through the session's own panel as the user's, and opens its documents as the
 * Files tab opens a file. No bar of buttons (record 74).
 */
final class LogbookTab {

    static final String NEEDS_YOU = "Needs you";
    /** The link of a group's row: Needs you's, or the topic's after {@link #TOPIC_LINK}. */
    static final String NEEDS_YOU_LINK = "comando-logbook-group:needs-you";
    /** The heading of the tasks the index lists under no topic. */
    static final String NO_TOPIC = "Without a topic";
    /** The link of a task's row, its number after it. */
    static final String TASK_LINK = "comando-logbook-task:";
    static final String TOPIC_LINK = "comando-logbook-group:topic:";
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH);
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH);
    private static final Comparator<LogbookTask> NEWEST_FIRST =
            Comparator.comparingInt(LogbookTask::number).reversed();

    /** The sidebar's cards, shown in place of a row's tooltip. */
    private final Supplier<SessionsSidebar.Cards> cards;
    /**
     * A row's card: after a click, as in the Instances tab, it follows the pointer onto the other rows, each row's card
     * after a rest of {@link SessionsSidebar#FOLLOW_MILLIS}, until it closes otherwise.
     */
    private final CardFollower<Row> follower;
    private final IntentSink intents;
    /** Opens a file in a panel, as a double click in the Files tab does. */
    private final Consumer<Path> open;
    private final TreeView view = new TreeView();
    /** {@link #shown}'s tasks by number. */
    private Map<Integer, LogbookTask> numbered = Map.of();
    /** The logbook the rows show; {@link Logbook#NONE} before the first render. */
    private Logbook shown = Logbook.NONE;
    private AppState state;

    LogbookTab(final IntentSink intents, final Consumer<Path> open, final Supplier<SessionsSidebar.Cards> cards) {
        this.intents = intents;
        this.open = open;
        this.cards = cards;
        final JTree tree = view.tree();
        tree.setRootVisible(false);
        tree.getSelectionModel().setSelectionMode(TreeSelectionModel.SINGLE_TREE_SELECTION);
        tree.getAccessibleContext().setAccessibleName("Logbook");
        view.icons(this::icon);
        view.colors(n -> n.getUserObject() instanceof ViewSpec.TreeNode node && node.link().isEmpty()
                ? Theme.MUTED_STRONG : null);
        view.toggleOnClick(true);
        view.sidebar(LogbookTab::header);
        view.spaceAbove();
        view.onActivate(n -> {
            final LogbookTask t = task(n);
            if (t != null) {
                page(t);
            }
        });
        view.onMenu((n, e) -> {
            final LogbookTask t = task(n);
            if (t != null) {
                menu(t).show(e.getComponent(), e.getX(), e.getY());
            }
        });
        // a row tells its task on its card, not in a tooltip: the tree's own, and a detail cut short, are not shown
        ToolTipManager.sharedInstance().unregisterComponent(tree);
        follower = new CardFollower<>(tree, cards, this::rowAt, LogbookTab::key, LogbookTab::same, r -> r);
        tree.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(final MouseEvent e) {
                // a click shows the row's card, which then follows the pointer; a double click shows the page
                if (e.getClickCount() == 1 && SwingUtilities.isLeftMouseButton(e)) {
                    final Row r = rowUnder(e.getX(), e.getY());
                    if (r != null) {
                        follower.clicked(r);
                    }
                }
            }
        });
        if (view.component() instanceof JScrollPane sp) {
            // as the Files tab: a name wider than the tab scrolls into view rather than being cut at the edge
            sp.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        }
        view.setRoot(root(Logbook.NONE), 2);
    }

    /**
     * The tree of {@code l}: a group per Needs you, when a task waits on the user, and per topic in the index's order,
     * each with its tasks newest first; a logbook with no task to show, one row saying why.
     */
    static ViewSpec.TreeNode root(final Logbook l) {
        final String why = why(l);
        if (!why.isEmpty()) {
            return new ViewSpec.TreeNode("", "", List.of(new ViewSpec.TreeNode(why, "", List.of())));
        }
        final String owner = owner(l);
        final List<ViewSpec.TreeNode> groups = new ArrayList<>();
        final List<LogbookTask> waiting = l.needingUser();
        if (!waiting.isEmpty()) {
            groups.add(group(NEEDS_YOU + " (" + waiting.size() + ")", NEEDS_YOU_LINK, waiting, owner));
        }
        final Map<String, List<LogbookTask>> byTopic = new LinkedHashMap<>();
        l.tasks().forEach(t -> byTopic.computeIfAbsent(t.topic(), _ -> new ArrayList<>()).add(t));
        byTopic.forEach((topic, tasks) ->
                groups.add(group(topic.isEmpty() ? NO_TOPIC : topic, TOPIC_LINK + topic, tasks, owner)));
        return new ViewSpec.TreeNode("", "", "", groups);
    }

    /**
     * What a task's row shows muted after its name: its number, its start day when it has one, and who asked for it
     * when that is not {@code owner}.
     */
    static String detail(final LogbookTask t, final String owner) {
        final StringBuilder d = new StringBuilder().append(t.number());
        final LocalDate started = day(t.started());
        if (started != null) {
            d.append(" · ").append(DAY.format(started));
        }
        if (!t.by().isEmpty() && !t.by().equals(owner)) {
            d.append(" · ").append(t.by());
        }
        return d.toString();
    }

    /** Who asked for most of {@code l}'s tasks, the first of them on a tie; empty when no task names anyone. */
    static String owner(final Logbook l) {
        final Map<String, Integer> counts = new LinkedHashMap<>();
        l.tasks().forEach(t -> {
            if (!t.by().isEmpty()) {
                counts.merge(t.by(), 1, Integer::sum);
            }
        });
        String owner = "";
        int most = 0;
        for (final Map.Entry<String, Integer> e : counts.entrySet()) {
            if (e.getValue() > most) {
                owner = e.getKey();
                most = e.getValue();
            }
        }
        return owner;
    }

    /** What a task's card lists, as label and value, each only when it has one. */
    static List<Map.Entry<String, String>> facts(final LogbookTask t) {
        final List<Map.Entry<String, String>> f = new ArrayList<>();
        final LocalDate started = day(t.started());
        if (started != null) {
            f.add(Map.entry("Started", DATE.format(started)));
        }
        fact(f, "By", t.by());
        fact(f, "From", t.from());
        fact(f, "Topic", t.topic());
        fact(f, "Ticket", String.join(", ", t.tickets()));
        fact(f, "State", firstSentence(t.state()));
        fact(f, "Next", firstSentence(t.next()));
        if (!t.needsYou().isEmpty()) {
            f.add(Map.entry(NEEDS_YOU, String.valueOf(t.needsYou().size())));
        }
        return f;
    }

    /** The tab header's tooltip after its name: what the tab holds and how many tasks wait on the user. */
    static String tip(final Logbook l) {
        final int n = l.needingUser().size();
        return "the scratchpad's open tasks; " + n + (n == 1 ? " needs you" : " need you");
    }

    /** The text up to the first full stop followed by a space, or all of it when it has none. */
    static String firstSentence(final String text) {
        for (int i = 0; i < text.length() - 1; i++) {
            if (text.charAt(i) == '.' && Character.isWhitespace(text.charAt(i + 1))) {
                return text.substring(0, i + 1);
            }
        }
        return text;
    }

    static String resumePrompt(final int task) {
        return "Resume task " + task + " from the scratchpad.";
    }

    static String askPrompt(final int task) {
        return "Task " + task + " holds decisions for me (its Needs you line or its State). Ask them now as question "
                + "cards, recommended option first, then write the answers into the task.";
    }

    /**
     * {@code document}, as a task's Documents line writes it, against the folder of {@code l}'s index; empty when the
     * logbook names no index or the text is no path.
     */
    static List<Path> resolved(final Logbook l, final String document) {
        final Path folder = dayFolder(l);
        if (folder == null) {
            return List.of();
        }
        try {
            return List.of(folder.resolve(document).normalize());
        } catch (final InvalidPathException _) {
            return List.of();
        }
    }

    JComponent component() {
        return view.component();
    }

    /**
     * Shows {@code s}'s logbook; a new one rebuilds the rows, keeping the selected task selected by its number and
     * the groups the user closed closed.
     */
    void render(final AppState s) {
        state = s;
        final Logbook l = s.logbook();
        if (l.equals(shown)) {
            return;
        }
        final Row selected = selectedRow();
        final Set<String> closed = closedGroups();
        final JTree tree = view.tree();
        final Rectangle seen = tree.getVisibleRect();
        shown = l;
        final Map<Integer, LogbookTask> byNumber = new HashMap<>();
        l.tasks().forEach(t -> byNumber.putIfAbsent(t.number(), t));
        numbered = byNumber;
        view.setRoot(root(l), 2);
        final DefaultMutableTreeNode top = (DefaultMutableTreeNode) tree.getModel().getRoot();
        for (int i = 0; i < top.getChildCount(); i++) {
            final DefaultMutableTreeNode g = (DefaultMutableTreeNode) top.getChildAt(i);
            if (g.getUserObject() instanceof ViewSpec.TreeNode node && closed.contains(node.link())) {
                tree.collapsePath(new TreePath(g.getPath()));
            }
        }
        reselect(selected);
        tree.scrollRectToVisible(seen);
        follower.refresh(this::now);
    }

    /**
     * What a task's row offers on a right click: its page; each of its tickets in Linear, once the window knows
     * Linear's workspace; Resume in, and Ask me its decisions in for a task that waits on the user, each over the
     * running sessions; Open document over the task's documents, when it names any.
     */
    JPopupMenu menu(final LogbookTask t) {
        final JPopupMenu m = new JPopupMenu();
        m.add(item("Show the task", "markdown", () -> page(t)));
        final LinearTickets.Site site = cards.get().linearSite();
        if (!site.workspace().isEmpty()) {
            for (final String id : t.tickets()) {
                final String url = LinearTickets.url(site, id);
                m.add(item(Plain.text("Open " + id + " in Linear"), "browser", () -> Links.open(url, null)));
            }
        }
        final List<String> running = state == null ? List.of()
                : state.sessions().stream().filter(x -> x.status().isUsable()).map(SessionState::name).toList();
        m.add(sessions("Resume in", "play", running, resumePrompt(t.number())));
        if (t.isWaitingOnUser()) {
            m.add(sessions("Ask me its decisions in", "question", running, askPrompt(t.number())));
        }
        final JMenu documents = submenu("Open document", "open-file");
        for (final String d : t.documents()) {
            for (final Path p : resolved(shown, d)) {
                documents.add(item(Plain.text(d), "file", () -> open.accept(p)));
            }
        }
        if (documents.getItemCount() > 0) {
            m.add(documents);
        }
        return m;
    }

    /**
     * The task's page, as a Markdown view of the lower panel: its section of the index, its tickets linked to Linear
     * and its documents to their files in the day folder.
     */
    void page(final LogbookTask t) {
        final String markdown = TaskPages.markdown(t, dayFolder(shown), cards.get().linearSite());
        intents.dispatch(
                new Intent.ShowView("", "Task " + t.number(), new ViewSpec.Markdown(markdown), Placement.LOWER));
    }

    /** The folder that holds {@code l}'s index; null when it names none. */
    private static Path dayFolder(final Logbook l) {
        if (l.index().isEmpty()) {
            return null;
        }
        try {
            return Path.of(l.index()).getParent();
        } catch (final InvalidPathException _) {
            return null;
        }
    }

    /** Why {@code l} lists no task, for its one row; empty when it lists some. */
    private static String why(final Logbook l) {
        if (!l.problem().isEmpty()) {
            return l.problem();
        }
        if (l.index().isEmpty()) {
            return "No scratchpad day folder yet";
        }
        return l.tasks().isEmpty() ? "No open tasks in the scratchpad" : "";
    }

    private static ViewSpec.TreeNode group(final String label, final String link, final List<LogbookTask> tasks,
                                           final String owner) {
        final List<ViewSpec.TreeNode> rows = tasks.stream().sorted(NEWEST_FIRST)
                .map(t -> new ViewSpec.TreeNode(t.name(), detail(t, owner), TASK_LINK + t.number(), List.of()))
                .toList();
        return new ViewSpec.TreeNode(label, "", link, rows);
    }

    /** {@code started}, {@code YYYY-MM-DD}, as a day; null when it is empty or no date. */
    private static LocalDate day(final String started) {
        if (started.isEmpty()) {
            return null;
        }
        try {
            return LocalDate.parse(started);
        } catch (final DateTimeParseException _) {
            return null;
        }
    }

    private static void fact(final List<Map.Entry<String, String>> facts, final String label, final String value) {
        if (!value.isEmpty()) {
            facts.add(Map.entry(label, value));
        }
    }

    /** A group after the first opens with the Files tab's separator, a hairline alone; nothing else has a header. */
    private static String header(final DefaultMutableTreeNode n) {
        return n.getLevel() == 1 && n.getParent() != null && n.getParent().getIndex(n) > 0 ? "" : null;
    }

    private static String key(final Row r) {
        return "logbook:" + r.task().number();
    }

    private static boolean same(final Row a, final Row b) {
        return b != null && a.waiting() == b.waiting() && a.task().number() == b.task().number();
    }

    private static JMenuItem item(final String text, final String icon, final Runnable r) {
        final JMenuItem i = new JMenuItem(text, Icons.get(icon));
        i.addActionListener(_ -> r.run());
        return i;
    }

    private static JMenu submenu(final String text, final String icon) {
        final JMenu m = new JMenu(text);
        m.setIcon(Icons.get(icon));
        return m;
    }

    private Icon icon(final ViewSpec.TreeNode n) {
        final LogbookTask t = task(n);
        if (t != null) {
            return Icons.get(t.isWaitingOnUser() ? "question" : "logbook");
        }
        // a group's row is its name alone, as the Files tab's groups; the row saying why there is no task, a problem's
        // warning
        return n.link().isEmpty() && !shown.problem().isEmpty() ? Icons.get("warning") : TreeView.NO_ICON;
    }

    /** The task of a task's row; null for any other row. */
    private LogbookTask task(final ViewSpec.TreeNode n) {
        return n.link().startsWith(TASK_LINK) ? numbered.get(Integer.parseInt(n.link().substring(TASK_LINK.length())))
                : null;
    }

    /** The task of the row at {@code path} and whether it is under Needs you; null for any other row. */
    private Row rowAt(final TreePath path) {
        if (path == null || !(path.getLastPathComponent() instanceof DefaultMutableTreeNode n)
                || !(n.getUserObject() instanceof ViewSpec.TreeNode node)) {
            return null;
        }
        final LogbookTask t = task(node);
        if (t == null) {
            return null;
        }
        final boolean waiting = n.getParent() instanceof DefaultMutableTreeNode g
                && g.getUserObject() instanceof ViewSpec.TreeNode group && group.link().equals(NEEDS_YOU_LINK);
        return new Row(waiting, t);
    }

    /** The row under {@code (x, y)}, its icon or text, or null; the selection stays. */
    private Row rowUnder(final int x, final int y) {
        return rowAt(view.tree().getPathForLocation(x, y));
    }

    /** {@code r} with its task as the logbook says now; null when the task is gone. */
    private Row now(final Row r) {
        final LogbookTask t = numbered.get(r.task().number());
        return t == null ? null : new Row(r.waiting(), t);
    }

    private Row selectedRow() {
        return rowAt(view.tree().getSelectionPath());
    }

    /** The links of the groups shown closed. */
    private Set<String> closedGroups() {
        final Set<String> closed = new HashSet<>();
        final JTree tree = view.tree();
        final DefaultMutableTreeNode top = (DefaultMutableTreeNode) tree.getModel().getRoot();
        for (int i = 0; i < top.getChildCount(); i++) {
            final DefaultMutableTreeNode g = (DefaultMutableTreeNode) top.getChildAt(i);
            if (g.getUserObject() instanceof ViewSpec.TreeNode node && !g.isLeaf()
                    && !tree.isExpanded(new TreePath(g.getPath()))) {
                closed.add(node.link());
            }
        }
        return closed;
    }

    /**
     * {@code was}'s task selected again: in the same group, else wherever it is shown; no row when it is gone or its
     * groups are closed.
     */
    private void reselect(final Row was) {
        final JTree tree = view.tree();
        if (was == null) {
            tree.clearSelection();
            return;
        }
        TreePath elsewhere = null;
        for (int i = 0; i < tree.getRowCount(); i++) {
            final TreePath p = tree.getPathForRow(i);
            final Row r = rowAt(p);
            if (r != null && r.task().number() == was.task().number()) {
                if (r.waiting() == was.waiting()) {
                    tree.setSelectionPath(p);
                    return;
                }
                elsewhere = elsewhere == null ? p : elsewhere;
            }
        }
        if (elsewhere != null) {
            tree.setSelectionPath(elsewhere);
        } else {
            tree.clearSelection();
        }
    }

    /** A submenu with one item per running session, each sending {@code prompt}; disabled while none runs. */
    private JMenu sessions(final String text, final String icon, final List<String> running, final String prompt) {
        final JMenu m = submenu(text, icon);
        running.forEach(n -> m.add(item(Plain.text(n), "session", () -> prompt(n, prompt))));
        m.setEnabled(!running.isEmpty());
        return m;
    }

    /**
     * {@code text} to {@code session} as the user's own prompt (no sender): the session's own panel opened, or brought
     * forward, first, as a double click on its row does, so the prompt is seen going in.
     */
    private void prompt(final String session, final String text) {
        intents.dispatch(new Intent.OpenSession(session, state.focused(), PanelId.random()));
        intents.dispatch(new Intent.PromptSession(session, text, ""));
    }

    /**
     * A task's row: {@code task} under Needs you when {@code waiting}, else under its topic.
     *
     * @param waiting whether the row is in the Needs you group
     * @param task    the task
     */
    record Row(boolean waiting, LogbookTask task) implements CardFollower.Card {

        @Override
        public String title() {
            return "Task " + task.number();
        }

        @Override
        public List<Map.Entry<String, String>> facts() {
            return LogbookTab.facts(task);
        }

        @Override
        public String heading() {
            return task.name();
        }

        @Override
        public String text() {
            return task.ask();
        }
    }
}
