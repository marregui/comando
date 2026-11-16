package dev.marregui.comando.cdb.store;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.SortedMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.marregui.comando.cdb.CdbAppender;
import dev.marregui.comando.cdb.CdbType;

import static dev.marregui.comando.cdb.store.StoreRows.micros;
import static dev.marregui.comando.cdb.store.StoreRows.timestamps;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/// Power cuts on a store that forces its logs on a timer ([StoreOptions] with an interval): after any operation of its
/// commits, forces, checkpoints and log cuts, the disk opens, per table, to a state from the last one a force, a
/// checkpoint or the close made durable to the one in flight, a prefix of the commits answered since, and takes
/// writes; no frame of an earlier generation of a log replays. One seeded workload runs once under a [DiskModel]; every
/// cut point is then checked on the images the model builds of it. The class sets [StoreFiles#recorder], a static: it
/// is safe because cdb's tests run no two classes at once in one JVM (Gradle forks, JUnit's parallel mode off), and
/// every test that sets it resets it.
class StoreTimedPowerCutTest {

    private static final long DAY1 = micros("2026-11-01T00:00:00Z");
    private static final long DAY2 = DAY1 + Retention.DAY_MICROS;
    // the store's threshold for a commit written as a checkpoint, lowered so the workload's big commit crosses it
    private static final int DIRECT_ROWS = 200;
    private static final TableDef E = new TableDef("e", List.of(
            new ColumnDef("l", CdbType.LONG),
            new ColumnDef("v", CdbType.VARCHAR),
            new ColumnDef("s", CdbType.SYMBOL)), -1, PartitionBy.NONE, List.of(), 0L);
    private static final String[] KEYS = {"a", "b", "c", null};
    private static final TableDef M = new TableDef("m", List.of(
            new ColumnDef("k", CdbType.SYMBOL),
            new ColumnDef("l", CdbType.LONG),
            new ColumnDef("v", CdbType.VARCHAR),
            new ColumnDef("ts", CdbType.TIMESTAMP)), 3, PartitionBy.DAY, List.of("ts", "k"), 0L);
    private static final long SECOND = 1_000_000L;
    private static final long SEED = Long.getLong("cdb.powercut.seed", System.nanoTime());
    // the interval of the timed force: long beside a commit here (well under a millisecond), so records pile up
    // unforced between forces, and short, so the workload's waits for a force cost it little; not measured
    private static final StoreOptions TIMED = new StoreOptions(50L);
    // a wait for a force: 200 times the interval; a force that does not come by then is a defect, not a slow disk
    private static final long FORCE_WAIT_NANOS = TimeUnit.SECONDS.toNanos(10L);
    // the full run: reordered, stale and kill images at every third cut point, a second power cut after a recovery
    // from every 24th, its strict images at every point; the default run takes every sixth, every 48th and every sixth,
    // 1,069 s with every point after a recovery (measured with a dozen other suites on the disk), so the suite runs the
    // full one only when asked (CDB_POWERCUT_FULL=true), as for the store's power-cut test
    private static final boolean FULL = Boolean.getBoolean("cdb.powercut.full");
    private static final int STRIDE = FULL ? 3 : 6;
    private static final int RECOVERY_STRIDE = FULL ? 24 : 48;
    // the workload holds no writer across another's request: a wait this long is a leak
    private static final long WAIT_MILLIS = 1_000L;
    // the log header's version field (after its u32 magic) and the version without the salt that closes stale frames
    private static final int LOG_VERSION_OFFSET = Integer.BYTES;
    private static final int UNSALTED_LOG_VERSION = 1;

    @TempDir
    static Path dir;

    private static DiskModel model;
    private static TimedRun run;

    @BeforeAll
    static void runTheWorkload() {
        System.out.println("StoreTimedPowerCutTest seed=" + SEED);
        final Path root = dir.resolve("store");
        model = new DiskModel(root);
        run = new TimedRun(model, root, Map.of());
        final Random random = new Random(SEED);
        StoreFiles.recorder = model;
        try {
            beforeReopen(root, random);
            afterReopen(root, random);
        } finally {
            StoreFiles.recorder = null;
        }
        System.out.println("StoreTimedPowerCutTest operations=" + model.operations() + " answers=" + run.answers());
    }

    /// The durable state after every operation opens, per table, to a state from its last durable one to the one in
    /// flight, and takes a commit that a reopen keeps.
    @Test
    void everyStrictImageReadsADurablePrefix() throws IOException {
        final Set<String> verified = new HashSet<>();
        for (int k = 0; k <= model.operations(); k++) {
            verify(verified, run, "strict", k, model.at(k).strictImage(), SEED, false);
        }
    }

