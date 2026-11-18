package dev.marregui.comando.cdb.store;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.marregui.comando.cdb.CdbAppender;
import dev.marregui.comando.cdb.CdbType;

import static dev.marregui.comando.cdb.store.StoreRows.micros;
import static dev.marregui.comando.cdb.store.StoreRows.rows;
import static dev.marregui.comando.cdb.store.StoreRows.timestamps;
import static org.assertj.core.api.Assertions.assertThat;

/// A power cut after any operation of a partition drop or a table drop leaves a disk that opens to a state some
/// commit answered: a table whole or gone, never half-dropped, and a dropped row never back. One seeded workload runs
/// once under a [DiskModel], as in [StorePowerCutTest]; every cut point is then checked on the images the model builds
/// of it. The class sets [StoreFiles#recorder], a static: cdb's tests run no two classes at once in one JVM, and every
/// test that sets it resets it.
class StoreDropPowerCutTest {

    // ANSWERED.get(j): the store's state once answer j returned, 0 the empty store; ANSWERED_AT.get(j): the number of
    // operations recorded by then
    private static final List<Map<String, List<String>>> ANSWERED = new ArrayList<>();
    private static final List<Integer> ANSWERED_AT = new ArrayList<>();
    private static final long DAY1 = micros("2026-11-01T00:00:00Z");
    private static final long DAY2 = DAY1 + Retention.DAY_MICROS;
    private static final long DAY3 = DAY2 + Retention.DAY_MICROS;
    private static final long DAY4 = DAY3 + Retention.DAY_MICROS;
    private static final long SECOND = 1_000_000L;
    private static final TableDef M = new TableDef("m", List.of(
            new ColumnDef("k", CdbType.SYMBOL),
            new ColumnDef("l", CdbType.LONG),
            new ColumnDef("v", CdbType.VARCHAR),
            new ColumnDef("ts", CdbType.TIMESTAMP)), 3, PartitionBy.DAY, List.of(), 0L);
    private static final TableDef G = new TableDef("g", List.of(
            new ColumnDef("l", CdbType.LONG),
            new ColumnDef("ts", CdbType.TIMESTAMP)), 1, PartitionBy.DAY, List.of(), 0L);
    private static final long SEED = Long.getLong("cdb.powercut.seed", System.nanoTime());
    // adversarial images at every third point: the workload is a third of the size of the store's power-cut workload
    private static final int STRIDE = 3;
    // the workload holds no writer across another's request: a wait this long is a leak
    private static final long WAIT_MILLIS = 1_000L;

    @TempDir
    static Path dir;

    private static DiskModel model;

    @BeforeAll
    static void runTheWorkload() throws IOException {
        System.out.println("StoreDropPowerCutTest seed=" + SEED);
        final Path root = dir.resolve("store");
        Files.createDirectories(root);
        model = new DiskModel(root);
        ANSWERED.add(Map.of());
        ANSWERED_AT.add(0);
        final Random random = new Random(SEED);
        StoreFiles.recorder = model;
        try {
            beforeReopen(root, random);
            afterReopen(root, random);
        } finally {
            StoreFiles.recorder = null;
        }
        System.out.println("StoreDropPowerCutTest operations=" + model.operations() + " answers=" + ANSWERED.size());
    }

    /// The durable state after every operation opens to the last answered commit or the one in flight, and takes a
    /// commit that a reopen keeps.
    @Test
    void everyStrictImageReadsAnAnsweredCommit() throws IOException {
        final Set<String> verified = new HashSet<>();
        for (int k = 0; k <= model.operations(); k++) {
            verify(verified, "strict", k, model.at(k).strictImage(), SEED);
        }
    }

    /// The same check on adversarial images: at every third operation, one seeded mix of what was not forced.
    @Test
    void everyAdversarialImageReadsAnAnsweredCommit() throws IOException {
        final Set<String> verified = new HashSet<>();
        for (int k = 0; k <= model.operations(); k += STRIDE) {
            final long seed = mix(SEED * 1_000_003L + k);
            verify(verified, "adversarial", k, model.at(k).adversarialImage(seed), seed);
        }
    }

    /// The workload did what it claims: one answer reads g without m, one reads neither, and the last reads both again.
    @Test
    void theWorkloadDropsPartitionsAndTables() {
        assertThat(ANSWERED).anySatisfy(state -> assertThat(state).containsKey("g").doesNotContainKey("m"));
        assertThat(ANSWERED).anySatisfy(state -> assertThat(state).doesNotContainKeys("m", "g"));
        assertThat(ANSWERED.getLast()).containsKeys("m", "g");
    }

    /// SplitMix64's finaliser: neighbouring inputs give unrelated seeds.
    private static long mix(final long x) {
        long z = x + 0x9E3779B97F4A7C15L;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    // checks the image of cut point k unless verified holds an equal one checked against the same answers
    private static void verify(final Set<String> verified, final String kind, final int k,
            final SortedMap<String, byte[]> image, final long seed) throws IOException {
        if (!verified.add(answered(k) + ":" + fingerprint(image))) {
            return;
        }
        final Path target = dir.resolve(kind + "-" + k + "-" + verified.size());
        DiskModel.write(target, image);
        try {
            check(kind, k, target, seed);
        } finally {
            delete(target);
        }
    }

    // the image must read answer j or j + 1, then take a commit a reopen keeps
    private static void check(final String kind, final int k, final Path image, final long seed) {
        final Map<String, List<String>> probed;
        try (final Store store = open(image)) {
            assertThat(allowed(k)).as(message("image reads no answered state", kind, k, seed)).contains(state(store));
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
                assertThat(reader.rowCount()).as(message("image lost the probe row", kind, k, seed))
                        .isEqualTo(before + 1L);
            }
            probed = state(store);
        } catch (final RuntimeException e) {
            throw new AssertionError(message("cannot open the image or commit to it", kind, k, seed), e);
        }
        try (final Store store = open(image)) {
            assertThat(state(store)).as(message("reopened image lost the probe commit", kind, k, seed))
                    .isEqualTo(probed);
        }
    }

