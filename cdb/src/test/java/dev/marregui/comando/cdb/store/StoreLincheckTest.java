package dev.marregui.comando.cdb.store;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

import org.jetbrains.lincheck.datastructures.ModelCheckingOptions;
import org.jetbrains.lincheck.datastructures.Operation;
import org.jetbrains.lincheck.datastructures.StressOptions;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.marregui.comando.cdb.Cdb;
import dev.marregui.comando.cdb.CdbAppender;
import dev.marregui.comando.cdb.CdbException;

/// Lincheck checks that a table's appends, `DELETE`, `TRUNCATE`, `DROP PARTITION`, `DROP TABLE`, `DROP COLUMN`,
/// `RENAME COLUMN` and `count()` are linearisable: every concurrent history of them answers as some serial order of the
/// same calls answers on `Model`.
/// A `Cdb` runs in stress mode; the model checker cannot drive one (its memory snapshot walks the whole store and
/// fails, and a query's frames run on the query pool's threads, which it does not schedule), so it checks
/// `Published`, the acquire, publish and release protocol of `TableState` over versions in memory, instead.
class StoreLincheckTest {

    /// Lincheck builds a subject per scenario run; they share one database.
    private static Cdb cdb;
    @TempDir
    private static Path root;

    /// Opens the shared database.
    @BeforeAll
    static void open() {
        cdb = Cdb.open(root);
    }

    /// Closes it.
    @AfterAll
    static void close() {
        if (cdb != null) {
            cdb.close();
        }
    }

    /// Stress mode over a `Cdb`: concurrent runs of three threads of three calls each find no history that no serial
    /// order of the same calls explains.
    @Test
    void stressFindsNoNonLinearisableHistory() {
        // 5 scenarios of 30 runs each, about 150 tables made and dropped, keep the class near a minute: 20 of 50 took
        // 221 s on a laptop
        new StressOptions()
                .iterations(5)
                .invocationsPerIteration(30)
                .threads(3)
                .actorsPerThread(3)
                .actorsBefore(2)
                .actorsAfter(1)
                .sequentialSpecification(Model.class)
                .check(Subject.class);
    }

    /// Model checking of the snapshot publish: every interleaving the checker explores of three threads of three calls
    /// each answers as some serial order does, and no read finds a version freed under it.
    @Test
    void modelCheckingFindsNoNonLinearisableHistoryOfThePublish() {
        // 10 scenarios of up to 1,000 interleavings each, the rest of the minute: 50 scenarios took 138 s on a laptop
        new ModelCheckingOptions()
                .iterations(10)
                .invocationsPerIteration(1_000)
                .threads(3)
                .actorsPerThread(3)
                .actorsBefore(2)
                .actorsAfter(1)
                .sequentialSpecification(Model.class)
                .check(Published.class);
    }

    /// The database's side: one table per instance, created by the constructor, the previous instance's table
    /// dropped first so the store stays far under its table limit.
    public static final class Subject {

        private static final AtomicInteger TABLES = new AtomicInteger();
        private static final long DAY0_MICROS = StoreRows.micros("2026-11-01T00:00:00Z");
        private static final long DAY1_MICROS = StoreRows.micros("2026-11-02T00:00:00Z");

        private final String table;
        private final AtomicLong offset = new AtomicLong();

        /// A fresh table `l<n>`, its column `w` never written: the column changes move it, the rows stay.
        public Subject() {
            final int n = TABLES.incrementAndGet();
            cdb.run("DROP TABLE IF EXISTS l" + (n - 1));
            table = "l" + n;
            cdb.run("CREATE TABLE " + table + " (v LONG, w LONG, ts TIMESTAMP) TIMESTAMP(ts) PARTITION BY DAY");
        }

        /// Appends a 1 on day 0.
        @Operation
        public void appendOneOnDay0() {
            append(1L, DAY0_MICROS);
        }

        /// Appends a 2 on day 1.
        @Operation
        public void appendTwoOnDay1() {
            append(2L, DAY1_MICROS);
        }

        /// Deletes the ones; the rows removed, -1 when the table is absent.
        @Operation
        public long deleteOnes() {
            return change("DELETE FROM " + table + " WHERE v = 1");
        }

        /// Truncates; the rows removed, -1 when the table is absent.
        @Operation
        public long truncate() {
            return change("TRUNCATE TABLE " + table);
        }