    /// The same on images whose unforced blocks landed out of order, later ones kept where earlier ones were dropped:
    /// a log's prefix property alone keeps the commits after a dropped frame out.
    @Test
    void everyReorderedImageReadsADurablePrefix() throws IOException {
        final Set<String> verified = new HashSet<>();
        for (int k = 0; k <= model.operations(); k += STRIDE) {
            final long seed = seed(k, 0);
            verify(verified, run, "reordered", k, model.at(k).reorderedImage(seed), seed, false);
        }
    }

    /// The same on images whose dropped bytes read the file's earlier generation: a log cut back to its header and
    /// written again shows the frames it held before. Only a log whose frames carry the header's salt tells those
    /// apart from its own; a log without one may refuse to open on them (a stale frame out of order), but no stale
    /// frame may ever replay, so a store that opens reads an allowed state.
    @Test
    void everyStaleImageReplaysNoEarlierGeneration() throws IOException {
        final int[] refused = new int[1];
        final Set<String> verified = new HashSet<>();
        for (int k = 0; k <= model.operations(); k += STRIDE) {
            final long seed = seed(k, 1);
            refused[0] += verify(verified, run, "stale", k, model.at(k).staleImage(seed), seed, true) ? 0 : 1;
        }
        System.out.println("StoreTimedPowerCutTest stale images checked=" + verified.size() + " refusedUnsalted="
                + refused[0]);
    }

    /// A process kill loses no answered commit: the volatile state reads the last answer or the one in flight.
    @Test
    void everyKillImageLosesNoAnsweredCommit() throws IOException {
        final Set<String> verified = new HashSet<>();
        for (int k = 0; k <= model.operations(); k += STRIDE) {
            final SortedMap<String, byte[]> image = model.at(k).killImage();
            if (verified.add(run.bounds(k, true) + ":" + fingerprint(image))) {
                final Path target = dir.resolve("kill-" + k);
                DiskModel.write(target, image);
                try {
                    check(run, "kill", k, target, SEED, run.allowed(k, true));
                } finally {
                    delete(target);
                }
            }
        }
    }

    /// A second power cut, after a store recovered from a reordered image and took commits on the timer, reads a
    /// durable prefix of what that store answered: the recovery cut a torn frame off a log, and the commits written
    /// over it must not bring back the frames that followed it.
    @Test
    void aCutAfterARecoveryReplaysNoFrameItCut() throws IOException {
        int checked = 0;
        for (int k = RECOVERY_STRIDE / 2; k <= model.operations(); k += RECOVERY_STRIDE) {
            final long seed = seed(k, 2);
            final SortedMap<String, byte[]> first = model.at(k).reorderedImage(seed);
            final Path image = dir.resolve("first-" + k);
            DiskModel.write(image, first);
            final DiskModel second = DiskModel.of(image, first);
            final TimedRun again;
            StoreFiles.recorder = second;
            try (final Store store = open(image)) {
                again = new TimedRun(second, image, TimedRun.state(store));
                final Random random = new Random(seed);
                store.create(E, true);
                again.answer(store);
                try (final TableWriter e = store.writer(E.name(), WAIT_MILLIS)) {
                    for (int c = 0; c < 4; c++) {
                        eRow(e, random);
                        eRow(e, random);
                        e.commit();
                        again.answer(store);
                    }
                }
                store.create(M, true);
                again.answer(store);
                try (final TableWriter m = store.writer(M.name(), WAIT_MILLIS)) {
                    mRow(m, random, DAY2 + random.nextLong(Retention.DAY_MICROS));
                    m.commit();
                    again.answer(store);
                }
            } finally {
                StoreFiles.recorder = null;
            }
            again.heldAll();
            final Set<String> verified = new HashSet<>();
            for (int r = 0; r <= second.operations(); r++) {
                final DiskModel cut = second.at(r);
                if (FULL || r % STRIDE == 0) {
                    verify(verified, again, "second-strict-" + k, r, cut.strictImage(), seed, false);
                }
                if (r % STRIDE == 0) {
                    final long staleSeed = mix(seed + r);
                    verify(verified, again, "second-stale-" + k, r, cut.staleImage(staleSeed), staleSeed, true);
                }
            }
            checked += verified.size();
            delete(image);
        }
        assertThat(checked).as("images checked after a recovery").isPositive();
    }

    // the seed of cut point k's image of family i (reordered 0, stale 1, recovery 2), printed on a failure
    private static long seed(final int k, final int i) {
        return mix(SEED * 1_000_003L + k * 3L + i);
    }

