package dev.marregui.comando.app;

import dev.marregui.comando.core.dispatch.Target;
import dev.marregui.comando.core.intent.EditMode;
import dev.marregui.comando.core.intent.Intent;
import dev.marregui.comando.core.loop.StateLoop;
import dev.marregui.comando.core.model.PanelId;
import dev.marregui.comando.core.model.SessionSpec;
import dev.marregui.comando.core.model.Status;
import dev.marregui.comando.core.state.SessionState;
import dev.marregui.comando.ui.panels.EditorPanel;
import dev.marregui.comando.ui.widgets.transcript.render.TranscriptCanvas;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import javax.swing.AbstractButton;
import javax.swing.SwingUtilities;
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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Checkpoint button in the real window, against a stand-in for {@code claude -p}: once the checkpoint's turn has
 * finished, the conversation is cleared, the Session tab shows only the summary, the panel's text stays, and the next
 * prompt takes the summary with it without showing it again. Opens the window, so this runs in
 * {@code ./gradlew :app:uiTest}.
 */
@EnabledIfSystemProperty(named = "comando.uiTests", matches = "true")
class CheckpointClearUiTest {

    private static final String NOTES = "notes the panel keeps as they are";
    private static final String SUMMARY_JSON = "## Reflected\\nmemory written";
    private static final String SUMMARY = "## Reflected\nmemory written";

    @TempDir
    Path home;

    @Test
    void theCheckpointButtonClearsTheConversationAndTheSessionTabToItsSummaryWhichGoesWithTheNextPrompt() throws Exception {
        Path stdin = home.resolve("stdin.jsonl");
        Path claude = home.resolve("fake-claude.sh");
        String init = "{\"type\":\"system\",\"subtype\":\"init\",\"session_id\":\"'$id'\",\"model\":\"fake\",\"cwd\":\"/tmp\","
                + "\"claude_code_version\":\"0.0.1\",\"permissionMode\":\"default\",\"capabilities\":[]}";
        Files.writeString(claude, """
                #!/bin/sh
                # stands in for claude -p: /reflect answers with a summary, /clear starts a new conversation, the rest pong
                id=fake-1
                n=0
                while IFS= read -r line; do
                  printf '%%s\\n' "$line" >> '%s'
                  case "$line" in
                    *'"control_request"'*)
                      printf '%%s\\n' '{"type":"control_response","response":{"subtype":"success","request_id":"x"}}' ;;
                    *'"content":"/clear"'*)
                      id=fake-2
                      printf '%%s\\n' '{"type":"conversation_reset","trigger":"clear"}'
                      printf '%%s\\n' '%s'
                      printf '%%s\\n' '{"type":"result","subtype":"success","is_error":false,"result":"","total_cost_usd":0,"duration_ms":1,"num_turns":0}'
                      ;;
                    *'"content":"/reflect"'*)
                      printf '%%s\\n' '%s'
                      printf '%%s\\n' '{"type":"assistant","parent_tool_use_id":null,"message":{"id":"r1","content":[{"type":"text","text":"%s"}],"usage":{"input_tokens":10}}}'
                      printf '%%s\\n' '{"type":"result","subtype":"success","is_error":false,"result":"%s","total_cost_usd":0,"duration_ms":1,"num_turns":1,"usage":{"input_tokens":10}}'
                      ;;
                    *'"type":"user"'*)
                      n=$((n+1))
                      printf '%%s\\n' '%s'
                      printf '%%s\\n' '{"type":"assistant","parent_tool_use_id":null,"message":{"id":"m'$n'","content":[{"type":"text","text":"pong '$n'"}],"usage":{"input_tokens":10}}}'
                      printf '%%s\\n' '{"type":"result","subtype":"success","is_error":false,"result":"pong","total_cost_usd":0,"duration_ms":1,"num_turns":1,"usage":{"input_tokens":10}}'
                      ;;
                  esac
                done
                """.formatted(stdin, init, init, SUMMARY_JSON, SUMMARY_JSON, init));
        claude.toFile().setExecutable(true);
        App app = new App(new AppConfig(home.resolve("data"), claude.toString(), home.resolve("projects"), false), "checkpoint", () -> {
        });
        try {
            app.start();
            StateLoop loop = app.loop();
            Awaitility.await("the window").atMost(Duration.ofSeconds(20)).until(() -> loop.state().focused() != null && editor() != null);
            // a new session comes with its own panel, titled with its name (record 88)
            loop.dispatch(new Intent.SaveSession("", SessionSpec.fresh("work", home.toString())));
            Awaitility.await("its own panel").atMost(Duration.ofSeconds(10)).until(() -> loop.state().homePanel("work").isPresent());
            PanelId panel = loop.state().homePanel("work").orElseThrow().id();
            Awaitility.await("its panel shown").atMost(Duration.ofSeconds(10))
                    .until(() -> editor() != null && editor().panel().id().equals(panel));
            loop.dispatch(new Intent.EditPanel(panel, NOTES, EditMode.REPLACE, ""));
            loop.dispatch(new Intent.StartSession("work"));
            Awaitility.await("running").atMost(Duration.ofSeconds(20)).until(() -> session(loop).status() == Status.OPEN);
            loop.dispatch(new Intent.RunText(panel, "hello", Target.CLAUDE));
            Awaitility.await("the first turn").atMost(Duration.ofSeconds(20))
                    .until(() -> !session(loop).isBusy() && session(loop).spec().claudeSessionId().equals("fake-1"));
            Awaitility.await("the Session tab shows it").atMost(Duration.ofSeconds(10)).until(() -> transcript().contains("pong 1"));

            // the gesture: a click on the session bar's Checkpoint button, the focused panel's session (record 105)
            EditorPanel view = editor();
            SwingUtilities.invokeAndWait(() -> button(SwingUtilities.getWindowAncestor(view), "Checkpoint").doClick());
            Awaitility.await("checkpointed, cleared, the summary carried").atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
                SessionState x = session(loop);
                assertThat(List.of(x.pendingTurns(), x.spec().claudeSessionId(), x.spec().carried()))
                        .as("sent: %s", Files.readAllLines(stdin)).containsExactly(0, "fake-2", SUMMARY);
            });
            Awaitility.await("the Session tab holds only the summary").atMost(Duration.ofSeconds(10))
                    .untilAsserted(() -> assertThat(transcript()).contains("memory written").doesNotContain("pong 1", "hello", "/reflect", "/clear"));
            assertThat(onEdt(() -> view.editor().text())).as("the panel's text is left alone").isEqualTo(NOTES);
            assertThat(Files.readAllLines(stdin)).filteredOn(l -> l.contains("\"type\":\"user\""))
                    .extracting(l -> l.replaceAll(".*\"content\":\"([^\"]*)\".*", "$1")).containsExactly("hello", "/reflect", "/clear");

