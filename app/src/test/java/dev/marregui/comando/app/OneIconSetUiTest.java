package dev.marregui.comando.app;

import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Frame;
import java.awt.Rectangle;
import java.awt.Window;
import java.awt.event.InputEvent;
import java.awt.event.MouseEvent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.JTabbedPane;
import javax.swing.MenuElement;
import javax.swing.MenuSelectionManager;
import javax.swing.SwingUtilities;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import dev.marregui.comando.core.intent.Intent;
import dev.marregui.comando.core.loop.StateLoop;
import dev.marregui.comando.core.model.PanelId;
import dev.marregui.comando.core.model.SessionSpec;
import dev.marregui.comando.core.model.Status;
import dev.marregui.comando.core.state.SessionState;
import dev.marregui.comando.ui.kit.action.Command;
import dev.marregui.comando.ui.kit.theme.Ground;
import dev.marregui.comando.ui.kit.theme.Theme;
import dev.marregui.comando.ui.kit.theme.Tint;
import dev.marregui.comando.ui.panels.ThemeChooser;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/// One icon set, Classic, and the palette's session commands, in the real window (`./gradlew :app:uiTest`).
/// - The palette offers no icon set to choose and no Checkpoint: Reflect, Compact the conversation and Clear the
///   conversation instead, each with its icon, each sending its command to the focused panel's running session.
/// - A panel tab's menu, opened under a dark and a light theme, has an icon on every item and paints from the theme;
///   the theme chooser, opened under each, has no row of icon sets and paints from the theme too.
@EnabledIfSystemProperty(named = "comando.uiTests", matches = "true")
class OneIconSetUiTest {

    private static final Duration WAIT = Duration.ofSeconds(20);
    // the middle of the 0..255 channel range: a ground above it reads as light
    private static final int MIDDLE = 128;

    @TempDir
    Path home;

    /// No command id starts with `icons.` and `session.checkpoint` is gone; `session.reflect`, `session.compact` and
    /// `session.clearConversation` sit in the Session area with the icons `checkpoint`, `compact` and
    /// `clear-conversation`; on a running session each is enabled, and run in turn the stand-in for `claude -p`
    /// receives `/clear`, `/compact` and the session's checkpoint command, `/reflect`, in that order.
    @Test
    void thePaletteSendsReflectCompactAndClearAndOffersNoIconSet() throws Exception {
        final Path stdin = home.resolve("stdin.jsonl");
        final Path claude = home.resolve("fake-claude.sh");
        final String init = "{\"type\":\"system\",\"subtype\":\"init\",\"session_id\":\"fake-1\",\"model\":\"fake\","
                + "\"cwd\":\"/tmp\",\"claude_code_version\":\"0.0.1\",\"permissionMode\":\"default\","
                + "\"capabilities\":[]}";
        final String answer = "{\"type\":\"assistant\",\"parent_tool_use_id\":null,\"message\":{\"id\":\"m1\","
                + "\"content\":[{\"type\":\"text\",\"text\":\"done\"}],\"usage\":{\"input_tokens\":10}}}";
        final String result = "{\"type\":\"result\",\"subtype\":\"success\",\"is_error\":false,\"result\":\"done\","
                + "\"total_cost_usd\":0,\"duration_ms\":1,\"num_turns\":1,\"usage\":{\"input_tokens\":10}}";
        Files.writeString(claude, """
                #!/bin/sh
                # stands in for claude -p: every user line is kept and answered with one turn
                while IFS= read -r line; do
                  printf '%%s\\n' "$line" >> '%s'
                  case "$line" in
                    *'"control_request"'*)
                      printf '%%s\\n' '{"type":"control_response","response":{"subtype":"success","request_id":"x"}}' ;;
                    *'"type":"user"'*)
                      printf '%%s\\n' '%s'
                      printf '%%s\\n' '%s'
                      printf '%%s\\n' '%s'
                      ;;
                  esac
                done
                """.formatted(stdin, init, answer, result));
        claude.toFile().setExecutable(true);
        try (final App app = new App(new AppConfig(home.resolve("data"), claude.toString(), home.resolve("projects"),
                false), "one-icon-set", () -> {
        })) {
            app.start();
            final StateLoop loop = app.loop();
            Awaitility.await("the window").atMost(WAIT).until(() -> loop.state().focused() != null);
            loop.dispatch(new Intent.SaveSession("", SessionSpec.fresh("work", home.toString())));
            Awaitility.await("its own panel").atMost(WAIT).until(() -> loop.state().homePanel("work").isPresent());
            final PanelId panel = loop.state().homePanel("work").orElseThrow().id();
            loop.dispatch(new Intent.FocusPanel(panel));
            loop.dispatch(new Intent.StartSession("work"));
            Awaitility.await("running, its panel focused").atMost(WAIT)
                    .until(() -> session(loop).status() == Status.OPEN
                            && loop.state().focusedPanel().id().equals(panel));

            final List<Command> commands = onEdt(() -> app.ui().commands());
            assertThat(commands).extracting(Command::id).as("no icon set to choose, no Checkpoint")
                    .noneMatch(id -> id.startsWith("icons.")).doesNotContain("session.checkpoint");
            final Command reflect = command(commands, "session.reflect");
            final Command compact = command(commands, "session.compact");
            final Command clear = command(commands, "session.clearConversation");
            assertThat(List.of(reflect, compact, clear)).extracting(Command::label, Command::icon, Command::group)
                    .containsExactly(
                            tuple("Reflect", "checkpoint", "Session"),
                            tuple("Compact the conversation", "compact", "Session"),
                            tuple("Clear the conversation", "clear-conversation", "Session"));

            for (final Command c : List.of(clear, compact, reflect)) {
                Awaitility.await(c.label() + ", enabled").atMost(WAIT)
                        .until(() -> !session(loop).isBusy() && onEdt(() -> c.enabled().getAsBoolean()));
                SwingUtilities.invokeAndWait(c.run());
                Awaitility.await(c.label() + ", its turn over").atMost(WAIT).until(() -> !session(loop).isBusy());
            }
            Awaitility.await("the three commands sent").atMost(WAIT).untilAsserted(() -> assertThat(sent(stdin))
                    .containsExactly("/clear", SessionSpec.COMPACT_COMMAND, SessionSpec.DEFAULT_CHECKPOINT));
        }
    }

