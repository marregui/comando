package dev.marregui.comando.cdb.plan;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.IsoFields;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import dev.marregui.comando.cdb.CdbException;
import dev.marregui.comando.cdb.CdbType;
import dev.marregui.comando.cdb.sql.Ast;
import dev.marregui.comando.cdb.store.ColumnDef;
import dev.marregui.comando.cdb.store.PartitionBy;
import dev.marregui.comando.cdb.store.Store;
import dev.marregui.comando.cdb.store.TableDef;

/**
 * {@code CREATE TABLE} to a {@link TableDef}, QuestDB's DDL type names to the six cdb types, and the plans of
 * {@code ALTER}, {@code DROP} and {@code TRUNCATE TABLE}, and of {@code BACKUP}.
 */
final class Ddl {

    // partition names as QuestDB writes them, one shape per unit: 2026-11-14T10, 2026-11-14, 2026-W46, 2026-11, 2026
    private static final Pattern HOUR_NAME = Pattern.compile("\\d{4}-\\d{2}-\\d{2}T\\d{2}");
    private static final Pattern DAY_NAME = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");
    private static final Pattern WEEK_NAME = Pattern.compile("(\\d{4})-W(\\d{2})");
    private static final Pattern MONTH_NAME = Pattern.compile("\\d{4}-\\d{2}");
    private static final Pattern YEAR_NAME = Pattern.compile("\\d{4}");

    /** QuestDB's {@code MIN_SYMBOL_CAPACITY}. */
    private static final long MIN_SYMBOL_CAPACITY = 2;

    /** QuestDB's {@code MAX_SYMBOL_CAPACITY}, {@code ceilPow2(Integer.MAX_VALUE)}: 2^30. */
    private static final long MAX_SYMBOL_CAPACITY = 1L << 30;

    /** QuestDB's {@code MAX_SYMBOL_CAPACITY_CACHED}, {@code ceilPow2(30_000_000)}. */
    private static final long MAX_SYMBOL_CAPACITY_CACHED = 1L << 25;

    /** QuestDB's {@code MIN_INDEX_VALUE_BLOCK_SIZE}. */
    private static final long MIN_INDEX_BLOCK_CAPACITY = 2;

    /** QuestDB's {@code MAX_INDEX_VALUE_BLOCK_SIZE}, {@code ceilPow2(8 * 1024 * 1024)}. */
    private static final long MAX_INDEX_BLOCK_CAPACITY = 1L << 23;

    private Ddl() {
    }

    /** QuestDB's INT, SHORT and BYTE are LONG here, FLOAT DOUBLE, STRING and CHAR VARCHAR, DATE TIMESTAMP. */
    static CdbType type(final String name, final int pos) {
        return switch (name.toUpperCase(Locale.ROOT)) {
            case "LONG", "INT", "INTEGER", "SHORT", "BYTE" -> CdbType.LONG;
            case "DOUBLE", "FLOAT" -> CdbType.DOUBLE;
            case "BOOLEAN" -> CdbType.BOOLEAN;
            case "TIMESTAMP", "DATE" -> CdbType.TIMESTAMP;
            case "SYMBOL" -> CdbType.SYMBOL;
            case "VARCHAR", "STRING", "CHAR" -> CdbType.VARCHAR;
            case "JSON" -> CdbType.JSON;
            default -> throw new CdbException("cannot map unsupported type [type=" + name + ']', pos);
        };
    }

