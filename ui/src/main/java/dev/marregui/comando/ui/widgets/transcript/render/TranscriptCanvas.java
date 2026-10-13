package dev.marregui.comando.ui.widgets.transcript.render;

import dev.marregui.comando.core.model.Approval;
import dev.marregui.comando.core.transcript.Block;
import dev.marregui.comando.ui.kit.theme.Theme;

import javax.swing.AbstractAction;
import javax.swing.JComponent;
import javax.swing.JViewport;
import javax.swing.KeyStroke;
import javax.swing.Scrollable;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.awt.event.ActionEvent;
import java.awt.event.HierarchyEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * The transcript painted directly: one {@link Item} per block, laid out once per width and style and
 * again only when its block changes; painting visits only the items that intersect the clip. Text can
 * be selected across blocks with the mouse and copied; links open through a callback; tool cards, thinking,
 * notes and hook groups open and close with a click on their header.
 */
public final class TranscriptCanvas extends JComponent implements Scrollable, Marks {

    private static final int PAD_X = Theme.PAD * 2;
    private static final int PAD_Y = Theme.PAD;
    private static final int[] NONE = {0, 0};

    private final List<Item> items = new ArrayList<>();
    private final Map<Integer, Item> byIndex = new HashMap<>();
    private final Map<String, Items.ToolItem> cards = new HashMap<>();
    /** The card or hook group each nested item sits in. */
    private final Map<Item, Item> parentOf = new IdentityHashMap<>();
    private final Set<Items.ToolItem> running = Collections.newSetFromMap(new IdentityHashMap<>());
    private Set<Items.ToolItem> waitingCards = Collections.newSetFromMap(new IdentityHashMap<>());
    private List<Approval> waitingFor = List.of();
    /** Repaints running cards' elapsed time once a second while one runs and the canvas is on screen. */
    private final Timer ticker = new Timer(1000, e -> tick());
    private LongSupplier clock = System::currentTimeMillis;
    private final Consumer<String> links;
    private int[] tops = new int[0];
    /** Heights used for positions: exact, or estimated while {@link #estimated}. */
    private int[] heights = new int[0];
    private boolean estimated;
    private final Timer refiner = new Timer(1, e -> refine());
    private int total;
    private int laidWidth = -1;
    private boolean structureChanged = true;
    private Style style;
    private int zoom;

    // selection and find work on the boxes in reading order, rebuilt lazily
    private List<Placed> placed;
    private Map<TextBox, Integer> boxIndex;
    private TextBox anchorBox;
    private int anchorChar;
    private TextBox focusBox;
    private int focusChar;
    private String pressedLink;
    private final List<int[]> matches = new ArrayList<>();
    private Map<TextBox, List<int[]>> matchesByBox = new IdentityHashMap<>();
    private int currentMatch = -1;
    private String findText = "";
    /** Items the current find opened; clearing the find closes them again. */
    private final List<Item> revealed = new ArrayList<>();
    private Item flashing;

    public TranscriptCanvas(Consumer<String> links) {
        this.links = links;
        setFocusable(true);
        setOpaque(false);
        MouseAdapter mouse = new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                pressed(e);
            }

            @Override
            public void mouseDragged(MouseEvent e) {
                dragged(e);
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                released(e);
            }