    /// Under Graphite with Treasure, then Fog with Slate ink: the first panel tab's menu shows every item with an
    /// icon on a ground dark, then light, as the theme's; the chooser opened from the swatch has no `Icons` label and
    /// its ground is the theme's too.
    @Test
    void theTabMenuAndTheThemeChooserPaintFromADarkAndALightTheme() throws Exception {
        try (final App app = new App(new AppConfig(home.resolve("data"), home.resolve("no-claude").toString(),
                home.resolve("projects"), false), "one-icon-set-menus", () -> {
        })) {
            app.start();
            Awaitility.await("the window").atMost(WAIT)
                    .until(() -> app.loop().state().focused() != null && frame() != null);
            for (final boolean light : new boolean[] {false, true}) {
                final String mode = light ? "light" : "dark";
                SwingUtilities.invokeAndWait(() -> Theme.choose(light ? Ground.FOG : Ground.GRAPHITE,
                        light ? Tint.SLATE_INK : Tint.TREASURE));

                final JTabbedPane strip = await(JTabbedPane.class,
                        t -> "workspace".equals(t.getName()) && t.getTabCount() > 0);
                rightClickFirstTab(strip);
                final JPopupMenu menu = await(OneIconSetUiTest::openMenu);
                assertThat(onEdt(() -> items(menu))).as(mode + ": the tab menu's items").isNotEmpty()
                        .allSatisfy(i -> assertThat(i.getIcon()).as(mode + ": the icon of " + i.getText()).isNotNull());
                assertThat(onEdt(() -> bright(menu.getBackground()))).as(mode + ": the tab menu's ground")
                        .isEqualTo(light);
                onEdt(() -> {
                    menu.setVisible(false);
                    MenuSelectionManager.defaultManager().clearSelectedPath();
                    return null;
                });

                onEdt(() -> {
                    final JButton swatch = find(frame(), JButton.class,
                            b -> "Choose the theme".equals(b.getAccessibleContext().getAccessibleName()));
                    assertThat(swatch).as("the swatch beside New panel").isNotNull();
                    swatch.doClick(0);
                    return null;
                });
                final JDialog chooser = await(JDialog.class, d -> ThemeChooser.TITLE.equals(d.getTitle()));
                assertThat(onEdt(() -> find(chooser, JLabel.class, l -> "Icons".equals(l.getText()))))
                        .as(mode + ": the chooser's row of icon sets").isNull();
                assertThat(onEdt(() -> bright(chooser.getContentPane().getBackground())))
                        .as(mode + ": the chooser's ground").isEqualTo(light);
                onEdt(() -> {
                    chooser.getRootPane().getActionMap().get("close").actionPerformed(null);
                    return null;
                });
                Awaitility.await(mode + ": the chooser closed").atMost(WAIT)
                        .until(() -> onEdt(() -> !chooser.isShowing()));
            }
        } finally {
            SwingUtilities.invokeAndWait(() -> Theme.choose(Ground.GRAPHITE, Tint.TREASURE));
        }
    }

