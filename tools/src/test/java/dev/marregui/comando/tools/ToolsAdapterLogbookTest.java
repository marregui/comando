package dev.marregui.comando.tools;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.marregui.comando.core.console.ConsoleRegistry;
import dev.marregui.comando.core.logbook.Logbook;
import dev.marregui.comando.core.logbook.LogbookTask;
import dev.marregui.comando.testkit.RecordingSink;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/// [ToolsAdapter] watches the newest day folder's `INDEX.md` of a scratchpad and gives the Logbook it holds to a sink.
/// - The first read always sends; the newest day folder by name wins.
/// - An edit of the index is sent once, an unchanged index is not sent again.
/// - A scratchpad without a day folder sends `NONE`; a day folder without an index sends a problem.
/// - A second watch replaces the first sink; after close nothing is sent.
class ToolsAdapterLogbookTest {

    private static final Duration WAIT = Duration.ofSeconds(20);
    private static final Duration QUIET = Duration.ofMillis(300);
    private static final String INDEX = """
            ## Open

            ### `t/`: synthetic

            #### 1. First task

            - **State:** open
            """;

    private final BlockingQueue<Logbook> sent = new LinkedBlockingQueue<>();

    /// With two day folders, other entries and a file that only looks like a day, the read parses the newer day's index,
    /// whose absolute path the Logbook names.
    @Test
    void theNewestDayFolderWins(@TempDir final Path scratchpad) throws Exception {
        day(scratchpad, "2026-11-09", INDEX.replace("First task", "Old task"));
        final Path newest = day(scratchpad, "2026-11-10", INDEX);
        Files.writeString(scratchpad.resolve("2026-11-11"), "a file, not a day folder");
        Files.writeString(scratchpad.resolve("README.md"), "not a day");
        Files.createDirectories(scratchpad.resolve("inbox"));
        try (final ToolsAdapter tools = adapter()) {
            tools.watchLogbook(scratchpad, sent::add);
            final Logbook first = next(sent);
            assertThat(first.index()).isEqualTo(newest.resolve("INDEX.md").toAbsolutePath().toString());
            assertThat(first.problem()).isEmpty();
            assertThat(first.tasks()).extracting(LogbookTask::title, LogbookTask::topic).containsExactly(
                    tuple("First task", "t/"));
        }
    }

    /// An edit of the index is sent once by the poll, and the polls that follow send nothing while it stays as it is.
    @Test
    void anEditIsSentOnce(@TempDir final Path scratchpad) throws Exception {
        final Path day = day(scratchpad, "2026-11-10", INDEX);
        try (final ToolsAdapter tools = adapter()) {
            tools.pollFolders(Duration.ofMillis(20));
            tools.watchLogbook(scratchpad, sent::add);
            assertThat(next(sent).tasks()).hasSize(1);
            assertThat(sent.poll(QUIET.toMillis(), TimeUnit.MILLISECONDS)).as("an unchanged index is not sent again").isNull();

            edit(day, INDEX + "\n#### 2. Second task\n\n- **State:** waiting on the user\n");
            final Logbook edited = next(sent);
            assertThat(edited.tasks()).hasSize(2);
            assertThat(edited.needingUser()).extracting(LogbookTask::number).containsExactly(2);
            assertThat(sent.poll(QUIET.toMillis(), TimeUnit.MILLISECONDS)).as("the edit is sent once").isNull();
        }
    }

    /// A day folder rolled in after the watch began, newer than the one read, takes over at the next poll.
    @Test
    void aNewerDayFolderTakesOver(@TempDir final Path scratchpad) throws Exception {
        day(scratchpad, "2026-11-10", INDEX);
        try (final ToolsAdapter tools = adapter()) {
            tools.pollFolders(Duration.ofMillis(20));
            tools.watchLogbook(scratchpad, sent::add);
            assertThat(next(sent).tasks()).extracting(LogbookTask::title).containsExactly("First task");

            day(scratchpad, "2026-11-11", INDEX.replace("First task", "Rolled task"));
            assertThat(next(sent).tasks()).extracting(LogbookTask::title).containsExactly("Rolled task");
        }
    }

