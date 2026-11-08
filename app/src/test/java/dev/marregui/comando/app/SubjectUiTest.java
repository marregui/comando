package dev.marregui.comando.app;

import java.awt.Component;
import java.awt.Container;
import java.awt.Frame;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.event.MouseEvent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import javax.swing.AbstractButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JToggleButton;
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

import static org.assertj.core.api.Assertions.assertThat;

/// A session shows what it works on in the real window, from a real turn's writes. Opens the window, so this runs in
/// `./gradlew :app:uiTest`.
/// - a session running in Claude Code's folder shows no branch of that folder
/// - once it writes to a repository the session bar names that branch and repository, the card the ticket
/// - the sidebar's row stays one line
@EnabledIfSystemProperty(named = "comando.uiTests", matches = "true")
class SubjectUiTest {

    private static final Duration WAIT = Duration.ofSeconds(20);

    @TempDir
    Path home;

    /// A session started in the kit, itself a repository on `master`, shows its name alone; a turn writing a file in
    /// `acme` on `feature/eng-1-x` puts that branch and `acme` on the bar, and Repository, Branch and `ENG-1` on
    /// the card opened from the row, whose height has not changed.
    @Test
    void aSessionWritingToARepositoryShowsThatBranchAndTheTicket() throws Exception {
        final Path kit = Files.createDirectories(home.resolve("claude/projects")).getParent();
        head(kit, "master");
        final Path repo = home.resolve("acme");
        head(repo, "feature/eng-1-x");
        final String claude = fakeClaude(repo.resolve("src/F.java")).toString();
        final AppConfig config = new AppConfig(home.resolve("data"), claude, kit.resolve("projects"), false);
        final App app = new App(config, "subject", () -> {
        });
        try {
            app.start();
            final StateLoop loop = app.loop();
            Awaitility.await("the window").atMost(WAIT)
                    .until(() -> loop.state().focused() != null && front("Sessions"));
            loop.dispatch(new Intent.SaveSession("", SessionSpec.fresh("work", kit.toString())));
            Awaitility.await("its own panel").atMost(WAIT).until(() -> loop.state().homePanel("work").isPresent());
            final PanelId panel = loop.state().homePanel("work").orElseThrow().id();
            Awaitility.await("its panel in front").atMost(WAIT)
                    .until(() -> panel.equals(loop.state().focused()) && "work".equals(chipText()));
            loop.dispatch(new Intent.StartSession("work"));
            Awaitility.await("running").atMost(WAIT).until(() -> session(loop).status() == Status.OPEN);
            assertThat(chipText()).as("the kit's branch is not the session's").isEqualTo("work");
            final JList<?> list = sessions();
            final int rowHeight = onEdt(() -> rowBounds(list).height);

            loop.dispatch(new Intent.RunText(panel, "write it", Target.CLAUDE));
            Awaitility.await("the bar").atMost(WAIT)
                    .until(() -> "work  ⎇ feature/eng-1-x · acme".equals(chipText()));

            assertThat(onEdt(() -> rowBounds(list).height)).as("the row stays one line").isEqualTo(rowHeight);
            clickInfo(list);
            Awaitility.await("the card").atMost(WAIT).until(() -> card() != null);
            final JComponent card = card();
            assertThat(onEdt(() -> {
                final List<JLabel> out = new ArrayList<>();
                collect(card, JLabel.class, l -> "tab-card-fact".equals(l.getName()), out);
                return out.stream().map(JLabel::getText).toList();
            })).anyMatch(f -> f.endsWith("/acme")).contains("feature/eng-1-x", "ENG-1");
        } finally {
            app.close();
        }
    }

    /** A repository at {@code folder} on {@code branch}. */
    private static void head(final Path folder, final String branch) throws Exception {
        Files.createDirectories(folder.resolve(".git"));
        Files.writeString(folder.resolve(".git/HEAD"), "ref: refs/heads/" + branch + "\n");
    }

