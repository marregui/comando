package dev.marregui.comando.app;

import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.prefs.Preferences;
import javax.swing.SwingUtilities;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import dev.marregui.comando.core.loop.StateLoop;
import dev.marregui.comando.ui.kit.action.Command;
import dev.marregui.comando.ui.kit.icon.IconSet;
import dev.marregui.comando.ui.kit.icon.Icons;

import static org.assertj.core.api.Assertions.assertThat;

/// The icon set chosen from the palette, as comando runs it (`./gradlew :app:uiTest`).
/// - `Icons: <set>` chooses the set at once and is kept; the next start paints with it again.
@EnabledIfSystemProperty(named = "comando.uiTests", matches = "true")
class IconSetUiTest {

    @TempDir
    Path home;

    @AfterEach
    void classicAgain() {
        Icons.choose(IconSet.CLASSIC);
        Preferences.userRoot().node(System.getProperty("comando.prefsNode", "dev/marregui/comando-test")).remove("icons.id");
    }

    /// Running `icons.hairline` makes Hairline the set; after the window closes and the set is reset to Classic, a
    /// new start reads Hairline back from the settings.
    @Test
    void aSetChosenFromThePaletteIsKeptAndPaintsAgainAfterARestart() throws Exception {
        try (final App app = start()) {
            final AtomicReference<Command> found = new AtomicReference<>();
            Awaitility.await("the command").atMost(Duration.ofSeconds(10)).until(() -> {
                SwingUtilities.invokeAndWait(() -> app.ui().commands().stream()
                        .filter(c -> c.id().equals(IconSet.HAIRLINE.commandId())).findFirst().ifPresent(found::set));
                return found.get() != null;
            });
            final Command command = found.get();
            assertThat(command.label()).isEqualTo("Icons: Hairline");
            SwingUtilities.invokeAndWait(command.run());
            assertThat(Icons.chosen()).isEqualTo(IconSet.HAIRLINE);
        }
        Icons.choose(IconSet.CLASSIC);
        try (final App _ = start()) {
            final AtomicBoolean restored = new AtomicBoolean();
            Awaitility.await("the kept set").atMost(Duration.ofSeconds(10)).until(() -> {
                SwingUtilities.invokeAndWait(() -> restored.set(Icons.chosen() == IconSet.HAIRLINE));
                return restored.get();
            });
        }
    }

    private App start() {
        final App app = new App(new AppConfig(home.resolve("data"), home.resolve("no-claude").toString(),
                home.resolve("projects"), false), "icon-sets", () -> {
        });
        app.start();
        final StateLoop loop = app.loop();
        Awaitility.await("the window").atMost(Duration.ofSeconds(20)).until(() -> loop.state().focused() != null);
        return app;
    }
}
