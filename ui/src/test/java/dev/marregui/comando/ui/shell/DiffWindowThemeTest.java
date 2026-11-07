package dev.marregui.comando.ui.shell;

import java.util.concurrent.CompletableFuture;
import javax.swing.JPanel;
import javax.swing.LookAndFeel;
import javax.swing.UIManager;
import javax.swing.UnsupportedLookAndFeelException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import dev.marregui.comando.ui.kit.async.OnEdt;
import dev.marregui.comando.ui.kit.theme.Ground;
import dev.marregui.comando.ui.kit.theme.Theme;
import dev.marregui.comando.ui.kit.theme.Tint;

import static org.assertj.core.api.Assertions.assertThat;

/// [DiffWindow] follows the theme before it is first opened, when its body is in no window for the look to reach.
/// - a body built on one theme, the saved one chosen after it as at startup, is on the theme chosen
/// - the same for a theme chosen later, with the window still never opened
/// - a disposed window hears no more choices
@ExtendWith(OnEdt.class)
class DiffWindowThemeTest {

    /// Built on the dark ground as at startup, then a light theme chosen: its panels and text are the light look's.
    @Test
    void aWindowNotYetOpenedTakesTheThemeChosenAfterItWasBuilt() throws UnsupportedLookAndFeelException {
        onComandosLook(() -> {
            Theme.choose(Ground.GRAPHITE, Tint.TREASURE);
            final DiffWindow window = window();
            Theme.choose(Ground.FOG, Tint.SLATE_INK);
            assertOnLook(window, "the saved theme, chosen after the window was built");
            Theme.choose(Ground.GRAPHITE, Tint.TREASURE);
            assertOnLook(window, "another chosen before it opened");
            window.dispose();
        });
    }

    /// Once disposed, a theme chosen leaves its body as it was.
    @Test
    void aDisposedWindowHearsNoMoreChoices() throws UnsupportedLookAndFeelException {
        onComandosLook(() -> {
            Theme.choose(Ground.GRAPHITE, Tint.TREASURE);
            final DiffWindow window = window();
            final int before = window.editor().component().getBackground().getRGB();
            window.dispose();
            Theme.choose(Ground.FOG, Tint.SLATE_INK);
            assertThat(window.editor().component().getBackground().getRGB()).isEqualTo(before);
        });
    }

    private static void assertOnLook(final DiffWindow window, final String when) {
        final JPanel body = (JPanel) window.editor().component();
        assertThat(body.getBackground().getRGB()).as(when).isEqualTo(Theme.ground().getRGB());
        assertThat(body.getComponent(0).getBackground().getRGB()).as("its header, " + when)
                .isEqualTo(Theme.ground().getRGB());
    }

    private static void onComandosLook(final Runnable body) throws UnsupportedLookAndFeelException {
        final LookAndFeel before = UIManager.getLookAndFeel();
        Theme.install();
        try {
            body.run();
        } finally {
            Theme.choose(Ground.GRAPHITE, Tint.TREASURE);
            UIManager.setLookAndFeel(before);
        }
    }

    private static DiffWindow window() {
        return new DiffWindow(null, () -> { }, intent -> { }, (file, text) -> CompletableFuture.completedFuture(null),
                (parent, text, title, options) -> 1);
    }
}
