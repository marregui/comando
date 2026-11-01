package dev.marregui.comando.core.model;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A file comando keeps as one entry a line, its schema's version in a first line {@code # comando <format>, version
 * <n>} (record 133). A file without that line, written before versions, is version 1; lines that start with {@code #}
 * are not entries. A file of a later version than the reader's is refused: the reader keeps it as it is and writes
 * nothing to it.
 */
public final class VersionedLines {

    private static final Pattern HEADER = Pattern.compile("#\\s*comando\\s+(\\S+),\\s*version\\s+(\\d+)\\s*");

    private VersionedLines() {
    }

    /**
     * A file's entries, or why it is refused.
     *
     * @param refused why the file is kept as it is and not written: a later version; empty when it reads
     */
    public record Read(List<String> lines, String refused) {
        public Read {
            lines = List.copyOf(lines);
            refused = refused == null ? "" : refused;
        }
    }

    /** The first line of a file of {@code format} at {@code version}. */
    public static String header(final String format, final int version) {
        return "# comando " + format + ", version " + version;
    }

    /**
     * The entries of {@code file}, stripped and not blank, when it is of {@code version} or earlier; none when it is
     * missing. Throws when it cannot be read.
     */
    public static Read read(final Path file, final int version) throws IOException {
        if (!Files.isRegularFile(file)) {
            return new Read(List.of(), "");
        }
        final List<String> out = new ArrayList<>();
        boolean first = true;
        for (final String raw : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            final String line = raw.strip();
            if (first) {
                first = false;
                final Matcher m = HEADER.matcher(line);
                if (m.matches()) {
                    final long read = Long.parseLong(m.group(2).length() > 9 ? "999999999" : m.group(2));
                    if (read > version) {
                        return new Read(List.of(), file.getFileName() + " is of version " + read + ", written by a later comando; this one reads version "
                                + version + ". It is kept as it is and nothing is saved to it until a comando that reads it runs");
                    }
                    continue;
                }
            }
            if (!line.isEmpty() && !line.startsWith("#")) {
                out.add(line);
            }
        }
        return new Read(out, "");
    }

    /** {@code lines} as a file of {@code format} at {@code version}: the header, then one entry a line. */
    public static String text(final String format, final int version, final List<String> lines) {
        final StringBuilder b = new StringBuilder(header(format, version)).append('\n');
        for (final String l : lines) {
            b.append(l).append('\n');
        }
        return b.toString();
    }
}
