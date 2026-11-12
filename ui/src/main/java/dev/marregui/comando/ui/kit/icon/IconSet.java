package dev.marregui.comando.ui.kit.icon;

import java.util.Arrays;
import java.util.Optional;

/** The SVG families shipped with comando. */
public enum IconSet {

    CLASSIC("classic", "Classic"),
    HAIRLINE("hairline", "Hairline"),
    GOLD_DUOTONE("gold-duotone", "Gold duotone"),
    SOLID_GLYPH("solid-glyph", "Solid glyph"),
    CONSOLE("console", "Console"),
    CHEVRON_LINE("chevron-line", "Chevron line"),
    GRAPHITE_TILE("graphite-tile", "Graphite tile"),
    SIGNAL_LAMP("signal-lamp", "Signal lamp"),
    MONOSPACE("monospace", "Monospace");

    private final String id;
    private final String label;

    IconSet(final String id, final String label) {
        this.id = id;
        this.label = label;
    }

    public static Optional<IconSet> withId(final String id) {
        return Arrays.stream(values()).filter(set -> set.id.equals(id)).findFirst();
    }

    public String id() {
        return id;
    }

    public String label() {
        return label;
    }

    public String commandId() {
        return "icons." + id;
    }

    public String path(final String name) {
        return this == CLASSIC ? "icons/" + name + ".svg" : "icons/sets/" + id + "/" + name + ".svg";
    }
}
