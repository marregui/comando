package dev.marregui.comando.ui.widgets.transcript.render;

import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import javax.swing.AbstractAction;
import javax.swing.JComponent;
import javax.swing.JViewport;
import javax.swing.KeyStroke;
import javax.swing.Scrollable;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.ToolTipManager;

import dev.marregui.comando.core.model.Approval;
import dev.marregui.comando.core.state.BackgroundTask;
import dev.marregui.comando.core.transcript.Block;
import dev.marregui.comando.ui.kit.async.ShownTimer;
import dev.marregui.comando.ui.kit.desktop.Platform;
import dev.marregui.comando.ui.kit.theme.Theme;

/**
 * The transcript painted directly: one {@link Item} per block, the whole conversation. Only a window of pixels around
 * the view is laid out, once per width and style and again when its block changes; an item far from the view lets its
 * layout go and keeps its height. Painting visits only the items that intersect the clip. Find, select-all and copy
 * read the blocks' text and lay out nothing ({@link Item#findTexts}). Text can
 * be selected across blocks with the mouse and copied; links open through a callback; tool cards, thinking,
 * notes and hook groups open and close with a click on their header. A turn that ended with a report folds what it did
 * before the report to one row, which opens and closes the same way.
 */
public final class TranscriptCanvas extends JComponent implements Scrollable, Marks {

    /** The scheme of a sub-agent card's header link to its stored conversation: {@code subagent:<toolUseId>}. */
    public static final String SUBAGENT_SCHEME = "subagent:";
    /** How long a sub-agent launched into the background counts as working before the session lists it. */
    static final long LAUNCH_GRACE_MILLIS = 10_000;

    private static final int PAD_X = Theme.PAD * 2;
    private static final int PAD_Y = Theme.PAD;
    private static final int[] NONE = {0, 0};
    /**
     * How close to the end, in pixels, the view still counts as showing it and follows new blocks. Set by hand with
     * the first painted transcript, not measured: it is more than one scroll step (24 px, {@link
     * #getScrollableUnitIncrement}), so a view a step short of the end still follows. A test that scrolls a step and
     * a line short of the end would settle it.
     */
    private static final int END_SLACK_PX = 40;
    /** The view shows the end, which follows new blocks, or nothing yet: no block to keep in place. */
    private static final Anchor NO_ANCHOR = new Anchor(null, -1, 0, -1, 0, 0);
    /**
     * The window laid out around the view, in pixels: the idle pass lays out the items within this distance above and
     * below it, at least this many view heights; items farther than twice that let their layout go. The conversation
     * is whole, and what it costs follows what is near the view, not its length.
     */
    private static final int BAND_MIN = 1600;
    private static final int BAND_VIEWS = 2;

    private final List<Item> items = new ArrayList<>();
    /**
     * The item of each block, by transcript index; a gap holds null. A list, not a map by boxed index: the widget asks
     * for every block on each render, and boxing an index past 127 allocated per block per render (run 8, 2026-11-03:
     * up to 766 MB an hour, a quarter of comando's allocation, growing with the transcript).
     */
    private final List<Item> byIndex = new ArrayList<>();
    /** How many indexes hold an item. */
    private int held;
    /** The card, hook group or turn's steps each nested item sits in. */
    private final Map<Item, Item> parentOf = new IdentityHashMap<>();
    /** The turns' steps held open by a call that still works: each closes when its last one ends. */
    private final List<Items.StepsItem> heldOpen = new ArrayList<>();
    /** Repaints running cards' elapsed time once a second while one runs and the canvas is on screen. */
    private final ShownTimer ticker = new ShownTimer(this, 1000, this::tick);
    private LongSupplier clock = System::currentTimeMillis;
    /** The cards by id, what they run, and the approvals that hold them up. */
    private final AgentTracker agents =
            new AgentTracker(this::nowMillis, parentOf::get, this::runningChanged, this::repaint);
    private final Consumer<String> links;
    private int[] tops = new int[0];
    /** Heights used for positions: exact, or estimated while {@link #estimated}. */
    private int[] heights = new int[0];
    private boolean estimated;
    /**
     * Where the view was when the visible pass grew the content while the view showed the end, -1 when not: the idle
     * pass puts the view back at the end, as long as the view is still there (a scroll away gives the end up).
     */
    private int endKeptAt = -1;
    /**
     * How long a slice of layout may hold the event thread: 8 ms, the slice the idle pass ({@link #refine}) uses.
     * Unmeasured as a bound of its own; the gate's frames (`TranscriptGate`, 16 ms p99) are what it must keep.
     */
    private static final long SLICE_NANOS = 8_000_000L;
    private final Timer refiner = new Timer(1, e -> refine());
    private int total;
    /** Counts changes of blocks, folds and style: a find's matches counted before the last one are counted again. */
    private long version;
    private int laidWidth = -1;
    private boolean structureChanged = true;
    private Style style;
    /** Whether {@link #style} is to be made again for the zoom and tint before it is read. */
    private boolean styleStale = true;
    private int zoom;

    // selection and find work on the boxes in reading order, rebuilt lazily
    private final List<Placed> placed = new ArrayList<>();
    /** Whether {@link #placed} holds the boxes as laid out now; when not, it is placed again before it is read. */
    private boolean placedNow;
    /** Where each top-level item's boxes start in {@link #placed}; the last entry, one past the items, is its size. */
    private int[] firstBox = {0};
    /** The top-level item being painted, -1 outside a paint: the item whose boxes {@link #selection} is asked about. */
    private int painting = -1;
    /** One past the box {@link #indexOf} last found: boxes are painted in the order they were placed. */
    private int nextBox;
    private TextBox anchorBox;
    /** Where {@link #anchorBox} is in {@link #placed}, -1 when nothing is selected or until it is placed again. */
    private int anchorIndex = -1;
    private int anchorChar;
    private TextBox focusBox;
    /** Where {@link #focusBox} is in {@link #placed}, -1 when nothing is selected or until it is placed again. */
    private int focusIndex = -1;
    private int focusChar;
    /**
     * Select-all: every box is selected, laid out or not, so nothing is laid out for it; its text is read from the
     * blocks. A click that starts another selection ends it.
     */
    private boolean allSelected;
    private String pressedLink;
    /** The clicks of the press on {@link #pressedLink}: a double click follows a link, as everywhere in comando. */
    private int pressedClicks;
    /** Find: its matches, the current one, and the items it opened. */
    private final Finder finder = new Finder(new FindHost());

