package dev.marregui.comando.ui.kit.theme;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// [Theme#choose] applies a theme's ground and tint in one pass.
/// - Everything listening to the tint runs once, and sees both the new ground and the new tint.
/// - Choosing the pair already chosen runs nothing.
class ThemeChoiceTest {

    /// One choice of ground and tint runs each listener once, after both are set; the same pair again runs none.
    @Test
    void aThemeIsAppliedInOnePass() {
        final List<String> heard = new ArrayList<>();
        final Runnable stop = Theme.onTint(() -> heard.add(Theme.groundChosen() + "/" + Theme.tint()));
        try {
            Theme.choose(Ground.MIDNIGHT, Tint.ELECTRIC_BLUE);
            assertThat(heard).as("one pass, never the new ground in the old tint").containsExactly("MIDNIGHT/ELECTRIC_BLUE");
            Theme.choose(Ground.MIDNIGHT, Tint.ELECTRIC_BLUE);
            assertThat(heard).as("the theme chosen already").hasSize(1);
        } finally {
            stop.run();
            Theme.choose(Ground.GRAPHITE, Tint.TREASURE);
        }
    }
}
