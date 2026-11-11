package dev.marregui.comando.ui.kit.theme;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * A curated theme (record 103): a ground and the tint that sits on it, chosen together from the swatch beside New
 * panel or the palette. The ground and the tint are not chosen apart. Each is a JSON file (record 126): the fourteen
 * built in (six dark and their six light twins, records 127 and 128, then the two dark ones of the console style
 * without a twin, record 151), then those read from the data root's {@code themes} folder at start, which
 * {@link #offer} adds after them.
 */
public final class NamedTheme {

    public static final NamedTheme GRAPHITE_GOLD = builtIn("graphite_gold", Ground.GRAPHITE, Tint.TREASURE);
    public static final NamedTheme ESPRESSO_CHAMPAGNE = builtIn("espresso_champagne", Ground.ESPRESSO, Tint.CHAMPAGNE);
    public static final NamedTheme AUBERGINE_GOLD = builtIn("aubergine_gold", Ground.AUBERGINE, Tint.YELLOW);
    public static final NamedTheme MIDNIGHT_BLUE = builtIn("midnight_blue", Ground.MIDNIGHT, Tint.ELECTRIC_BLUE);
    public static final NamedTheme EVERGREEN_FOREST = builtIn("evergreen_forest", Ground.EVERGREEN, Tint.TROPICAL_FOREST);
    public static final NamedTheme SLATE_SILVER = builtIn("slate_silver", Ground.SLATE, Tint.SILVER);
    public static final NamedTheme PAPER_OCHRE = builtIn("paper_ochre", Ground.PAPER, Tint.OCHRE);
    public static final NamedTheme CREAM_BRONZE = builtIn("cream_bronze", Ground.CREAM, Tint.BRONZE);
    public static final NamedTheme LILAC_GOLD = builtIn("lilac_gold", Ground.LILAC, Tint.DEEP_GOLD);
    public static final NamedTheme ICE_NAVY = builtIn("ice_navy", Ground.ICE, Tint.NAVY);
    public static final NamedTheme MINT_FOREST = builtIn("mint_forest", Ground.MINT, Tint.FOREST);
    public static final NamedTheme FOG_SLATE = builtIn("fog_slate", Ground.FOG, Tint.SLATE_INK);
    public static final NamedTheme CONSOLE_AMBER = builtInOwn("console_amber");
    public static final NamedTheme CONSOLE_CYAN = builtInOwn("console_cyan");
    private static final List<NamedTheme> BUILT_IN = List.of(GRAPHITE_GOLD, ESPRESSO_CHAMPAGNE, AUBERGINE_GOLD, MIDNIGHT_BLUE,
            EVERGREEN_FOREST, SLATE_SILVER, PAPER_OCHRE, CREAM_BRONZE, LILAC_GOLD, ICE_NAVY, MINT_FOREST, FOG_SLATE, CONSOLE_AMBER, CONSOLE_CYAN);
    /** The themes offered: the built-in ones, then the user's files read at start. */
    private static volatile List<NamedTheme> offered = BUILT_IN;

    /** The theme's id, as its file gives it: {@code graphite_gold}. */
    public final String id;
    public final String label;
    public final Ground ground;
    public final Tint tint;
    /** The user's file it was read from; empty for a built-in theme. */
    public final Optional<Path> file;
    /** The id of the theme of the other mode this one pairs with, as its file names it (record 128). */
    public final Optional<String> twin;
    /** What the theme's colours do to the mind, in short, as its file gives it (record 147): shown in its tooltips. */
    public final Optional<String> description;
    /** How the theme draws the window around its colours, as its file gives it (record 151). */
    public final Style style;

    private NamedTheme(final String id,
                       final String label,
                       final Optional<String> description,
                       final Ground ground,
                       final Tint tint,
                       final Optional<Path> file,
                       final Optional<String> twin,
                       final Style style) {
        this.id = id;
        this.style = style;
        this.twin = twin;
        this.label = label;
        this.description = description;
        this.ground = ground;
        this.tint = tint;
        this.file = file;
    }

    private static NamedTheme builtIn(final String id, final Ground ground, final Tint tint) {
        final ThemeSpec s = BuiltInThemes.spec(id);
        return new NamedTheme(id, s.label(), s.description(), ground, tint, Optional.empty(), s.twin(), s.style());
    }

    /** The built-in theme {@code id} with a ground and a tint of its own, as a file's theme has. */
    private static NamedTheme builtInOwn(final String id) {
        return own(BuiltInThemes.spec(id), Optional.empty());
    }

    /** The theme {@code spec} describes, read from {@code file}, with a ground and a tint of its own. */
    static NamedTheme fromFile(final ThemeSpec spec, final Path file) {
        return own(spec, Optional.of(file));
    }

    /** The theme {@code spec} describes, with a ground and a tint named by its id. */
    private static NamedTheme own(final ThemeSpec spec, final Optional<Path> file) {
        final String name = spec.id().toUpperCase(Locale.ROOT);
        return new NamedTheme(spec.id(), spec.label(), spec.description(),
                new Ground(name, spec.groundLabel(), spec.ground(), spec.light()),
                new Tint(name, spec.tintLabel(), spec.highlight(), spec.body(), spec.shade(), spec.light()), file,
                spec.twin(), spec.style());
    }

    /** The command that chooses this theme: {@code theme.graphite_gold}. */
    public String commandId() {
        return "theme." + id;
    }

    @Override
    public String toString() {
        return id;
    }

    /** The themes offered, the built-in ones first, in the swatch's order. */
    public static NamedTheme[] values() {
        return offered.toArray(NamedTheme[]::new);
    }

    /**
     * The built-in themes, in the swatch's order: the six dark ones, then their light twins in the same order, then the
     * two of the console style.
     */
    public static List<NamedTheme> builtIn() {
        return BUILT_IN;
    }

    /** Offers {@code fromFiles} after the built-in themes, in place of any offered before. At start, before the window. */
    public static void offer(final List<NamedTheme> fromFiles) {
        final List<NamedTheme> all = new ArrayList<>(BUILT_IN);
        all.addAll(fromFiles);
        offered = List.copyOf(all);
    }

    /**
     * The theme of the other mode {@code t} pairs with, among {@code themes}: the one its {@code twin} names, else the
     * first whose {@code twin} names it. A twin may be named on either side (record 128).
     */
    public static Optional<NamedTheme> twinOf(final List<NamedTheme> themes, final NamedTheme t) {
        final List<NamedTheme> other = themes.stream().filter(o -> o.ground.light != t.ground.light).toList();
        return t.twin.flatMap(id -> other.stream().filter(o -> o.id.equals(id)).findFirst())
                .or(() -> other.stream().filter(o -> o.twin.map(t.id::equals).orElse(false)).findFirst());
    }

    /** The theme offered with id {@code id}, if one is. */
    public static Optional<NamedTheme> withId(final String id) {
        return offered.stream().filter(t -> t.id.equals(id)).findFirst();
    }

    /** The curated theme of {@code ground} and {@code tint}, if they make one. */
    public static Optional<NamedTheme> of(final Ground ground, final Tint tint) {
        return offered.stream().filter(t -> t.ground == ground && t.tint == tint).findFirst();
    }

    /**
     * The theme for a ground and tint stored apart: the one they make; else, when a ground was chosen, the theme on
     * that ground; else the theme of that tint; else {@link #GRAPHITE_GOLD}.
     */
    public static NamedTheme closest(final Optional<Ground> ground, final Tint tint) {
        final Optional<NamedTheme> pair = ground.flatMap(g -> of(g, tint));
        if (pair.isPresent()) {
            return pair.get();
        }
        return ground.flatMap(g -> offered.stream().filter(t -> t.ground == g).findFirst())
                .or(() -> offered.stream().filter(t -> t.tint == tint).findFirst())
                .orElse(GRAPHITE_GOLD);
    }

    /** The curated theme chosen now, if the ground and tint chosen make one. */
    public static Optional<NamedTheme> chosen() {
        return of(Theme.groundChosen(), Theme.tint());
    }
}
