package dev.marregui.comando.app;

import java.awt.Component;
import java.awt.Container;
import java.awt.Frame;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import javax.swing.AbstractButton;
import javax.swing.SwingUtilities;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import dev.marregui.comando.core.dispatch.Target;
import dev.marregui.comando.core.intent.Intent;
import dev.marregui.comando.core.loop.StateLoop;
import dev.marregui.comando.core.model.PanelId;
import dev.marregui.comando.core.model.SessionSpec;
import dev.marregui.comando.core.model.Status;
import dev.marregui.comando.core.state.SessionState;
import dev.marregui.comando.ui.kit.action.Command;
import dev.marregui.comando.ui.panels.EditorPanel;

import static org.assertj.core.api.Assertions.assertThat;

/// A session locks while its checkpoint runs (record 130), in the real window against a stand-in for `claude -p`.
/// Opens the window, so this runs in `./gradlew :app:uiTest`.
/// - the Checkpoint button locks the session's panel: not editable; Checkpoint, Fresh and Compact off; Stop on
/// - Stop interrupts the checkpoint: the panel is editable again and no `/compact` goes out
/// - a checkpoint left to finish stays locked through its `/compact`, and unlocks when the compaction ends
@EnabledIfSystemProperty(named = "comando.uiTests", matches = "true")
class CheckpointLockUiTest {

    @TempDir
    Path home;

