package dev.marregui.comando.ui.panels;

import java.awt.Rectangle;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.List;
import java.util.Map;
import java.util.function.BiPredicate;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;
import javax.swing.JTree;
import javax.swing.tree.TreePath;

import dev.marregui.comando.ui.kit.async.Debouncer;

/**
 * The card of a sidebar tab's rows, shown in place of a tooltip, as the Sessions tab shows its rows' cards. A click
 * shows the row's card ({@link #clicked}), which then follows the pointer onto the other rows, each row's card after a
 * rest of {@link SessionsSidebar#FOLLOW_MILLIS}, until it closes otherwise; off the rows it closes unless the pointer
 * goes on into it. It listens to the tree's pointer from its construction; the tab keeps its own clicks.
 *
 * @param <R> what a row of the tree stands for
 */
final class CardFollower<R> {

    /** What {@code R}'s card says. */
    private final Function<R, Card> card;
    /** The sidebar's cards. */
    private final Supplier<SessionsSidebar.Cards> cards;
    private final Debouncer cardWait = new Debouncer(SessionsSidebar.FOLLOW_MILLIS, this::showResting);
    /** The card's key on {@link #cards}, one per row. */
    private final Function<R, String> key;
    /** The row at a path of the tree, or null for a path that is no such row or no path. */
    private final Function<TreePath, R> row;
    /** Whether two rows are one row, the tree rebuilt in between; the second may be null. */
    private final BiPredicate<R, R> same;
    private final JTree tree;
    /** The row whose card shows, or null. */
    private R carded;
    /** After a click: the card follows the pointer onto the other rows, until it closes otherwise. */
    private boolean following;
    /** The row the pointer rests on, whose card waits. */
    private R resting;

    CardFollower(
            final JTree tree,
            final Supplier<SessionsSidebar.Cards> cards,
            final Function<TreePath, R> row,
            final Function<R, String> key,
            final BiPredicate<R, R> same,
            final Function<R, Card> card
    ) {
        this.tree = tree;
        this.cards = cards;
        this.row = row;
        this.key = key;
        this.same = same;
        this.card = card;
        tree.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseExited(final MouseEvent e) {
                // off the rows the card closes, unless the pointer goes on into it
                cardWait.stop();
                resting = null;
                if (carded != null && cards.get().showing(key.apply(carded))) {
                    cards.get().left();
                }
            }
        });
        tree.addMouseMotionListener(new MouseAdapter() {
            @Override
            public void mouseMoved(final MouseEvent e) {
                restOn(row.apply(tree.getPathForLocation(e.getX(), e.getY())));
            }
        });
    }

    /** {@code r} was clicked: its card shows at once, and from now on follows the pointer. */
    void clicked(final R r) {
        following = true;
        cardWait.stop();
        show(r);
    }

    /**
     * The card shown, with what it says now for the row {@code now} gives for its row; when that is null, its row gone,
     * it stays as it was until it closes.
     */
    void refresh(final UnaryOperator<R> now) {
        if (carded == null || !cards.get().showing(key.apply(carded))) {
            return;
        }
        final R r = now.apply(carded);
        if (r != null) {
            carded = r;
            final Card c = card.apply(r);
            cards.get().updateFacts(key.apply(r), c.title(), c.facts(), c.heading(), c.text());
        }
    }

    /**
     * The pointer is over {@code over}, or over no row. Back on the card's row, the card stays; on another row while
     * the card follows, that row's card shows after the rest; off every row, the card closes unless the pointer goes on
     * into it.
     */
    private void restOn(final R over) {
        if (carded != null && !cards.get().showing(key.apply(carded))) {
            // closed otherwise: its ✕, a click on it, the pointer leaving it for elsewhere
            carded = null;
            following = false;
        }
        if (over == null) {
            cardWait.stop();
            resting = null;
            if (carded != null) {
                cards.get().left();
            }
            return;
        }
        if (carded != null && same.test(carded, over)) {
            cardWait.stop();
            resting = null;
            cards.get().stay();
            return;
        }
        if (following && (resting == null || !same.test(resting, over))) {
            resting = over;
            cardWait.request();
        }
    }

    private void showResting() {
        if (resting != null && following) {
            show(resting);
        }
    }

    /** {@code r}'s card, beside the rows at its row's height. */
    private void show(final R r) {
        final Rectangle bounds = boundsOf(r);
        if (bounds == null) {
            return;
        }
        carded = r;
        resting = null;
        final Card c = card.apply(r);
        cards.get().showFacts(key.apply(r), c.title(), c.facts(), c.heading(), c.text(), tree, bounds, beside());
    }

    /** {@code r}'s row in the tree as it is now, or null when it is gone. */
    private Rectangle boundsOf(final R r) {
        for (int i = 0; i < tree.getRowCount(); i++) {
            final TreePath path = tree.getPathForRow(i);
            final R x = row.apply(path);
            if (x != null && same.test(r, x)) {
                return tree.getPathBounds(path);
            }
        }
        return null;
    }

    /**
     * Where a card beside the rows starts: after the widest row shown, reaching {@link SessionsSidebar#OVERLAP} back.
     */
    private int beside() {
        int widest = 0;
        for (int i = 0; i < tree.getRowCount(); i++) {
            final Rectangle b = tree.getRowBounds(i);
            widest = Math.max(widest, b.x + b.width);
        }
        final Rectangle seen = tree.getVisibleRect();
        final int right = seen.isEmpty() ? tree.getWidth() : seen.x + seen.width;
        return Math.max(0, Math.min(widest, right) - SessionsSidebar.OVERLAP);
    }

    /** What a row's card says: its title, its facts, and under a heading a text. */
    interface Card {

        String title();

        List<Map.Entry<String, String>> facts();

        String heading();

        String text();
    }
}
