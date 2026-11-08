package dev.marregui.comando.ui.widgets.transcript.render;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import dev.marregui.comando.ui.kit.text.CaseFold;

/**
 * Find in the transcript: the matches over the whole conversation, the current one, and the closed items a find
 * opened, which close again when it ends. The matches are read from the blocks' text as each item's boxes would show
 * it ({@link Item#findTexts}), so a find lays out nothing; a match is a top-level item, the box within it in reading
 * order, and the offset in that box's text. Only the item of the match shown is laid out, to scroll to it; the boxes
 * the view has laid out take their marks from their own text. EDT only.
 */
final class Finder {

    /** What a find needs from the canvas. */
    interface Host {

        /** The top-level items in order. */
        List<Item> items();

        /** The card, hook group or turn's steps {@code item} sits in; null at the top level. */
        Item parentOf(Item item);

        /** The fold of {@code item} changed: it and the items around it lay out again. */
        void foldChanged(Item item);

        /** Items opened or closed: the structure lays out again. */
        void structureChanged();

        /** Lays out the top-level item at {@code index} if it is not, and returns its boxes in reading order, placed. */
        List<Placed> place(int index);

        /** A count that changes whenever a block, a fold or the style does: matches counted before it are stale. */
        long version();

        /** Scrolls the content at {@code y} into the view. */
        void scrollTo(int y);

        void repaint();
    }

    private final Host host;
    /** The matches, in reading order: top-level item, box within it, offset; {@link #count} of them. */
    private int[] matchItem = new int[0];
    private int[] matchBox = new int[0];
    private int[] matchAt = new int[0];
    private int count;
    /** The {@link Host#version} the matches were counted at; -1 when they must be counted again. */
    private long countedAt = -1;
    private int currentMatch = -1;
    private String findText = "";
    private String needle = "";
    /** The marks of the boxes laid out now, from their own text, and the box holding the current match. */
    private Map<TextBox, List<int[]>> matchesByBox = new IdentityHashMap<>();
    private TextBox currentBox;
    /**
     * Where {@link #rewind} left the current match: its top-level item, the item that owns its box (the top-level item
     * or one nested in it), the box's place among that owner's own boxes, and the offset; item -1 when not rewound. By
     * owner, not by place in the top-level item: the next text closes and opens nested cards before it.
     */
    private int rewoundItem = -1;
    private Item rewoundOwner;
    private int rewoundOwnBox;
    private int rewoundAt;
    /** Items the current find opened; clearing the find closes them again. */
    private final List<Item> revealed = new ArrayList<>();

    Finder(final Host host) {
        this.host = host;
    }

    /** Another session's blocks: the next find, even with the text the find bar kept, is a new one. */
    void reset() {
        revealed.clear();
        findText = "";
        needle = "";
        clearMatches();
    }

    boolean active() {
        return !findText.isEmpty();
    }

    /**
     * Finds {@code text}, opening the closed items that hold it; returns the number of matches. A new text first
     * closes what the previous one opened. The same text again moves to the next match, counting again first when
     * blocks arrived or changed since.
     */
    int find(final String text, final boolean forward) {
        boolean fromRewound = false;
        if (!text.equals(findText)) {
            fromRewound = rewoundItem >= 0;
            findText = text;
            needle = CaseFold.fold(text);
            currentMatch = -1;
            countedAt = -1;
            concealRevealed(Set.of());
            if (!text.isEmpty()) {
                revealContaining(host.items());
            }
            host.structureChanged();
        }
        if (countedAt != host.version()) {
            count();
            countedAt = host.version();
        }
        if (count == 0) {
            currentMatch = -1;
            dropRewound();
            host.repaint();
            return 0;
        }
        if (fromRewound) {
            currentMatch = atOrAfterRewound();
        } else {
            currentMatch = currentMatch < 0 ? (forward ? 0 : count - 1) : Math.floorMod(currentMatch + (forward ? 1 : -1), count);
        }
        dropRewound();
        final int y = currentMatchY();
        if (y >= 0) {
            host.scrollTo(y);
        }
        host.repaint();
        return count;
    }

    /**
     * Counts the matches over every item from the texts its boxes would show, keeping the current match when it still
     * is one. Each item's texts are made for the count and let go.
     */
    private void count() {
        final int keep = currentMatch;
        count = 0;
        final List<Item> items = host.items();
        // each item's matches as box and offset pairs, on every core; then in reading order
        final int[][] found = new int[items.size()][];
        final String folded = needle;
        Forks.each("transcript-find", found.length, i -> found[i] = matchesIn(items.get(i), folded));
        for (int i = 0; i < found.length; i++) {
            for (int j = 0; j < found[i].length; j += 2) {
                add(i, found[i][j], found[i][j + 1]);
            }
        }
        currentMatch = keep < count ? keep : -1;
    }