        /// Drops day 0's partition; the partitions dropped, -1 when the table is absent.
        @Operation
        public long dropDay0() {
            return change("ALTER TABLE " + table + " DROP PARTITION LIST '2026-11-01'");
        }

        /// Drops the table; the next append recreates it through line protocol.
        @Operation
        public long dropTable() {
            return change("DROP TABLE IF EXISTS " + table);
        }

        /// Drops column `w` if it is there; 0, -1 when the table is absent.
        @Operation
        public long dropW() {
            return change("ALTER TABLE " + table + " DROP COLUMN IF EXISTS w");
        }

        /// Renames `w` to `x`; 0, -1 when the table or `w` is absent.
        @Operation
        public long renameWToX() {
            return change("ALTER TABLE " + table + " RENAME COLUMN w TO x");
        }

        /// Renames `x` back to `w`; 0, -1 when the table or `x` is absent.
        @Operation
        public long renameXToW() {
            return change("ALTER TABLE " + table + " RENAME COLUMN x TO w");
        }

        /// The rows, -1 when the table is absent.
        @Operation
        public long count() {
            return query("SELECT count() FROM " + table);
        }

        /// The rows, every one NULL in `w`, read through `w`: -1 when the table or `w` is absent.
        @Operation
        public long countW() {
            return query("SELECT count() FROM " + table + " WHERE w IS NULL");
        }

        private long query(final String sql) {
            try {
                return cdb.run(sql).getLong(0, 0);
            } catch (final CdbException _) {
                return -1L;
            }
        }

        // a refusal answers -1, which the model gives only while the table is absent: one in any other state is a
        // history no serial order explains
        private long change(final String sql) {
            try {
                return cdb.run(sql).updateCount();
            } catch (final CdbException _) {
                return -1L;
            }
        }

        private void append(final long v, final long dayMicros) {
            try (CdbAppender appender = cdb.appender()) {
                appender.table(table).longColumn("v", v).at(dayMicros + offset.incrementAndGet());
                appender.flush();
            }
        }
    }

    /// `TableState`'s snapshot protocol in memory: a writer lease serialises the changes; a change builds the next
    /// snapshot from immutable versions (a rewrite makes new ones, a partition drop carries the others over) and
    /// publishes it under the lock, holding the new versions before letting go of the old; a read holds the current
    /// snapshot's versions under the lock, reads them outside it and lets go; a version nobody holds is freed. A read
    /// of a freed version answers -1, which no serial order gives.
    public static final class Published {

        private final ReentrantLock lease = new ReentrantLock();
        private final ReentrantLock lock = new ReentrantLock();
        // guarded by lock
        private List<Version> current = List.of();

        /// Appends a row to day 0: its version replaced by one a row longer.
        @Operation
        public void appendOneOnDay0() {
            change(0, 1L);
        }

        /// Appends a row to day 1.
        @Operation
        public void appendTwoOnDay1() {
            change(1, 1L);
        }

        /// Rewrites the table without day 0's rows, every version new, as DELETE re-appends what it keeps.
        @Operation
        public long deleteOnes() {
            lease.lock();
            try {
                final List<Version> next = new ArrayList<>();
                long removed = 0L;
                for (final Version v : base()) {
                    if (v.day == 0) {
                        removed += v.rows;
                    } else {
                        next.add(new Version(v.day, v.rows));
                    }
                }
                publish(next);
                return removed;
            } finally {
                lease.unlock();
            }
        }

        /// Rewrites the table under a new definition, every version new and every row kept, as a column change
        /// re-appends the rows.
        @Operation
        public long dropW() {
            lease.lock();
            try {
                final List<Version> next = new ArrayList<>();
                for (final Version v : base()) {
                    next.add(new Version(v.day, v.rows));
                }
                publish(next);
                return 0L;
            } finally {
                lease.unlock();
            }
        }

        /// Publishes the empty snapshot.
        @Operation
        public long truncate() {
            lease.lock();
            try {
                long removed = 0L;
                for (final Version v : base()) {
                    removed += v.rows;
                }
                publish(List.of());
                return removed;
            } finally {
                lease.unlock();
            }
        }

        /// Publishes the snapshot without day 0's version, day 1's carried over as it is.
        @Operation
        public long dropDay0() {
            lease.lock();
            try {
                final List<Version> next = new ArrayList<>();
                long dropped = 0L;
                for (final Version v : base()) {
                    if (v.day == 0) {
                        dropped++;
                    } else {
                        next.add(v);
                    }
                }
                publish(next);
                return dropped;
            } finally {
                lease.unlock();
            }
        }