    public TranscriptCanvas(final Consumer<String> links) {
        this.links = links;
        setFocusable(true);
        setOpaque(false);
        final MouseAdapter mouse = new MouseAdapter() {
            @Override
            public void mousePressed(final MouseEvent e) {
                pressed(e);
            }

            @Override
            public void mouseDragged(final MouseEvent e) {
                dragged(e);
            }

            @Override
            public void mouseReleased(final MouseEvent e) {
                released(e);
            }

            @Override
            public void mouseMoved(final MouseEvent e) {
                moved(e);
            }
        };
        addMouseListener(mouse);
        addMouseMotionListener(mouse);
        // no text of its own: getToolTipText(MouseEvent) answers at each point, null where nothing has a tooltip
        ToolTipManager.sharedInstance().registerComponent(this);
        final int menu = Platform.menuShortcutMask();
        getInputMap(WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_C, menu), "copy-selection");
        getActionMap().put("copy-selection", new AbstractAction() {
            @Override
            public void actionPerformed(final ActionEvent e) {
                final String s = selectionText();
                if (!s.isEmpty()) {
                    Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(s), null);
                }
            }
        });
        getInputMap(WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_A, menu), "select-all");
        getActionMap().put("select-all", new AbstractAction() {
            @Override
            public void actionPerformed(final ActionEvent e) {
                selectAll();
            }
        });
    }

    // ---- content

    public void clear() {
        refiner.stop();
        estimated = false;
        endKeptAt = -1;
        items.clear();
        byIndex.clear();
        held = 0;
        agents.clear();
        parentOf.clear();
        heldOpen.clear();
        // the places of the blocks that left: nothing appended from now on reads them as its own
        tops = new int[0];
        heights = new int[0];
        updateTicker();
        clearSelection();
        // another session's blocks: the next find, even with the text the find bar kept, is a new one that opens the
        // closed cards holding it
        finder.reset();
        structureChanged = true;
        changed();
    }

    /**
     * Adds the block at transcript index {@code index}; a sub-agent's block goes inside its call's card, and a hook
     * note right after another joins its group.
     */
    public void append(final int index, final Block b) {
        final Item item = switch (b) {
            case Block.ToolCall call -> agents.newCard(call, index);
            case Block.Note n when n.kind() == Block.NoteKind.HOOK -> new Items.NoteItem(n, true);
            case Block.Note _, Block.UserPrompt _, Block.AssistantText _, Block.Thinking _, Block.TurnEnd _ ->
                    Items.of(b);
        };
        hold(index, item);
        if (item instanceof Items.ToolItem card) {
            agents.added(card);
        }
        final Items.ToolItem parent = b.parent().isEmpty() ? null : agents.card(b.parent());
        final List<Item> siblings = parent != null ? parent.children() : items;
        Item shown = item;
        if (item instanceof Items.NoteItem hook && hook.isHook()) {
            if (!siblings.isEmpty() && siblings.getLast() instanceof Items.HookGroupItem group) {
                group.add(hook);
                parentOf.put(hook, group);
                invalidateAncestors(parentOf.get(group));
                structureChanged = true;
                changed();
                return;
            }
            final Items.HookGroupItem group = new Items.HookGroupItem(hook);
            parentOf.put(hook, group);
            shown = group;
        }
        // thinking says only that something takes time: it shows until the next item in its list takes its place
        while (!(shown instanceof Items.ThinkingItem) && !siblings.isEmpty() && siblings.getLast() instanceof Items.ThinkingItem thought) {
            if (parent != null) {
                parent.removeLastChild();
            } else {
                items.removeLast();
            }
            parentOf.remove(thought);
            if (selects(thought)) {
                clearSelection();
            }
            thought.discard(); // it stays in byIndex for its block; its layout goes with it
        }
        if (parent != null) {
            parent.addChild(shown);
            parentOf.put(shown, parent);
            invalidateAncestors(parent);
            if (item instanceof Items.ToolItem card) {
                agents.nested(parent, card);
            }
        } else {
            items.add(shown);
            if (shown instanceof Items.TurnItem turn) {
                final int report = frameReport();
                if (report >= 0 && !turn.cutShort()) {
                    foldSteps(report, turn.tookMillis());
                }
            }
        }
        agents.appended();
        structureChanged = true;
        changed();
    }

    /**
     * The turn just ended: its last text, when nothing but notes, hooks or thinking came after it, is its report and
     * takes the report's frame. A turn that ended on a tool call or a prompt has none. Returns the report's place, -1
     * when there is none.
     */
    private int frameReport() {
        for (int i = items.size() - 2; i >= 0; i--) {
            final Item item = items.get(i);
            if (item instanceof Items.TextItem text) {
                text.report(true);
                return i;
            }
            if (!(item instanceof Items.NoteItem || item instanceof Items.HookGroupItem || item instanceof Items.ThinkingItem)) {
                return -1;
            }
        }
        return -1;
    }

    /**
     * What the turn did before its report goes on one row: the items since its prompt, or since the turn before when
     * it has none here, once a call or a text is among them, as a row of hook notes is no shorter folded. A turn that
     * failed or was stopped has no conclusion to stand for its steps, and does not get here. The row stays open
     * while a call of the turn works, and where the reader is: on those items with the view away from the end, or with
     * the selection in or across them. Prompts stay at the top level, so going from prompt to prompt is unchanged.
     */
    private void foldSteps(final int report, final long tookMillis) {
        int from = report;
        boolean steps = false;
        while (from > 0 && !(items.get(from - 1) instanceof Items.PromptItem
                || items.get(from - 1) instanceof Items.TurnItem)) {
            from--;
            steps |= items.get(from) instanceof Items.ToolItem || items.get(from) instanceof Items.TextItem;
        }
        if (!steps) {
            return;
        }
        final boolean readerThere = readerOn(from, report) || selectionMeets(from, report);
        final List<Item> run = items.subList(from, report);
        final Items.StepsItem fold = new Items.StepsItem(List.copyOf(run), tookMillis);
        final boolean working = fold.working();
        fold.fold().follow(working || readerThere);
        run.clear();
        items.add(from, fold);
        final List<Item> children = fold.children();
        for (int k = 0, n = children.size(); k < n; k++) {
            parentOf.put(children.get(k), fold);
        }
        spliceTops(from, report, fold);
        if (working) {
            heldOpen.add(fold);
        }
        if (!fold.isOpen()) {
            fold.dropSteps();
        }
        finder.folded(fold);
    }

    /**
     * The items from {@code from} up to {@code to} became {@code fold}: its place is the span they had, and what
     * follows keeps its own, so the next layout's {@link Anchor} reads the same content at the same place and an item
     * below the fold stays where it is on screen. An open fold is placed by the step at the top of the view, whose
     * exact place in the fold may differ from the estimates above it: that step does not move.
     */
    private void spliceTops(final int from, final int to, final Items.StepsItem fold) {
        if (from >= tops.length) {
            return;
        }
        final int end = Math.min(to, tops.length);
        final int rest = tops.length - end;
        final int bottom = tops[end - 1] + heights[end - 1];
        final Rectangle vis = getVisibleRect();
        int step = from;
        while (step < end - 1 && tops[step] + heights[step] <= vis.y) {
            step++;
        }
        final int stepTop = tops[step];
        final int foldTop = tops[from];
        heights[from] = bottom - foldTop;
        System.arraycopy(tops, end, tops, from + 1, rest);
        System.arraycopy(heights, end, heights, from + 1, rest);
        tops = Arrays.copyOf(tops, from + 1 + rest);
        heights = Arrays.copyOf(heights, from + 1 + rest);
        if (fold.isOpen()) {
            fold.layoutTo(laidWidth, style(), vis.y + vis.height - foldTop, System.nanoTime() + SLICE_NANOS);
            tops[from] = stepTop - fold.topOf(fold.children().get(step - from));
            heights[from] = bottom - tops[from];
        }
    }

    /** True when the view shows some of the items from {@code from} up to {@code to} and is away from the end. */
    private boolean readerOn(final int from, final int to) {
        final int known = Math.min(to, tops.length);
        if (!(getParent() instanceof JViewport) || laidWidth < 0 || from >= known) {
            return false;
        }
        final Rectangle vis = getVisibleRect();
        return vis.height > 0 && !showsEnd(vis) && vis.y < tops[known - 1] + heights[known - 1]
                && vis.y + vis.height > tops[from];
    }

    /** True when the selection starts or ends in the items from {@code from} up to {@code to}, or runs across them. */
    private boolean selectionMeets(final int from, final int to) {
        if (allSelected) {
            return true;
        }
        if (anchorBox == null && focusBox == null) {
            return false;
        }
        boolean before = false;
        boolean after = false;
        for (int i = 0; i < items.size(); i++) {
            if (selects(items.get(i))) {
                if (i >= from && i < to) {
                    return true;
                }
                before |= i < from;
                after |= i >= to;
            }
        }
        return before && after;
    }

    /** The calls of folds held open may have ended: the folds with none left close, unless the reader is on them. */
    private void closeHeld() {
        boolean closed = false;
        for (int k = heldOpen.size() - 1; k >= 0; k--) {
            final Items.StepsItem fold = heldOpen.get(k);
            if (!fold.working()) {
                heldOpen.remove(k);
                final int i = items.indexOf(fold);
                if (!readerOn(i, i + 1) && !selectionMeets(i, i + 1)) {
                    fold.fold().follow(false);
                }
                if (!fold.isOpen()) {
                    fold.foldChanged();
                    fold.dropSteps();
                    finder.folded(fold);
                    closed = true;
                }
            }
        }
        if (closed) {
            structureChanged = true;
            changed();
        }
    }

    /** True when the selection starts or ends in {@code item}'s text. */
    private boolean selects(final Item item) {
        if (anchorBox == null && focusBox == null || !item.laidOut()) {
            return false; // nothing selected, or never laid out, so never selected
        }
        final List<Placed> boxes = new ArrayList<>();
        item.collect(0, 0, boxes);
        for (int k = 0, n = boxes.size(); k < n; k++) {
            final TextBox box = boxes.get(k).box();
            if (box == anchorBox || box == focusBox) {
                return true;
            }
        }
        return false;
    }

    /** The writer replaced the block at {@code index}. */
    public void update(final int index, final Block b) {
        final Item item = itemOf(index);
        if (item == null || item.block == b) {
            return;
        }
        item.update(b);
        invalidateAncestors(parentOf.get(item));
        if (item instanceof Items.ToolItem card) {
            agents.updated(card);
        }
        structureChanged = true;
        changed();
    }

    /**
     * The session's open background tasks, from Claude Code's task list: a card whose result launched one of its
     * {@code local_agent} tasks reads as running in the background until the task leaves the list.
     */
    public void setBackgroundTasks(final List<BackgroundTask> tasks) {
        agents.setBackgroundTasks(tasks);
    }

    /** Runs when the running sub-agents change without a block change: a launch's grace ending, a task list. */
    public void onAgentsChanged(final Runnable listener) {
        agents.onAgentsChanged(listener);
    }

    /**
     * A running top-level sub-agent: its card's tool-use id, its description, when the canvas first saw it running, and
     * the id of its background task when it was launched into the background (else empty).
     */
    public record RunningAgent(String toolUseId, String description, long startedMillis, String agentId) {
    }

    /** The running top-level sub-agent cards, in the order they started. */
    public List<RunningAgent> runningAgents() {
        return agents.runningAgents();
    }

    /** The tool calls nested in the card {@code toolUseId}, at any depth; -1 when there is no such card. For tests. */
    int toolCount(final String toolUseId) {
        final Items.ToolItem card = agents.card(toolUseId);
        return card == null ? -1 : card.toolCount();
    }

    /** The latest tool call nested in the card {@code toolUseId}, as its status shows it. For tests. */
    String lastTool(final String toolUseId) {
        final Items.ToolItem card = agents.card(toolUseId);
        return card == null ? "" : card.lastTool();
    }

    /** Whether the card {@code toolUseId} is open. */
    public boolean isCardOpen(final String toolUseId) {
        final Items.ToolItem card = agents.card(toolUseId);
        return card != null && card.isOpen();
    }

    /**
     * The top of the top-level item holding the card {@code toolUseId}; -1 when there is none or it has no position.
     * For tests.
     */
    int topOf(final String toolUseId) {
        Item item = agents.card(toolUseId);
        if (item == null) {
            return -1;
        }
        while (parentOf.get(item) != null) {
            item = parentOf.get(item);
        }
        final int index = items.indexOf(item);
        return index < 0 || index >= tops.length ? -1 : tops[index];
    }

    /**
     * Opens the card {@code toolUseId} and the cards around it, and scrolls its top-level item to the top of the view,
     * or the step it sits in when that item is a turn's steps; false when there is no such card.
     */
    public boolean reveal(final String toolUseId) {
        final Items.ToolItem card = agents.card(toolUseId);
        if (card == null) {
            return false;
        }
        for (Item i = card; i != null; i = parentOf.get(i)) {
            if (i.fold() != null && !i.fold().isOpen()) {
                i.toggle();
                invalidateAncestors(parentOf.get(i));
            }
        }
        Item top = card;
        Item step = card;
        while (parentOf.get(top) != null) {
            step = top;
            top = parentOf.get(top);
        }
        final int index = items.indexOf(top);
        if (index < 0) {
            return false;
        }
        structureChanged = true;
        changed();
        ensureLayout(getWidth() > 0 ? getWidth() : viewportWidth());
        int y = tops[index];
        if (top instanceof Items.StepsItem fold) {
            layOut(index);
            y = tops[index] + fold.topOf(step);
        }
        if (getParent() instanceof JViewport v) {
            v.setViewSize(new Dimension(Math.max(getWidth(), 1), total));
        }
        final Rectangle visible = getVisibleRect();
        scrollRectToVisible(new Rectangle(0, y, 1, Math.max(1, visible.height)));
        repaint();
        return true;
    }

    public Block blockAt(final int index) {
        final Item item = itemOf(index);
        return item == null ? null : item.block;
    }

    public int itemCount() {
        return held;
    }

    private Item itemOf(final int index) {
        return index >= 0 && index < byIndex.size() ? byIndex.get(index) : null;
    }

    private void hold(final int index, final Item item) {
        while (byIndex.size() < index) {
            byIndex.add(null);
        }
        if (index == byIndex.size()) {
            byIndex.add(item);
            held++;
        } else if (byIndex.set(index, item) == null) {
            held++;
        }
    }

    /**
     * The link at {@code (x, y)}, in a card's header or in its text, as a double click there follows; null when none;
     * for tests.
     */
    String linkAt(final int x, final int y) {
        final int i = firstAt(y);
        if (i < items.size() && y >= tops[i] && items.get(i).laidOut()) {
            final String header = items.get(i).headerLinkAt(x - PAD_X, y - tops[i]);
            if (header != null) {
                return header;
            }
        }
        final int k = boxAt(x, y, false);
        if (k < 0) {
            return null;
        }
        final Placed p = placed.get(k);
        return !p.contains(x, y) ? null : p.box().linkAt(x - p.x(), y - p.y());
    }

    /** The item of the block at {@code index}; for tests. */
    Item itemAt(final int index) {
        return itemOf(index);
    }

    /** The top-level items in order; for tests. */
    List<Item> topLevel() {
        return Collections.unmodifiableList(items);
    }

    /** For tests: whether the elapsed-time repaint runs. */
    boolean ticking() {
        return ticker.running();
    }

    // ---- running cards and approvals

    /**
     * The approval cards that hold up this session's tool calls. A card whose {@code toolUseId} names a running
     * call marks that call; one without an id marks the last running call of its tool, by plain name or as an
     * MCP tool ({@code mcp__server__name}).
     */
    public void setWaiting(final List<Approval> approvals) {
        agents.setWaiting(approvals);
    }

    /**
     * The status a card shows beside its title ({@code running · 12 s}, the wait for an approval); empty when done or
     * unknown.
     */
    public String cardStatus(final String toolUseId) {
        final Items.ToolItem card = agents.card(toolUseId);
        return card == null ? "" : card.statusText(nowMillis());
    }

    /** Whether some card runs, so the elapsed-time repaint has work while the canvas is on screen. */
    public boolean hasRunningCards() {
        return agents.hasRunning();
    }

    /** For tests: the clock running cards are timed by. */
    void clock(final LongSupplier millis) {
        clock = millis;
    }

    @Override
    public long nowMillis() {
        return clock.getAsLong();
    }

    private void updateTicker() {
        ticker.want(agents.hasRunning());
    }

    private void runningChanged(final Items.ToolItem card) {
        updateTicker();
        if (card.isActive()) {
            openRowOf(card);
        }
        if (!heldOpen.isEmpty()) {
            closeHeld();
        }
    }

    /**
     * {@code card} works, and the row its turn folded to closed before anyone knew: a rebuilt transcript reads the
     * session's open tasks after its blocks. The row opens as it would have held, unless it was held and the user
     * closed it; a row a find opened is held as well, so the next find does not close it over the working card.
     */
    private void openRowOf(final Items.ToolItem card) {
        Item top = card;
        while (parentOf.get(top) != null) {
            top = parentOf.get(top);
        }
        if (top instanceof Items.StepsItem fold && (!fold.isOpen() || fold.fold().revealed())
                && !heldOpen.contains(fold)) {
            fold.fold().follow(true);
            heldOpen.add(fold);
            fold.foldChanged();
            finder.folded(fold);
            structureChanged = true;
            changed();
        }
    }

    private void tick() {
        if (!agents.hasRunning()) {
            return;
        }
        agents.tick();
        repaint();
    }

    private void invalidateAncestors(Item p) {
        for (; p != null; p = parentOf.get(p)) {
            p.invalidate();
        }
    }

    private void changed() {
        version++;
        unplace();
        revalidate();
        repaint();
    }

    /** The boxes moved or went: they are placed again before they are next read, the selection's ends found again. */
    private void unplace() {
        placedNow = false;
        anchorIndex = -1;
        focusIndex = -1;
    }

    public void setZoom(final int steps) {
        zoom = steps;
        styleStale = true;
        structureChanged = true;
        clearSelection();
        changed();
    }

    public int zoom() {
        return zoom;
    }

    // ---- layout

    private Style style() {
        if (styleStale) {
            style = Style.of(zoom);
            styleStale = false;
        }
        return style;
    }

    /**
     * Another tint was chosen: every item is laid out and painted again in it now, as after a zoom change; the
     * selection goes, as its boxes are made again. Runs on its own while the canvas is in a window.
     */
    public void restyle() {
        setZoom(zoom);
    }

    /** Stops hearing the tint chosen; set while the canvas is in a window. */
    private Runnable stopHearingTint;

    @Override
    public void addNotify() {
        super.addNotify();
        stopHearingTint = Theme.onTint(this::restyle);
    }

    @Override
    public void removeNotify() {
        if (stopHearingTint != null) {
            stopHearingTint.run();
            stopHearingTint = null;
        }
        super.removeNotify();
    }

    /**
     * Lays out at width {@code w}. Changed items and items near the viewport are laid out now; items far from it get
     * an estimated height and the idle pass lays them out later, keeping the view where it was: after a width or style
     * change, and for blocks never laid out (a session's history when the view is rebuilt). Cost per call is bounded
     * by what is visible, not by the session length.
     */
    public void ensureLayout(final int w) {
        layout(w, false);
    }

    /** Lays out every item exactly at width {@code w}; for measurements and tests, as nothing in the window needs it. */
    public void layoutFully(final int w) {
        layout(w, true);
    }

    private void layout(final int w, final boolean exact) {
        final int inner = Math.max(80, w - 2 * PAD_X);
        final Style s = style();
        boolean any = inner != laidWidth || structureChanged || tops.length != items.size() || (exact && estimated);
        if (!any) {
            for (int i = 0, n = items.size(); i < n; i++) {
                final Item item = items.get(i);
                if (item.isDirty() && item.laidOut()) { // a block never laid out is the idle pass's
                    any = true;
                    break;
                }
            }
        }
        if (!any) {
            return;
        }
        final Anchor anchor = anchor();
        laidWidth = inner;
        structureChanged = false;
        if (tops.length != items.size()) {
            tops = Arrays.copyOf(tops, items.size());
            heights = Arrays.copyOf(heights, items.size());
        }
        if (exact) {
            layOutInParallel(inner, s);
        }
        final Rectangle vis = getVisibleRect();
        final int lo = vis.y;
        final int hi = vis.y + vis.height;
        boolean guessed = false;
        int y = PAD_Y;
        for (int i = 0; i < items.size(); i++) {
            final Item item = items.get(i);
            tops[i] = y;
            int h;
            if (!item.needsLayoutTo(inner, s, exact ? Integer.MAX_VALUE : hi - y)) {
                h = item.height;
            } else if (exact) {
                h = item.layout(inner, s);
            } else if ((item.laidOut() && item.isDirty()) || (y <= hi && y + item.estimate(inner, s) >= lo)) {
                h = layOutTo(item, inner, s, hi - y, System.nanoTime() + SLICE_NANOS);
            } else {
                h = item.estimate(inner, s);
            }
            guessed |= item.needsLayoutTo(inner, s, Integer.MAX_VALUE);
            heights[i] = h;
            y += h;
        }
        total = y + PAD_Y;
        unplace();
        estimated = guessed;
        if (estimated) {
            refiner.start();
        }
        keep(anchor);
    }

    /**
     * Lays out {@code item} at {@code width}, a row of steps only down to {@code to} below its top, by {@code
     * deadlineNanos}.
     */
    private static int layOutTo(
            final Item item,
            final int width,
            final Style s,
            final int to,
            final long deadlineNanos
    ) {
        return item instanceof Items.StepsItem fold
                ? fold.layoutTo(width, s, to, deadlineNanos)
                : item.layout(width, s);
    }

    /**
     * The block at the top of the view and its top, taken before a layout changes heights; {@link #NO_ANCHOR} when the
     * view shows the end, which follows new blocks, or nothing is shown yet. In an open row, also the step at the top
     * of the view and its top within the row, -1 when the view shows the row's heading: the steps above it may hold
     * estimates that a later slice makes exact.
     */
    private record Anchor(Item item, int index, int top, int step, int stepTop, int viewY) {
    }

    private Anchor anchor() {
        final int n = Math.min(tops.length, items.size());
        if (!(getParent() instanceof JViewport) || laidWidth < 0 || n == 0) {
            return NO_ANCHOR;
        }
        final Rectangle vis = getVisibleRect();
        if (vis.height <= 0 || showsEnd(vis)) {
            return NO_ANCHOR;
        }
        final int i = indexAt(vis.y, n);
        if (i >= n) {
            return NO_ANCHOR;
        }
        final int step = stepAt(i, vis.y);
        return new Anchor(items.get(i), i, tops[i], step, stepTop(i, step), vis.y);
    }

    /** The step of the open row at {@code i} shown at {@code y}; -1 when it is no row with its steps placed there. */
    private int stepAt(final int i, final int y) {
        return items.get(i) instanceof Items.StepsItem fold ? fold.stepPlacedAt(y - tops[i]) : -1;
    }

    /** The top within the row at {@code i} of its step at {@code step}; -1 when there is no such step placed. */
    private int stepTop(final int i, final int step) {
        return step >= 0 && items.get(i) instanceof Items.StepsItem fold ? fold.topAt(step) : -1;
    }

    /**
     * How far the step at {@code step} of the row at {@code i} moved within it from {@code was}; 0 when no step was
     * taken or it is no longer placed.
     */
    private int stepMoved(final int i, final int step, final int was) {
        final int now = stepTop(i, step);
        return was < 0 || now < 0 ? 0 : now - was;
    }

    /** True when {@code vis} shows the end of the content, or did when the idle pass last grew it. */
    private boolean showsEnd(final Rectangle vis) {
        return vis.y + vis.height >= total - END_SLACK_PX || (endKeptAt >= 0 && vis.y == endKeptAt);
    }

    /**
     * Keeps the block that was at the top of the view at the same place on screen: heights above it changed (a result
     * arrived, text streamed, a card opened, a guess became exact) or blocks arrived below. The canvas is sized to its
     * content first, so the position is not clamped to the old size.
     */
    private void keep(final Anchor anchor) {
        if (anchor.index() < 0 || anchor.index() >= items.size() || items.get(anchor.index()) != anchor.item()) {
            return;
        }
        final int delta = tops[anchor.index()] - anchor.top()
                + stepMoved(anchor.index(), anchor.step(), anchor.stepTop());
        if (delta != 0 && getParent() instanceof JViewport v && v.getViewPosition().y == anchor.viewY()) {
            v.setViewSize(new Dimension(Math.max(getWidth(), 1), total));
            v.setViewPosition(new Point(0, Math.max(0, anchor.viewY() + delta)));
        }
    }

    /**
     * The first of the first {@code n} items whose bottom is below {@code y}, by the positions as they are: the item the
     * view shows at its top, not one that ends there.
     */
    private int indexAt(final int y, final int n) {
        int lo = 0;
        int hi = n - 1;
        int found = n;
        while (lo <= hi) {
            final int mid = (lo + hi) >>> 1;
            if (tops[mid] + heights[mid] > y) {
                found = mid;
                hi = mid - 1;
            } else {
                lo = mid + 1;
            }
        }
        return found;
    }

    /**
     * Lays out the items an exact layout still has to, on every core when there are many ({@link Forks}): an item lays
     * out only its own subtree, with its own boxes and the shared immutable style, so items are independent.
     */
    private void layOutInParallel(final int inner, final Style s) {
        final List<Item> stale = new ArrayList<>();
        for (int i = 0, n = items.size(); i < n; i++) {
            final Item item = items.get(i);
            if (item.needsLayout(inner, s)) {
                stale.add(item);
            }
        }
        if (stale.size() >= Forks.PARALLEL_FROM) {
            Forks.each("transcript-layout", stale.size(), i -> stale.get(i).layout(inner, s));
        }
    }

    private void recomputeTops() {
        int y = PAD_Y;
        for (int i = 0; i < items.size(); i++) {
            tops[i] = y;
            y += heights[i];
        }
        total = y + PAD_Y;
        unplace();
    }

    /**
     * Lays out stale items inside {@code clip} before they are painted; true when positions moved. A view that showed
     * the end keeps showing it: the next slice of the idle pass puts it back at the end the grown content has.
     */
    private boolean layoutVisible(final Rectangle clip) {
        if (!estimated) {
            return false;
        }
        final View before = view();
        boolean moved = false;
        final Style s = style();
        for (int round = 0; round < 3; round++) {
            boolean changed = false;
            // from the item shown at the clip's top: one that ends there would move it if laid out
            final int n = Math.min(tops.length, items.size());
            for (int i = indexAt(clip.y, n); i < n && tops[i] <= clip.y + clip.height; i++) {
                final Item item = items.get(i);
                final int to = clip.y + clip.height - tops[i];
                if (item.needsLayoutTo(laidWidth, s, to)) {
                    heights[i] = layOutTo(item, laidWidth, s, to, System.nanoTime() + SLICE_NANOS);
                    changed = true;
                }
            }
            if (!changed) {
                break;
            }
            recomputeTops();
            moved = true;
        }
        if (moved) {
            if (before.atEnd()) {
                endKeptAt = getVisibleRect().y;
            } else if (stepMoved(before.item(), before.step(), before.stepTop()) != 0) {
                restore(before); // the row at the view's top made steps above it exact: the step in view stays
            }
            revalidate();
        }
        return moved;
    }

    /**
     * One slice of the idle pass: lays out the stale items within the window around the view, nearest first, for up to
     * 8 ms, keeping the view in place; items beyond it keep their estimates. True when the window still has work.
     */
    private boolean refine() {
        final Style s = style();
        final long deadlineNanos = System.nanoTime() + SLICE_NANOS;
        final View before = view();
        final Rectangle vis = getVisibleRect();
        final int band = band(vis);
        final int lo = vis.y - band;
        final int hi = vis.y + vis.height + band;
        final int n = Math.min(tops.length, items.size());
        final int first = Math.min(before.item(), Math.max(0, n - 1));
        boolean left = false;
        boolean changed = false;
        boolean down = true;
        boolean up = true;
        for (int d = 0; (down || up) && !left; d++) {
            final int i = first + (d % 2 == 0 ? d / 2 : -(d / 2 + 1));
            if (d % 2 == 0 && (i >= n || tops[i] > hi)) {
                down = false;
                continue;
            }
            if (d % 2 == 1 && (i < 0 || tops[i] + heights[i] < lo)) {
                up = false;
                continue;
            }
            if (i < 0 || i >= n) {
                continue;
            }
            final Item item = items.get(i);
            if (item.needsLayoutTo(laidWidth, s, hi - tops[i])) {
                if (System.nanoTime() - deadlineNanos > 0) {
                    left = true;
                    break;
                }
                heights[i] = layOutTo(item, laidWidth, s, hi - tops[i], deadlineNanos);
                changed = true;
                left = item instanceof Items.StepsItem fold && fold.outOfTime();
            }
        }
        if (!left) {
            refiner.stop();
            releaseFar(vis, band);
            estimated = anyStale(s);
        }
        if (!changed && !(endKeptAt >= 0 && before.atEnd())) {
            endKeptAt = -1;
            return left;
        }
        recomputeTops();
        restore(before);
        return left;
    }

    /** How far around the view the window reaches, in pixels. */
    private static int band(final Rectangle vis) {
        return Math.max(BAND_MIN, BAND_VIEWS * vis.height);
    }

    /**
     * Lets the items farther than twice the window from the view drop their layout, keeping their heights; not the items
     * the selection starts or ends in. A find's matches name items and boxes by position, so they hold no layout.
     */
    private void releaseFar(final Rectangle vis, final int band) {
        final int lo = vis.y - 2 * band;
        final int hi = vis.y + vis.height + 2 * band;
        boolean released = false;
        for (int i = 0; i < Math.min(tops.length, items.size()); i++) {
            final Item item = items.get(i);
            if (item.laidOut() && (tops[i] + heights[i] < lo || tops[i] > hi) && !selects(item)) {
                item.release();
                released = true;
            }
        }
        if (released) {
            // the placed boxes, the selection's places among them and the find's marks name boxes: they go until the
            // next paint places again
            unplace();
            finder.startPlacing();
            estimated = true;
        }
    }

    private boolean anyStale(final Style s) {
        for (int i = 0, n = items.size(); i < n; i++) {
            if (items.get(i).needsLayoutTo(laidWidth, s, Integer.MAX_VALUE)) {
                return true;
            }
        }
        return false;
    }

    /** Where the view was when the window last slid; it slides again once the view has moved half a window from there. */
    private int slidAt = Integer.MIN_VALUE;

    /** After a paint: a view that moved far enough has the idle pass lay out the window around it and let go of the rest. */
    private void slide(final Rectangle vis) {
        if (Math.abs((long) vis.y - slidAt) >= band(vis) / 2) {
            slidAt = vis.y;
            refiner.start();
        }
    }

    /**
     * What the view shows: the end, or the item at its top and how far into it, and in an open row the step at its top
     * and that step's top within the row ({@link Anchor}). Taken before a layout changes heights, so {@link #restore}
     * can show the same content after it.
     */
    private record View(boolean atEnd, int item, int offset, int step, int stepTop, int height) {
    }

    private View view() {
        if (tops.length != items.size() && laidWidth >= 0) {
            ensureLayout(Math.max(getWidth(), 1)); // blocks appended since the last layout get their positions first
        }
        final Rectangle vis = getVisibleRect();
        final boolean atEnd = showsEnd(vis);
        if (items.isEmpty() || tops.length != items.size()) {
            return new View(atEnd, 0, 0, -1, -1, vis.height); // never laid out: nothing shown yet
        }
        // the item the view shows at its top, not one that ends there: that one may change height above the view
        final int first = Math.min(indexAt(vis.y, items.size()), items.size() - 1);
        final int step = stepAt(first, vis.y);
        return new View(atEnd, first, vis.y - tops[first], step, stepTop(first, step), vis.height);
    }

    /** Sizes the canvas to its content and shows what {@code before} showed; the end when it showed the end. */
    private void restore(final View before) {
        endKeptAt = -1;
        if (getParent() instanceof JViewport v) {
            v.setViewSize(new Dimension(getWidth(), total));
            final int y = before.atEnd() || before.item() >= items.size() ? Math.max(0, total - before.height())
                    : Math.max(0, tops[before.item()] + before.offset()
                            + stepMoved(before.item(), before.step(), before.stepTop()));
            v.setViewPosition(new Point(0, y));
        }
        revalidate();
        repaint();
    }

    /** Runs the idle pass over the window around the view at once; for measurements and tests. */
    public void refineAll() {
        if (laidWidth < 0) {
            return;
        }
        while (refine()) {
            // the next slice
        }
    }

    /** Whether the idle pass is scheduled to run; for tests. */
    boolean refining() {
        return refiner.isRunning();
    }

    public boolean isEstimated() {
        return estimated;
    }

    private int viewportWidth() {
        final Component p = getParent();
        return p instanceof JViewport v ? v.getWidth() : getWidth();
    }

    /** Over a prompt's or a turn end's time, the whole time: its date, seconds and zone. */
    @Override
    public String getToolTipText(final MouseEvent e) {
        final int i = firstAt(e.getY());
        return i < items.size() && e.getY() >= tops[i] && items.get(i).laidOut()
                ? items.get(i).hoverAt(e.getX() - PAD_X, e.getY() - tops[i]) : null;
    }

    @Override
    public Dimension getPreferredSize() {
        final int w = viewportWidth();
        if (w <= 0) {
            return new Dimension(400, 100);
        }
        ensureLayout(w);
        return new Dimension(w, total);
    }

    // ---- painting

    @Override
    protected void paintComponent(final Graphics g0) {
        // in a viewport the canvas sits at minus the view's y, and g0 was translated by it before this paint began
        final int paintedAt = getY();
        ensureLayout(getWidth());
        final Graphics2D g = (Graphics2D) g0.create();
        try {
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            // the whole canvas when no clip is set
            final Rectangle clip = g.getClipBounds(new Rectangle(0, 0, getWidth(), getHeight()));
            layoutVisible(clip);
            final int moved = getY() - paintedAt;
            if (moved != 0) {
                // a layout moved the view to keep the step in view in place, and g still draws where the view was:
                // the frame draws where the view is now, as the repaint the move asked for will
                g.translate(0, moved);
                clip.translate(0, -moved);
            }
            ensurePlaced();
            for (int i = firstAt(clip.y); i < items.size(); i++) {
                final int top = tops[i];
                if (top > clip.y + clip.height) {
                    break;
                }
                final Item item = items.get(i);
                if (!item.laidOut()) {
                    continue; // estimated past the visible pass; the idle pass lays it out and repaints
                }
                painting = i;
                item.paint(g, PAD_X, top, clip, this);
            }
        } finally {
            painting = -1;
            g.dispose();
        }
        if (laidWidth >= 0) {
            slide(getVisibleRect());
        }
    }

    /**
     * The first item whose bottom is below {@code y}; {@code items.size()} when none. A block appended since
     * the last layout has no position yet, so a mouse event lays out first instead of indexing past it.
     */
    private int firstAt(final int y) {
        if (tops.length != items.size()) {
            ensureLayout(Math.max(getWidth(), 1));
        }
        int lo = 0;
        int hi = items.size() - 1;
        int found = items.size();
        while (lo <= hi) {
            final int mid = (lo + hi) >>> 1;
            if (tops[mid] + heights[mid] >= y) {
                found = mid;
                hi = mid - 1;
            } else {
                lo = mid + 1;
            }
        }
        return found;
    }

    // ---- Marks

    /** Asked while painting, of a box of the item being painted; outside a paint {@link #NONE}, unless all selected. */
    @Override
    public int[] selection(final TextBox box) {
        return selection(painting, box);
    }

    /**
     * The selection inside {@code box}, one of the boxes of the top-level item {@code item}; {@link #NONE} when that
     * item is -1, or when {@code box} is not among that item's placed boxes.
     */
    private int[] selection(final int item, final TextBox box) {
        if (allSelected) {
            return new int[]{0, box.length()};
        }
        if (anchorIndex < 0 || focusIndex < 0 || item < 0) {
            return NONE;
        }
        final int a = anchorIndex;
        final int f = focusIndex;
        final boolean forward = a < f || (a == f && anchorChar <= focusChar);
        final int startBox = forward ? a : f;
        final int endBox = forward ? f : a;
        final int from = firstBox[item];
        final int to = firstBox[item + 1];
        if (to <= startBox || from > endBox) {
            return NONE; // the item's boxes lie before or after the selection
        }
        final int i = indexOf(box, from, to);
        if (i < startBox || i > endBox) {
            return NONE;
        }
        final int startChar = forward ? anchorChar : focusChar;
        final int endChar = forward ? focusChar : anchorChar;
        return new int[]{i == startBox ? startChar : 0, i == endBox ? endChar : box.length()};
    }

    /**
     * Where {@code box} is in {@link #placed}, looked for from {@code from} up to {@code to}; -1 when it is not there.
     * The search starts after the box found last, as an item paints its boxes in the order it placed them.
     */
    private int indexOf(final TextBox box, final int from, final int to) {
        final int start = nextBox > from && nextBox < to ? nextBox : from;
        for (int k = start; k < to; k++) {
            if (placed.get(k).box() == box) {
                nextBox = k + 1;
                return k;
            }
        }
        for (int k = from; k < start; k++) {
            if (placed.get(k).box() == box) {
                nextBox = k + 1;
                return k;
            }
        }
        return -1;
    }

    @Override
    public List<int[]> finds(final TextBox box) {
        return finder.finds(box);
    }

    @Override
    public int currentFind(final TextBox box) {
        return finder.currentFind(box);
    }

    @Override
    public Component component() {
        return this;
    }

    private void ensurePlaced() {
        if (placedNow) {
            return;
        }
        placed.clear();
        finder.startPlacing();
        final int n = items.size();
        if (firstBox.length != n + 1) {
            firstBox = new int[n + 1];
        }
        for (int i = 0; i < n; i++) {
            final int from = placed.size();
            firstBox[i] = from;
            if (items.get(i).laidOut()) {
                items.get(i).collect(PAD_X, tops[i], placed);
                finder.placed(i, placed.subList(from, placed.size()));
            }
        }
        firstBox[n] = placed.size();
        anchorIndex = -1;
        focusIndex = -1;
        for (int k = 0, m = placed.size(); k < m; k++) {
            final TextBox box = placed.get(k).box();
            if (box == anchorBox) {
                anchorIndex = k;
            }
            if (box == focusBox) {
                focusIndex = k;
            }
        }
        nextBox = 0;
        placedNow = true;
        // an end of the selection is no longer placed; with nothing selected both are -1 and this clears nothing
        if (!allSelected && (anchorIndex < 0 || focusIndex < 0)) {
            clearSelection();
        }
    }

    // ---- mouse

    /**
     * Where the box at {@code (x, y)} is in {@link #placed}, -1 when none; only what is shown is laid out for it, the
     * rest stays with the idle pass.
     */
    private int boxAt(final int x, final int y, final boolean nearest) {
        ensureLayout(getWidth());
        layoutVisible(getVisibleRect());
        ensurePlaced();
        int best = -1;
        for (int k = 0, n = placed.size(); k < n; k++) {
            final Placed p = placed.get(k);
            if (p.contains(x, y) && x >= p.x() - 4 && x <= p.x() + p.box().width() + 4) {
                return k;
            }
            if (nearest && p.y() <= y) {
                best = k;
            }
        }
        return best;
    }

    private void pressed(final MouseEvent e) {
        requestFocusInWindow();
        if (!SwingUtilities.isLeftMouseButton(e)) {
            return;
        }
        final int i = firstAt(e.getY());
        if (i < items.size() && e.getY() >= tops[i] && items.get(i).laidOut()) {
            final String link = items.get(i).headerLinkAt(e.getX() - PAD_X, e.getY() - tops[i]);
            if (link != null) {
                // followed on the double click's release, as a link in text is
                clearSelection();
                pressedLink = link;
                pressedClicks = e.getClickCount();
                repaint();
                return;
            }
            final Item toggle = items.get(i).toggleAt(e.getX() - PAD_X, e.getY() - tops[i]);
            if (toggle != null && toggle.toggle()) {
                invalidateAncestors(parentOf.get(toggle));
                structureChanged = true;
                changed();
                return;
            }
        }
        final int k = boxAt(e.getX(), e.getY(), true);
        pressedLink = null;
        if (k < 0) {
            clearSelection();
            repaint();
            return;
        }
        final Placed p = placed.get(k);
        pressedLink = p.contains(e.getX(), e.getY()) ? p.box().linkAt(e.getX() - p.x(), e.getY() - p.y()) : null;
        pressedClicks = e.getClickCount();
        allSelected = false; // whatever this press selects replaces select-all
        if (e.getClickCount() == 2 && pressedLink != null) {
            // a link's double click follows it (record 94): no word to select
            clearSelection();
        } else if (e.getClickCount() == 2) {
            selectWord(k, p.box().indexAt(e.getX() - p.x(), e.getY() - p.y()));
        } else if (e.isShiftDown() && anchorBox != null) {
            focusBox = p.box();
            focusIndex = k;
            focusChar = p.box().indexAt(e.getX() - p.x(), e.getY() - p.y());
        } else {
            anchorBox = focusBox = p.box();
            anchorIndex = focusIndex = k;
            anchorChar = focusChar = p.box().indexAt(e.getX() - p.x(), e.getY() - p.y());
        }
        repaint();
    }

    private void dragged(final MouseEvent e) {
        if (anchorBox == null) {
            return;
        }
        final int k = boxAt(e.getX(), e.getY(), true);
        if (k >= 0) {
            final Placed p = placed.get(k);
            focusBox = p.box();
            focusIndex = k;
            focusChar = p.box().indexAt(e.getX() - p.x(), e.getY() - p.y());
            pressedLink = null;
            repaint();
        }
        scrollRectToVisible(new Rectangle(e.getX(), e.getY(), 1, 1));
    }

    private void released(final MouseEvent e) {
        if (pressedLink != null && pressedClicks == 2 && anchorBox == focusBox && anchorChar == focusChar) {
            final String href = pressedLink;
            pressedLink = null;
            links.accept(href);
        }
    }

    private void moved(final MouseEvent e) {
        final int i = firstAt(e.getY());
        if (i < items.size() && e.getY() >= tops[i] && items.get(i).laidOut()
                && items.get(i).toggleAt(e.getX() - PAD_X, e.getY() - tops[i]) != null) {
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            return;
        }
        final int k = boxAt(e.getX(), e.getY(), false);
        if (k < 0) {
            setCursor(Cursor.getDefaultCursor());
            return;
        }
        final Placed p = placed.get(k);
        if (p.box().linkAt(e.getX() - p.x(), e.getY() - p.y()) != null) {
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        } else {
            setCursor(Cursor.getPredefinedCursor(Cursor.TEXT_CURSOR));
        }
    }

    private void selectWord(final int k, final int at) {
        final Placed p = placed.get(k);
        final String t = p.box().text();
        int s = Math.min(at, t.length());
        int e = s;
        while (s > 0 && Character.isLetterOrDigit(t.charAt(s - 1)) || s > 0 && t.charAt(s - 1) == '_') {
            s--;
        }
        while (e < t.length() && (Character.isLetterOrDigit(t.charAt(e)) || t.charAt(e) == '_')) {
            e++;
        }
        anchorBox = focusBox = p.box();
        anchorIndex = focusIndex = k;
        anchorChar = s;
        focusChar = e;
    }

    // ---- selection

    public void clearSelection() {
        allSelected = false;
        anchorBox = focusBox = null;
        anchorIndex = focusIndex = -1;
        anchorChar = focusChar = 0;
    }

    /** Selects every box of the conversation, laying out none; the view and its layout stay as they are. */
    public void selectAll() {
        if (items.isEmpty()) {
            return;
        }
        anchorBox = focusBox = null;
        anchorIndex = focusIndex = -1;
        allSelected = true;
        repaint();
    }

    /**
     * The selected text; boxes are joined by newlines. Empty when nothing is selected. After select-all, the text of
     * every box as the blocks give it ({@link Item#findTexts}), so copying all lays out nothing.
     */
    public String selectionText() {
        if (allSelected) {
            // each item's text read from its block, on every core: what lies far from the view needs no layout
            final String[] parts = new String[items.size()];
            Forks.each("transcript-copy", parts.length, i -> {
                final List<String> texts = new ArrayList<>();
                items.get(i).findTexts(texts);
                final StringBuilder sb = new StringBuilder();
                for (int k = 0, n = texts.size(); k < n; k++) {
                    if (!texts.get(k).isEmpty()) {
                        appendLine(sb, texts.get(k));
                    }
                }
                parts[i] = sb.toString();
            });
            final StringBuilder sb = new StringBuilder();
            for (final String part : parts) {
                if (!part.isEmpty()) {
                    appendLine(sb, part);
                }
            }
            return sb.toString();
        }
        if (anchorBox == null) {
            return "";
        }
        if (tops.length != items.size()) {
            ensureLayout(getWidth() > 0 ? getWidth() : viewportWidth()); // blocks appended since the last layout
        }
        ensurePlaced();
        final int a = anchorIndex;
        final int f = focusIndex;
        if (a < 0 || f < 0) {
            return "";
        }
        // the items between the selection's ends are selected whole: a laid-out one gives its boxes, one that is not
        // (scrolled past, or let go far from the view) its text as the blocks give it
        final int from = topIndex(placed.get(Math.min(a, f)).item());
        final int to = topIndex(placed.get(Math.max(a, f)).item());
        if (from < 0 || to < 0) {
            return "";
        }
        final StringBuilder sb = new StringBuilder();
        final List<Placed> boxes = new ArrayList<>();
        final List<String> texts = new ArrayList<>();
        for (int i = from; i <= to; i++) {
            copy(i, items.get(i), sb, boxes, texts);
        }
        return sb.toString();
    }

    /**
     * Adds what the selection takes of {@code item}: a laid-out item its boxes, one that is not its text as the blocks
     * give it; an open row step by step, as its steps below the view may not be laid out yet. {@code top} is the
     * top-level item that holds {@code item}.
     */
    private void copy(
            final int top,
            final Item item,
            final StringBuilder sb,
            final List<Placed> boxes,
            final List<String> texts
    ) {
        if (item instanceof Items.StepsItem row && row.isOpen() && row.laidOut()) {
            final List<Item> steps = row.children();
            for (int k = 0, n = steps.size(); k < n; k++) {
                copy(top, steps.get(k), sb, boxes, texts);
            }
        } else if (item.laidOut()) {
            boxes.clear();
            item.collect(0, 0, boxes);
            for (int k = 0, n = boxes.size(); k < n; k++) {
                final TextBox box = boxes.get(k).box();
                final int[] r = selection(top, box);
                if (r[0] < r[1]) {
                    appendLine(sb, box.text().substring(r[0], r[1]));
                }
            }
        } else {
            texts.clear();
            item.findTexts(texts);
            for (int k = 0, n = texts.size(); k < n; k++) {
                if (!texts.get(k).isEmpty()) {
                    appendLine(sb, texts.get(k));
                }
            }
        }
    }

    private static void appendLine(final StringBuilder sb, final String text) {
        if (!sb.isEmpty()) {
            sb.append('\n');
        }
        sb.append(text);
    }

    /** The index of the top-level item {@code item} sits in, at any depth. */
    private int topIndex(final Item item) {
        Item top = item;
        while (parentOf.get(top) != null) {
            top = parentOf.get(top);
        }
        return items.indexOf(top);
    }

    // ---- find

    /**
     * Finds {@code text}, opening the closed items that hold it; returns the number of matches. A new text first
     * closes what the previous one opened.
     */
    public int find(final String text, final boolean forward) {
        return finder.find(text, forward);
    }

    /**
     * The next find of a new text starts where the current match begins, so typing on keeps the match under the
     * reader's eye rather than going back to the first in the transcript.
     */
    public void rewindFind() {
        finder.rewind();
    }

    /** The find's matches as {@code [top-level item, box within it, offset]}, in reading order; for tests. */
    List<int[]> findMatches() {
        return finder.matches();
    }

    /** The top of the current match in the canvas, -1 when there is none; for tests. */
    int currentMatchY() {
        return finder.currentMatchY();
    }

    /**
     * Ends the find: the items it opened close again, except those the user toggled since and the item holding the
     * current match, with the cards around it.
     */
    public void clearFind() {
        finder.clear();
    }

    /** Lays out the top-level item at {@code index} alone if it is not; the block at the view's top stays in place. */
    private void layOut(final int index) {
        final Item item = items.get(index);
        final Style s = style();
        if (item.needsLayoutTo(laidWidth, s, Integer.MAX_VALUE)) {
            // its exact height may differ from the estimate
            final Anchor anchor = anchor();
            heights[index] = item.layout(laidWidth, s);
            recomputeTops();
            keep(anchor);
            revalidate();
        }
    }

    /** What a find needs from the canvas. */
    private final class FindHost implements Finder.Host {

        @Override
        public List<Item> items() {
            return items;
        }

        @Override
        public Item parentOf(final Item item) {
            return parentOf.get(item);
        }

        /** Lays out the item alone if it is not, at the width the rest has; the window around the view stays as it is. */
        @Override
        public List<Placed> place(final int index) {
            ensureLayout(getWidth() > 0 ? getWidth() : viewportWidth());
            layOut(index);
            final List<Placed> out = new ArrayList<>();
            items.get(index).collect(PAD_X, tops[index], out);
            return out;
        }

        @Override
        public long version() {
            return version;
        }

        @Override
        public void foldChanged(final Item item) {
            item.foldChanged();
            invalidateAncestors(parentOf.get(item));
            structureChanged = true;
        }

        @Override
        public void structureChanged() {
            structureChanged = true;
            changed();
        }

        @Override
        public void scrollTo(final int y) {
            if (getParent() instanceof JViewport v && getHeight() != total) {
                v.setViewSize(new Dimension(Math.max(getWidth(), 1), total)); // a match past the old size is reachable
            }
            final Rectangle visible = getVisibleRect();
            scrollRectToVisible(new Rectangle(0, Math.max(0, y - visible.height / 3), 1, visible.height / 2));
        }

        @Override
        public void repaint() {
            TranscriptCanvas.this.repaint();
        }
    }

    /**
     * All blocks as Markdown, in order: prompts quoted, text as written, a tool call as its summary line with its
     * input and result fenced, notes in italics, each turn's duration as an italic footer; thinking stays out.
     */
    public String markdown() {
        final StringBuilder sb = new StringBuilder();
        for (int i = 0, n = items.size(); i < n; i++) {
            final String m = items.get(i).markdown();
            if (!m.isEmpty()) {
                sb.append(m).append("\n\n");
            }
        }
        return sb.toString();
    }

    public int contentHeight() {
        return total;
    }

    /**
     * The top of the nearest top-level user prompt above {@code y} ({@code forward} false) or below it, by the positions
     * as laid out now; -1 when there is none that way.
     */
    public int promptTop(final int y, final boolean forward) {
        ensureLayout(getWidth() > 0 ? getWidth() : viewportWidth());
        int found = -1;
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i) instanceof Items.PromptItem) {
                if (forward && tops[i] > y) {
                    return tops[i];
                }
                if (!forward && tops[i] < y) {
                    found = tops[i];
                }
            }
        }
        return found;
    }

    /** The top of the last turn's report as laid out now, -1 when that turn ended with none. */
    public int reportTop() {
        ensureLayout(getWidth() > 0 ? getWidth() : viewportWidth());
        boolean ended = false;
        for (int i = items.size() - 1; i >= 0; i--) {
            final Item item = items.get(i);
            if (item instanceof Items.TextItem text && text.isReport()) {
                return tops[i];
            }
            if (ended && (item instanceof Items.TurnItem || item instanceof Items.PromptItem)) {
                return -1; // the turn before: its report is not what the last turn came to
            }
            ended |= item instanceof Items.TurnItem;
        }
        return -1;
    }

    /** The top of the top-level item {@code item} as laid out now, -1 when it is not one; for tests. */
    int topOf(final Item item) {
        final int i = items.indexOf(item);
        return i < 0 || i >= tops.length ? -1 : tops[i];
    }

    // ---- Scrollable

    @Override
    public Dimension getPreferredScrollableViewportSize() {
        return getPreferredSize();
    }

    @Override
    public int getScrollableUnitIncrement(final Rectangle visible, final int orientation, final int direction) {
        return 24;
    }

    @Override
    public int getScrollableBlockIncrement(final Rectangle visible, final int orientation, final int direction) {
        return Math.max(visible.height - 48, 24);
    }

    @Override
    public boolean getScrollableTracksViewportWidth() {
        return true;
    }

    @Override
    public boolean getScrollableTracksViewportHeight() {
        return false;
    }
}
