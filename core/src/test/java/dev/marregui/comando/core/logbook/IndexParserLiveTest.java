package dev.marregui.comando.core.logbook;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import dev.marregui.comando.core.files.Mapped;

import static org.assertj.core.api.Assertions.assertThat;

/// [IndexParser] read over a real scratchpad index, named by the system property `logbook.liveIndex` or the
/// environment variable `LOGBOOK_LIVE_INDEX` (Gradle's `-D` stops at its own JVM); skipped when neither names a file.
/// - The open tasks are as many as the `####` headings between `## Open` and the next `##` heading.
/// - Every one has a State.
class IndexParserLiveTest {

    /// The parse finds every `####` heading of the Open section and gives each a State.
    @Test
    void everyHeadingOfTheOpenSectionIsATaskWithAState() throws Exception {
        final Path file = liveIndex();
        final String text = Mapped.text(file);
        int headings = 0;
        boolean open = false;
        for (final String line : text.lines().toList()) {
            if (line.startsWith("## ")) {
                open = line.equals("## Open");
            } else if (open && line.startsWith("#### ")) {
                headings++;
            }
        }
        final Logbook logbook = IndexParser.parse(file.toString(), text);
        System.out.println("logbook live: tasks=" + logbook.tasks().size() + ", headings=" + headings + ", needingUser="
                + logbook.needingUser().size());
        assertThat(headings).isPositive();
        assertThat(logbook.tasks()).hasSize(headings);
        assertThat(logbook.tasks()).extracting(LogbookTask::state).doesNotContain("");
    }

    private static Path liveIndex() {
        String named = System.getProperty("logbook.liveIndex");
        if (named == null || named.isBlank()) {
            named = System.getenv("LOGBOOK_LIVE_INDEX");
        }
        Assumptions.assumeTrue(named != null && !named.isBlank(), "no live index named");
        final Path file = Path.of(named);
        Assumptions.assumeTrue(Files.isRegularFile(file), "the live index is no file");
        return file;
    }
}
