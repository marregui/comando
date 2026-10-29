package dev.marregui.comando.ui.widgets.transcript.render;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Find in the transcript: the matches over the boxes in reading order, the current one, and the closed items a find
 * opened, which close again when it ends. EDT only.
 */
final class Finder {

    /** What a find needs from the canvas. */
    interface Host {

        /** The top-level items in order. */
        List<Item> items();

        /** The card or hook group {@code item} sits in; null at the top level. */
        Item parentOf(Item item);

        /** The boxes in reading order, exactly laid out. */
        List<Placed> placedExactly();

        /** The fold of {@code item} changed: it and the items around it lay out again. */
        void foldChanged(Item item);

        /** Lays out every item exactly, keeping the view; the boxes are read again. */
        void relayout();

        /** Items opened or closed: the structure lays out again. */
        void structureChanged();

        /** Scrolls the content at {@code y} into the view. */
        void scrollTo(int y);

        void repaint();
    }

    private final Host host;
    private final List<int[]> matches = new ArrayList<>();
    private Map<TextBox, List<int[]>> matchesByBox = new IdentityHashMap<>();
    private int currentMatch = -1;
    private String findText = "";
    /** Where {@link #rewind} left the current match: its box, its item and its offset; -1 when not rewound. */
    private TextBox rewoundBox;
    private Item rewoundItem;
    private int rewoundAt = -1;
    /** Items the current find opened; clearing the find closes them again. */
    private final List<Item> revealed = new ArrayList<>();

    Finder(Host host) {
        this.host = host;
    }

    /** Another session's blocks: the next find, even with the text the find bar kept, is a new one. */
    void reset() {
        revealed.clear();
        findText = "";
        clearMatches();
    }

    boolean active() {
        return !findText.isEmpty();
    }

    /**
     * Finds {@code text}, opening the closed items that hold it; returns the number of matches. A new text first
     * closes what the previous one opened.
     */
    int find(String text, boolean forward) {
        boolean fromRewound = false;
        if (!text.equals(findText)) {
            fromRewound = rewoundAt >= 0;
            findText = text;
            currentMatch = -1;
            concealRevealed(Set.of());
            if (!text.isEmpty()) {
                revealContaining(host.items(), text.toLowerCase(Locale.ROOT));
            }
            host.relayout();
        }
        // the same text again, after a switch or new blocks: matches are counted over every item
        List<Placed> placed = host.placedExactly();
        rematch(placed);
        if (matches.isEmpty()) {
            currentMatch = -1;
            dropRewound();
            host.repaint();
            return 0;
        }
        if (fromRewound) {
            currentMatch = atOrAfterRewound(placed);
        } else {
            currentMatch = currentMatch < 0 ? (forward ? 0 : matches.size() - 1)
                    : Math.floorMod(currentMatch + (forward ? 1 : -1), matches.size());
        }
        dropRewound();
        int[] m = matches.get(currentMatch);
        Placed p = placed.get(m[0]);
        host.scrollTo(p.y() + Math.round(p.box().topOf(m[1])));
        host.repaint();
        return matches.size();
    }

    /** The next find of a new text starts where the current match begins; {@code placed} null when not placed. */
    void rewind(List<Placed> placed) {
        dropRewound();
        if (placed != null && currentMatch >= 0 && currentMatch < matches.size()) {
            int[] m = matches.get(currentMatch);
            rewoundBox = placed.get(m[0]).box();
            rewoundItem = placed.get(m[0]).item();
            rewoundAt = m[1];
        }
    }

    /** The first match at or after the anchor, in reading order; the first match when the anchor's text is gone. */
    private int atOrAfterRewound(List<Placed> placed) {
        int from = -1;
        for (int i = 0; i < placed.size() && from < 0; i++) {
            if (placed.get(i).box() == rewoundBox) {
                from = i;
            }
        }
        for (int i = 0; i < placed.size() && from < 0; i++) {
            if (placed.get(i).item() == rewoundItem) {
                from = i;
            }
        }
        if (from < 0) {
            return 0;
        }
        for (int k = 0; k < matches.size(); k++) {
            int[] m = matches.get(k);
            if (m[0] > from || (m[0] == from && m[1] >= rewoundAt)) {
                return k;
            }
        }
        return 0;
    }

    private void dropRewound() {
        rewoundBox = null;
        rewoundItem = null;
        rewoundAt = -1;
    }

    /** The top of the current match in the canvas, -1 when there is none; {@code placed} null when not placed. */
    int currentMatchY(List<Placed> placed) {
        if (placed == null || currentMatch < 0 || currentMatch >= matches.size()) {
            return -1;
        }
        int[] m = matches.get(currentMatch);
        Placed p = placed.get(m[0]);
        return p.y() + Math.round(p.box().topOf(m[1]));
    }

    /**
     * Ends the find: the items it opened close again, except those the user toggled since and the item holding the
     * current match, with the cards around it.
     */
    void clear() {
        Set<Item> keep = Collections.newSetFromMap(new IdentityHashMap<>());
        if (!findText.isEmpty() && !revealed.isEmpty()) {
            List<Placed> placed = host.placedExactly();
            if (currentMatch >= 0 && currentMatch < matches.size()) {
                for (Item i = placed.get(matches.get(currentMatch)[0]).item(); i != null; i = host.parentOf(i)) {
                    keep.add(i);
                }
            }
        }
        findText = "";
        dropRewound();
        clearMatches();
        if (concealRevealed(keep)) {
            host.structureChanged();
        } else {
            host.repaint();
        }
    }

    /** Closes what the find opened, except {@code keep}; true when something closed. */
    private boolean concealRevealed(Set<Item> keep) {
        boolean closed = false;
        for (Item item : revealed) {
            if (item.fold().conceal(keep.contains(item))) {
                host.foldChanged(item);
                closed = true;
            }
        }
        revealed.clear();
        return closed;
    }

    private void clearMatches() {
        matches.clear();
        matchesByBox = new IdentityHashMap<>();
        currentMatch = -1;
    }

    /**
     * Matches the find's text over {@code placed}, the boxes in reading order, keeping the current match when it still
     * is one.
     */
    void rematch(List<Placed> placed) {
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
                host.foldChanged(item);
                revealed.add(item);
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

    // ---- what painting reads

    /** The matches in {@code box}, as {@code [start, end]} pairs; null when none. */
    List<int[]> finds(TextBox box) {
        return matchesByBox.get(box);
    }

    /** The index among {@code box}'s matches of the current match; -1 when it is not in that box. */
    int currentFind(TextBox box, List<Placed> placed) {
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
}
