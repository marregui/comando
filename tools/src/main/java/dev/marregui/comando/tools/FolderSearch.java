package dev.marregui.comando.tools;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

import dev.marregui.comando.core.files.ByteScan;
import dev.marregui.comando.core.files.Mapped;
import dev.marregui.comando.core.loop.ExecutorStop;
import dev.marregui.comando.core.loop.Threads;
import dev.marregui.comando.core.model.FolderRoot;
import dev.marregui.comando.core.model.HomeDir;
import dev.marregui.comando.core.model.SearchHit;
import dev.marregui.comando.core.model.SearchQuery;
import dev.marregui.comando.core.port.SearchPort;

/**
 * Looks in the Files tab's folders, with no index (record 139): every search walks them and reads them. The walk leaves
 * out what the tab leaves out, hidden entries and the folder's excluded names, and follows no link to a folder, then
 * hands the files whose name holds the text. The contents come next: each file is mapped and its bytes scanned in
 * place, and only the part of a line a hit shows is decoded. A file with a NUL in its first 8 kB is binary and is not
 * read
 * further. A line gives one hit, at its first match.
 * <p>
 * The reading is spread over forks, as many as the searcher allows, which {@link #scan} collects before it returns.
 * The hits still reach {@code found} on the scanning thread and in the walk's order, whichever fork was first, so one
 * search cut at its limit lists the same hits every time. Forks stay at most {@link #AHEAD} files ahead of what was
 * handed over, which bounds the hits held.
 */
public final class FolderSearch implements SearchPort.Source {

    /** A snippet's length at most, in chars; set by record 139, a row of the list at its widest. */
    static final int SNIPPET_CHARS = 200;
    /**
     * Files one search reads at most: past it a folder counts as not read. A bound on the list, not a measured limit;
     * a folder that reaches it in use would settle it.
     */
    static final int MAX_FILES = 500_000;
    /**
     * How far the forks may read ahead of the hits handed over, in files. Unmeasured: a queue for the forks to fill;
     * the 15 MB search timed with 16 and 256 would settle it.
     */
    static final int AHEAD = 64;

    private static final System.Logger LOG = System.getLogger(FolderSearch.class.getName());
    /**
     * Bytes scanned between two looks at {@code ended}: a file of any size answers a cancel within a millisecond or so,
     * at the 1.2 s for 400 MB measured on one fork (record 139), about 3 ms a chunk.
     */
    private static final long CHUNK_BYTES = 1L << 20;
    /**
     * Chars kept before a match in a long line, so what precedes it reads while most of the snippet follows it.
     * Unmeasured.
     */
    private static final int CHARS_BEFORE = 60;
    /** A char takes at most this many bytes in UTF-8, counting the bytes a clipped snippet may need around a match. */
    private static final int BYTES_PER_CHAR = 4;
    /**
     * How often a wait for a file looks at {@code ended}, in milliseconds; a cancel is seen this fast. Unmeasured:
     * below what a keystroke's next search waits for (200 ms), which is the bound it must keep.
     */
    private static final long WAIT_MILLIS = 50L;
    private static final byte NEWLINE = '\n';
    private static final byte HASH = '#';
    /** The deepest heading Markdown has, {@code ######}. */
    private static final int HEADING_LEVELS = 6;
    /** The shortest fence, three backticks or tildes, as CommonMark has it. */
    private static final int FENCE = 3;

    private final int scanners;
    private final int fileHits;

    /**
     * @param scanners how many files are read at once
     * @param fileHits the hits one file gives at most; one more than a searcher lists tells it that it was cut
     */
    public FolderSearch(final int scanners, final int fileHits) {
        this.scanners = scanners;
        this.fileHits = fileHits;
    }

    @Override
    public int scan(final SearchQuery query, final Predicate<SearchHit> found, final BooleanSupplier ended) {
        final byte[] needle = ByteScan.needle(query.text());
        final Walk walk = new Walk(needle, found, ended);
        for (final FolderRoot folder : query.folders()) {
            walk.folder(folder);
        }
        return walk.stopped() ? walk.failed : walk.failed + new Reading(walk.files, needle, found, ended).read();
    }

    /** A file to read: where it is, the folder walked to it, and what its hits say. */
    private record Target(Path path, String source, String root, String group, long modifiedMillis) {

        /** Whether the file is Markdown, whose hits name the headings above them. */
        boolean markdown() {
            final String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
            return name.endsWith(".md") || name.endsWith(".markdown");
        }
    }