    // answer j, the last one given by operation k, and answer j + 1, the one in flight
    private static List<Map<String, List<String>>> allowed(final int k) {
        final int j = answered(k);
        return j + 1 < ANSWERED.size() ? List.of(ANSWERED.get(j), ANSWERED.get(j + 1)) : List.of(ANSWERED.get(j));
    }

    // the last answer given by operation k
    private static int answered(final int k) {
        int j = 0;
        while (j + 1 < ANSWERED_AT.size() && ANSWERED_AT.get(j + 1) <= k) {
            j++;
        }
        return j;
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

    private static String message(final String what, final String kind, final int k, final long seed) {
        return what + " [kind=" + kind + ", k=" + k + ", op=" + model.describe(k - 1) + ", seed=" + SEED
                + ", imageSeed=" + seed + ']';
    }

    // each table: its definition, then its rows sorted
    private static Map<String, List<String>> state(final Store store) {
        final Map<String, List<String>> state = new TreeMap<>();
        for (final String table : store.tables()) {
            try (final TableReader reader = store.reader(table)) {
                final StringBuilder header = new StringBuilder();
                for (final ColumnDef column : reader.def().columns()) {
                    header.append(column.name()).append(':').append(column.type()).append(' ');
                }
                header.append("ts=").append(reader.def().timestampIndex());
                final List<String> sorted = new ArrayList<>(rows(reader));
                sorted.sort(null);
                final List<String> lines = new ArrayList<>(sorted.size() + 1);
                lines.add(header.toString());
                lines.addAll(sorted);
                state.put(table, lines);
            }
        }
        return state;
    }

    private static Store open(final Path root) {
        final Store store = Store.open(root);
        // a checkpoint every third commit, so the workload's logged commits are cut by the drops and by the applier
        store.checkpointRecords = 3;
        return store;
    }

    private static void answer(final Store store) {
        ANSWERED_AT.add(model.operations());
        ANSWERED.add(state(store));
    }

    // logged commits over three days; a partition drop, with pending rows in and out of the range, under a held
    // reader; a table dropped under a held reader, created again beside its folder and written by the line protocol;
    // a table dropped without readers
    private static void beforeReopen(final Path root, final Random random) {
        try (final Store store = open(root)) {
            store.create(M, false);
            answer(store);
            try (final TableWriter m = store.writer(M.name(), WAIT_MILLIS)) {
                for (final long day : new long[] {DAY1, DAY2, DAY3}) {
                    for (int i = 0; i < 3; i++) {
                        mRow(m, random, day + random.nextLong(Retention.DAY_MICROS));
                    }
                    m.commit();
                    answer(store);
                }
            }
            try (final TableReader held = store.reader(M.name())) {
                try (final TableWriter m = store.writer(M.name(), WAIT_MILLIS)) {
                    mRow(m, random, DAY2 + random.nextLong(Retention.DAY_MICROS));
                    mRow(m, random, DAY4 + random.nextLong(Retention.DAY_MICROS));
                    m.dropPartitions(DAY2, DAY3);
                    mRow(m, random, DAY3 + random.nextLong(Retention.DAY_MICROS));
                    m.commit();
                    answer(store);
                    mRow(m, random, DAY4 + random.nextLong(Retention.DAY_MICROS));
                    m.commit();
                    answer(store);
                }
                assertThat(held.rowCount()).isEqualTo(9L);
            }
            store.create(G, false);
            answer(store);
            try (final TableWriter g = store.writer(G.name(), WAIT_MILLIS)) {
                for (int c = 0; c < 2; c++) {
                    g.newRow(DAY1 + c * SECOND);
                    g.putLong(0, random.nextLong());
                    g.endRow();
                    g.commit();
                    answer(store);
                }
            }
            try (final TableReader held = store.reader(G.name())) {
                assertThat(store.dropTable(G.name(), WAIT_MILLIS)).isTrue();
                answer(store);
                // created first: a line-protocol write to a missing table publishes it empty before its rows
                store.create(G, false);
                answer(store);
                try (final CdbAppender ilp = store.appender()) {
                    ilp.table(G.name()).longColumn("l", random.nextLong()).at(DAY2);
                    ilp.flush();
                }
                answer(store);
                assertThat(held.rowCount()).isEqualTo(2L);
            }
            assertThat(store.dropTable(M.name(), WAIT_MILLIS)).isTrue();
            answer(store);
        }
    }

    // the dropped name created again after a reopen, and written
    private static void afterReopen(final Path root, final Random random) {
        try (final Store store = open(root)) {
            store.create(M, false);
            answer(store);
            try (final TableWriter m = store.writer(M.name(), WAIT_MILLIS)) {
                mRow(m, random, DAY2 + random.nextLong(Retention.DAY_MICROS));
                m.commit();
                answer(store);
            }
        }
    }

    // one row of m at ts, the LONG column NULL one time in 8
    private static void mRow(final TableWriter m, final Random random, final long ts) {
        m.newRow(ts);
        m.putText(0, "k" + random.nextInt(3));
        if (random.nextInt(8) != 0) {
            m.putLong(1, random.nextLong());
        }
        m.putText(2, "v" + random.nextInt(1_000));
        m.endRow();
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