    /** A {@code claude} whose every turn writes {@code file}; the path is only named, the process changes nothing. */
    private Path fakeClaude(final Path file) throws Exception {
        final Path claude = home.resolve("claude-fake");
        Files.writeString(claude, """
                #!/bin/sh
                n=0
                while IFS= read -r line; do
                  case "$line" in
                    *'"control_request"'*)
                      printf '%%s\\n' '{"type":"control_response","response":{"subtype":"success","request_id":"x"}}' ;;
                    *'"type":"user"'*)
                      n=$((n+1))
                      printf '%%s\\n' '{"type":"system","subtype":"init","session_id":"fake-1","model":"fake","cwd":"/tmp","claude_code_version":"0.0.1","permissionMode":"default","capabilities":[]}'
                      printf '%%s\\n' '{"type":"assistant","parent_tool_use_id":null,"message":{"id":"m'$n'","content":[{"type":"tool_use","id":"t'$n'","name":"Write","input":{"file_path":"%s"}}],"usage":{"input_tokens":10}}}'
                      printf '%%s\\n' '{"type":"user","parent_tool_use_id":null,"message":{"role":"user","content":[{"type":"tool_result","tool_use_id":"t'$n'","content":"ok"}]}}'
                      printf '%%s\\n' '{"type":"result","subtype":"success","is_error":false,"result":"","total_cost_usd":0,"duration_ms":1,"num_turns":1,"usage":{"input_tokens":10}}'
                      ;;
                  esac
                done
                """.formatted(file));
        assertThat(claude.toFile().setExecutable(true)).isTrue();
        return claude;
    }

    private static SessionState session(final StateLoop loop) {
        return loop.state().sessions().stream().filter(s -> "work".equals(s.name())).findFirst().orElseThrow();
    }

    /** What the session chip of the bar in front says; empty while none shows. */
    private static String chipText() throws Exception {
        return onEdt(() -> {
            final AbstractButton chip = first(AbstractButton.class, b -> named(b, "Session") && b.isShowing());
            return chip == null ? "" : chip.getText();
        });
    }

    private static boolean front(final String tab) throws Exception {
        return onEdt(() -> {
            final JToggleButton header = first(JToggleButton.class, b -> named(b, tab) && b.isShowing());
            if (header != null) {
                header.doClick();
            }
            return header != null;
        });
    }

    private static JList<?> sessions() throws Exception {
        return onEdt(() -> first(JList.class, l -> named(l, "Sessions") && l.isShowing()));
    }

    private static boolean named(final JComponent c, final String name) {
        return name.equals(c.getAccessibleContext().getAccessibleName());
    }

    /** The only row of {@code list}, the session's. */
    private static Rectangle rowBounds(final JList<?> list) {
        return list.getCellBounds(0, 0);
    }

    /** A click on the ⓘ at the row's end, low in the cell: a group's first row has the group's heading above it. */
    private static void clickInfo(final JList<?> list) throws Exception {
        final Point at = onEdt(() -> {
            final Rectangle r = rowBounds(list);
            return new Point(r.x + r.width - 14, r.y + r.height - 14);
        });
        mouse(list, MouseEvent.MOUSE_MOVED, at, 0);
        mouse(list, MouseEvent.MOUSE_CLICKED, at, 1);
    }

    private static void mouse(final JList<?> list, final int id, final Point p, final int clicks) throws Exception {
        final int button = clicks == 0 ? MouseEvent.NOBUTTON : MouseEvent.BUTTON1;
        SwingUtilities.invokeAndWait(() -> list.dispatchEvent(
                new MouseEvent(list, id, System.currentTimeMillis(), 0, p.x, p.y, clicks, false, button)));
    }

    /** The card, while it shows. */
    private static JComponent card() throws Exception {
        return onEdt(() -> first(JComponent.class, c -> "tab-card".equals(c.getName()) && c.isShowing()));
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

    private static <T extends Component> void collect(
            final Component c,
            final Class<T> type,
            final Predicate<T> which,
            final List<T> out
    ) {
        if (type.isInstance(c) && which.test(type.cast(c))) {
            out.add(type.cast(c));
        }
        if (c instanceof Container k) {
            for (final Component child : k.getComponents()) {
                collect(child, type, which, out);
            }
        }
    }

    private interface EdtCall<T> {
        T call() throws Exception;
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
}