    private static SessionState session(final StateLoop loop) {
        return loop.state().session("work").orElseThrow();
    }

    private static Command command(final List<Command> commands, final String id) {
        return commands.stream().filter(c -> c.id().equals(id)).findFirst()
                .orElseThrow(() -> new AssertionError("no command " + id));
    }

    /// The text of every user line the stand-in received, in order.
    private static List<String> sent(final Path stdin) throws Exception {
        if (!Files.exists(stdin)) {
            return List.of();
        }
        return Files.readAllLines(stdin).stream().filter(l -> l.contains("\"type\":\"user\""))
                .map(l -> l.replaceAll(".*\"content\":\"([^\"]*)\".*", "$1")).toList();
    }

    /// A right-button press in the middle of `strip`'s first tab: the press is the popup trigger.
    private static void rightClickFirstTab(final JTabbedPane strip) throws Exception {
        onEdt(() -> {
            final Rectangle tab = strip.getBoundsAt(0);
            strip.dispatchEvent(new MouseEvent(strip, MouseEvent.MOUSE_PRESSED, System.currentTimeMillis(),
                    InputEvent.BUTTON3_DOWN_MASK, tab.x + tab.width / 2, tab.y + tab.height / 2, 1, true,
                    MouseEvent.BUTTON3));
            return null;
        });
    }

    /// The popup menu open now, or null.
    private static JPopupMenu openMenu() {
        for (final MenuElement e : MenuSelectionManager.defaultManager().getSelectedPath()) {
            if (e instanceof JPopupMenu m && m.isVisible()) {
                return m;
            }
        }
        return null;
    }

    private static List<JMenuItem> items(final JPopupMenu menu) {
        final List<JMenuItem> out = new ArrayList<>();
        for (final Component c : menu.getComponents()) {
            if (c instanceof JMenuItem i) {
                out.add(i);
            }
        }
        return out;
    }

    private static boolean bright(final Color c) {
        return (c.getRed() + c.getGreen() + c.getBlue()) / 3 > MIDDLE;
    }

    private static JFrame frame() {
        for (final Frame f : Frame.getFrames()) {
            if (f instanceof JFrame j && f.isDisplayable() && f.getTitle().startsWith("comando")) {
                return j;
            }
        }
        return null;
    }

    /// The first showing `type` in any window that `which` accepts, once there is one.
    private static <T extends Component> T await(final Class<T> type, final Predicate<T> which) {
        return await(() -> {
            for (final Window w : Window.getWindows()) {
                final T found = find(w, type, c -> c.isShowing() && which.test(c));
                if (found != null) {
                    return found;
                }
            }
            return null;
        });
    }

    private static <T> T await(final Callable<T> lookup) {
        final AtomicReference<T> out = new AtomicReference<>();
        Awaitility.await("the component").atMost(WAIT).until(() -> {
            out.set(onEdt(lookup));
            return out.get() != null;
        });
        return out.get();
    }

    private static <T extends Component> T find(final Component c, final Class<T> type, final Predicate<T> which) {
        if (c == null) {
            return null;
        }
        if (type.isInstance(c) && which.test(type.cast(c))) {
            return type.cast(c);
        }
        if (c instanceof Container k) {
            for (final Component child : k.getComponents()) {
                final T found = find(child, type, which);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static <T> T onEdt(final Callable<T> call) throws Exception {
        final AtomicReference<T> out = new AtomicReference<>();
        final AtomicReference<Exception> failure = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            try {
                out.set(call.call());
            } catch (final Exception e) {
                failure.set(e);
            }
        });
        if (failure.get() != null) {
            throw failure.get();
        }
        return out.get();
    }
}