    private static final int[] NONE = new int[0];

    /** The matches of {@code needle} in {@code item}'s texts, as box and offset pairs. */
    private static int[] matchesIn(final Item item, final String needle) {
        final List<String> texts = new ArrayList<>();
        item.findTexts(texts);
        int[] out = NONE;
        int n = 0;
        for (int k = 0; k < texts.size(); k++) {
            final String hay = CaseFold.fold(texts.get(k));
            for (int at = hay.indexOf(needle); at >= 0; at = hay.indexOf(needle, at + needle.length())) {
                if (n + 2 > out.length) {
                    out = Arrays.copyOf(out, Math.max(8, out.length * 2));
                }
                out[n++] = k;
                out[n++] = at;
            }
        }
        return n == out.length ? out : Arrays.copyOf(out, n);
    }

    private void add(final int item, final int box, final int at) {
        if (count == matchItem.length) {
            final int n = Math.max(64, count * 2);
            matchItem = Arrays.copyOf(matchItem, n);
            matchBox = Arrays.copyOf(matchBox, n);
            matchAt = Arrays.copyOf(matchAt, n);
        }
        matchItem[count] = item;
        matchBox[count] = box;
        matchAt[count] = at;
        count++;
    }

    /**
     * {@code item} closed by itself, its turn over, and may have taken top-level items in: under a find it opens again
     * when it holds the text, as it would have for the find, and the matches are counted again as their places moved.
     * They keep their order, so the current one stays.
     */
    void folded(final Item item) {
        if (!active()) {
            return;
        }
        final List<Item> closed = new ArrayList<>();
        closedContaining(item, needle, closed);
        for (final Item i : closed) {
            if (i.fold().reveal()) {
                host.foldChanged(i);
                revealed.add(i);
            }
        }
        count();
    }

    /** The next find of a new text starts where the current match begins. */
    void rewind() {
        dropRewound();
        final Placed p = currentPlaced();
        if (p != null) {
            final List<Placed> boxes = host.place(matchItem[currentMatch]);
            int own = 0;
            for (int j = 0; j < matchBox[currentMatch]; j++) {
                if (boxes.get(j).item() == p.item()) {
                    own++;
                }
            }
            rewoundItem = matchItem[currentMatch];
            rewoundOwner = p.item();
            rewoundOwnBox = own;
            rewoundAt = matchAt[currentMatch];
        }
    }

    /**
     * The first match at or after the anchor, in reading order, the anchor's box found again among its owner's boxes
     * as the new text's folds place them; the owner's first box when that one is gone, the top-level item's start when
     * the owner shows none; the first match when no match is at or after it.
     */
    private int atOrAfterRewound() {
        int box = 0;
        int at = 0;
        if (rewoundItem < host.items().size()) {
            final List<Placed> boxes = host.place(rewoundItem);
            int own = 0;
            int first = -1;
            int found = -1;
            for (int j = 0; j < boxes.size() && found < 0; j++) {
                if (boxes.get(j).item() == rewoundOwner) {
                    first = first < 0 ? j : first;
                    found = own++ == rewoundOwnBox ? j : -1;
                }
            }
            if (found >= 0) {
                box = found;
                at = rewoundAt;
            } else if (first >= 0) {
                box = first;
            }
        }
        for (int k = 0; k < count; k++) {
            final int order = matchItem[k] != rewoundItem ? Integer.compare(matchItem[k], rewoundItem)
                    : matchBox[k] != box ? Integer.compare(matchBox[k], box) : Integer.compare(matchAt[k], at);
            if (order >= 0) {
                return k;
            }
        }
        return 0;
    }

    private void dropRewound() {
        rewoundItem = -1;
        rewoundOwner = null;
    }

    /** The current match's placed box, laying its item out; null when there is none. */
    private Placed currentPlaced() {
        if (currentMatch < 0 || currentMatch >= count || matchItem[currentMatch] >= host.items().size()) {
            return null;
        }
        final List<Placed> boxes = host.place(matchItem[currentMatch]);
        return matchBox[currentMatch] < boxes.size() ? boxes.get(matchBox[currentMatch]) : null;
    }

    /** The top of the current match in the canvas, -1 when there is none. */
    int currentMatchY() {
        final Placed p = currentPlaced();
        if (p == null) {
            return -1;
        }
        return p.y() + Math.round(p.box().topOf(Math.min(matchAt[currentMatch], p.box().length())));
    }