    /// A scratchpad with no day folder, or none at all, sends `NONE` once.
    @Test
    void noDayFolderSendsNone(@TempDir final Path scratchpad) throws Exception {
        Files.writeString(scratchpad.resolve("README.md"), "not a day");
        try (final ToolsAdapter tools = adapter()) {
            tools.pollFolders(Duration.ofMillis(20));
            tools.watchLogbook(scratchpad, sent::add);
            assertThat(next(sent)).isSameAs(Logbook.NONE);
            tools.watchLogbook(scratchpad.resolve("missing"), sent::add);
            assertThat(next(sent)).isEqualTo(Logbook.NONE);
            assertThat(sent.poll(QUIET.toMillis(), TimeUnit.MILLISECONDS)).isNull();
        }
    }

    /// A day folder without an index sends a problem naming the index, and the index sent once it appears.
    @Test
    void aDayFolderWithoutAnIndexSendsAProblem(@TempDir final Path scratchpad) throws Exception {
        final Path day = Files.createDirectories(scratchpad.resolve("2026-11-10"));
        try (final ToolsAdapter tools = adapter()) {
            tools.pollFolders(Duration.ofMillis(20));
            tools.watchLogbook(scratchpad, sent::add);
            final Logbook problem = next(sent);
            assertThat(problem.tasks()).isEmpty();
            assertThat(problem.problem()).isNotEmpty();
            assertThat(problem.index()).isEqualTo(day.resolve("INDEX.md").toAbsolutePath().toString());

            edit(day, INDEX);
            assertThat(next(sent).problem()).isEmpty();
        }
    }

    /// A second watch replaces the first sink: the new one gets the Logbook at once and the old one nothing more.
    @Test
    void aSecondWatchReplacesTheSink(@TempDir final Path scratchpad) throws Exception {
        final Path day = day(scratchpad, "2026-11-10", INDEX);
        final BlockingQueue<Logbook> second = new LinkedBlockingQueue<>();
        try (final ToolsAdapter tools = adapter()) {
            tools.pollFolders(Duration.ofMillis(20));
            tools.watchLogbook(scratchpad, sent::add);
            next(sent);
            tools.watchLogbook(scratchpad, second::add);
            assertThat(next(second).tasks()).hasSize(1);

            edit(day, INDEX + "\n#### 2. Second task\n\n- **State:** open\n");
            assertThat(next(second).tasks()).hasSize(2);
            assertThat(sent.poll(QUIET.toMillis(), TimeUnit.MILLISECONDS)).as("the replaced sink hears nothing more").isNull();
        }
    }

    /// After close nothing is sent: not an edit the poll had yet to see, and not a watch asked for afterwards.
    @SuppressWarnings("try") // closed inside its try on purpose: the test is what happens after close, which is idempotent
    @Test
    void nothingIsSentAfterClose(@TempDir final Path scratchpad) throws Exception {
        final Path day = day(scratchpad, "2026-11-10", INDEX);
        try (final ToolsAdapter tools = adapter()) {
            tools.pollFolders(Duration.ofSeconds(30));
            tools.watchLogbook(scratchpad, sent::add);
            next(sent);
            edit(day, INDEX + "\n#### 2. Second task\n\n- **State:** open\n");
            tools.close();
            tools.watchLogbook(scratchpad, sent::add);
            assertThat(sent.poll(QUIET.toMillis(), TimeUnit.MILLISECONDS)).isNull();
            assertThat(List.copyOf(sent)).isEmpty();
        }
    }

    private static ToolsAdapter adapter() {
        return new ToolsAdapter(new RecordingSink(), new ConsoleRegistry(),
                () -> new ToolSettings(Optional.empty(), System.getProperty("java.home")));
    }

    private static Path day(final Path scratchpad, final String name, final String index) throws Exception {
        final Path dir = Files.createDirectories(scratchpad.resolve(name));
        Files.writeString(dir.resolve("INDEX.md"), index);
        return dir;
    }

    /// Replaces the index whole, so a poll never reads it half written.
    private static void edit(final Path day, final String index) throws Exception {
        final Path draft = Files.writeString(day.resolve("INDEX.draft"), index);
        Files.move(draft, day.resolve("INDEX.md"), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    private Logbook next(final BlockingQueue<Logbook> from) throws Exception {
        final Logbook logbook = from.poll(WAIT.toMillis(), TimeUnit.MILLISECONDS);
        assertThat(logbook).as("a Logbook within %s", WAIT).isNotNull();
        return logbook;
    }
}