            @Override
            public void mouseMoved(MouseEvent e) {
                moved(e);
            }
        };
        addMouseListener(mouse);
        addMouseMotionListener(mouse);
        addHierarchyListener(e -> {
            if ((e.getChangeFlags() & HierarchyEvent.SHOWING_CHANGED) != 0) {
                updateTicker();
            }
        });
        int menu = GraphicsEnvironment.isHeadless() ? InputEvent.META_DOWN_MASK : Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx();
        getInputMap(WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_C, menu), "copy-selection");
        getActionMap().put("copy-selection", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                String s = selectionText();
                if (!s.isEmpty()) {
                    Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(s), null);
                }
            }
        });
        getInputMap(WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_A, menu), "select-all");
        getActionMap().put("select-all", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                selectAll();
            }
        });
    }

    // ---- content

    public void clear() {
        refiner.stop();
        estimated = false;
        items.clear();
        byIndex.clear();
        cards.clear();
        parentOf.clear();
        running.clear();
        waitingCards.clear();
        revealed.clear();
        updateTicker();
        clearSelection();
        clearMatches();
        structureChanged = true;
        changed();
    }

    /**
     * Adds the block at transcript index {@code index}; a subagent's block goes inside its call's card, and a hook
     * note right after another joins its group.
     */
    public void append(int index, Block b) {
        Item item = switch (b) {
            case Block.ToolCall call -> new Items.ToolItem(call, index);
            case Block.Note n when n.kind() == Block.NoteKind.HOOK -> new Items.NoteItem(n, true);
            default -> Items.of(b);
        };
        byIndex.put(index, item);
        if (item instanceof Items.ToolItem card) {
            cards.put(card.call().toolUseId(), card);
            track(card);
        }
        Items.ToolItem parent = b.parent().isEmpty() ? null : cards.get(b.parent());
        List<Item> siblings = parent != null ? parent.children() : items;
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
            Items.HookGroupItem group = new Items.HookGroupItem(hook);
            parentOf.put(hook, group);
            shown = group;
        }
        if (parent != null) {
            parent.addChild(shown);
            parentOf.put(shown, parent);
            invalidateAncestors(parent);
        } else {
            items.add(shown);
        }
        if (!waitingFor.isEmpty()) {
            resolveWaiting();
        }
        structureChanged = true;
        changed();
    }

    /** The writer replaced the block at {@code index}. */
    public void update(int index, Block b) {
        Item item = byIndex.get(index);
        if (item == null || item.block == b) {
            return;
        }
        item.update(b);
        invalidateAncestors(parentOf.get(item));
        if (item instanceof Items.ToolItem card) {
            track(card);
            if (!waitingFor.isEmpty() || !waitingCards.isEmpty()) {
                resolveWaiting();
            }
        }
        structureChanged = true;
        changed();
    }

    /** Keeps the set of running cards, which drives the elapsed-time repaint. */
    private void track(Items.ToolItem card) {
        card.seen(nowMillis());
        if (card.isRunning()) {
            running.add(card);
        } else {
            running.remove(card);
        }
        updateTicker();
    }

    public Block blockAt(int index) {
        Item item = byIndex.get(index);
        return item == null ? null : item.block;
    }

    public int itemCount() {
        return byIndex.size();
    }

    /** The item of the block at {@code index}; for tests. */
    Item itemAt(int index) {
        return byIndex.get(index);
    }

    /** The top-level items in order; for tests. */
    List<Item> topLevel() {
        return Collections.unmodifiableList(items);
    }

    /** For tests: whether the elapsed-time repaint runs. */
    boolean ticking() {
        return ticker.isRunning();
    }

    // ---- running cards and approvals

    /**
     * The approval cards that hold up this session's tool calls. A card whose {@code toolUseId} names a running
     * call marks that call; one without an id marks the last running call of its tool, by plain name or as an
     * MCP tool ({@code mcp__server__name}).
     */
    public void setWaiting(List<Approval> approvals) {
        waitingFor = List.copyOf(approvals);
        resolveWaiting();
    }

    private void resolveWaiting() {
        Set<Items.ToolItem> now = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Approval a : waitingFor) {
            Items.ToolItem card = null;
            if (!a.toolUseId().isEmpty()) {
                card = cards.get(a.toolUseId());
            } else {
                for (Items.ToolItem c : running) {
                    if (isTool(c.call().name(), a.toolName()) && (card == null || c.order() > card.order())) {
                        card = c;
                    }
                }
            }
            if (card != null && card.isRunning()) {
                now.add(card);
            }
        }
        if (now.equals(waitingCards)) {
            return;
        }
        for (Items.ToolItem c : waitingCards) {
            c.setWaiting(now.contains(c));
        }
        for (Items.ToolItem c : now) {
            c.setWaiting(true);
        }
        waitingCards = now;
        repaint();
    }

    private static boolean isTool(String callName, String toolName) {
        return !toolName.isEmpty() && (callName.equals(toolName) || callName.endsWith("__" + toolName));
    }

    /** The status a card shows beside its title ({@code running · 12 s}, the wait for an approval); empty when done or unknown. */
    public String cardStatus(String toolUseId) {
        Items.ToolItem card = cards.get(toolUseId);
        return card == null ? "" : card.statusText(nowMillis());
    }

    /** Whether some card runs, so the elapsed-time repaint has work while the canvas is on screen. */
    public boolean hasRunningCards() {
        return !running.isEmpty();
    }

    /** For tests: the clock running cards are timed by. */
    void clock(LongSupplier millis) {
        clock = millis;
    }

    @Override
    public long nowMillis() {
        return clock.getAsLong();
    }

    private void updateTicker() {
        boolean want = !running.isEmpty() && isShowing();
        if (want && !ticker.isRunning()) {
            ticker.start();
        } else if (!want && ticker.isRunning()) {
            ticker.stop();
        }
    }

    private void tick() {
        if (running.isEmpty() || !isShowing()) {
            ticker.stop();
            return;
        }
        repaint();
    }

    private void invalidateAncestors(Item p) {
        for (; p != null; p = parentOf.get(p)) {
            p.invalidate();
        }
    }

    private void changed() {
        placed = null;
        revalidate();
        repaint();
    }

    public void setZoom(int steps) {
        zoom = steps;
        style = null;
        structureChanged = true;
        clearSelection();
        changed();
    }

    public int zoom() {
        return zoom;
    }

    // ---- layout

    private Style style() {
        if (style == null) {
            style = Style.of(zoom);
        }
        return style;
    }

    /**
     * Lays out at width {@code w}. Changed items and items near the viewport are laid out now; after a width
     * or style change, items far from it get an estimated height and the idle pass lays them out later,
     * keeping the view where it was. Cost per call is bounded by what is visible, not by the session length.
     */
    public void ensureLayout(int w) {
        layout(w, false);
    }

    /** Lays out every item exactly at width {@code w}; find, select-all and measurements need exact positions. */
    public void layoutFully(int w) {
        layout(w, true);
    }

    private void layout(int w, boolean exact) {
        int inner = Math.max(80, w - 2 * PAD_X);
        Style s = style();
        boolean any = inner != laidWidth || structureChanged || tops.length != items.size() || (exact && estimated);
        if (!any) {
            for (Item item : items) {
                if (item.isDirty()) {
                    any = true;
                    break;
                }
            }
        }
        if (!any) {
            return;
        }
        laidWidth = inner;
        structureChanged = false;
        if (tops.length != items.size()) {
            tops = Arrays.copyOf(tops, items.size());
            heights = Arrays.copyOf(heights, items.size());
        }
        Rectangle vis = getVisibleRect();
        int lo = vis.y;
        int hi = vis.y + vis.height;
        boolean guessed = false;
        int y = PAD_Y;
        for (int i = 0; i < items.size(); i++) {
            Item item = items.get(i);
            tops[i] = y;
            int h;
            if (!item.needsLayout(inner, s)) {
                h = item.height;
            } else if (exact || item.width() <= 0 || item.isDirty() || (y <= hi && y + item.estimate(inner) >= lo)) {
                h = item.layout(inner, s);
            } else {
                h = item.estimate(inner);
                guessed = true;
            }
            heights[i] = h;
            y += h;
        }
        total = y + PAD_Y;
        placed = null;
        estimated = guessed;
        if (estimated) {
            refiner.start();
        }
    }

    private void recomputeTops() {
        int y = PAD_Y;
        for (int i = 0; i < items.size(); i++) {
            tops[i] = y;
            y += heights[i];
        }
        total = y + PAD_Y;
        placed = null;
    }

    /** Lays out stale items inside {@code clip} before they are painted; true when positions moved. */
    private boolean layoutVisible(Rectangle clip) {
        if (!estimated) {
            return false;
        }
        boolean moved = false;
        Style s = style();
        for (int round = 0; round < 3; round++) {
            boolean changed = false;
            for (int i = firstAt(clip.y); i < items.size() && tops[i] <= clip.y + clip.height; i++) {
                Item item = items.get(i);
                if (item.needsLayout(laidWidth, s)) {
                    heights[i] = item.layout(laidWidth, s);
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
            revalidate();
        }
        return moved;
    }

    /** One slice of the idle pass: lays out stale items nearest the view for up to 8 ms, keeping the view in place. */
    private void refine() {
        Style s = style();
        long deadline = System.nanoTime() + 8_000_000L;
        Rectangle vis = getVisibleRect();
        boolean atEnd = vis.y + vis.height >= total - 40;
        int first = Math.min(firstAt(vis.y), Math.max(0, items.size() - 1));
        int offset = items.isEmpty() ? 0 : vis.y - tops[first];
        boolean left = false;
        boolean changed = false;
        for (int d = 0; d < items.size() * 2 + 2; d++) {
            int i = first + (d % 2 == 0 ? d / 2 : -(d / 2 + 1));
            if (i < 0 || i >= items.size()) {
                continue;
            }
            Item item = items.get(i);
            if (item.needsLayout(laidWidth, s)) {
                if (System.nanoTime() > deadline) {
                    left = true;
                    break;
                }
                heights[i] = item.layout(laidWidth, s);
                changed = true;
            }
        }
        estimated = left;
        if (!left) {
            refiner.stop();
        }
        if (!changed) {
            return;
        }
        recomputeTops();
        if (getParent() instanceof JViewport v) {
            v.setViewSize(new Dimension(getWidth(), total));
            int y = atEnd ? Math.max(0, total - vis.height) : Math.max(0, tops[first] + offset);
            v.setViewPosition(new Point(0, y));
        }
        revalidate();
        repaint();
    }

    /** Runs the idle pass to the end at once; for measurements and tests. */
    public void refineAll() {
        while (estimated) {
            refine();
        }
    }

    public boolean isEstimated() {
        return estimated;
    }

    private int viewportWidth() {
        Component p = getParent();
        return p instanceof JViewport v ? v.getWidth() : getWidth();
    }

    @Override
    public Dimension getPreferredSize() {
        int w = viewportWidth();
        if (w <= 0) {
            return new Dimension(400, 100);
        }
        ensureLayout(w);
        return new Dimension(w, total);
    }

    // ---- painting

    @Override
    protected void paintComponent(Graphics g0) {
        ensureLayout(getWidth());
        Graphics2D g = (Graphics2D) g0.create();
        try {
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            Rectangle clip = g.getClipBounds();
            if (clip == null) {
                clip = new Rectangle(0, 0, getWidth(), getHeight());
            }
            layoutVisible(clip);
            ensurePlaced();
            for (int i = firstAt(clip.y); i < items.size(); i++) {
                int top = tops[i];
                if (top > clip.y + clip.height) {
                    break;
                }
                Item item = items.get(i);
                item.paint(g, PAD_X, top, clip, this);
                if (item == flashing) {
                    g.setColor(new Color(Theme.ACCENT.getRed(), Theme.ACCENT.getGreen(), Theme.ACCENT.getBlue(), 50));
                    g.fillRoundRect(PAD_X - 4, top, laidWidth + 8, heights[i], Theme.RADIUS, Theme.RADIUS);
                }
            }
        } finally {
            g.dispose();
        }
    }

    /**
     * The first item whose bottom is below {@code y}; {@code items.size()} when none. A block appended since
     * the last layout has no position yet, so a mouse event lays out first instead of indexing past it.
     */
    private int firstAt(int y) {
        if (tops.length != items.size()) {
            ensureLayout(Math.max(getWidth(), 1));
        }
        int lo = 0;
        int hi = items.size() - 1;
        int found = items.size();
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
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

    @Override
    public int[] selection(TextBox box) {
        if (anchorBox == null || focusBox == null || boxIndex == null) {
            return NONE;
        }
        Integer i = boxIndex.get(box);
        Integer a = boxIndex.get(anchorBox);
        Integer f = boxIndex.get(focusBox);
        if (i == null || a == null || f == null) {
            return NONE;
        }
        boolean forward = a < f || (a.equals(f) && anchorChar <= focusChar);
        int startBox = forward ? a : f;
        int endBox = forward ? f : a;
        int startChar = forward ? anchorChar : focusChar;
        int endChar = forward ? focusChar : anchorChar;
        if (i < startBox || i > endBox) {
            return NONE;
        }
        return new int[]{i == startBox ? startChar : 0, i == endBox ? endChar : box.length()};
    }

    @Override
    public List<int[]> finds(TextBox box) {
        return matchesByBox.get(box);
    }

    @Override
    public int currentFind(TextBox box) {
        if (currentMatch < 0 || placed == null) {
            return -1;
        }
        int[] m = matches.get(currentMatch);
        if (placed.get(m[0]).box() != box) {
            return -1;
        }
        List<int[]> inBox = matchesByBox.get(box);
        for (int i = 0; inBox != null && i < inBox.size(); i++) {
            if (inBox.get(i)[0] == m[1]) {
                return i;
            }
        }
        return -1;
    }

    @Override
    public Component component() {
        return this;
    }

    private void ensurePlaced() {
        if (placed != null) {
            return;
        }
        placed = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            items.get(i).collect(PAD_X, tops[i], placed);
        }
        boxIndex = new IdentityHashMap<>(placed.size() * 2);
        for (int i = 0; i < placed.size(); i++) {
            boxIndex.put(placed.get(i).box(), i);
        }
        if (anchorBox != null && (!boxIndex.containsKey(anchorBox) || !boxIndex.containsKey(focusBox))) {
            clearSelection();
        }
        if (!findText.isEmpty()) {
            rematch();
        }
    }

    /** Placed boxes need exact positions; an estimated layout is completed first. */
    private void ensureExact() {
        // a canvas never laid out (its tab not yet shown) has items but no tops, and is not marked estimated
        if (estimated || tops.length != items.size()) {
            layoutFully(getWidth() > 0 ? getWidth() : viewportWidth());
        }
    }

    // ---- mouse

    private Placed boxAt(int x, int y, boolean nearest) {
        ensureLayout(getWidth());
        ensureExact();
        ensurePlaced();
        Placed best = null;
        for (Placed p : placed) {
            if (p.contains(x, y) && x >= p.x() - 4 && x <= p.x() + p.box().width() + 4) {
                return p;
            }
            if (nearest && p.y() <= y) {
                best = p;
            }
        }
        return best;
    }

    private void pressed(MouseEvent e) {
        requestFocusInWindow();
        if (!SwingUtilities.isLeftMouseButton(e)) {
            return;
        }
        int i = firstAt(e.getY());
        if (i < items.size() && e.getY() >= tops[i]) {
            Item toggle = items.get(i).toggleAt(e.getX() - PAD_X, e.getY() - tops[i]);
            if (toggle != null && toggle.toggle()) {
                invalidateAncestors(parentOf.get(toggle));
                structureChanged = true;
                changed();
                return;
            }
        }
        Placed p = boxAt(e.getX(), e.getY(), true);
        pressedLink = null;
        if (p == null) {
            clearSelection();
            repaint();
            return;
        }
        pressedLink = p.contains(e.getX(), e.getY()) ? p.box().linkAt(e.getX() - p.x(), e.getY() - p.y()) : null;
        if (e.getClickCount() == 2) {
            selectWord(p, p.box().indexAt(e.getX() - p.x(), e.getY() - p.y()));
        } else if (e.isShiftDown() && anchorBox != null) {
            focusBox = p.box();
            focusChar = p.box().indexAt(e.getX() - p.x(), e.getY() - p.y());
        } else {
            anchorBox = focusBox = p.box();
            anchorChar = focusChar = p.box().indexAt(e.getX() - p.x(), e.getY() - p.y());
        }
        repaint();
    }

    private void dragged(MouseEvent e) {
        if (anchorBox == null) {
            return;
        }
        Placed p = boxAt(e.getX(), e.getY(), true);
        if (p != null) {
            focusBox = p.box();
            focusChar = p.box().indexAt(e.getX() - p.x(), e.getY() - p.y());
            pressedLink = null;
            repaint();
        }
        scrollRectToVisible(new Rectangle(e.getX(), e.getY(), 1, 1));
    }

    private void released(MouseEvent e) {
        if (pressedLink != null && anchorBox == focusBox && anchorChar == focusChar) {
            String href = pressedLink;
            pressedLink = null;
            links.accept(href);
        }
    }

    private void moved(MouseEvent e) {
        int i = firstAt(e.getY());
        if (i < items.size() && e.getY() >= tops[i] && items.get(i).toggleAt(e.getX() - PAD_X, e.getY() - tops[i]) != null) {
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            return;
        }
        Placed p = boxAt(e.getX(), e.getY(), false);
        if (p == null) {
            setCursor(Cursor.getDefaultCursor());
        } else if (p.box().linkAt(e.getX() - p.x(), e.getY() - p.y()) != null) {
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        } else {
            setCursor(Cursor.getPredefinedCursor(Cursor.TEXT_CURSOR));
        }
    }

    private void selectWord(Placed p, int at) {
        String t = p.box().text();
        int s = Math.min(at, t.length());
        int e = s;
        while (s > 0 && Character.isLetterOrDigit(t.charAt(s - 1)) || s > 0 && t.charAt(s - 1) == '_') {
            s--;
        }
        while (e < t.length() && (Character.isLetterOrDigit(t.charAt(e)) || t.charAt(e) == '_')) {
            e++;
        }
        anchorBox = focusBox = p.box();
        anchorChar = s;
        focusChar = e;
    }

    // ---- selection

    public void clearSelection() {
        anchorBox = focusBox = null;
        anchorChar = focusChar = 0;
    }

    public void selectAll() {
        layoutFully(getWidth());
        ensurePlaced();
        if (placed.isEmpty()) {
            return;
        }
        anchorBox = placed.getFirst().box();
        anchorChar = 0;
        focusBox = placed.getLast().box();
        focusChar = focusBox.length();
        repaint();
    }

    /** The selected text; boxes are joined by newlines. Empty when nothing is selected. */
    public String selectionText() {
        if (anchorBox == null) {
            return "";
        }
        ensureExact();
        ensurePlaced();
        StringBuilder sb = new StringBuilder();
        for (Placed p : placed) {
            int[] r = selection(p.box());
            if (r[0] < r[1]) {
                if (!sb.isEmpty()) {
                    sb.append('\n');
                }
                sb.append(p.box().text(), r[0], r[1]);
            }
        }
        return sb.toString();
    }

    // ---- find

    /**
     * Finds {@code text}, opening the closed items that hold it; returns the number of matches. A new text first
     * closes what the previous one opened.
     */
    public int find(String text, boolean forward) {
        if (!text.equals(findText)) {
            findText = text;
            currentMatch = -1;
            concealRevealed(Set.of());
            if (!text.isEmpty()) {
                revealContaining(items, text.toLowerCase(Locale.ROOT));
            }
            layoutFully(getWidth() > 0 ? getWidth() : viewportWidth());
            placed = null;
        }
        ensurePlaced();
        rematch();
        if (matches.isEmpty()) {
            currentMatch = -1;
            repaint();
            return 0;
        }
        currentMatch = currentMatch < 0 ? (forward ? 0 : matches.size() - 1)
                : Math.floorMod(currentMatch + (forward ? 1 : -1), matches.size());
        int[] m = matches.get(currentMatch);
        Placed p = placed.get(m[0]);
        int y = p.y() + Math.round(p.box().topOf(m[1]));
        Rectangle visible = getVisibleRect();
        scrollRectToVisible(new Rectangle(0, Math.max(0, y - visible.height / 3), 1, visible.height / 2));
        repaint();
        return matches.size();
    }

    /**
     * Ends the find: the items it opened close again, except those the user toggled since and the item holding the
     * current match, with the cards around it.
     */
    public void clearFind() {
        Set<Item> keep = Collections.newSetFromMap(new IdentityHashMap<>());
        if (!findText.isEmpty() && !revealed.isEmpty()) {
            ensureExact();
            ensurePlaced();
            if (currentMatch >= 0 && currentMatch < matches.size()) {
                for (Item i = placed.get(matches.get(currentMatch)[0]).item(); i != null; i = parentOf.get(i)) {
                    keep.add(i);
                }
            }
        }
        findText = "";
        clearMatches();
        if (concealRevealed(keep)) {
            structureChanged = true;
            changed();
        } else {
            repaint();
        }
    }

    /** Closes what the find opened, except {@code keep}; true when something closed. */
    private boolean concealRevealed(Set<Item> keep) {
        boolean closed = false;
        for (Item item : revealed) {
            if (item.fold().conceal(keep.contains(item))) {
                item.foldChanged();
                invalidateAncestors(parentOf.get(item));
                closed = true;
            }
        }
        revealed.clear();
        if (closed) {
            structureChanged = true;
        }
        return closed;
    }

    private void clearMatches() {
        matches.clear();
        matchesByBox = new IdentityHashMap<>();
        currentMatch = -1;
    }

    private void rematch() {
        int keep = currentMatch;
        matches.clear();
        matchesByBox = new IdentityHashMap<>();
        if (findText.isEmpty()) {
            currentMatch = -1;
            return;
        }
        String needle = findText.toLowerCase(Locale.ROOT);
        for (int i = 0; i < placed.size(); i++) {
            TextBox box = placed.get(i).box();
            String hay = box.text().toLowerCase(Locale.ROOT);
            for (int at = hay.indexOf(needle); at >= 0; at = hay.indexOf(needle, at + needle.length())) {
                matches.add(new int[]{i, at});
                matchesByBox.computeIfAbsent(box, k -> new ArrayList<>()).add(new int[]{at, at + needle.length()});
            }
        }
        currentMatch = keep < matches.size() ? keep : -1;
    }

    private void revealContaining(List<Item> list, String needle) {
        for (Item item : list) {
            Fold fold = item.fold();
            if ((fold == null && item.children().isEmpty()) || !subtreeContains(item, needle)) {
                continue;
            }
            if (fold != null && fold.reveal()) {
                item.foldChanged();
                invalidateAncestors(parentOf.get(item));
                revealed.add(item);
                structureChanged = true;
            }
            revealContaining(item.children(), needle);
        }
    }

    /** True when the item or any block nested in it, at any depth, contains {@code needle}. */
    private static boolean subtreeContains(Item item, String needle) {
        if (item.text().toLowerCase(Locale.ROOT).contains(needle)) {
            return true;
        }
        for (Item c : item.children()) {
            if (subtreeContains(c, needle)) {
                return true;
            }
        }
        return false;
    }

    // ---- navigation

    /** Scrolls to the last prompt and highlights it briefly; false when there is none. */
    public boolean jumpToLastPrompt() {
        ensureExact();
        for (int i = items.size() - 1; i >= 0; i--) {
            if (items.get(i) instanceof Items.PromptItem) {
                Rectangle visible = getVisibleRect();
                scrollRectToVisible(new Rectangle(0, Math.max(0, tops[i] - 20), 1, visible.height));
                flash(items.get(i));
                return true;
            }
        }
        return false;
    }

    private void flash(Item item) {
        flashing = item;
        repaint();
        Timer t = new Timer(900, e -> {
            flashing = null;
            repaint();
        });
        t.setRepeats(false);
        t.start();
    }

    /**
     * All blocks as Markdown, in order: prompts quoted, text as written, a tool call as its summary line with its
     * input and result fenced, notes in italics, each turn's duration and cost as an italic footer; thinking stays out.
     */
    public String markdown() {
        StringBuilder sb = new StringBuilder();
        for (Item item : items) {
            String m = item.markdown();
            if (!m.isEmpty()) {
                sb.append(m).append("\n\n");
            }
        }
        return sb.toString();
    }

    public int contentHeight() {
        return total;
    }

    // ---- Scrollable

    @Override
    public Dimension getPreferredScrollableViewportSize() {
        return getPreferredSize();
    }

    @Override
    public int getScrollableUnitIncrement(Rectangle visible, int orientation, int direction) {
        return 24;
    }

    @Override
    public int getScrollableBlockIncrement(Rectangle visible, int orientation, int direction) {
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