    /**
     * The definition {@code create} declares: the designated timestamp must be a TIMESTAMP column, a partitioned table
     * must have one, and the dedup keys must name columns and include it.
     */
    static Plan.CreateTable createTable(final Ast.CreateTable create, final Function<String, TableDef> tables) {
        final int pos = create.pos();
        if (!create.ifNotExists() && tables.apply(create.name()) != null) {
            throw new CdbException("cannot create existing table [table=" + create.name() + ']', pos);
        }
        final List<ColumnDef> columns = new ArrayList<>(create.columns().size());
        final Set<String> names = new HashSet<>();
        for (final Ast.ColumnSpec c : create.columns()) {
            if (!names.add(c.name().toLowerCase(Locale.ROOT))) {
                throw new CdbException("cannot repeat column name [name=" + c.name() + ']', pos);
            }
            columns.add(column(c, type(c.type(), pos), pos));
        }
        final TableDef draft = new TableDef(create.name(), columns, -1, PartitionBy.NONE, List.of(), 0);
        int timestamp = -1;
        if (create.timestamp() != null) {
            timestamp = draft.columnIndex(create.timestamp());
            if (timestamp < 0 || columns.get(timestamp).type() != CdbType.TIMESTAMP) {
                throw new CdbException("cannot designate non-timestamp column [column=" + create.timestamp() + ']',
                        pos);
            }
        }
        final PartitionBy partitionBy = partitionBy(create.partitionBy(), pos);
        if (partitionBy != PartitionBy.NONE && timestamp < 0) {
            throw new CdbException("cannot partition table without designated timestamp [table=" + create.name() + ']',
                    pos);
        }
        final List<String> keys = new ArrayList<>(create.dedupKeys().size());
        for (final String k : create.dedupKeys()) {
            final int i = draft.columnIndex(k);
            if (i < 0) {
                throw new CdbException("cannot find dedup key [column=" + k + ']', pos);
            }
            if (keys.contains(columns.get(i).name())) {
                throw new CdbException("cannot repeat dedup key [column=" + k + ']', pos);
            }
            keys.add(columns.get(i).name());
        }
        if (!keys.isEmpty() && (timestamp < 0 || !keys.contains(columns.get(timestamp).name()))) {
            throw new CdbException("cannot dedup without designated timestamp key [table=" + create.name() + ']', pos);
        }
        final long ttlMicrosOrMonths = create.ttl() == null ? 0
                : ttlMicrosOrMonths(create.ttl(), create.name(), partitionBy, pos);
        return new Plan.CreateTable(
                new TableDef(create.name(), columns, timestamp, partitionBy, keys, ttlMicrosOrMonths),
                create.ifNotExists());
    }

    /**
     * {@code ALTER TABLE}: SET TTL as a CREATE reads it; ADD COLUMN refuses a column the table has, except under
     * {@code IF NOT EXISTS} with the same type, which QuestDB skips.
     */
    static Plan alterTable(final Ast.AlterTable alter, final Function<String, TableDef> tables) {
        final int pos = alter.pos();
        final TableDef def = tables.apply(alter.table());
        if (def == null) {
            throw new CdbException("cannot find table [table=" + alter.table() + ']', pos);
        }
        if (alter.ttl() != null) {
            return new Plan.SetTtl(def.name(), ttlMicrosOrMonths(alter.ttl(), def.name(), def.partitionBy(), pos));
        }
        final List<ColumnDef> added = new ArrayList<>(alter.addColumns().size());
        final Set<String> names = new HashSet<>();
        for (final Ast.AddColumn add : alter.addColumns()) {
            final Ast.ColumnSpec spec = add.column();
            final CdbType type = type(spec.type(), pos);
            final int existing = def.columnIndex(spec.name());
            if (existing >= 0) {
                final CdbType current = def.columns().get(existing).type();
                if (!add.ifNotExists()) {
                    throw new CdbException("cannot add existing column [table=" + def.name() + ", column="
                            + spec.name() + ']', pos);
                }
                if (current != type) {
                    throw new CdbException("cannot add existing column of other type [table=" + def.name()
                            + ", column=" + spec.name() + ", type=" + current + ", requested=" + type + ']', pos);
                }
                continue;
            }
            if (!names.add(spec.name().toLowerCase(Locale.ROOT))) {
                throw new CdbException("cannot repeat column name [name=" + spec.name() + ']', pos);
            }
            added.add(column(spec, type, pos));
        }
        return new Plan.AddColumns(def.name(), List.copyOf(added));
    }