    /** What reading one file gave. */
    private record Outcome(List<SearchHit> hits, int failed) {
        static final Outcome UNREAD = new Outcome(List.of(), 1);
    }

    /** Lists the folders' files, handing over those whose name holds the text. */
    private static final class Walk extends SimpleFileVisitor<Path> {

        final List<Target> files = new ArrayList<>();
        int failed;

        private final Set<Path> seen = new HashSet<>();
        private final byte[] needle;
        private final Predicate<SearchHit> found;
        private final BooleanSupplier ended;
        private FolderRoot folder;
        private Path root;
        /** The folder as the Files tab lists it: {@link #root} unless the tab's folder is a link to it. */
        private Path listed;
        private boolean refused;

        Walk(final byte[] needle, final Predicate<SearchHit> found, final BooleanSupplier ended) {
            this.needle = needle;
            this.found = found;
            this.ended = ended;
        }

        /** Whether the search should go no further. */
        boolean stopped() {
            return refused || ended.getAsBoolean();
        }

        void folder(final FolderRoot next) {
            if (stopped()) {
                return;
            }
            folder = next;
            try {
                final Path path = Path.of(HomeDir.expand(next.path()));
                // a walk does not follow links, and a folder of the tab may be one
                root = Files.isSymbolicLink(path) ? path.toRealPath() : path;
                listed = path;
                Files.walkFileTree(root, this);
            } catch (final IOException | RuntimeException e) {
                failed++;
                LOG.log(System.Logger.Level.DEBUG, "cannot walk folder [folder=" + next.name() + ']', e);
            }
        }

        private boolean left(final Path p) {
            final String name = p.getFileName().toString();
            return name.startsWith(".") || folder.excludes(name);
        }

        @Override
        public FileVisitResult preVisitDirectory(final Path dir, final BasicFileAttributes attributes) {
            if (stopped()) {
                return FileVisitResult.TERMINATE;
            }
            return !dir.equals(root) && left(dir) ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult visitFile(final Path file, final BasicFileAttributes attributes) {
            if (left(file) || !seen.add(file)) {
                return FileVisitResult.CONTINUE;
            }
            // a link is read when it leads to a file; a FIFO or a device would block whoever opened it
            if (!attributes.isRegularFile() && !(attributes.isSymbolicLink() && Files.isRegularFile(file))) {
                return FileVisitResult.CONTINUE;
            }
            if (files.size() == MAX_FILES) {
                failed++;
                return FileVisitResult.TERMINATE;
            }
            // a hit names the file where the Files tab shows it, under the folder as listed, links or not
            final Path shown = listed.resolve(root.relativize(file));
            final Target target = new Target(file, shown.toAbsolutePath().toString(),
                    listed.toAbsolutePath().toString(), folder.name(), attributes.lastModifiedTime().toMillis());
            files.add(target);
            final byte[] name = file.getFileName().toString().getBytes(StandardCharsets.UTF_8);
            final long at = ByteScan.indexOf(MemorySegment.ofArray(name), needle, 0L);
            if (at >= 0L && !found.test(nameHit(target, name, (int) at))) {
                refused = true;
                return FileVisitResult.TERMINATE;
            }
            return FileVisitResult.CONTINUE;
        }

        private SearchHit nameHit(final Target target, final byte[] name, final int at) {
            final String before = new String(name, 0, at, StandardCharsets.UTF_8);
            final String match = new String(name, at, needle.length, StandardCharsets.UTF_8);
            return new SearchHit(SearchHit.Kind.NAME, target.source(), "", target.group(), 0,
                    new String(name, StandardCharsets.UTF_8), before.length(), match.length(), target.modifiedMillis())
                    .placed(target.root(), "");
        }

        @Override
        public FileVisitResult visitFileFailed(final Path file, final IOException e) {
            failed++;
            LOG.log(System.Logger.Level.DEBUG, "cannot read entry [folder=" + folder.name() + ']', e);
            return FileVisitResult.CONTINUE;
        }
    }

    /** Reads the walk's files on forks, and hands their hits over in the walk's order. */
    private final class Reading {

        private final List<Target> files;
        private final List<CompletableFuture<Outcome>> outcomes;
        private final byte[] needle;
        private final Predicate<SearchHit> found;
        private final BooleanSupplier ended;
        private final AtomicInteger next = new AtomicInteger();
        private final Semaphore ahead = new Semaphore(AHEAD);
        /** Set by the scanning thread when it is done with the hits, read by the forks between files. */
        private volatile boolean over;