        /// The rows of the held snapshot, -1 when a version was freed under the read.
        @Operation
        public long count() {
            final List<Version> held;
            lock.lock();
            try {
                held = current;
                for (final Version v : held) {
                    v.refs++;
                }
            } finally {
                lock.unlock();
            }
            long rows = 0L;
            boolean freed = false;
            for (final Version v : held) {
                freed |= v.freed;
                rows += v.rows;
            }
            lock.lock();
            try {
                for (final Version v : held) {
                    letGo(v);
                }
            } finally {
                lock.unlock();
            }
            return freed ? -1L : rows;
        }

        // the lease holder's base: no other change publishes while it holds the lease
        private List<Version> base() {
            lock.lock();
            try {
                return current;
            } finally {
                lock.unlock();
            }
        }

        private void change(final int day, final long rows) {
            lease.lock();
            try {
                final List<Version> next = new ArrayList<>();
                long before = 0L;
                for (final Version v : base()) {
                    if (v.day == day) {
                        before = v.rows;
                    } else {
                        next.add(v);
                    }
                }
                next.add(new Version(day, before + rows));
                publish(next);
            } finally {
                lease.unlock();
            }
        }

        private void publish(final List<Version> next) {
            lock.lock();
            try {
                for (final Version v : next) {
                    v.refs++;
                }
                for (final Version v : current) {
                    letGo(v);
                }
                current = List.copyOf(next);
            } finally {
                lock.unlock();
            }
        }

        // called with lock held
        private static void letGo(final Version v) {
            if (--v.refs == 0) {
                v.freed = true;
            }
        }

        /// One partition's immutable rows; `refs` guarded by the lock.
        private static final class Version {

            private final int day;
            private final long rows;
            private int refs;
            // written by whichever change or count lets go last, under the lock; read by every count outside it
            private volatile boolean freed;

            Version(final int day, final long rows) {
                this.day = day;
                this.rows = rows;
            }
        }
    }

    /// The serial specification: day 0 holds only ones and day 1 only twos, so two counters, the name column `w` has
    /// now and whether the table exists are the table. A statement on an absent table answers -1; an append creates it,
    /// with no column `w`, as the line protocol creates a table with the columns its line names.
    public static final class Model {

        private boolean present = true;
        private long day0;
        private long day1;
        // "w", "x", or null when the column is gone
        private String column = "w";

        /// One more row on day 0.
        public void appendOneOnDay0() {
            create();
            day0++;
        }

        /// One more row on day 1.
        public void appendTwoOnDay1() {
            create();
            day1++;
        }

        /// Day 0's rows go.
        public long deleteOnes() {
            if (!present) {
                return -1L;
            }
            final long removed = day0;
            day0 = 0L;
            return removed;
        }

        /// Every row goes.
        public long truncate() {
            if (!present) {
                return -1L;
            }
            final long removed = day0 + day1;
            day0 = 0L;
            day1 = 0L;
            return removed;
        }

        /// Day 0's partition goes when it has rows: DELETE and TRUNCATE leave no empty partition behind.
        public long dropDay0() {
            if (!present) {
                return -1L;
            }
            final long dropped = day0 > 0L ? 1L : 0L;
            day0 = 0L;
            return dropped;
        }

        /// The table goes; `IF EXISTS` answers 0 either way.
        public long dropTable() {
            present = false;
            day0 = 0L;
            day1 = 0L;
            column = null;
            return 0L;
        }

        /// The column `w` goes; `IF EXISTS` answers 0 either way.
        public long dropW() {
            if (!present) {
                return -1L;
            }
            if ("w".equals(column)) {
                column = null;
            }
            return 0L;
        }

        /// `w` becomes `x`.
        public long renameWToX() {
            return rename("w", "x");
        }

        /// `x` becomes `w`.
        public long renameXToW() {
            return rename("x", "w");
        }

        /// The rows.
        public long count() {
            return present ? day0 + day1 : -1L;
        }

        /// The rows, while the column is `w`.
        public long countW() {
            return present && "w".equals(column) ? day0 + day1 : -1L;
        }

        // an append to an absent table creates it without w
        private void create() {
            if (!present) {
                present = true;
                column = null;
            }
        }

        // a rename of an absent column is refused; x is never there beside w, so the new name is always free
        private long rename(final String from, final String to) {
            if (!present || !from.equals(column)) {
                return -1L;
            }
            column = to;
            return 0L;
        }
    }
}
