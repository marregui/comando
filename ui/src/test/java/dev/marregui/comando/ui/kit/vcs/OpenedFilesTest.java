package dev.marregui.comando.ui.kit.vcs;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class OpenedFilesTest {

    @Test
    void theFilesOpenedAreKeptAndReadBack(@TempDir Path dir) throws Exception {
        Path store = dir.resolve("home").resolve("opened-files");
        OpenedFiles o = OpenedFiles.load(store);
        assertThat(o.contains(Path.of("/a/x.md"))).as("nothing opened yet").isFalse();
        o.add(Path.of("/a/x.md"));
        o.add(Path.of("/a/b/../y.md"));
        o.add(Path.of("/a/x.md"));
        o.written().join();
        assertThat(Files.readAllLines(store)).as("normalized, once each, oldest first").containsExactly("/a/x.md", "/a/y.md");
        assertThat(store.resolveSibling("opened-files.tmp")).doesNotExist();

        OpenedFiles again = OpenedFiles.load(store);
        assertThat(again.contains(Path.of("/a/x.md"))).isTrue();
        assertThat(again.contains(Path.of("/a/y.md"))).isTrue();
        assertThat(again.contains(Path.of("/a/z.md"))).isFalse();
    }

    @Test
    void listenersHearOnlyOfAFileNotOpenedBefore() {
        OpenedFiles o = OpenedFiles.inMemory();
        AtomicInteger heard = new AtomicInteger();
        Runnable stop = o.listen(heard::incrementAndGet);
        o.add(Path.of("/a/x.md"));
        o.add(Path.of("/a/x.md"));
        assertThat(heard).hasValue(1);
        stop.run();
        o.add(Path.of("/a/y.md"));
        assertThat(heard).hasValue(1);
        assertThat(o.written()).as("in memory: nothing written").isCompleted();
    }

    @Test
    void pastTheBoundTheOldestIsDropped() {
        OpenedFiles o = OpenedFiles.inMemory();
        for (int i = 0; i <= OpenedFiles.MAX; i++) {
            o.add(Path.of("/f/" + i));
        }
        assertThat(o.contains(Path.of("/f/0"))).isFalse();
        assertThat(o.contains(Path.of("/f/1"))).isTrue();
        assertThat(o.contains(Path.of("/f/" + OpenedFiles.MAX))).isTrue();
    }

    @Test
    void aStoreThatCannotBeReadStartsEmpty(@TempDir Path dir) throws Exception {
        Path store = Files.createDirectory(dir.resolve("opened-files"));
        OpenedFiles o = OpenedFiles.load(store);
        assertThat(o.contains(store)).isFalse();
    }
}