    /// SplitMix64's finaliser: neighbouring inputs give unrelated seeds, so each cut's mix of unforced writes differs
    /// (`java.util.Random`'s first draws barely move between seeds that differ by a few).
    private static long mix(final long x) {
        long z = x + 0x9E3779B97F4A7C15L;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    // checks the image of cut point k unless verified holds an equal one checked against the same bounds: the check
    // costs tens of milliseconds, most of it forces, and many cut points leave the image as the last one left it.
    // False when a log without a salt refused a stale image, which tolerateUnsalted allows
    private static boolean verify(final Set<String> verified, final TimedRun answers, final String kind, final int k,
            final SortedMap<String, byte[]> image, final long seed, final boolean tolerateUnsalted)
            throws IOException {
        if (!verified.add(kind + ":" + answers.bounds(k, false) + ":" + fingerprint(image))) {
            return true;
        }
        final Path target = dir.resolve(kind + "-" + k + "-" + verified.size());
        DiskModel.write(target, image);
        try {
            if (tolerateUnsalted && unsalted(image)) {
                try (final Store store = open(target)) {
                    store.tables();
                } catch (final RuntimeException _) {
                    return false;
                }
            }
            check(answers, kind, k, target, seed, answers.allowed(k, false));
            return true;
        } finally {
            delete(target);
        }
    }

    // the image must read an allowed state, then take a commit a reopen keeps
    private static void check(final TimedRun answers, final String kind, final int k, final Path image,
            final long seed, final Map<String, Set<List<String>>> allowed) {
        final Map<String, List<String>> probed;
        try (final Store store = open(image)) {
            final String mismatch = TimedRun.mismatch(TimedRun.state(store), allowed);
            if (mismatch != null) {
                fail(message("image reads no allowed state", answers, kind, k, seed) + ": " + mismatch);
            }
            store.create(M, true);
            final long newest;
            final long before;
            try (final TableReader reader = store.reader(M.name())) {
                newest = timestamps(reader).stream().mapToLong(Long::longValue).max().orElse(DAY1);
                before = reader.rowCount();
            }
            try (final TableWriter writer = store.writer(M.name(), WAIT_MILLIS)) {
                writer.newRow(newest);
                writer.putText(0, "probe");
                writer.putText(2, "probe-" + k);
                writer.endRow();
                writer.commit();
            }
            try (final TableReader reader = store.reader(M.name())) {
                assertThat(reader.rowCount()).as(message("image lost the probe row", answers, kind, k, seed))
                        .isEqualTo(before + 1L);
            }
            probed = TimedRun.state(store);
        } catch (final RuntimeException e) {
            throw new AssertionError(message("cannot open the image or commit to it", answers, kind, k, seed), e);
        }
        try (final Store store = open(image)) {
            assertThat(TimedRun.state(store))
                    .as(message("reopened image lost the probe commit", answers, kind, k, seed))
                    .isEqualTo(probed);
        }
    }

    // whether a table log in image carries the version without a salt in its header
    private static boolean unsalted(final SortedMap<String, byte[]> image) {
        for (final Map.Entry<String, byte[]> entry : image.entrySet()) {
            final byte[] bytes = entry.getValue();
            if (bytes != null && entry.getKey().endsWith("/" + Wal.FILE) && bytes.length >= Wal.HEADER
                    && ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).getInt(LOG_VERSION_OFFSET)
                    == UNSALTED_LOG_VERSION) {
                return true;
            }
        }
        return false;
    }