    /**
     * A TTL clause as the store keeps it, refused where QuestDB's {@code validateTtlGranularity} refuses it: on a table
     * with no partitions to drop, or when it is not a whole number of partitions (a month TTL on DAY or WEEK partitions
     * is whole, an hour or day TTL on MONTH partitions is not).
     */
    private static long ttlMicrosOrMonths(
            final String text,
            final String table,
            final PartitionBy partitionBy,
            final int pos
    ) {
        final long ttlMicrosOrMonths = Times.ttlMicrosOrMonths(text, pos);
        final boolean whole = switch (partitionBy) {
            case NONE -> throw new CdbException("cannot set TTL on a non-partitioned table [table=" + table + ']', pos);
            case HOUR -> true;
            case DAY -> ttlMicrosOrMonths <= 0 || ttlMicrosOrMonths % TimeUnit.DAYS.toMicros(1) == 0;
            case WEEK -> ttlMicrosOrMonths <= 0 || ttlMicrosOrMonths % TimeUnit.DAYS.toMicros(7) == 0;
            case MONTH -> ttlMicrosOrMonths <= 0;
            case YEAR -> ttlMicrosOrMonths <= 0 && ttlMicrosOrMonths % 12 == 0;
        };
        if (!whole) {
            throw new CdbException("cannot set TTL that is not a multiple of the partition size [table=" + table
                    + ", ttl=" + text + ", partitionBy=" + partitionBy + ']', pos);
        }
        return ttlMicrosOrMonths;
    }

    /** {@code DROP TABLE}: a missing table is refused, except under {@code IF EXISTS}, which keeps the name given. */
    static Plan dropTable(final Ast.DropTable drop, final Function<String, TableDef> tables) {
        final TableDef def = tables.apply(drop.table());
        if (def != null) {
            return new Plan.DropTable(def.name(), drop.ifExists());
        }
        if (!drop.ifExists()) {
            throw new CdbException("cannot find table [table=" + drop.table() + ']', drop.pos());
        }
        return new Plan.DropTable(drop.table(), true);
    }

    static Plan truncate(final Ast.Truncate truncate, final Function<String, TableDef> tables) {
        final TableDef def = tables.apply(truncate.table());
        if (def == null) {
            throw new CdbException("cannot find table [table=" + truncate.table() + ']', truncate.pos());
        }
        return new Plan.Truncate(def.name());
    }

    /**
     * {@code ALTER TABLE DROP COLUMN}: a missing column is refused, except under {@code IF EXISTS}, which keeps the
     * name given for the runner to find absent. The designated timestamp, a dedup key and the last column stay.
     */
    static Plan dropColumn(final Ast.DropColumn drop, final Function<String, TableDef> tables) {
        final int pos = drop.pos();
        final TableDef def = tables.apply(drop.table());
        if (def == null) {
            throw new CdbException("cannot find table [table=" + drop.table() + ']', pos);
        }
        final int c = def.columnIndex(drop.column());
        if (c < 0) {
            if (!drop.ifExists()) {
                throw new CdbException("cannot find column [table=" + def.name() + ", column=" + drop.column() + ']',
                        pos);
            }
            return new Plan.DropColumn(def.name(), drop.column());
        }
        final String column = def.columns().get(c).name();
        if (c == def.timestampIndex()) {
            throw new CdbException("cannot drop the designated timestamp [table=" + def.name() + ", column=" + column
                    + ']', pos);
        }
        for (final String key : def.dedupKeys()) {
            if (def.columnIndex(key) == c) {
                throw new CdbException("cannot drop a dedup key [table=" + def.name() + ", column=" + column + ']',
                        pos);
            }
        }
        if (def.columns().size() == 1) {
            throw new CdbException("cannot drop the last column [table=" + def.name() + ", column=" + column + ']',
                    pos);
        }
        return new Plan.DropColumn(def.name(), column);
    }

