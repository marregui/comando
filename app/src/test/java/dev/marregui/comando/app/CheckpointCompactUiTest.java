package dev.marregui.comando.app;

import dev.marregui.comando.core.dispatch.Target;
import dev.marregui.comando.core.intent.EditMode;
import dev.marregui.comando.core.intent.Intent;
import dev.marregui.comando.core.loop.StateLoop;
import dev.marregui.comando.core.model.PanelId;
import dev.marregui.comando.core.model.SessionSpec;
import dev.marregui.comando.core.model.Status;
import dev.marregui.comando.core.state.SessionState;
import dev.marregui.comando.core.transcript.Block;
import dev.marregui.comando.core.transcript.Transcript;
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

/// The Checkpoint button in the real window, against a stand-in for `claude -p`. Opens the window, so this runs in
/// `./gradlew :app:uiTest`.
/// - once the checkpoint's turn has finished, `/compact` goes out and the conversation is compacted, not cleared
/// - the Session tab keeps the conversation and shows the compaction; the panel's text stays
/// - nothing is carried: the next prompt goes as typed
@EnabledIfSystemProperty(named = "comando.uiTests", matches = "true")
class CheckpointCompactUiTest {

    private static final String NOTES = "notes the panel keeps as they are";
    private static final String SUMMARY_JSON = "## Reflected\\nmemory written";

    @TempDir
    Path home;

    /// After the checkpoint's turn the session has no pending turn, the same conversation (`fake-1`) and nothing
    /// carried; the stand-in received `hello`, `/reflect`, `/compact`; the Session tab still shows the first turn and
    /// the summary, with the compaction's note; the panel keeps its text; the next prompt goes out as typed.
    @Test
    void theCheckpointButtonCompactsTheConversationOnceItsTurnHasFinished() throws Exception {
        Path stdin = home.resolve("stdin.jsonl");
        Path claude = home.resolve("fake-claude.sh");
        String init = "{\"type\":\"system\",\"subtype\":\"init\",\"session_id\":\"fake-1\",\"model\":\"fake\",\"cwd\":\"/tmp\","
                + "\"claude_code_version\":\"0.0.1\",\"permissionMode\":\"default\",\"capabilities\":[]}";
        Files.writeString(claude, """
                #!/bin/sh
                # stands in for claude -p: /reflect answers with a summary, /compact compacts as Claude Code reports it, the
                # rest pong
                n=0
                while IFS= read -r line; do
                  printf '%%s\\n' "$line" >> '%s'
                  case "$line" in
                    *'"control_request"'*)
                      printf '%%s\\n' '{"type":"control_response","response":{"subtype":"success","request_id":"x"}}' ;;
                    *'"content":"/compact"'*)
                      printf '%%s\\n' '{"type":"system","subtype":"status","status":"compacting"}'
                      printf '%%s\\n' '{"type":"system","subtype":"status","status":null,"compact_result":"success"}'
                      printf '%%s\\n' '%s'
                      printf '%%s\\n' '{"type":"system","subtype":"compact_boundary","compact_metadata":{"trigger":"manual","pre_tokens":30,"post_tokens":5}}'
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
            Awaitility.await("checkpointed, then compacted").atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
                assertThat(Files.readAllLines(stdin)).filteredOn(l -> l.contains("\"type\":\"user\""))
                        .extracting(l -> l.replaceAll(".*\"content\":\"([^\"]*)\".*", "$1")).containsExactly("hello", "/reflect", "/compact");
                SessionState x = session(loop);
                assertThat(List.of(x.pendingTurns(), x.spec().claudeSessionId(), x.spec().carried(), x.checkpointCompacts()))
                        .as("sent: %s", Files.readAllLines(stdin)).containsExactly(0, "fake-1", "", false);
            });
            Awaitility.await("the compaction shown").atMost(Duration.ofSeconds(10)).until(() -> notes(app).contains("Compacted manual: 30 → 5 tokens"));
            Awaitility.await("the Session tab keeps the conversation").atMost(Duration.ofSeconds(10))
                    .untilAsserted(() -> assertThat(transcript()).contains("pong 1", "hello", "memory written"));
            assertThat(onEdt(() -> view.editor().text())).as("the panel's text is left alone").isEqualTo(NOTES);

            loop.dispatch(new Intent.RunText(panel, "go on", Target.CLAUDE));
            Awaitility.await("the next turn").atMost(Duration.ofSeconds(20))
                    .until(() -> !session(loop).isBusy() && Files.readAllLines(stdin).getLast().contains("go on"));
            assertThat(Files.readAllLines(stdin).getLast()).as("nothing carried in front of the prompt").doesNotContain("Context carried over");

            loop.dispatch(new Intent.StopSession("work"));
            Awaitility.await("stopped").atMost(Duration.ofSeconds(20)).until(() -> session(loop).status() == Status.CLOSED);
        } finally {
            app.close();
        }
    }

    /** The texts of the session's notes, from its transcript. */
    private static List<String> notes(App app) {
        Transcript t = app.transcripts().of("work");
        List<String> out = new ArrayList<>();
        for (int i = 0; i < t.size(); i++) {
            if (t.get(i) instanceof Block.Note n) {
                out.add(n.text());
            }
        }
        return out;
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
