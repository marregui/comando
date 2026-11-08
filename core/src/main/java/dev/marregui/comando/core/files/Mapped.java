package dev.marregui.comando.core.files;

import java.io.IOException;
import java.io.InputStream;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

/**
 * A file mapped read-only, for one thread and one read (record 140). Every file comando reads goes through here: the
 * bytes are scanned where they lie, and only the slice a reader needs is decoded.
 * <p>
 * A file cut shorter while it is mapped faults on the missing page. A byte read from the segment is guarded: the JDK
 * reports the fault as an {@link InternalError}, which {@link #read(Path, Body)} and the whole-file helpers turn into
 * an
 * {@link IOException}, and a reader that keeps a mapping open by {@link #open(Path)} handles itself. A bulk copy is not
 * guarded once compiled: the fault ends the JVM. So bytes leave a mapping only through this class, byte by byte, and
 * nothing calls {@code MemorySegment.copy}, {@code toArray} or {@code asByteBuffer} on one ({@code FileReadRulesTest}).
 * <p>
 * The whole-file reads refuse malformed UTF-8, as {@code Files.readString} does: their callers may write the text back.
 * A slice of a log or a transcript decodes with the replacement character instead, and is only read.
 */
public final class Mapped implements AutoCloseable {

    private static final byte NEWLINE = '\n';
    private static final byte RETURN = '\r';

    private final Arena arena;
    private final MemorySegment segment;

    private Mapped(final Arena arena, final MemorySegment segment) {
        this.arena = arena;
        this.segment = segment;
    }

    /** What a read does with the mapping. */
    @FunctionalInterface
    public interface Body<T> {
        T read(Mapped file) throws IOException;
    }

    /** Takes each line, without its line end; {@code false} ends the walk. The slice is valid only during the call. */
    @FunctionalInterface
    public interface LineSink {
        boolean line(MemorySegment line) throws IOException;
    }

    /**
     * Maps {@code file} at the size it has now; bytes appended later are not seen. The caller closes it, on this
     * thread.
     */
    public static Mapped open(final Path file) throws IOException {
        final Arena arena = Arena.ofConfined();
        try (final FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
            final long size = channel.size();
            final MemorySegment segment = size == 0L ? MemorySegment.ofArray(new byte[0])
                    : channel.map(FileChannel.MapMode.READ_ONLY, 0L, size, arena);
            return new Mapped(arena, segment);
        } catch (final IOException | RuntimeException e) {
            arena.close();
            throw e;
        }
    }

    /** Runs {@code body} on {@code file} mapped, and unmaps it. */
    public static <T> T read(final Path file, final Body<T> body) throws IOException {
        try (final Mapped mapped = open(file)) {
            return body.read(mapped);
        } catch (final InternalError e) {
            throw new IOException("cannot read file cut while mapped [file=" + file + ']', e);
        }
    }

    /** The whole of {@code file} as UTF-8 text, for a reader that needs all of it; malformed text is refused. */
    public static String text(final Path file) throws IOException {
        final byte[] bytes = bytes(file);
        return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
    }

    /** The whole of {@code file}. */
    public static byte[] bytes(final Path file) throws IOException {
        return read(file, m -> bytes(m.segment));
    }

    /** The lines of {@code file} as UTF-8 text, without their line ends; malformed text is refused. */
    public static List<String> lines(final Path file) throws IOException {
        return text(file).lines().toList();
    }

    /** The first {@code max} bytes of {@code file}, fewer when it is shorter. */
    public static byte[] head(final Path file, final long max) throws IOException {
        return read(file, m -> bytes(m.segment.asSlice(0L, Math.min(max, m.size()))));
    }

    /** The lines of {@code file} that hold {@code lowerCaseNeedle}, as UTF-8 text; the others are never decoded. */
    public static List<String> linesWith(final Path file, final byte[] lowerCaseNeedle) throws IOException {
        return read(file, m -> {
            final List<String> out = new ArrayList<>();
            m.lines(line -> {
                if (ByteScan.indexOf(line, lowerCaseNeedle, 0L) >= 0L) {
                    out.add(decode(line));
                }
                return true;
            });
            return out;
        });
    }

    /** The bytes of {@code slice} on the heap. */
    public static byte[] bytes(final MemorySegment slice) {
        final byte[] out = new byte[Math.toIntExact(slice.byteSize())];
        copy(slice, 0L, out, 0, out.length);
        return out;
    }

    /** {@code count} bytes of {@code from} from {@code at} on, one at a time: see the class's note on bulk copies. */
    private static void copy(
            final MemorySegment from,
            final long at,
            final byte[] into,
            final int offset,
            final int count
    ) {
        for (int i = 0; i < count; i++) {
            into[offset + i] = from.get(ValueLayout.JAVA_BYTE, at + i);
        }
    }

    /** {@code slice} as UTF-8 text. */
    public static String decode(final MemorySegment slice) {
        return new String(bytes(slice), StandardCharsets.UTF_8);
    }

    public MemorySegment segment() {
        return segment;
    }

    public long size() {
        return segment.byteSize();
    }

    /** The bytes from {@code from} up to {@code to} as UTF-8 text. */
    public String text(final long from, final long to) {
        return decode(segment.asSlice(from, to - from));
    }

    /** Walks the lines from the first; a last line with no line end counts, an empty end after a line end does not. */
    public void lines(final LineSink sink) throws IOException {
        final long size = segment.byteSize();
        long start = 0L;
        while (start < size) {
            long end = start;
            while (end < size && segment.get(ValueLayout.JAVA_BYTE, end) != NEWLINE) {
                end++;
            }
            final boolean returned = end > start && segment.get(ValueLayout.JAVA_BYTE, end - 1L) == RETURN;
            if (!sink.line(segment.asSlice(start, (returned ? end - 1L : end) - start))) {
                return;
            }
            start = end + 1L;
        }
    }

    /** The mapping as a stream, for a library that reads one; valid until {@link #close()}, on this thread. */
    public InputStream stream() {
        return new SegmentStream(segment);
    }

    /** Bytes {@code from} to {@code to} as a stream, as {@link #stream()}: a slice is parsed without copying it. */
    public InputStream stream(final long from, final long to) {
        return new SegmentStream(segment.asSlice(from, to - from));
    }

    @Override
    public void close() {
        arena.close();
    }

    /** Reads a segment from its start. */
    private static final class SegmentStream extends InputStream {

        private final MemorySegment segment;
        private long position;

        SegmentStream(final MemorySegment segment) {
            this.segment = segment;
        }

        @Override
        public int read() {
            return position < segment.byteSize() ? segment.get(ValueLayout.JAVA_BYTE, position++) & 0xff : -1;
        }

        @Override
        public int read(final byte[] into, final int offset, final int length) {
            final long left = segment.byteSize() - position;
            if (length == 0) {
                return 0;
            }
            if (left <= 0L) {
                return -1;
            }
            final int count = (int) Math.min(length, left);
            copy(segment, position, into, offset, count);
            position += count;
            return count;
        }
    }
}
