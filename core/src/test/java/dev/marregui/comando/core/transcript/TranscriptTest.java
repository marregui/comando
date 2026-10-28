package dev.marregui.comando.core.transcript;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// [Transcript] publishes one writer's ordered blocks safely to concurrent readers.
///
/// - Appends grow the log and replacements advance its version.
/// - Readers see a block or a clear boundary, never an unwritten slot.
/// - Readers racing a clear complete without an indexing failure.
class TranscriptTest {

    /// [Transcript] assigns sequential append indexes, retains the 200-block size after replacement, and increments
    /// its version so views can observe an in-place block change.
    @Test
    void appendGrowsAndReplaceBumpsVersion() {
        Transcript t = new Transcript();
        for (int i = 0; i < 200; i++) {
            assertThat(t.append(new Block.UserPrompt("p" + i, "", ""))).isEqualTo(i);
        }
        long v = t.version();
        t.replace(5, new Block.UserPrompt("changed", "", ""));
        assertThat(t.version()).isEqualTo(v + 1);
        assertThat(((Block.UserPrompt) t.get(5)).text()).isEqualTo("changed");
        assertThat(t.size()).isEqualTo(200);
    }

    /// [Transcript] publishes each of 200,000 appended blocks before its size, so a concurrent reader never finds a
    /// null block below the size it observed.
    @Test
    void readerNeverSeesAHoleBelowSize() throws Exception {
        Transcript t = new Transcript();
        AtomicBoolean stop = new AtomicBoolean();
        AtomicReference<String> failure = new AtomicReference<>();
        Thread reader = Thread.ofPlatform().start(() -> {
            while (!stop.get()) {
                int n = t.size();
                for (int i = 0; i < n; i++) {
                    if (t.get(i) == null) {
                        failure.set("null block at " + i + " of " + n);
                        return;
                    }
                }
            }
        });
        for (int i = 0; i < 200_000; i++) {
            t.append(new Block.Note(Block.NoteKind.INFO, "n", ""));
        }
        stop.set(true);
        reader.join();
        assertThat(failure.get()).isNull();
    }

    /// [Transcript#get] returns null for a non-negative index retained from before `clear`, while negative indexes
    /// remain invalid, allowing an old reader to stop safely.
    @Test
    void aReaderHoldingASizeFromBeforeAClearGetsNull() {
        Transcript t = new Transcript();
        for (int i = 0; i < 100; i++) {
            t.append(new Block.Note(Block.NoteKind.INFO, "n" + i, ""));
        }
        int n = t.size();
        t.clear();
        assertThat(t.get(n - 1)).as("past the end of the cleared log, and past its new array").isNull();
        assertThat(t.get(0)).isNull();
        assertThatThrownBy(() -> t.get(-1)).isInstanceOf(IndexOutOfBoundsException.class);
    }

    /// [Transcript] lets a reader stop at null while 20,000 clears replace the backing array, without exposing an
    /// exception from a size-and-get race.
    @Test
    void aReaderRacingClearsNeverFails() throws Exception {
        Transcript t = new Transcript();
        AtomicBoolean stop = new AtomicBoolean();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread reader = Thread.ofPlatform().start(() -> {
            try {
                while (!stop.get()) {
                    int n = t.size();
                    int i = 0;
                    // a null ends the walk: the log was cleared under it
                    while (i < n && t.get(i) != null) {
                        i++;
                    }
                }
            } catch (Throwable e) {
                failure.set(e);
            }
        });
        for (int round = 0; round < 20_000 && failure.get() == null; round++) {
            for (int i = 0; i < 100; i++) {
                t.append(new Block.Note(Block.NoteKind.INFO, "n", ""));
            }
            t.clear();
        }
        stop.set(true);
        reader.join();
        assertThat(failure.get()).isNull();
    }
}