            loop.dispatch(new Intent.RunText(panel, "go on", Target.CLAUDE));
            Awaitility.await("the next turn").atMost(Duration.ofSeconds(20))
                    .until(() -> !session(loop).isBusy() && session(loop).spec().carried().isEmpty());
            String sent = Files.readAllLines(stdin).getLast();
            assertThat(sent).as("the summary goes in front of the prompt").contains("Context carried over", "memory written", "go on");
            Awaitility.await("the prompt shown as typed").atMost(Duration.ofSeconds(10))
                    .untilAsserted(() -> assertThat(transcript()).contains("go on").doesNotContain("Context carried over"));

            loop.dispatch(new Intent.StopSession("work"));
            Awaitility.await("stopped").atMost(Duration.ofSeconds(20)).until(() -> session(loop).status() == Status.CLOSED);
        } finally {
            app.close();
        }
    }

    private static SessionState session(StateLoop loop) {
        return loop.state().session("work").orElseThrow();
    }

    /** The focused window's editor panel, or null before the window shows one. */
    private static EditorPanel editor() throws Exception {
        return onEdt(() -> first(EditorPanel.class, c -> c.isShowing()));
    }

    /** What the Session tab shows, as Markdown; empty while it shows nothing. */
    private static String transcript() throws Exception {
        return onEdt(() -> {
            TranscriptCanvas canvas = first(TranscriptCanvas.class, Component::isShowing);
            return canvas == null ? "" : canvas.markdown();
        });
    }

    private static AbstractButton button(Container root, String name) {
        List<AbstractButton> out = new ArrayList<>();
        collect(root, AbstractButton.class, b -> name.equals(b.getAccessibleContext().getAccessibleName()) && b.isVisible(), out);
        assertThat(out).as("a visible %s button", name).isNotEmpty();
        return out.getFirst();
    }

    private static <T extends Component> T first(Class<T> type, Predicate<T> which) {
        List<T> out = new ArrayList<>();
        for (Frame f : Frame.getFrames()) {
            if (f.isVisible()) {
                collect(f, type, which, out);
            }
        }
        return out.isEmpty() ? null : out.getFirst();
    }

    private static <T extends Component> void collect(Component c, Class<T> type, Predicate<T> which, List<T> out) {
        if (type.isInstance(c) && which.test(type.cast(c))) {
            out.add(type.cast(c));
        }
        if (c instanceof Container k) {
            for (Component child : k.getComponents()) {
                collect(child, type, which, out);
            }
        }
    }

    private interface EdtCall<T> {
        T call() throws Exception;
    }

    private static <T> T onEdt(EdtCall<T> call) throws Exception {
        AtomicReference<T> out = new AtomicReference<>();
        AtomicReference<Exception> failed = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            try {
                out.set(call.call());
            } catch (Exception e) {
                failed.set(e);
            }
        });
        if (failed.get() != null) {
            throw failed.get();
        }
        return out.get();
    }
}