        Reading(
                final List<Target> files,
                final byte[] needle,
                final Predicate<SearchHit> found,
                final BooleanSupplier ended
        ) {
            this.files = files;
            this.needle = needle;
            this.found = found;
            this.ended = ended;
            outcomes = new ArrayList<>(files.size());
            for (int i = 0; i < files.size(); i++) {
                outcomes.add(new CompletableFuture<>());
            }
        }

        /** Whether the forks should stop. Forks call it, so {@code ended} is read by several threads. */
        private boolean stopped() {
            return over || ended.getAsBoolean();
        }

        /**
         * Reads every file or stops; returns the files not read. Every fork has ended, or been waited for, on return.
         */
        int read() {
            final List<Thread> forks = new ArrayList<>();
            for (int f = 0; f < Math.min(scanners, files.size()); f++) {
                forks.add(Threads.fork("folder-search-" + f, this::work));
            }
            try {
                return handOver(forks);
            } finally {
                over = true;
                collect(forks);
            }
        }

        /** A fork: takes the next file, within what may be read ahead, until none is left or the search stops. */
        private void work() {
            try {
                while (!stopped()) {
                    if (!ahead.tryAcquire(WAIT_MILLIS, TimeUnit.MILLISECONDS)) {
                        continue;
                    }
                    final int i = next.getAndIncrement();
                    if (i >= files.size()) {
                        return;
                    }
                    Outcome outcome = Outcome.UNREAD;
                    try {
                        outcome = hitsOf(files.get(i));
                    } finally {
                        outcomes.get(i).complete(outcome);
                    }
                }
            } catch (final InterruptedException _) {
                Thread.currentThread().interrupt(); // the search is closing; the scanning thread stops too
            }
        }

        private Outcome hitsOf(final Target target) {
            try {
                return new Outcome(Mapped.read(target.path(), m -> fileScan(m, target).hits()), 0);
            } catch (final IOException | RuntimeException e) {
                LOG.log(System.Logger.Level.DEBUG, "cannot read file [file=" + target.source() + ']', e);
                return Outcome.UNREAD;
            }
        }

        private FileScan fileScan(final Mapped file, final Target target) {
            return new FileScan(file.segment(), target, needle, this::stopped);
        }

        private int handOver(final List<Thread> forks) {
            int failed = 0;
            for (final CompletableFuture<Outcome> file : outcomes) {
                final Outcome outcome = await(file, forks);
                if (outcome == null) {
                    return failed;
                }
                failed += outcome.failed();
                for (final SearchHit hit : outcome.hits()) {
                    if (!found.test(hit)) {
                        return failed;
                    }
                }
                ahead.release();
            }
            return failed;
        }

        /** The file's outcome once a fork gave it; null when the search stopped first or no fork is left to give it. */
        private Outcome await(final CompletableFuture<Outcome> file, final List<Thread> forks) {
            while (!ended.getAsBoolean()) {
                try {
                    return file.get(WAIT_MILLIS, TimeUnit.MILLISECONDS);
                } catch (final TimeoutException _) {
                    if (forks.stream().noneMatch(Thread::isAlive) && !file.isDone()) {
                        return null;
                    }
                } catch (final InterruptedException _) {
                    Thread.currentThread().interrupt();
                    return null;
                } catch (final ExecutionException e) {
                    throw new IllegalStateException("cannot complete a file exceptionally", e);
                }
            }
            return null;
        }

