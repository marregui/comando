package dev.marregui.comando.ui.shell;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.marregui.comando.core.model.ChangeTexts;
import dev.marregui.comando.core.model.RepoStatus;
import dev.marregui.comando.ui.widgets.changes.ChangeEditor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/// [DiffWindow]: the comparison window around [ChangeEditor].
/// - a file changed elsewhere, dropped, has its edits waiting to be written forgotten, so no later write undoes it
class DiffWindowTest {

    @TempDir
    Path root;

    private final List<Map.Entry<Path, String>> written = new CopyOnWriteArrayList<>();
    /** Why a write fails; null writes. */
    private final AtomicReference<String> failing = new AtomicReference<>();

    /// The window moved from A to B while A's edits could not be written, so they wait to be written again; A is then
    /// rolled back elsewhere and dropped. Once writes succeed again, writing every waiting edit writes nothing of A.
    @Test
    void aDroppedFilesWaitingEditsAreNeverWritten() throws Exception {
        final AtomicReference<DiffWindow> made = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> made.set(new DiffWindow(null, () -> { }, i -> { }, (file, text) -> {
            written.add(Map.entry(file, text));
            return CompletableFuture.completedFuture(failing.get());
        }, (parent, text, title, options) -> 1)));
        final DiffWindow window = made.get();
        final ChangeEditor e = window.editor();
        show(e, "A.java", "a\n", "b\n");
        SwingUtilities.invokeAndWait(() -> e.diff().right().insert("x", 0));
        failing.set("disk full");
        show(e, "B.java", "c\n", "d\n");
        await().atMost(Duration.ofSeconds(10)).until(() -> writesOf("A.java") >= 2);

        final AtomicLong before = new AtomicLong();
        SwingUtilities.invokeAndWait(() -> {
            window.drop(root.toString(), List.of("A.java"));
            before.set(writesOf("A.java"));
        });
        failing.set(null);
        // a retry under way when A was dropped has ended once the events before this one have run
        SwingUtilities.invokeAndWait(() -> { });
        final AtomicReference<CompletableFuture<String>> all = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> all.set(e.saveAll()));
        assertThat(all.get().get(5, TimeUnit.SECONDS)).isNull();
        assertThat(writesOf("A.java")).as("A's edits, dropped, are not written over its rollback").isEqualTo(before.get());
        SwingUtilities.invokeAndWait(e::unmount);
    }

    /** Shows {@code path} changed from {@code before} to {@code after} and waits until its comparison is on screen. */
    private void show(final ChangeEditor e, final String path, final String before, final String after) throws Exception {
        final ChangeTexts t = new ChangeTexts(root.toString(), new RepoStatus.FileChange(path, "", RepoStatus.Kind.MODIFIED), before, after, "");
        SwingUtilities.invokeAndWait(() -> e.show(t, false));
        await().atMost(Duration.ofSeconds(10)).until(() -> {
            final AtomicReference<String> right = new AtomicReference<>();
            SwingUtilities.invokeAndWait(() -> right.set(e.diff().right().getText()));
            return after.equals(right.get());
        });
    }

    private long writesOf(final String name) {
        return written.stream().filter(w -> w.getKey().getFileName().toString().equals(name)).count();
    }
}