    /**
     * {@code ALTER TABLE RENAME COLUMN}: the column must exist and not be the designated timestamp; the new name must
     * be one no column has, in any case (the column's own in another case included), and fit
     * {@link Store#MAX_NAME_LENGTH}. A dedup key may be renamed: the store renames the key with it.
     */
    static Plan renameColumn(final Ast.RenameColumn rename, final Function<String, TableDef> tables) {
        final int pos = rename.pos();
        final TableDef def = tables.apply(rename.table());
        if (def == null) {
            throw new CdbException("cannot find table [table=" + rename.table() + ']', pos);
        }
        final int c = def.columnIndex(rename.from());
        if (c < 0) {
            throw new CdbException("cannot find column [table=" + def.name() + ", column=" + rename.from() + ']', pos);
        }
        final String from = def.columns().get(c).name();
        final String to = rename.to();
        if (c == def.timestampIndex()) {
            throw new CdbException("cannot rename the designated timestamp [table=" + def.name() + ", column=" + from
                    + ']', pos);
        }
        if (to.length() > Store.MAX_NAME_LENGTH) {
            throw new CdbException("cannot rename column: name too long [table=" + def.name() + ", column=" + from
                    + ", length=" + to.length() + ", limit=" + Store.MAX_NAME_LENGTH + ']', pos);
        }
        // the column's own name in another case too: names are matched in any case, and its files would collide on a
        // file system that folds case
        if (def.columnIndex(to) >= 0) {
            throw new CdbException("cannot rename to an existing column [table=" + def.name() + ", column=" + from
                    + ", to=" + to + ']', pos);
        }
        return new Plan.RenameColumn(def.name(), from, to);
    }

    /** {@code BACKUP TO}: the folder as written, which must name something; the runner refuses one that exists. */
    static Plan backup(final Ast.Backup backup) {
        if (backup.folder().isBlank()) {
            throw new CdbException("cannot back up into a folder without a name [folder=" + backup.folder() + ']',
                    backup.pos());
        }
        return new Plan.Backup(backup.folder());
    }

    /**
     * {@code ALTER TABLE DROP PARTITION}, of a table partitioned by its designated timestamp. A LIST name is read at
     * the table's unit ({@code 2026-11-14T10}, {@code 2026-11-14}, {@code 2026-W46}, {@code 2026-11}, {@code 2026}) to
     * its partition's range. A WHERE must be a time range: top-level AND terms, each comparing the designated timestamp
     * (other than by {@code !=}) with a value known at plan time, or a BETWEEN of two such values; anything else
     * (OR, NOT, IN a list, {@code !=}) would read as a wider range than it says and is refused.
     */
    static Plan dropPartition(
            final Ast.DropPartition drop,
            final Function<String, TableDef> tables,
            final long nowMicros
    ) {
        final int pos = drop.pos();
        final TableDef def = tables.apply(drop.table());
        if (def == null) {
            throw new CdbException("cannot find table [table=" + drop.table() + ']', pos);
        }
        final int ts = def.timestampIndex();
        if (ts < 0) {
            throw new CdbException("cannot drop partitions of a table without designated timestamp [table="
                    + def.name() + ']', pos);
        }
        if (def.partitionBy() == PartitionBy.NONE) {
            throw new CdbException("cannot drop partitions of a table not partitioned [table=" + def.name() + ']', pos);
        }
        if (drop.where() == null) {
            final long[] from = new long[drop.names().size()];
            final long[] to = new long[from.length];
            for (int i = 0; i < from.length; i++) {
                final long[] range = partitionRange(drop.names().get(i), def, pos);
                from[i] = range[0];
                to[i] = range[1];
            }
            return new Plan.DropPartitions(def.name(), from, to);
        }
        final Bound where = new Binder(Rel.of(Planner.scan(def), def.name(), ts), null).bindValue(drop.where());
        if (where.type() != CdbType.BOOLEAN) {
            throw new CdbException("cannot drop partitions by non-boolean condition [table=" + def.name() + ", type="
                    + where.type() + ']', pos);
        }
        if (readsOtherColumn(where, ts)) {
            throw new CdbException("cannot drop partitions by a condition on other columns [table=" + def.name() + ']',
                    pos);
        }
        final long[] range = {Long.MIN_VALUE, Long.MAX_VALUE};
        if (!timeRange(where, ts, nowMicros, range)) {
            throw new CdbException("cannot drop partitions by a condition other than a time range [table="
                    + def.name() + ']', pos);
        }
        return new Plan.DropPartitions(def.name(), new long[] {range[0]}, new long[] {range[1]});
    }