    // the image's names, kinds and bytes
    private static String fingerprint(final SortedMap<String, byte[]> image) {
        final MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (final NoSuchAlgorithmException e) {
            throw new IllegalStateException("cannot hash an image [algorithm=SHA-256]", e);
        }
        for (final Map.Entry<String, byte[]> entry : image.entrySet()) {
            final byte[] bytes = entry.getValue();
            digest.update((entry.getKey() + (bytes == null ? "/" : ":" + bytes.length) + '\n')
                    .getBytes(StandardCharsets.UTF_8));
            if (bytes != null) {
                digest.update(bytes);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static String message(final String what, final TimedRun answers, final String kind, final int k,
            final long seed) {
        return what + " [kind=" + kind + ", k=" + k + ", op=" + answers.describe(k) + ", seed=" + SEED
                + ", imageSeed=" + seed + ']';
    }

    private static Store open(final Path root) {
        final Store store = Store.open(root, TIMED);
        store.checkpointRecords = 3;
        store.directRows = DIRECT_ROWS;
        return store;
    }

    // waits for the force covering writer's last commit, a durable request's wait
    private static void awaitForce(final TableWriter writer, final String table) {
        try {
            assertThat(writer.ticket().await(FORCE_WAIT_NANOS)).as("force of table " + table + " came").isTrue();
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted waiting for a force [table=" + table + ']', e);
        }
        run.held(table);
    }

    // commits to both tables between forces, durable waits, a held reader, an out-of-order rewrite, checkpoints that
    // cut the logs back and the commits written over them, a column added by the line protocol, a truncate and a
    // commit of DIRECT_ROWS rows or more
    private static void beforeReopen(final Path root, final Random random) {
        try (final Store store = open(root)) {
            store.create(M, false);
            run.answer(store);
            store.create(E, false);
            run.answer(store);
            final TableReader held;
            try (final TableWriter m = store.writer(M.name(), WAIT_MILLIS);
                    final TableWriter e = store.writer(E.name(), WAIT_MILLIS)) {
                for (int c = 0; c < 6; c++) {
                    for (int i = 0; i < 3; i++) {
                        mRow(m, random, DAY1 + c * Retention.HOUR_MICROS + i * SECOND);
                    }
                    m.commit();
                    run.answer(store);
                    eRow(e, random);
                    eRow(e, random);
                    e.commit();
                    run.answer(store);
                    if (c % 3 == 2) {
                        awaitForce(e, E.name());
                    }
                }
                held = store.reader(M.name());
                awaitForce(m, M.name());
            }
            try (held) {
                store.checkpoint(E.name());
                run.held(E.name());
                try (final TableWriter m = store.writer(M.name(), WAIT_MILLIS);
                        final TableWriter e = store.writer(E.name(), WAIT_MILLIS)) {
                    for (int c = 0; c < 5; c++) {
                        eRow(e, random);
                        eRow(e, random);
                        e.commit();
                        run.answer(store);
                        mRow(m, random, DAY1 + random.nextLong(Retention.DAY_MICROS));
                        mRow(m, random, DAY2 + random.nextLong(Retention.DAY_MICROS));
                        m.commit();
                        run.answer(store);
                    }
                    awaitForce(e, E.name());
                }
            }
            try (final CdbAppender ilp = store.appender()) {
                for (int i = 0; i < 3; i++) {
                    ilp.table(M.name()).symbol("k", KEYS[random.nextInt(KEYS.length - 1)])
                            .symbol("site", "s" + i)
                            .longColumn("l", random.nextLong())
                            .stringColumn("v", "ilp-" + i)
                            .at(DAY2 + Retention.HOUR_MICROS + i * SECOND);
                }
                ilp.flush();
                run.answer(store);
            }
            try (final TableWriter e = store.writer(E.name(), WAIT_MILLIS)) {
                e.truncate();
                eRow(e, random);
                e.commit();
                run.answer(store);
                for (int c = 0; c < 4; c++) {
                    eRow(e, random);
                    e.commit();
                    run.answer(store);
                }
            }
            try (final TableWriter m = store.writer(M.name(), WAIT_MILLIS)) {
                for (int i = 0; i < DIRECT_ROWS + 20; i++) {
                    mRow(m, random, DAY2 + 2 * Retention.HOUR_MICROS + i * SECOND);
                }
                m.commit();
                run.answer(store);
                mRow(m, random, DAY2 + random.nextLong(Retention.DAY_MICROS));
                m.commit();
                run.answer(store);
            }
        }
        run.heldAll();
    }

    private static void afterReopen(final Path root, final Random random) {
        try (final Store store = open(root)) {
            try (final TableWriter m = store.writer(M.name(), WAIT_MILLIS);
                    final TableWriter e = store.writer(E.name(), WAIT_MILLIS)) {
                for (int c = 0; c < 3; c++) {
                    mRow(m, random, DAY2 + 3 * Retention.HOUR_MICROS + c * SECOND);
                    m.commit();
                    run.answer(store);
                    eRow(e, random);
                    e.commit();
                    run.answer(store);
                }
            }
        }
        run.heldAll();
    }

    // one row of m at ts, each nullable column NULL one time in 8
    private static void mRow(final TableWriter m, final Random random, final long ts) {
        m.newRow(ts);
        m.putText(0, KEYS[random.nextInt(KEYS.length)]);
        if (random.nextInt(8) != 0) {
            m.putLong(1, random.nextLong());
        }
        m.putText(2, random.nextInt(8) == 0 ? null : "v" + random.nextInt(1_000));
        m.endRow();
    }

    // one row of e, each column NULL one time in 8
    private static void eRow(final TableWriter e, final Random random) {
        e.newRow(Long.MIN_VALUE);
        if (random.nextInt(8) != 0) {
            e.putLong(0, random.nextLong());
        }
        e.putText(1, random.nextInt(8) == 0 ? null : "e" + random.nextInt(1_000));
        e.putText(2, KEYS[random.nextInt(KEYS.length)]);
        e.endRow();
    }

    private static void delete(final Path path) throws IOException {
        final List<Path> paths;
        try (final Stream<Path> walk = Files.walk(path)) {
            paths = walk.sorted(Comparator.reverseOrder()).toList();
        }
        for (final Path each : paths) {
            Files.delete(each);
        }
    }
}
