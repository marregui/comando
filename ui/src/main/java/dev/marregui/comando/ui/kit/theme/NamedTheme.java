package dev.marregui.comando.ui.kit.theme;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

/**
 * A curated theme (record 103): a ground and the tint that sits on it, chosen together in Actions ▸ Theme or from the
 * swatch beside Clear. Six, each looked at as a whole window; Miguel chose the set on 2026-10-27, and the ground and
 * the tint are no longer chosen apart (decision D3).
 */
public enum NamedTheme {

    GRAPHITE_GOLD("Graphite and gold", Ground.GRAPHITE, Tint.TREASURE),
    ESPRESSO_CHAMPAGNE("Espresso and champagne", Ground.ESPRESSO, Tint.CHAMPAGNE),
    AUBERGINE_GOLD("Aubergine and yellow gold", Ground.AUBERGINE, Tint.YELLOW),
    MIDNIGHT_BLUE("Midnight and electric blue", Ground.MIDNIGHT, Tint.ELECTRIC_BLUE),
    EVERGREEN_FOREST("Evergreen and tropical forest", Ground.EVERGREEN, Tint.TROPICAL_FOREST),
    SLATE_SILVER("Slate and silver", Ground.SLATE, Tint.SILVER);

    public final String label;
    public final Ground ground;
    public final Tint tint;

    NamedTheme(String label, Ground ground, Tint tint) {
        this.label = label;
        this.ground = ground;
        this.tint = tint;
    }

    /** The command that chooses this theme: {@code theme.graphite_gold}. */
    public String commandId() {
        return "theme." + name().toLowerCase(Locale.ROOT);
    }

    /** The curated theme of {@code ground} and {@code tint}, if they make one. */
    public static Optional<NamedTheme> of(Ground ground, Tint tint) {
        return Arrays.stream(values()).filter(t -> t.ground == ground && t.tint == tint).findFirst();
    }

    /**
     * The theme for a ground and tint kept from before themes: the one they make; else, when a ground was chosen, the
     * theme on that ground; else the theme of that tint; else {@link #GRAPHITE_GOLD}.
     */
    public static NamedTheme closest(Optional<Ground> ground, Tint tint) {
        Optional<NamedTheme> pair = ground.flatMap(g -> of(g, tint));
        if (pair.isPresent()) {
            return pair.get();
        }
        return ground.flatMap(g -> Arrays.stream(values()).filter(t -> t.ground == g).findFirst())
                .or(() -> Arrays.stream(values()).filter(t -> t.tint == tint).findFirst())
                .orElse(GRAPHITE_GOLD);
    }

    /** The curated theme chosen now, if the ground and tint chosen make one. */
    public static Optional<NamedTheme> chosen() {
        return of(Theme.groundChosen(), Theme.tint());
    }
}