    /** {@code [start, nextStart)} of the partition {@code name} names under the table's unit. */
    private static long[] partitionRange(final String name, final TableDef def, final int pos) {
        final PartitionBy unit = def.partitionBy();
        final Matcher m = partitionName(unit).matcher(name);
        if (m.matches()) {
            if (unit != PartitionBy.WEEK) {
                try {
                    return Times.interval(name, pos);
                } catch (final CdbException _) {
                    // a field out of range, as 2026-13: the name of no partition
                }
            } else {
                final LocalDate fourthOfJanuary = LocalDate.of(Integer.parseInt(m.group(1)), 1, 4);
                final int week = Integer.parseInt(m.group(2));
                if (IsoFields.WEEK_OF_WEEK_BASED_YEAR.rangeRefinedBy(fourthOfJanuary).isValidValue(week)) {
                    final LocalDate monday = fourthOfJanuary.with(IsoFields.WEEK_OF_WEEK_BASED_YEAR, week)
                            .with(DayOfWeek.MONDAY);
                    final long start = TimeUnit.DAYS.toMicros(monday.toEpochDay());
                    return new long[] {start, start + TimeUnit.DAYS.toMicros(7)};
                }
            }
        }
        throw new CdbException("cannot parse partition name [table=" + def.name() + ", name=" + name
                + ", partitionBy=" + unit + ']', pos);
    }

    /** The shape of a partition's name under {@code unit}, as QuestDB names partition folders. */
    private static Pattern partitionName(final PartitionBy unit) {
        return switch (unit) {
            case HOUR -> HOUR_NAME;
            case DAY -> DAY_NAME;
            case WEEK -> WEEK_NAME;
            case MONTH -> MONTH_NAME;
            case YEAR -> YEAR_NAME;
            case NONE -> throw new IllegalArgumentException("cannot name partitions of a table not partitioned");
        };
    }

    /** True when {@code b} reads a column other than {@code ts}. */
    private static boolean readsOtherColumn(final Bound b, final int ts) {
        return switch (b) {
            case Bound.Column(final int index, final CdbType _) -> index != ts;
            case Bound.Call(final String _, final List<Bound> args, final CdbType _) -> anyReadsOther(args, ts);
            case Bound.Agg agg -> anyReadsOther(agg.args(), ts);
            case Bound.WindowCall _ -> true;
            case Bound.Constant _ -> false;
        };
    }

    private static boolean anyReadsOther(final List<Bound> args, final int ts) {
        for (final Bound a : args) {
            if (readsOtherColumn(a, ts)) {
                return true;
            }
        }
        return false;
    }