    /**
     * Ends the find: the items it opened close again, except those the user toggled since and the item holding the
     * current match, with the cards around it.
     */
    void clear() {
        final Set<Item> keep = Collections.newSetFromMap(new IdentityHashMap<>());
        if (!findText.isEmpty() && !revealed.isEmpty()) {
            final Placed p = currentPlaced();
            if (p != null) {
                for (Item i = p.item(); i != null; i = host.parentOf(i)) {
                    keep.add(i);
                }
            }
        }
        findText = "";
        needle = "";
        dropRewound();
        clearMatches();
        if (concealRevealed(keep)) {
            host.structureChanged();
        } else {
            host.repaint();
        }
    }

    /** Closes what the find opened, except {@code keep}; true when something closed. */
    private boolean concealRevealed(final Set<Item> keep) {
        boolean closed = false;
        for (final Item item : revealed) {
            if (item.fold().conceal(keep.contains(item))) {
                host.foldChanged(item);
                closed = true;
            }
        }
        revealed.clear();
        return closed;
    }

    private void clearMatches() {
        count = 0;
        countedAt = -1;
        matchesByBox = new IdentityHashMap<>();
        currentBox = null;
        currentMatch = -1;
    }

    /**
     * Opens the closed items that hold the needle, at any depth: which they are is read on every core, as it reads every
     * block's text; they open on the caller.
     */
    private void revealContaining(final List<Item> list) {
        // one slot per item, each set by one task: no slot is added or removed while they run
        final List<List<Item>> closed = new ArrayList<>(Collections.nCopies(list.size(), List.of()));
        final String folded = needle;
        Forks.each("transcript-reveal", closed.size(), i -> {
            final List<Item> out = new ArrayList<>();
            closedContaining(list.get(i), folded, out);
            closed.set(i, out);
        });
        for (final List<Item> items : closed) {
            for (final Item item : items) {
                if (item.fold().reveal()) {
                    host.foldChanged(item);
                    revealed.add(item);
                }
            }
        }
    }

    /** Adds {@code item} and the items nested in it that are closed and hold {@code needle}, outer first. */
    private static void closedContaining(final Item item, final String needle, final List<Item> out) {
        final Fold fold = item.fold();
        if ((fold == null && item.children().isEmpty()) || !subtreeContains(item, needle)) {
            return;
        }
        if (fold != null && !fold.isOpen()) {
            out.add(item);
        }
        for (final Item child : item.children()) {
            closedContaining(child, needle, out);
        }
    }

    /** True when the item or any block nested in it, at any depth, contains {@code needle}. */
    private static boolean subtreeContains(final Item item, final String needle) {
        if (CaseFold.contains(item.findable(), needle)) {
            return true;
        }
        for (final Item c : item.children()) {
            if (subtreeContains(c, needle)) {
                return true;
            }
        }
        return false;
    }

    // ---- what painting reads

    /** The boxes laid out now are placed again: their marks are read from their own text. */
    void startPlacing() {
        matchesByBox = new IdentityHashMap<>();
        currentBox = null;
    }

    /** The top-level item at {@code index} is laid out with {@code boxes}, in reading order: marks them. */
    void placed(final int index, final List<Placed> boxes) {
        if (findText.isEmpty()) {
            return;
        }
        for (final Placed p : boxes) {
            final String hay = CaseFold.fold(p.box().text());
            for (int at = hay.indexOf(needle); at >= 0; at = hay.indexOf(needle, at + needle.length())) {
                matchesByBox.computeIfAbsent(p.box(), k -> new ArrayList<>()).add(new int[]{at, at + needle.length()});
            }
        }
        if (currentMatch >= 0 && currentMatch < count && matchItem[currentMatch] == index && matchBox[currentMatch] < boxes.size()) {
            currentBox = boxes.get(matchBox[currentMatch]).box();
        }
    }

    /** The matches in {@code box}, as {@code [start, end]} pairs; null when none. */
    List<int[]> finds(final TextBox box) {
        return matchesByBox.get(box);
    }

    /** The index among {@code box}'s matches of the current match; -1 when it is not in that box. */
    int currentFind(final TextBox box) {
        if (box != currentBox || currentMatch < 0 || currentMatch >= count) {
            return -1;
        }
        final List<int[]> inBox = matchesByBox.get(box);
        for (int i = 0; inBox != null && i < inBox.size(); i++) {
            if (inBox.get(i)[0] == matchAt[currentMatch]) {
                return i;
            }
        }
        return -1;
    }

    /** The matches as {@code [item, box, offset]}, in reading order; for tests. */
    List<int[]> matches() {
        final List<int[]> out = new ArrayList<>(count);
        for (int k = 0; k < count; k++) {
            out.add(new int[]{matchItem[k], matchBox[k], matchAt[k]});
        }
        return out;
    }
}