    /// The stand-in holds the first `/reflect` until it is interrupted, the second until `reflect.go` exists, and
    /// `/compact` until `compact.go` exists, so the locked window can be read at each step; the prompts it received
    /// say what was sent.
    @Test
    void theSessionLocksWhileItsCheckpointRunsAndStopCarriesOn() throws Exception {
        final Path stdin = home.resolve("stdin.jsonl");
        final Path reflectGo = home.resolve("reflect.go");
        final Path compactGo = home.resolve("compact.go");
        final Path claude = home.resolve("fake-claude.sh");
        final String init = "{\"type\":\"system\",\"subtype\":\"init\",\"session_id\":\"fake-1\",\"model\":\"fake\","
                + "\"cwd\":\"/tmp\",\"claude_code_version\":\"0.0.1\",\"permissionMode\":\"default\","
                + "\"capabilities\":[]}";
        Files.writeString(claude, """
                #!/bin/sh
                # stands in for claude -p: the first /reflect runs until interrupted, the second until
                # reflect.go exists; /compact compacts once compact.go exists; the rest pong
                n=0
                r=0
                while IFS= read -r line; do
                  printf '%%s\\n' "$line" >> '%1$s'
                  case "$line" in
                    *'"subtype":"interrupt"'*)
                      printf '%%s\\n' '{"type":"control_response","response":{"subtype":"success","request_id":"x"}}'
                      printf '%%s\\n' '{"type":"result","subtype":"error_during_execution","is_error":true,"result":"","total_cost_usd":0,"duration_ms":1,"num_turns":1}'
                      ;;
                    *'"control_request"'*)
                      printf '%%s\\n' '{"type":"control_response","response":{"subtype":"success","request_id":"x"}}' ;;
                    *'"content":"/compact"'*)
                      while [ ! -f '%3$s' ]; do sleep 0.05; done
                      printf '%%s\\n' '{"type":"system","subtype":"status","status":"compacting"}'
                      printf '%%s\\n' '{"type":"system","subtype":"status","status":null,"compact_result":"success"}'
                      printf '%%s\\n' '%4$s'
                      printf '%%s\\n' '{"type":"system","subtype":"compact_boundary","compact_metadata":{"trigger":"manual","pre_tokens":30,"post_tokens":5}}'
                      printf '%%s\\n' '{"type":"result","subtype":"success","is_error":false,"result":"","total_cost_usd":0,"duration_ms":1,"num_turns":0}'
                      ;;
                    *'"content":"/reflect"'*)
                      r=$((r+1))
                      printf '%%s\\n' '%4$s'
                      if [ "$r" -gt 1 ]; then
                        while [ ! -f '%2$s' ]; do sleep 0.05; done
                        printf '%%s\\n' '{"type":"assistant","parent_tool_use_id":null,"message":{"id":"r'$r'","content":[{"type":"text","text":"## Reflected"}],"usage":{"input_tokens":10}}}'
                        printf '%%s\\n' '{"type":"result","subtype":"success","is_error":false,"result":"## Reflected","total_cost_usd":0,"duration_ms":1,"num_turns":1,"usage":{"input_tokens":10}}'
                      fi
                      ;;
                    *'"type":"user"'*)
                      n=$((n+1))
                      printf '%%s\\n' '%4$s'
                      printf '%%s\\n' '{"type":"assistant","parent_tool_use_id":null,"message":{"id":"m'$n'","content":[{"type":"text","text":"pong '$n'"}],"usage":{"input_tokens":10}}}'
                      printf '%%s\\n' '{"type":"result","subtype":"success","is_error":false,"result":"pong","total_cost_usd":0,"duration_ms":1,"num_turns":1,"usage":{"input_tokens":10}}'
                      ;;
                  esac
                done
                """.formatted(stdin, reflectGo, compactGo, init));
        claude.toFile().setExecutable(true);
        final AppConfig config = new AppConfig(home.resolve("data"), claude.toString(), home.resolve("projects"),
                false);
        final App app = new App(config, "lock", () -> {
        });
        try {
            app.start();
            final StateLoop loop = app.loop();
            Awaitility.await("the window").atMost(Duration.ofSeconds(20))
                    .until(() -> loop.state().focused() != null && editor() != null);
            loop.dispatch(new Intent.SaveSession("", SessionSpec.fresh("work", home.toString())));
            Awaitility.await("its own panel").atMost(Duration.ofSeconds(10))
                    .until(() -> loop.state().homePanel("work").isPresent());
            final PanelId panel = loop.state().homePanel("work").orElseThrow().id();
            Awaitility.await("its panel shown").atMost(Duration.ofSeconds(10))
                    .until(() -> editor() != null && editor().panel().id().equals(panel));
            loop.dispatch(new Intent.StartSession("work"));
            Awaitility.await("running").atMost(Duration.ofSeconds(20))
                    .until(() -> session(loop).status() == Status.OPEN);
            loop.dispatch(new Intent.RunText(panel, "hello", Target.CLAUDE));
            Awaitility.await("the first turn").atMost(Duration.ofSeconds(20))
                    .until(() -> !session(loop).isBusy() && session(loop).spec().claudeSessionId().equals("fake-1"));
            final EditorPanel view = editor();
            final Command compact = app.ui().commands().stream().filter(c -> "session.compact".equals(c.id()))
                    .findFirst().orElseThrow();
            assertThat(onEdt(() -> view.editor().area().isEditable() && compact.enabled().getAsBoolean()))
                    .as("unlocked").isTrue();

            // the gesture: a click on the session bar's Checkpoint button; the first /reflect runs until stopped
            SwingUtilities.invokeAndWait(() -> button(SwingUtilities.getWindowAncestor(view), "Checkpoint").doClick());
            awaitLocked(loop, view, compact, true);
            // Stop: the panel's own button, which interrupts its session's turn
            SwingUtilities.invokeAndWait(() -> button(view, "Stop").doClick());
            awaitLocked(loop, view, compact, false);
            assertThat(prompts(stdin)).as("interrupted: no /compact").containsExactly("hello", "/reflect");

            // a checkpoint left to finish: locked through its /compact, unlocked when the compaction ends
            SwingUtilities.invokeAndWait(() -> button(SwingUtilities.getWindowAncestor(view), "Checkpoint").doClick());
            awaitLocked(loop, view, compact, true);
            Files.writeString(reflectGo, "");
            Awaitility.await("the compaction sent").atMost(Duration.ofSeconds(20))
                    .untilAsserted(() -> assertThat(prompts(stdin))
                            .containsExactly("hello", "/reflect", "/reflect", "/compact"));
            awaitLocked(loop, view, compact, true);
            Files.writeString(compactGo, "");
            awaitLocked(loop, view, compact, false);
            assertThat(session(loop).spec().claudeSessionId()).as("the same conversation").isEqualTo("fake-1");

            loop.dispatch(new Intent.StopSession("work"));
            Awaitility.await("stopped").atMost(Duration.ofSeconds(20))
                    .until(() -> session(loop).status() == Status.CLOSED);
        } finally {
            app.close();
        }
    }

