package dev.marregui.comando.core.files;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// A file mapped read-only, read in place.
/// - the whole-file helpers answer what the file holds, an empty file included
/// - lines come without their line ends, the last one with or without an end
/// - only the slice asked for is decoded, and malformed UTF-8 decodes rather than fails
/// - the stream reads the mapping from its start to its end
/// - a file that is not there fails as an `IOException`
class MappedTest {

    @TempDir
    Path dir;

    private Path file(final String text) throws IOException {
        return Files.writeString(dir.resolve("file.txt"), text);
    }

    /// The text and the bytes of a file are what was written, UTF-8 included.
    @Test
    void theWholeFileReadsAsWritten() throws IOException {
        final Path f = file("añb\n");
        assertThat(Mapped.text(f)).isEqualTo("añb\n");
        assertThat(Mapped.bytes(f)).isEqualTo("añb\n".getBytes(StandardCharsets.UTF_8));
    }

    /// An empty file cannot be mapped by the JDK's map call and reads as nothing, whichever way it is read.
    @Test
    void anEmptyFileReadsAsNothing() throws IOException {
        final Path f = file("");
        assertThat(Mapped.text(f)).isEmpty();
        assertThat(Mapped.bytes(f)).isEmpty();
        assertThat(Mapped.lines(f)).isEmpty();
        assertThat(Mapped.head(f, 8L)).isEmpty();
    }

    /// Lines come without `\n` or `\r\n`, an empty line counts, a last line with no end counts and an empty end after a
    /// line end does not.
    @Test
    void linesComeWithoutTheirLineEnds() throws IOException {
        assertThat(Mapped.lines(file("one\r\n\ntwo\n"))).containsExactly("one", "", "two");
        assertThat(Mapped.lines(file("one\ntwo"))).containsExactly("one", "two");
    }

    /// A sink that answers `false` ends the walk at that line.
    @Test
    void aLineSinkEndsTheWalk() throws IOException {
        final List<String> seen = new ArrayList<>();
        Mapped.read(file("one\ntwo\nthree\n"), m -> {
            m.lines(line -> {
                seen.add(Mapped.decode(line));
                return seen.size() < 2;
            });
            return null;
        });
        assertThat(seen).containsExactly("one", "two");
    }

    /// The head of a file is its first bytes, all of them when it is shorter.
    @Test
    void theHeadIsTheFirstBytes() throws IOException {
        final Path f = file("abcdef");
        assertThat(new String(Mapped.head(f, 3L), StandardCharsets.UTF_8)).isEqualTo("abc");
        assertThat(new String(Mapped.head(f, 100L), StandardCharsets.UTF_8)).isEqualTo("abcdef");
    }

    /// `linesWith` answers the lines that hold the needle in either case, and no other.
    @Test
    void linesWithAnswersOnlyTheLinesThatHoldTheNeedle() throws IOException {
        assertThat(Mapped.linesWith(file("one\nTWO three\nfour\ntwo\n"), "two".getBytes(StandardCharsets.UTF_8)))
                .containsExactly("TWO three", "two");
    }

    /// The bytes between two offsets decode alone, and a slice cut through a UTF-8 sequence decodes to the replacement
    /// character instead of failing.
    @Test
    void aSliceDecodesAloneAndAMalformedOneDoesNotFail() throws IOException {
        final String middle = Mapped.read(file("one two three"), m -> m.text(4L, 7L));
        assertThat(middle).isEqualTo("two");
        final String cut = Mapped.read(file("ñ"), m -> m.text(0L, 1L));
        assertThat(cut).isEqualTo("�");
    }

    /// The stream gives the file's bytes one at a time and in blocks, then the end.
    @Test
    void theStreamReadsTheMappingToItsEnd() throws IOException {
        final String read = Mapped.read(file("abcdef"), m -> {
            try (final InputStream in = m.stream()) {
                final int first = in.read();
                final byte[] block = new byte[3];
                final int count = in.read(block, 0, 3);
                final String rest = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                assertThat(in.read()).isEqualTo(-1);
                assertThat(in.read(block, 0, 3)).isEqualTo(-1);
                return (char) first + "|" + new String(block, 0, count, StandardCharsets.UTF_8) + "|" + rest;
            }
        });
        assertThat(read).isEqualTo("a|bcd|ef");
    }

    /// A file that is not there fails as an `IOException`.
    @Test
    void aFileThatIsNotThereFailsAsAnIoException() {
        assertThatThrownBy(() -> Mapped.text(dir.resolve("gone.txt"))).isInstanceOf(IOException.class);
    }

    /// A whole-file read refuses malformed UTF-8, as `Files.readString` does: its caller may write the text back, and a
    /// replaced byte would be lost on disk.
    @Test
    void aWholeFileOfMalformedTextIsRefused(@TempDir final Path dir) throws IOException {
        final Path file = dir.resolve("latin.txt");
        Files.write(file, new byte[]{'c', 'a', 'f', (byte) 0xe9, '\n'});
        assertThatThrownBy(() -> Mapped.text(file)).isInstanceOf(CharacterCodingException.class);
        assertThatThrownBy(() -> Mapped.lines(file)).isInstanceOf(CharacterCodingException.class);
    }
}
