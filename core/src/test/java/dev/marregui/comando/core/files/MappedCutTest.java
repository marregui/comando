package dev.marregui.comando.core.files;

import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/// A file cut shorter while it is mapped, once the read is compiled. A compiled bulk copy faults outside the JDK's
/// guard
/// and ends the JVM, so each case runs in a child JVM, warmed until the copy is compiled: the child must live and
/// report
/// an [IOException].
class MappedCutTest {

    /** Reads of the file before the cut: past what compiles the read path on the JVMs comando runs on. */
    private static final int WARM_READS = 6_000;
    private static final int FILE_BYTES = 64 * 1024;
    private static final int CUT_TO_BYTES = 4_096;
    private static final long CHILD_SECONDS = 120L;

    @TempDir
    Path dir;

    /// Each way of reading bytes off the mapping survives the cut, however often it ran before.
    @ParameterizedTest
    @ValueSource(strings = {"stream", "bytes", "decode", "head"})
    void aCompiledReadOfACutFileReportsItAndTheJvmLives(final String read) throws Exception {
        final Path file = dir.resolve("cut.txt");
        final Path crash = dir.resolve("crash.log");
        final Process child = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-XX:-CreateCoredumpOnCrash", "-XX:ErrorFile=" + crash, "-cp", System.getProperty("java.class.path"),
                Child.class.getName(), read, file.toString()).redirectErrorStream(true).start();
        final String output = new String(child.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(child.waitFor(CHILD_SECONDS, TimeUnit.SECONDS)).isTrue();
        assertThat(child.exitValue()).as(output).isZero();
        assertThat(output).contains("reported");
    }

    /// A file that was not cut reads whole through the same path.
    @Test
    void aFileThatIsNotCutReadsWhole(@TempDir final Path here) throws IOException {
        final Path file = here.resolve("whole.txt");
        Files.write(file, new byte[FILE_BYTES]);
        assertThat(Mapped.bytes(file)).hasSize(FILE_BYTES);
    }

    /// The child: warms one read, then cuts the file inside that read.
    static final class Child {

        private Child() {
        }

        public static void main(final String[] args) throws IOException {
            final Path file = Path.of(args[1]);
            final byte[] text = new byte[FILE_BYTES];
            Arrays.fill(text, (byte) 'x');
            Files.write(file, text);
            for (int i = 0; i < WARM_READS; i++) {
                Mapped.read(file, m -> read(args[0], m));
            }
            try {
                Mapped.read(file, m -> {
                    try (final FileChannel c = FileChannel.open(file, StandardOpenOption.WRITE)) {
                        c.truncate(CUT_TO_BYTES);
                    }
                    return read(args[0], m);
                });
                System.out.println("not faulted");
            } catch (final IOException _) {
                System.out.println("reported");
            }
        }

        private static int read(final String how, final Mapped m) throws IOException {
            return switch (how) {
                case "stream" -> {
                    final byte[] into = new byte[8_000];
                    int total = 0;
                    try (final InputStream in = m.stream()) {
                        for (int n; (n = in.read(into)) > 0; ) {
                            total += n;
                        }
                    }
                    yield total;
                }
                case "bytes" -> Mapped.bytes(m.segment()).length;
                case "decode" -> Mapped.decode(m.segment()).length();
                default -> Mapped.bytes(m.segment().asSlice(0L, m.size() / 2L + 1L)).length;
            };
        }
    }
}
