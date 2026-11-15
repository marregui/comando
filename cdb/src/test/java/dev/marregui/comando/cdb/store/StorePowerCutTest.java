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
import java.util.function.IntFunction;
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
import static org.assertj.core.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/// A power cut after any operation the store made leaves a disk that opens to a state some commit answered, and that
/// takes writes. One seeded workload runs once under a [DiskModel]; every cut point is then checked on the images the
/// model builds of it. The class sets [StoreFiles#recorder], a static: it is safe because cdb's tests run no two
/// classes at once in one JVM (Gradle forks, JUnit's parallel mode off), and every test that sets it resets it.
class StorePowerCutTest {

    // answered.get(j): the store's state once answer j returned, 0 the empty store; answeredAt.get(j): the number of
    // operations recorded by then
    private static final List<Map<String, List<String>>> ANSWERED = new ArrayList<>();
    private static final List<Integer> ANSWERED_AT = new ArrayList<>();
    private static final long DAY1 = micros("2026-11-01T00:00:00Z");
    private static final long DAY2 = DAY1 + Retention.DAY_MICROS;
    private static final long DAY3 = DAY2 + Retention.DAY_MICROS;
    private static final long DAY4 = DAY3 + Retention.DAY_MICROS;
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
            new ColumnDef("d", CdbType.DOUBLE),
            new ColumnDef("b", CdbType.BOOLEAN),
            new ColumnDef("v", CdbType.VARCHAR),
            new ColumnDef("at", CdbType.TIMESTAMP),
            new ColumnDef("ts", CdbType.TIMESTAMP)), 6, PartitionBy.DAY, List.of("ts", "k"), 0L);
    private static final long SECOND = 1_000_000L;
    private static final long SEED = Long.getLong("cdb.powercut.seed", System.nanoTime());
    // the full run: adversarial images and recovery cuts at every third point, three mixes per cut, and the check of
    // the strict check itself; the default run takes every sixth point and one mix, 39 s (measured alone) instead of
    // 140, so the suite runs the full one only when asked (CDB_POWERCUT_FULL=true)
    private static final boolean FULL = Boolean.getBoolean("cdb.powercut.full");
    private static final int STRIDE = FULL ? 3 : 6;
    // the workload holds no writer across another's request: a wait this long is a leak
    private static final long WAIT_MILLIS = 1_000L;

    @TempDir
    static Path dir;

    private static DiskModel model;

    @BeforeAll
    static void runTheWorkload() throws IOException {
        System.out.println("StorePowerCutTest seed=" + SEED);
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
        System.out.println("StorePowerCutTest operations=" + model.operations() + " answers=" + ANSWERED.size());
    }

    /// The durable state after every operation opens to the last answered commit or the one in flight, and takes a
    /// commit that a reopen keeps. Fails on a store that leaves a folder entry it relies on unforced.
    @Test
    void everyStrictImageReadsAnAnsweredCommit() throws IOException {
        strictCuts("strict", model::at);
    }

    /// The check of [#everyStrictImageReadsAnAnsweredCommit] on a disk that makes every folder entry durable at once:
    /// a store that forces each file it relies on and switches each commit with one rename passes it, so a strict
    /// failure this test does not share is a folder entry left unforced. In the full run only.
    @Test
    void theStrictCheckHoldsWhenFolderEntriesAreDurableAtOnce() throws IOException {
        assumeTrue(FULL, "full run only: CDB_POWERCUT_FULL=true");
        strictCuts("entries-durable", k -> model.at(k, _ -> true));
    }

    /// The same check on adversarial images: at every sixth operation, one seeded mix of what was not forced; at every
    /// third, three mixes, in the full run.
    @Test
    void everyAdversarialImageReadsAnAnsweredCommit() throws IOException {
        final int n = model.operations();
        final Set<String> verified = new HashSet<>();
        for (int k = 0; k <= n; k += STRIDE) {
            final DiskModel cut = model.at(k);
            for (int i = 0; i < (FULL ? 3 : 1); i++) {
                final long seed = mix(SEED * 1_000_003L + k * 3L + i);
                verify(verified, "adversarial", k, cut.adversarialImage(seed), seed);
            }
        }
    }

    /// A recovery cut at any of its own operations loses nothing it found. A kill in the middle of a commit leaves
    /// bytes past the committed ends, which the open cuts; that open runs under a model of its own, and every one of
    /// its cut points reads the state the finished recovery read.
    @Test
    void aRecoveryCutAtAnyOperationReadsWhatTheRecoveryRead() throws IOException {
        int recoveryOperations = 0;
        for (int j = 1; j < ANSWERED.size(); j += STRIDE) {
            final int[] cuts = {ANSWERED_AT.get(j) - 1, (ANSWERED_AT.get(j - 1) + ANSWERED_AT.get(j)) / 2};
            for (final int k : cuts) {
                final Path image = dir.resolve("kill-" + k);
                final DiskModel cut = model.at(k);
                DiskModel.write(image, cut.killImage());
                final DiskModel recovery = cut.afterKill(image);
                final Map<String, List<String>> recovered;
                StoreFiles.recorder = recovery;
                try (final Store store = open(image)) {
                    recovered = state(store);
                } finally {
                    StoreFiles.recorder = null;
                }
                assertThat(allowed(k)).as(message("kill image reads no answered state", "kill", k, SEED))
                        .contains(recovered);
                for (int r = 0; r <= recovery.operations(); r++) {
                    final Path again = dir.resolve("recovery-" + k + "-" + r);
                    DiskModel.write(again, recovery.at(r).strictImage());
                    try (final Store store = open(again)) {
                        assertThat(state(store)).as(message("recovery cut reads another state", "recovery", k, SEED)
                                + " [r=" + r + ", op=" + recovery.describe(r - 1) + ']').isEqualTo(recovered);
                    }
                    delete(again);
                }
                recoveryOperations += recovery.operations();
                delete(image);
            }
        }
        assertThat(recoveryOperations).as("recoveries that wrote anything").isPositive();
    }

    /// SplitMix64's finaliser: neighbouring inputs give unrelated seeds, so each cut's mix of unforced writes differs
    /// (`java.util.Random`'s first draws barely move between seeds that differ by a few).
    private static long mix(final long x) {
        long z = x + 0x9E3779B97F4A7C15L;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    private static void strictCuts(final String kind, final IntFunction<DiskModel> cutAt)
            throws IOException {
        final int n = model.operations();
        final Set<String> verified = new HashSet<>();
        for (int k = 0; k <= n; k++) {
            verify(verified, kind, k, cutAt.apply(k).strictImage(), SEED);
        }
    }

    // checks the image of cut point k unless verified holds an equal one checked against the same answers: the check
    // costs tens of milliseconds, most of it forces, and many cut points leave the image as the last one left it
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
            final Map<String, List<String>> actual = state(store);
            final List<Map<String, List<String>>> allowed = allowed(k);
            if (!allowed.contains(actual)) {
                fail(message("image reads no answered state", kind, k, seed) + ": " + difference(actual, allowed));
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
                writer.putText(4, "probe-" + k);
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

    // the first difference from each allowed state, for a failure message
    private static String difference(final Map<String, List<String>> actual,
            final List<Map<String, List<String>>> allowed) {
        final List<String> differences = new ArrayList<>();
        for (final Map<String, List<String>> expected : allowed) {
            differences.add(firstDifference(actual, expected));
        }
        return String.join("; or ", differences);
    }

    private static String firstDifference(final Map<String, List<String>> actual,
            final Map<String, List<String>> expected) {
        for (final Map.Entry<String, List<String>> table : expected.entrySet()) {
            final List<String> found = actual.get(table.getKey());
            if (found == null) {
                return "table " + table.getKey() + " missing";
            }
            final List<String> wanted = table.getValue();
            if (!found.getFirst().equals(wanted.getFirst())) {
                return "table " + table.getKey() + " reads [" + found.getFirst() + "], expected [" + wanted.getFirst()
                        + ']';
            }
            if (found.size() != wanted.size()) {
                return "table " + table.getKey() + " has " + (found.size() - 1) + " rows, expected "
                        + (wanted.size() - 1);
            }
            for (int i = 1; i < wanted.size(); i++) {
                if (!found.get(i).equals(wanted.get(i))) {
                    return "table " + table.getKey() + " row " + i + " reads [" + found.get(i) + "], expected ["
                            + wanted.get(i) + ']';
                }
            }
        }
        for (final String table : actual.keySet()) {
            if (!expected.containsKey(table)) {
                return "table " + table + " not expected";
            }
        }
        return "no difference";
    }

    // each table: its definition, then its rows sorted
    private static Map<String, List<String>> state(final Store store) {
        final Map<String, List<String>> state = new TreeMap<>();
        for (final String table : store.tables()) {
            try (final TableReader reader = store.reader(table)) {
                final TableDef def = reader.def();
                final StringBuilder header = new StringBuilder();
                for (final ColumnDef column : def.columns()) {
                    header.append(column.name()).append(':').append(column.type()).append(' ');
                }
                header.append("ts=").append(def.timestampIndex()).append(" ttl=").append(def.ttlMicros());
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
        store.checkpointRecords = 3;
        store.directRows = DIRECT_ROWS;
        return store;
    }

    private static void answer(final Store store) {
        ANSWERED_AT.add(model.operations());
        ANSWERED.add(state(store));
    }

    // creates, in-order and out-of-order commits, a new partition, a reader held across commits, a column added by
    // the line protocol, a TTL that expires partitions, a truncate and a commit of DIRECT_ROWS rows or more
    private static void beforeReopen(final Path root, final Random random) {
        try (final Store store = open(root)) {
            store.create(M, false);
            answer(store);
            store.create(E, false);
            answer(store);
            final TableReader held;
            try (final TableWriter m = store.writer(M.name(), WAIT_MILLIS)) {
                for (int c = 0; c < 4; c++) {
                    for (int i = 0; i < 4; i++) {
                        mRow(m, random, DAY1 + c * Retention.HOUR_MICROS + i * SECOND);
                    }
                    m.commit();
                    answer(store);
                }
                held = store.reader(M.name());
            }
            try (held) {
                try (final TableWriter m = store.writer(M.name(), WAIT_MILLIS)) {
                    for (int i = 0; i < 4; i++) {
                        mRow(m, random, DAY2 + i * SECOND);
                    }
                    m.commit();
                    answer(store);
                    for (int i = 0; i < 4; i++) {
                        mRow(m, random, DAY1 + random.nextLong(Retention.DAY_MICROS));
                    }
                    m.commit();
                    answer(store);
                    try (final TableWriter e = store.writer(E.name(), WAIT_MILLIS)) {
                        for (int c = 0; c < 2; c++) {
                            for (int i = 0; i < 3; i++) {
                                eRow(e, random);
                            }
                            e.commit();
                            answer(store);
                        }
                    }
                    for (int c = 0; c < 2; c++) {
                        mRow(m, random, DAY2 + (c + 1) * Retention.HOUR_MICROS);
                        mRow(m, random, DAY1 + random.nextLong(Retention.DAY_MICROS));
                        mRow(m, random, DAY2 + random.nextLong(Retention.DAY_MICROS));
                        m.commit();
                        answer(store);
                    }
                }
                try (final CdbAppender ilp = store.appender()) {
                    for (int f = 0; f < 2; f++) {
                        for (int i = 0; i < 3; i++) {
                            ilp.table(M.name()).symbol("k", KEYS[random.nextInt(KEYS.length - 1)])
                                    .symbol("site", "s" + f)
                                    .longColumn("l", random.nextLong())
                                    .doubleColumn("d", random.nextDouble())
                                    .booleanColumn("b", random.nextBoolean())
                                    .stringColumn("v", "ilp-" + f + "-" + i)
                                    .timestampColumn("at", DAY3 + i)
                                    .at(DAY3 + f * Retention.HOUR_MICROS + i * SECOND);
                        }
                        ilp.flush();
                        answer(store);
                    }
                }
                try (final TableWriter m = store.writer(M.name(), WAIT_MILLIS)) {
                    m.setTtl(Retention.DAY_MICROS);
                    for (int i = 0; i < 4; i++) {
                        mRow(m, random, DAY4 + Retention.HOUR_MICROS + i * SECOND);
                    }
                    m.commit();
                    answer(store);
                }
            }
            try (final TableWriter e = store.writer(E.name(), WAIT_MILLIS)) {
                e.truncate();
                eRow(e, random);
                eRow(e, random);
                e.commit();
                answer(store);
            }
            try (final TableWriter m = store.writer(M.name(), WAIT_MILLIS)) {
                for (int i = 0; i < DIRECT_ROWS + 50; i++) {
                    mRow(m, random, DAY4 + 2 * Retention.HOUR_MICROS + i * SECOND);
                }
                m.commit();
                answer(store);
                mRow(m, random, DAY4 + 3 * Retention.HOUR_MICROS);
                mRow(m, random, DAY4 + random.nextLong(Retention.DAY_MICROS));
                m.commit();
                answer(store);
            }
        }
    }

    private static void afterReopen(final Path root, final Random random) {
        try (final Store store = open(root)) {
            try (final TableWriter m = store.writer(M.name(), WAIT_MILLIS)) {
                mRow(m, random, DAY4 + 4 * Retention.HOUR_MICROS);
                mRow(m, random, DAY4 + random.nextLong(Retention.DAY_MICROS));
                m.commit();
                answer(store);
            }
            try (final TableWriter e = store.writer(E.name(), WAIT_MILLIS)) {
                eRow(e, random);
                e.commit();
                answer(store);
            }
            try (final TableWriter m = store.writer(M.name(), WAIT_MILLIS)) {
                mRow(m, random, DAY4 + 5 * Retention.HOUR_MICROS);
                m.commit();
                answer(store);
            }
        }
    }

    // one row of m at ts, each nullable column NULL one time in 8
    private static void mRow(final TableWriter m, final Random random, final long ts) {
        m.newRow(ts);
        m.putText(0, KEYS[random.nextInt(KEYS.length)]);
        if (random.nextInt(8) != 0) {
            m.putLong(1, random.nextLong());
        }
        if (random.nextInt(8) != 0) {
            m.putDouble(2, random.nextDouble());
        }
        if (random.nextInt(8) != 0) {
            m.putBoolean(3, random.nextBoolean());
        }
        m.putText(4, random.nextInt(8) == 0 ? null : "v" + random.nextInt(1_000));
        if (random.nextInt(8) != 0) {
            m.putLong(5, DAY1 + random.nextLong(4L * Retention.DAY_MICROS));
        }
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