    /**
     * Waits until the session and its window are {@code locked}, or not: the state's lock, the panel's text, the
     * session bar's Checkpoint and Fresh, the palette's Compact, all off while locked, with the panel's Stop on.
     */
    private static void awaitLocked(final StateLoop loop, final EditorPanel view, final Command compact,
            final boolean locked) {
        Awaitility.await(locked ? "locked" : "unlocked").atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            assertThat(session(loop).isCheckpointing()).as("the state").isEqualTo(locked);
            final List<Boolean> window = onEdt(() -> {
                final Container root = SwingUtilities.getWindowAncestor(view);
                return List.of(view.locked(), view.editor().area().isEditable(), button(root, "Checkpoint").isEnabled(),
                        button(root, "Start a new conversation").isEnabled(), compact.enabled().getAsBoolean());
            });
            assertThat(window).as("locked, editable, Checkpoint, Fresh, Compact")
                    .containsExactly(locked, !locked, !locked, false, !locked);
            if (locked) {
                assertThat(onEdt(() -> button(view, "Stop").isEnabled())).as("Stop stays on").isTrue();
            }
        });
    }

    /** The prompts the stand-in received, in order. */
    private static List<String> prompts(final Path stdin) throws Exception {
        return Files.readAllLines(stdin).stream().filter(l -> l.contains("\"type\":\"user\""))
                .map(l -> l.replaceAll(".*\"content\":\"([^\"]*)\".*", "$1")).toList();
    }

    private static SessionState session(final StateLoop loop) {
        return loop.state().session("work").orElseThrow();
    }

    /** The focused window's editor panel, or null before the window shows one. */
    private static EditorPanel editor() throws Exception {
        return onEdt(() -> first(EditorPanel.class, Component::isShowing));
    }

    private static AbstractButton button(final Container root, final String name) {
        final List<AbstractButton> out = new ArrayList<>();
        collect(root, AbstractButton.class,
                b -> name.equals(b.getAccessibleContext().getAccessibleName()) && b.isVisible(), out);
        assertThat(out).as("a visible %s button", name).isNotEmpty();
        return out.getFirst();
    }

    private static <T extends Component> T first(final Class<T> type, final Predicate<T> which) {
        final List<T> out = new ArrayList<>();
        for (final Frame f : Frame.getFrames()) {
            if (f.isVisible()) {
                collect(f, type, which, out);
            }
        }
        return out.isEmpty() ? null : out.getFirst();
    }

    private static <T extends Component> void collect(final Component c, final Class<T> type, final Predicate<T> which,
            final List<T> out) {
        if (type.isInstance(c) && which.test(type.cast(c))) {
            out.add(type.cast(c));
        }
        if (c instanceof Container k) {
            for (final Component child : k.getComponents()) {
                collect(child, type, which, out);
            }
        }
    }

    private static <T> T onEdt(final EdtCall<T> call) throws Exception {
        final AtomicReference<T> out = new AtomicReference<>();
        final AtomicReference<Exception> failed = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            try {
                out.set(call.call());
            } catch (final Exception e) {
                failed.set(e);
            }
        });
        if (failed.get() != null) {
            throw failed.get();
        }
        return out.get();
    }

    private interface EdtCall<T> {
        T call() throws Exception;
    }
}
