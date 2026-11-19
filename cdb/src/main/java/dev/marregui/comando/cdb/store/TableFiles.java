package dev.marregui.comando.cdb.store;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.temporal.IsoFields;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import dev.marregui.comando.cdb.CdbException;
import dev.marregui.comando.cdb.CdbType;

/**
 * The text files of a table folder. {@code _meta}: one {@code key=value} per line, {@code format} first (the version
 * of every file layout in the folder), then {@code name}, {@code partitionBy}, {@code timestamp} (the designated
 * column's index, -1 for none), {@code ttl} (microseconds), {@code dedup} (key names, comma-separated) and one
 * {@code col=<name>:<TYPE>} per column; from format 2 a SYMBOL column adds {@code :capacity=<n>}, then
 * {@code :nocache} when its dictionary is not cached and {@code :index=<block capacity>} when it is indexed. Format 1
 * is still read, its SYMBOL columns taking QuestDB's defaults. {@code _txn}: the transaction number, then one
 * {@code <folder>\t<rows>\t<columns>} line per partition in time order, {@code <columns>} the leading columns with
 * files in it (a line without it, written before, is read with -1: unknown). {@code _meta.prev}, written by a commit
 * that drops or renames a column before it replaces {@code _meta}: {@code txn=<n>}, the commit's transaction, then
 * {@code _meta} as it was; a {@code _txn} older than {@code n} pairs with that definition. Unknown keys, options and
 * other formats are refused, not skipped.
 */
final class TableFiles {

    static final String META = "_meta";
    static final String TXN = "_txn";
    // the definition before a column drop or rename, kept until the change's _txn holds
    static final String META_PREV = "_meta.prev";
    private static final String PREV_TXN = "txn=";
    // a dropped table's marker: the drop's commit point, so an open deletes the folder holding it
    static final String DROPPED = "_dropped";
    // the lines a checkpoint adds to _txn, after the partitions
    private static final String COLUMNS = "@columns=";
    private static final String TTL = "@ttl=";
    private static final String SYM = "@sym=";
    static final int FORMAT = 2;

    /** The format before {@link #FORMAT}, still read: its columns carry no options. */
    static final int PREVIOUS_FORMAT = 1;

    private TableFiles() {
    }

    /**
     * One partition named by {@code _txn}: its version folder, row count and the leading columns of the definition
     * with files in it, -1 when the line was written without them.
     */
    record Entry(String folder, long rows, int columns) {
    }

    /** The content of {@code _meta.prev}: the transaction of the column change and the {@code _meta} text before it. */
    record Prev(long txn, String meta) {
    }

    /**
     * The committed transaction recorded in {@code _txn}, as of the last checkpoint: its partitions, the number of
     * columns and the TTL of the definition it was taken at, and per column the byte length of its symbol file then
     * (-1 for a column that is not a SYMBOL). A file written before the log names no definition: {@code columns} is -1
     * and {@code symbolBytes} empty, and the table's {@code _meta} and symbol files are taken as they are.
     */
    record Txn(long number, List<Entry> partitions, int columns, long ttlMicros, long[] symbolBytes) {

        Txn {
            partitions = List.copyOf(partitions);
            symbolBytes = symbolBytes.clone();
        }

        /** A transaction in the format written before the log. */
        Txn(final long number, final List<Entry> partitions) {
            this(number, partitions, -1, 0L, new long[0]);
        }

        /** True when the file named the definition and symbol lengths of its checkpoint. */
        boolean checkpointed() {
            return columns >= 0;
        }

        @Override
        public long[] symbolBytes() {
            return symbolBytes.clone();
        }
    }

