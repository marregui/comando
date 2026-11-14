package dev.marregui.comando.cdb.store;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.temporal.IsoFields;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import dev.marregui.comando.cdb.CdbException;
import dev.marregui.comando.cdb.CdbType;

/**
 * The text files of a table folder. {@code _meta}: one {@code key=value} per line, {@code format} first (the version
 * of every file layout in the folder), then {@code name}, {@code partitionBy}, {@code timestamp} (the designated
 * column's index, -1 for none), {@code ttl} (microseconds), {@code dedup} (key names, comma-separated) and one
 * {@code col=<name>:<TYPE>} per column. {@code _txn}: the transaction number, then one {@code <folder>\t<rows>} line
 * per partition in time order. Unknown keys and other formats are refused, not skipped.
 */
final class TableFiles {

    static final String META = "_meta";
    static final String TXN = "_txn";
    static final int FORMAT = 1;

    private TableFiles() {
    }

    /** One partition named by {@code _txn}: its version folder and row count. */
    record Entry(String folder, long rows) {
    }

    /** The committed transaction recorded in {@code _txn}. */
    record Txn(long number, List<Entry> partitions) {

        Txn {
            partitions = List.copyOf(partitions);
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
            sb.append("col=").append(column.name()).append(':').append(column.type().name()).append('\n');
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
        boolean versioned = false;
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
                        if (Integer.parseInt(value) != FORMAT) {
                            throw new CdbException("refuse table of unknown format [file=" + source + ", format="
                                    + value + ", supported=" + FORMAT + ']', -1);
                        }
                        versioned = true;
                    }
                    case "name" -> name = value;
                    case "partitionBy" -> partitionBy = PartitionBy.valueOf(value);
                    case "timestamp" -> timestamp = Integer.parseInt(value);
                    case "ttl" -> ttl = Long.parseLong(value);
                    case "dedup" -> dedup = value.isEmpty() ? List.of() : List.of(value.split(","));
                    case "col" -> {
                        final int colon = value.lastIndexOf(':');
                        if (colon <= 0) {
                            throw corrupt(source, line);
                        }
                        columns.add(new ColumnDef(value.substring(0, colon),
                                CdbType.valueOf(value.substring(colon + 1))));
                    }
                    default -> throw corrupt(source, line);
                }
            }
        } catch (final IllegalArgumentException e) {
            throw new CdbException("cannot read table definition [file=" + source + ']', e);
        }
        if (!versioned || name == null || partitionBy == null) {
            throw new CdbException("cannot read table definition [file=" + source
                    + ", missing=format|name|partitionBy]", -1);
        }
        return new TableDef(name, columns, timestamp, partitionBy, dedup, ttl);
    }

    static String encodeTxn(final long number, final List<Entry> partitions) {
        final StringBuilder sb = new StringBuilder(32 + 32 * partitions.size());
        sb.append(number).append('\n');
        for (final Entry entry : partitions) {
            sb.append(entry.folder()).append('\t').append(entry.rows()).append('\n');
        }
        return sb.toString();
    }

    static Txn decodeTxn(final String text, final String source) {
        final String[] lines = text.split("\n");
        try {
            final long number = Long.parseLong(lines[0]);
            final List<Entry> partitions = new ArrayList<>(lines.length);
            for (int i = 1; i < lines.length; i++) {
                if (lines[i].isEmpty()) {
                    continue;
                }
                final int tab = lines[i].indexOf('\t');
                if (tab <= 0) {
                    throw corrupt(source, lines[i]);
                }
                final long rows = Long.parseLong(lines[i].substring(tab + 1));
                if (rows <= 0L) {
                    throw corrupt(source, lines[i]);
                }
                partitions.add(new Entry(lines[i].substring(0, tab), rows));
            }
            return new Txn(number, partitions);
        } catch (final NumberFormatException e) {
            throw new CdbException("cannot read transaction [file=" + source + ']', e);
        }
    }

    /** The folder of the partition starting at {@code lowerBound}, version {@code txn}, named as QuestDB names it. */
    static String folder(final long lowerBound, final PartitionBy partitionBy, final long txn) {
        if (partitionBy == PartitionBy.NONE) {
            return "default." + txn;
        }
        final ZonedDateTime t = Instant.ofEpochSecond(Math.floorDiv(lowerBound, 1_000_000L)).atZone(ZoneOffset.UTC);
        final String name = switch (partitionBy) {
            case HOUR -> String.format(Locale.ROOT, "%04d-%02d-%02dT%02d", t.getYear(), t.getMonthValue(),
                    t.getDayOfMonth(), t.getHour());
            case DAY -> String.format(Locale.ROOT, "%04d-%02d-%02d", t.getYear(), t.getMonthValue(), t.getDayOfMonth());
            case WEEK -> String.format(Locale.ROOT, "%04d-W%02d", t.get(IsoFields.WEEK_BASED_YEAR),
                    t.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR));
            case MONTH -> String.format(Locale.ROOT, "%04d-%02d", t.getYear(), t.getMonthValue());
            case YEAR -> String.format(Locale.ROOT, "%04d", t.getYear());
            case NONE -> "default";
        };
        return name + '.' + txn;
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

    private static CdbException corrupt(final String source, final String line) {
        return new CdbException("cannot read table file line [file=" + source + ", line=" + line + ']', -1);
    }
}