    /**
     * {@code range} narrowed by {@code where} through {@link Pruning#narrow}, true when every top-level AND term is one
     * it narrows by exactly: a bound on column {@code ts} by a value known at plan time.
     */
    private static boolean timeRange(final Bound where, final int ts, final long nowMicros, final long[] range) {
        if (!(where instanceof Bound.Call(final String function, final List<Bound> args, final CdbType _))) {
            return false;
        }
        if ("and".equals(function)) {
            for (final Bound a : args) {
                if (!timeRange(a, ts, nowMicros, range)) {
                    return false;
                }
            }
            return true;
        }
        if ("between".equals(function)) {
            if (!Pruning.isColumn(args.getFirst(), ts) || Pruning.fold(args.get(1), nowMicros) == null
                    || Pruning.fold(args.get(2), nowMicros) == null) {
                return false;
            }
        } else if (Signatures.isComparison(function) && !"!=".equals(function)) {
            final Long right = Pruning.fold(args.getLast(), nowMicros);
            final Long left = Pruning.fold(args.getFirst(), nowMicros);
            final boolean columnLeft = Pruning.isColumn(args.getFirst(), ts) && right != null;
            if (!columnLeft && !(Pruning.isColumn(args.getLast(), ts) && left != null)) {
                return false;
            }
            final String op = columnLeft ? function : Pruning.flip(function);
            if (">".equals(op) && (columnLeft ? right : left) == Long.MAX_VALUE) {
                // nothing is later than the last instant; narrow's value + 1 would wrap to the open start
                range[0] = Long.MAX_VALUE;
                return true;
            }
        } else {
            return false;
        }
        Pruning.narrow(range, where, ts, nowMicros);
        return true;
    }

    /**
     * The column {@code spec} declares, its SYMBOL options checked and rounded as QuestDB does: each capacity within
     * its range and raised to a power of two, a cached dictionary's capacity at most
     * {@link #MAX_SYMBOL_CAPACITY_CACHED}.
     */
    private static ColumnDef column(final Ast.ColumnSpec spec, final CdbType type, final int pos) {
        if (type != CdbType.SYMBOL) {
            return new ColumnDef(spec.name(), type);
        }
        int capacity = ColumnDef.DEFAULT_SYMBOL_CAPACITY;
        if (spec.capacity() >= 0) {
            if (spec.capacity() < MIN_SYMBOL_CAPACITY || spec.capacity() > MAX_SYMBOL_CAPACITY) {
                throw new CdbException("symbol capacity out of range [column=" + spec.name() + ", capacity="
                        + spec.capacity() + ", min=" + MIN_SYMBOL_CAPACITY + ", max=" + MAX_SYMBOL_CAPACITY + ']', pos);
            }
            capacity = ceilPow2(spec.capacity());
            if (!spec.noCache() && capacity > MAX_SYMBOL_CAPACITY_CACHED) {
                throw new CdbException("cached symbol capacity out of range [column=" + spec.name() + ", capacity="
                        + capacity + ", max=" + MAX_SYMBOL_CAPACITY_CACHED + ']', pos);
            }
        }
        int indexCapacity = ColumnDef.DEFAULT_INDEX_BLOCK_CAPACITY;
        if (spec.indexCapacity() >= 0) {
            if (spec.indexCapacity() < MIN_INDEX_BLOCK_CAPACITY || spec.indexCapacity() > MAX_INDEX_BLOCK_CAPACITY) {
                throw new CdbException("index block capacity out of range [column=" + spec.name() + ", capacity="
                        + spec.indexCapacity() + ", min=" + MIN_INDEX_BLOCK_CAPACITY + ", max="
                        + MAX_INDEX_BLOCK_CAPACITY + ']', pos);
            }
            indexCapacity = ceilPow2(spec.indexCapacity());
        }
        return new ColumnDef(spec.name(), type, capacity, !spec.noCache(), spec.index(), indexCapacity);
    }

    /** The least power of two at least {@code value}; {@code value} is in [2, 2^30]. */
    private static int ceilPow2(final long value) {
        return (int) (Long.highestOneBit(value - 1) << 1);
    }

    private static PartitionBy partitionBy(final String name, final int pos) {
        if (name == null) {
            return PartitionBy.NONE;
        }
        try {
            return PartitionBy.valueOf(name.toUpperCase(Locale.ROOT));
        } catch (final IllegalArgumentException _) {
            throw new CdbException("cannot partition by unknown unit [unit=" + name + ']', pos);
        }
    }
}