    static String encodeMeta(final TableDef def) {
        final StringBuilder sb = new StringBuilder(128);
        sb.append("format=").append(FORMAT).append('\n');
        sb.append("name=").append(def.name()).append('\n');
        sb.append("partitionBy=").append(def.partitionBy().name()).append('\n');
        sb.append("timestamp=").append(def.timestampIndex()).append('\n');
        sb.append("ttl=").append(def.ttlMicros()).append('\n');
        sb.append("dedup=").append(String.join(",", def.dedupKeys())).append('\n');
        for (final ColumnDef column : def.columns()) {
            sb.append("col=").append(column.name()).append(':').append(column.type().name());
            if (column.type() == CdbType.SYMBOL) {
                sb.append(":capacity=").append(column.symbolCapacity());
                if (!column.symbolCached()) {
                    sb.append(":nocache");
                }
                if (column.indexed()) {
                    sb.append(":index=").append(column.indexBlockCapacity());
                }
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    static TableDef decodeMeta(final String text, final String source) {
        String name = null;
        PartitionBy partitionBy = null;
        int timestamp = -1;
        long ttl = 0L;
        List<String> dedup = List.of();
        final List<ColumnDef> columns = new ArrayList<>();
        int format = 0;
        try {
            for (final String line : text.split("\n")) {
                if (line.isEmpty()) {
                    continue;
                }
                final int eq = line.indexOf('=');
                if (eq < 0) {
                    throw corrupt(source, line);
                }
                final String key = line.substring(0, eq);
                final String value = line.substring(eq + 1);
                switch (key) {
                    case "format" -> {
                        format = Integer.parseInt(value);
                        if (format != FORMAT && format != PREVIOUS_FORMAT) {
                            throw new CdbException("refuse table of unknown format [file=" + source + ", format="
                                    + value + ", supported=" + PREVIOUS_FORMAT + ".." + FORMAT + ']', -1);
                        }
                    }
                    case "name" -> name = value;
                    case "partitionBy" -> partitionBy = PartitionBy.valueOf(value);
                    case "timestamp" -> timestamp = Integer.parseInt(value);
                    case "ttl" -> ttl = Long.parseLong(value);
                    case "dedup" -> dedup = value.isEmpty() ? List.of() : List.of(value.split(","));
                    case "col" -> columns.add(column(value, format, source, line));
                    default -> throw corrupt(source, line);
                }
            }
        } catch (final IllegalArgumentException e) {
            throw new CdbException("cannot read table definition [file=" + source + ']', e);
        }
        if (format == 0 || name == null || partitionBy == null) {
            throw new CdbException("cannot read table definition [file=" + source
                    + ", missing=format|name|partitionBy]", -1);
        }
        return new TableDef(name, columns, timestamp, partitionBy, dedup, ttl);
    }

    static String encodeTxn(final long number, final List<Entry> partitions) {
        final StringBuilder sb = new StringBuilder(32 + 32 * partitions.size());
        sb.append(number).append('\n');
        for (final Entry entry : partitions) {
            sb.append(entry.folder()).append('\t').append(entry.rows());
            if (entry.columns() >= 0) {
                sb.append('\t').append(entry.columns());
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    /**
     * {@code txn} as a checkpoint writes it: its partitions, then {@code @columns=}, {@code @ttl=} and one
     * {@code @sym=<column>:<bytes>} line per SYMBOL column. The {@code @} lines hold no tab, so an engine from before
     * the log refuses the file rather than open a table whose last commits only its log holds.
     */
    static String encodeTxn(final Txn txn) {
        final StringBuilder sb = new StringBuilder(encodeTxn(txn.number(), txn.partitions()));
        sb.append(COLUMNS).append(txn.columns()).append('\n');
        sb.append(TTL).append(txn.ttlMicros()).append('\n');
        final long[] symbolBytes = txn.symbolBytes();
        for (int c = 0; c < symbolBytes.length; c++) {
            if (symbolBytes[c] >= 0L) {
                sb.append(SYM).append(c).append(':').append(symbolBytes[c]).append('\n');
            }
        }
        return sb.toString();
    }

    static Txn decodeTxn(final String text, final String source) {
        final String[] lines = text.split("\n");
        try {
            final long number = Long.parseLong(lines[0]);
            final List<Entry> partitions = new ArrayList<>(lines.length);
            int columns = -1;
            long ttl = 0L;
            final List<long[]> symbols = new ArrayList<>();
            for (int i = 1; i < lines.length; i++) {
                if (lines[i].isEmpty()) {
                    continue;
                }
                if (lines[i].startsWith(COLUMNS)) {
                    columns = Integer.parseInt(lines[i].substring(COLUMNS.length()));
                    if (columns < 0) {
                        throw corrupt(source, lines[i]);
                    }
                    continue;
                }
                if (lines[i].startsWith(TTL)) {
                    ttl = Long.parseLong(lines[i].substring(TTL.length()));
                    continue;
                }
                if (lines[i].startsWith(SYM)) {
                    final String value = lines[i].substring(SYM.length());
                    final int colon = value.indexOf(':');
                    if (colon <= 0) {
                        throw corrupt(source, lines[i]);
                    }
                    final long column = Integer.parseInt(value.substring(0, colon));
                    final long bytes = Long.parseLong(value.substring(colon + 1));
                    if (column < 0L || bytes < 0L) {
                        throw corrupt(source, lines[i]);
                    }
                    symbols.add(new long[] {column, bytes});
                    continue;
                }
                if (lines[i].startsWith("@")) {
                    throw corrupt(source, lines[i]);
                }
                final int tab = lines[i].indexOf('\t');
                if (tab <= 0) {
                    throw corrupt(source, lines[i]);
                }
                final int second = lines[i].indexOf('\t', tab + 1);
                final long rows = Long.parseLong(second < 0 ? lines[i].substring(tab + 1)
                        : lines[i].substring(tab + 1, second));
                final int columnCount = second < 0 ? -1 : Integer.parseInt(lines[i].substring(second + 1));
                if (rows <= 0L || second >= 0 && columnCount < 0) {
                    throw corrupt(source, lines[i]);
                }
                partitions.add(new Entry(lines[i].substring(0, tab), rows, columnCount));
            }
            if (columns < 0) {
                if (!symbols.isEmpty()) {
                    throw corrupt(source, "@sym without @columns");
                }
                return new Txn(number, partitions);
            }
            final long[] symbolBytes = new long[columns];
            Arrays.fill(symbolBytes, -1L);
            for (final long[] symbol : symbols) {
                if (symbol[0] >= columns) {
                    throw corrupt(source, SYM + symbol[0] + ':' + symbol[1]);
                }
                symbolBytes[(int) symbol[0]] = symbol[1];
            }
            return new Txn(number, partitions, columns, ttl, symbolBytes);
        } catch (final NumberFormatException e) {
            throw new CdbException("cannot read transaction [file=" + source + ']', e);
        }
    }

    static String encodePrev(final Prev prev) {
        return PREV_TXN + prev.txn() + '\n' + prev.meta();
    }

    static Prev decodePrev(final String text, final String source) {
        final int nl = text.indexOf('\n');
        if (!text.startsWith(PREV_TXN) || nl < 0) {
            throw corrupt(source, nl < 0 ? text : text.substring(0, nl));
        }
        try {
            final long txn = Long.parseLong(text.substring(PREV_TXN.length(), nl));
            if (txn <= 0L) {
                throw corrupt(source, text.substring(0, nl));
            }
            return new Prev(txn, text.substring(nl + 1));
        } catch (final NumberFormatException e) {
            throw new CdbException("cannot read previous table definition [file=" + source + ']', e);
        }
    }

    /** The bytes a row takes in a column's {@code .d} file of {@code type}; -1 for VARCHAR, whose rows vary. */
    static int width(final CdbType type) {
        return switch (type) {
            case LONG, TIMESTAMP, DOUBLE -> Long.BYTES;
            case BOOLEAN -> 1;
            case SYMBOL -> Integer.BYTES;
            case VARCHAR -> -1;
        };
    }

    /** The folder of the partition starting at {@code lowerBound}, version {@code txn}, named as QuestDB names it. */
    static String folder(final long lowerBound, final PartitionBy partitionBy, final long txn) {
        return partitionName(lowerBound, partitionBy) + '.' + txn;
    }

    /** The name of the partition starting at {@code lowerBound}, as QuestDB names it: its folder without the txn. */
    static String partitionName(final long lowerBound, final PartitionBy partitionBy) {
        if (partitionBy == PartitionBy.NONE) {
            return "default";
        }
        final ZonedDateTime t = Instant.ofEpochSecond(Math.floorDiv(lowerBound, 1_000_000L)).atZone(ZoneOffset.UTC);
        return switch (partitionBy) {
            case HOUR -> String.format(Locale.ROOT, "%04d-%02d-%02dT%02d", t.getYear(), t.getMonthValue(),
                    t.getDayOfMonth(), t.getHour());
            case DAY -> String.format(Locale.ROOT, "%04d-%02d-%02d", t.getYear(), t.getMonthValue(), t.getDayOfMonth());
            case WEEK -> String.format(Locale.ROOT, "%04d-W%02d", t.get(IsoFields.WEEK_BASED_YEAR),
                    t.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR));
            case MONTH -> String.format(Locale.ROOT, "%04d-%02d", t.getYear(), t.getMonthValue());
            case YEAR -> String.format(Locale.ROOT, "%04d", t.getYear());
            case NONE -> "default";
        };
    }

    /**
     * Refuses a table or column name that cannot be a file name or a {@code _meta} value: empty, with a path separator,
     * a control character, {@code :} or {@code ,}, or starting with {@code .}.
     */
    static void checkName(final String name, final String what) {
        boolean ok = name != null && !name.isEmpty() && name.charAt(0) != '.';
        for (int i = 0; ok && i < name.length(); i++) {
            final char c = name.charAt(i);
            ok = c >= ' ' && c != '/' && c != '\\' && c != ':' && c != ',' && c != 0x7f;
        }
        if (!ok) {
            throw new CdbException("refuse " + what + " name [name=" + name + ']', -1);
        }
    }

    /**
     * One {@code col} value: the name ends at the first {@code :} ({@link #checkName} refuses one in a name), then the
     * type, then, from format 2 and only on a SYMBOL, its options; one left out keeps QuestDB's default.
     */
    private static ColumnDef column(final String value, final int format, final String source, final String line) {
        final String[] parts = value.split(":", -1);
        if (parts.length < 2 || parts[0].isEmpty()) {
            throw corrupt(source, line);
        }
        final CdbType type = CdbType.valueOf(parts[1]);
        if (parts.length == 2) {
            return new ColumnDef(parts[0], type);
        }
        if (format < FORMAT || type != CdbType.SYMBOL) {
            throw corrupt(source, line);
        }
        int capacity = ColumnDef.DEFAULT_SYMBOL_CAPACITY;
        boolean cached = true;
        boolean indexed = false;
        int indexCapacity = ColumnDef.DEFAULT_INDEX_BLOCK_CAPACITY;
        for (int i = 2; i < parts.length; i++) {
            final String option = parts[i];
            if (option.startsWith("capacity=")) {
                capacity = Integer.parseInt(option.substring("capacity=".length()));
            } else if (option.equals("nocache")) {
                cached = false;
            } else if (option.startsWith("index=")) {
                indexed = true;
                indexCapacity = Integer.parseInt(option.substring("index=".length()));
            } else {
                throw corrupt(source, line);
            }
        }
        return new ColumnDef(parts[0], type, capacity, cached, indexed, indexCapacity);
    }

    private static CdbException corrupt(final String source, final String line) {
        return new CdbException("cannot read table file line [file=" + source + ", line=" + line + ']', -1);
    }
}
