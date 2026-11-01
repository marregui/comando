package dev.marregui.comando.app;

import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.marregui.comando.core.model.KitRoot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// [App#closeAll] closes every part, the last created first, whatever one of them throws; a start that fails rolls back
/// through it.
class AppCloseTest {

    /// A part whose close throws an `Exception` or an `Error` does not stop the parts after it: every part closes, in
    /// reverse order; the first `Error` is rethrown once all have closed, a later one suppressed in it.
    @Test
    void aPartThatThrowsAnErrorStopsNoOtherPartsClose() {
        final List<String> closed = new ArrayList<>();
        final Deque<AutoCloseable> owned = new ArrayDeque<>();
        owned.push(() -> closed.add("store"));
        owned.push(() -> {
            closed.add("claude");
            throw new LinkageError("a class that cannot load");
        });
        owned.push(() -> {
            closed.add("tools");
            throw new IllegalStateException("a part that fails");
        });
        owned.push(() -> {
            closed.add("peers");
            throw new AssertionError("a second error");
        });

        assertThatThrownBy(() -> App.closeAll(owned)).isInstanceOf(AssertionError.class).hasMessage("a second error")
                .satisfies(e -> assertThat(e.getSuppressed()).extracting(Throwable::getMessage).containsExactly("a class that cannot load"));
        assertThat(closed).containsExactly("peers", "tools", "claude", "store");
        assertThat(owned).isEmpty();
    }

    /// A start that fails before the window exists rolls back what it built and reports its own cause: here the intent
    /// log cannot open, its folder being a file. The constructor rethrows the log's failure, not a
    /// `NullPointerException` from the window it never built, and the first part it owned, the root it set with
    /// `KitRoot.use`, is closed: the root before it is back.
    @Test
    void aStartThatFailsBeforeTheWindowRollsBackAndReportsItsCause(@TempDir final Path home) throws Exception {
        final Path data = Files.createDirectories(home.resolve("data"));
        Files.writeString(data.resolve("logs"), "a file where the logs folder goes");
        final KitRoot outer = KitRoot.current();
        final KitRoot before = KitRoot.plain(home.resolve("before"));
        KitRoot.use(before);
        try {
            assertThatThrownBy(() -> new App(new AppConfig(data, home.resolve("no-claude").toString(), home.resolve("projects"), true), "rollback",
                    () -> {
                    }))
                    .as("the start's own failure is reported").isInstanceOf(UncheckedIOException.class).hasMessageContaining("intent log");
            assertThat(KitRoot.current()).as("the rollback closed the first part it owned: the root before it is back").isSameAs(before);
        } finally {
            KitRoot.use(outer);
        }
    }
}