        /**
         * Waits for the forks, which stop within one chunk of a file; one still running at the bound is logged and
         * left.
         */
        private void collect(final List<Thread> forks) {
            final long startNanos = System.nanoTime();
            boolean interrupted = false;
            for (final Thread fork : forks) {
                boolean waited = false;
                while (!waited) {
                    final Duration left = ExecutorStop.WAIT.minusNanos(System.nanoTime() - startNanos);
                    try {
                        if (fork.isAlive() && (left.isNegative() || !fork.join(left))) {
                            LOG.log(System.Logger.Level.WARNING,
                                "left running past the wait [thread=" + fork.getName() + ']');
                        }
                        waited = true;
                    } catch (final InterruptedException _) {
                        // closing: the forks are told to stop already, and the wait goes on within its bound
                        interrupted = true;
                    }
                }
            }
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** One mapped file's hits, found by one pass that counts lines only as far as the next match. */
    private final class FileScan {

        private final MemorySegment bytes;
        private final long size;
        private final Target target;
        private final byte[] needle;
        private final BooleanSupplier stopped;
        private final boolean markdown;
        /** The headings above the line counted to, by level; only {@link #depth} of them hold. */
        private final String[] headings = new String[HEADING_LEVELS];
        private int line = 1;
        private long lineStart;
        private long counted;
        private int depth;
        /** Where the heading line being counted starts; -1 when the line counted is no heading. */
        private long headingStart = -1L;
        /** Whether the lines counted are inside a fenced block, whose {@code #} lines are code, not headings. */
        private boolean fenced;
        /** The mark and the length of the fence that opened the block; it closes only on as many of the same. */
        private byte fenceMark;
        private int fenceLength;

        FileScan(final MemorySegment bytes, final Target target, final byte[] needle, final BooleanSupplier stopped) {
            this.bytes = bytes;
            this.size = bytes.byteSize();
            this.target = target;
            this.needle = needle;
            this.stopped = stopped;
            this.markdown = target.markdown();
            if (markdown) {
                lineBegins(0L);
            }
        }

        List<SearchHit> hits() {
            final List<SearchHit> out = new ArrayList<>();
            if (ByteScan.isBinary(bytes)) {
                return out;
            }
            long from = 0L;
            while (out.size() < fileHits) {
                final long at = next(from);
                if (at < 0L || !countLinesTo(at)) {
                    return out;
                }
                final long end = lineEnd(at);
                out.add(hit(at, end));
                from = end + 1L;
            }
            return out;
        }

        /** The next match at or after {@code from}, a chunk at a time; -1 when there is none or the search stopped. */
        private long next(final long from) {
            for (long start = from; start < size; start += CHUNK_BYTES) {
                if (stopped.getAsBoolean()) {
                    return -1L;
                }
                final long length = Math.min(CHUNK_BYTES + needle.length - 1L, size - start);
                final long at = ByteScan.indexOf(bytes.asSlice(start, length), needle, 0L);
                if (at >= 0L) {
                    return start + at;
                }
            }
            return -1L;
        }

        /** Counts the newlines up to {@code at}; false when the search stopped on the way. */
        private boolean countLinesTo(final long at) {
            for (long p = counted; p < at; p++) {
                if (bytes.get(ValueLayout.JAVA_BYTE, p) == NEWLINE) {
                    line++;
                    lineStart = p + 1L;
                    if (markdown) {
                        lineEnded(p);
                        lineBegins(lineStart);
                    }
                }
                if ((p & CHUNK_BYTES - 1L) == 0L && stopped.getAsBoolean()) {
                    return false;
                }
            }
            counted = at;
            return true;
        }

        /** Notes a fence or a heading opening at {@code start}, a line's first byte. */
        private void lineBegins(final long start) {
            if (start >= size) {
                return;
            }
            final byte first = bytes.get(ValueLayout.JAVA_BYTE, start);
            final int run = first == '`' || first == '~' ? run(start, first) : 0;
            if (run >= FENCE && !fenced) {
                fenced = true;
                fenceMark = first;
                fenceLength = run;
            } else if (run >= FENCE && first == fenceMark && run >= fenceLength) {
                fenced = false;
            } else if (first == HASH && !fenced) {
                headingStart = start;
            }
        }

        /** How many {@code mark} bytes the line at {@code start} opens with. */
        private int run(final long start, final byte mark) {
            int n = 0;
            while (start + n < size && bytes.get(ValueLayout.JAVA_BYTE, start + n) == mark) {
                n++;
            }
            return n;
        }

        /** Takes the heading whose line ends at {@code end}, when the line counted is one. */
        private void lineEnded(final long end) {
            if (headingStart < 0L) {
                return;
            }
            final long start = headingStart;
            headingStart = -1L;
            int level = 0;
            while (start + level < end && level <= HEADING_LEVELS
                    && bytes.get(ValueLayout.JAVA_BYTE, start + level) == HASH) {
                level++;
            }
            final long textStart = start + level;
            if (level > HEADING_LEVELS || textStart < end && bytes.get(ValueLayout.JAVA_BYTE, textStart) != ' ') {
                return; // "#hashtag" or seven marks: a paragraph, not a heading
            }
            final long cut = Math.min(end, textStart + (long) SNIPPET_CHARS * BYTES_PER_CHAR);
            final long textEnd = cut < end ? sequenceEnd(cut, textStart) : end;
            final String text = closing(Mapped.decode(bytes.asSlice(textStart, textEnd - textStart)).strip());
            headings[level - 1] = text;
            Arrays.fill(headings, level, HEADING_LEVELS, null); // a heading ends the sections below its level
            depth = level;
        }

        /** {@code text} without a closing sequence of {@code #}, which Markdown allows after a heading. */
        private static String closing(final String text) {
            int end = text.length();
            while (end > 0 && text.charAt(end - 1) == HASH) {
                end--;
            }
            return end == text.length() || end == 0 || text.charAt(end - 1) == ' ' ? text.substring(0, end).strip()
                    : text;
        }

        /** The headings above the line counted to, outermost first. */
        private String heading() {
            final StringBuilder out = new StringBuilder();
            for (int i = 0; i < depth; i++) {
                if (headings[i] != null && !headings[i].isEmpty()) {
                    out.append(out.isEmpty() ? "" : SearchHit.HEADING_SEPARATOR).append(headings[i]);
                }
            }
            return out.toString();
        }

        /** Where the line holding {@code at} ends: its newline, or the file's end. */
        private long lineEnd(final long at) {
            long end = at;
            while (end < size && bytes.get(ValueLayout.JAVA_BYTE, end) != NEWLINE) {
                end++;
                if ((end & CHUNK_BYTES - 1L) == 0L && stopped.getAsBoolean()) {
                    break; // a file on one line answers a cancel as any other
                }
            }
            return end;
        }

        /** The hit at {@code at}: at most a snippet's worth of bytes on each side of the match is decoded. */
        private SearchHit hit(final long at, final long end) {
            final long matchEnd = Math.min(at + needle.length, end);
            final long textEnd = end > lineStart && bytes.get(ValueLayout.JAVA_BYTE, end - 1L) == '\r' ? end - 1L : end;
            final long from = sequenceStart(Math.max(lineStart, at - (long) CHARS_BEFORE * BYTES_PER_CHAR), at);
            final long to = sequenceEnd(Math.min(textEnd, matchEnd + (long) SNIPPET_CHARS * BYTES_PER_CHAR), matchEnd);
            final String before = before(Mapped.decode(bytes.asSlice(from, at - from)));
            final String match = Mapped.decode(bytes.asSlice(at, matchEnd - at));
            final String after = Mapped.decode(bytes.asSlice(matchEnd, Math.max(to - matchEnd, 0L)));
            final String snippet = oneLine(clipped(before + match + after));
            final int length = Math.min(match.length(), snippet.length() - before.length());
            return new SearchHit(SearchHit.Kind.FILE, target.source(), "", target.group(), line, snippet,
                    before.length(), length, target.modifiedMillis()).placed(target.root(), markdown ? heading() : "");
        }

        /** {@code at}, moved on past the rest of a UTF-8 sequence it cut, and not past {@code limit}. */
        private long sequenceStart(final long at, final long limit) {
            long start = at;
            while (start < limit && isContinuation(bytes.get(ValueLayout.JAVA_BYTE, start))) {
                start++;
            }
            return start;
        }

        /** {@code at}, moved back to the start of a UTF-8 sequence it cut, and not before {@code limit}. */
        private long sequenceEnd(final long at, final long limit) {
            long end = at;
            while (end > limit && end < size && isContinuation(bytes.get(ValueLayout.JAVA_BYTE, end))) {
                end--;
            }
            return end;
        }

        private static boolean isContinuation(final byte b) {
            return (b & 0xC0) == 0x80;
        }

        /** What precedes a match, without the line's indent and at most {@link #CHARS_BEFORE} chars. */
        private static String before(final String text) {
            final String kept = text.stripLeading();
            if (kept.length() <= CHARS_BEFORE) {
                return kept;
            }
            final int start = kept.length() - CHARS_BEFORE;
            return kept.substring(Character.isLowSurrogate(kept.charAt(start)) ? start + 1 : start);
        }

        private static String clipped(final String text) {
            if (text.length() <= SNIPPET_CHARS) {
                return text;
            }
            final boolean splitsAPair = Character.isHighSurrogate(text.charAt(SNIPPET_CHARS - 1));
            return text.substring(0, splitsAPair ? SNIPPET_CHARS - 1 : SNIPPET_CHARS);
        }

        /** {@code text} with a tab or any other control char as a space, which keeps every offset. */
        private static String oneLine(final String text) {
            final StringBuilder out = new StringBuilder(text);
            for (int i = 0; i < out.length(); i++) {
                if (Character.isISOControl(out.charAt(i))) {
                    out.setCharAt(i, ' ');
                }
            }
            return out.toString();
        }
    }
}
