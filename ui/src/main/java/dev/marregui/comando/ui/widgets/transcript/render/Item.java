package dev.marregui.comando.ui.widgets.transcript.render;

import dev.marregui.comando.core.transcript.Block;

import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.util.List;

/**
 * The painter of one transcript block. Keeps its layout until the block, the width or the style changes;
 * coordinates passed in are the item's top left in canvas space.
 */
abstract class Item {

    Block block;
    private int width = -1;
    private Style style;
    private boolean dirty = true;
    int height;

    Item(Block block) {
        this.block = block;
    }

    /** The writer replaced the block; the next layout redoes this item. */
    final void update(Block next) {
        Block previous = block;
        block = next;
        changed(previous, next);
        dirty = true;
    }

    void changed(Block previous, Block next) {
    }

    final void invalidate() {
        dirty = true;
    }

    final boolean isDirty() {
        return dirty;
    }

    final int layout(int w, Style s) {
        if (!dirty && w == width && s == style) {
            return height;
        }
        width = w;
        style = s;
        height = doLayout(w, s);
        dirty = false;
        return height;
    }

    /** True when the next layout at {@code w} with {@code s} does work. */
    final boolean needsLayout(int w, Style s) {
        return dirty || w != width || s != style;
    }

    /**
     * A guess of the height at width {@code w} without laying out: wrapped text grows as the width shrinks.
     * Used for items far from the viewport until the idle pass lays them out.
     */
    final int estimate(int w) {
        if (width <= 0 || height <= 0) {
            return 24;
        }
        double h = height * (double) width / w;
        return (int) Math.round(Math.clamp(h, height * 0.5, height * 2.0));
    }

    final int width() {
        return width;
    }

    final Style style() {
        return style;
    }

    abstract int doLayout(int w, Style s);

    abstract void paint(Graphics2D g, int x, int y, Rectangle clip, Marks marks);

    /** Adds this item's text boxes, in reading order, at absolute positions. */
    abstract void collect(int x, int y, List<Placed> out);

    /** The item whose header is at {@code (px, py)} (relative), to open or close; null when none. */
    Item toggleAt(int px, int py) {
        return null;
    }

    /** Opens or closes the item; false when it has nothing to show. */
    boolean toggle() {
        return false;
    }

    /** Items nested in this one, for find over closed cards. */
    List<Item> children() {
        return List.of();
    }

    /** All text of the block, open or closed, for find and copy. */
    abstract String text();

    /** The block as Markdown for copying; empty for blocks that are not copied. */
    String markdown() {
        return text();
    }
}
