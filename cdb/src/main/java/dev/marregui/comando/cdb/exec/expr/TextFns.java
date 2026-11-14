package dev.marregui.comando.cdb.exec.expr;

import java.util.List;

import dev.marregui.comando.cdb.CdbException;
import dev.marregui.comando.cdb.CdbType;
import dev.marregui.comando.cdb.exec.Page;
import dev.marregui.comando.cdb.exec.Vector;
import dev.marregui.comando.cdb.plan.Bound;

/**
 * The text functions, with QuestDB's results: {@code concat} and {@code ||} read NULL as empty; {@code substring} is
 * 1-based over {@code [start, start + length)} clipped to the text, a negative length an error; {@code replace} with an
 * empty target returns the text; {@code length} of NULL is -1; {@code upper} and {@code lower} map each character on
 * its own, so {@code ß} stays {@code ß}.
 */
final class TextFns {

    private TextFns() {
    }

    static VectorFn concat(final List<Bound> args, final CdbType type, final long nowMicros) {
        final VectorFn[] parts = new VectorFn[args.size()];
        for (int j = 0; j < parts.length; j++) {
            parts[j] = Functions.compile(args.get(j), nowMicros);
        }
        final Vector[] evaluated = new Vector[parts.length];
        final StringBuilder sink = new StringBuilder();
        final Vector out = Vector.of(CdbType.VARCHAR, Page.CAPACITY);
        return Cast.exact(page -> {
            for (int j = 0; j < parts.length; j++) {
                evaluated[j] = parts[j].eval(page);
            }
            for (int i = 0, n = page.size; i < n; i++) {
                sink.setLength(0);
                for (final Vector part : evaluated) {
                    Cast.appendText(sink, part, i);
                }
                out.texts[i] = sink.toString();
            }
            return out;
        }, CdbType.VARCHAR, type);
    }

    static VectorFn substring(final List<Bound> args, final CdbType type, final long nowMicros) {
        final VectorFn text = Cast.arg(args.get(0), CdbType.VARCHAR, nowMicros);
        final VectorFn start = Cast.arg(args.get(1), CdbType.LONG, nowMicros);
        final VectorFn count = Cast.arg(args.get(2), CdbType.LONG, nowMicros);
        final Vector out = Vector.of(CdbType.VARCHAR, Page.CAPACITY);
        return Cast.exact(page -> {
            final String[] texts = text.eval(page).texts;
            final long[] starts = start.eval(page).longs;
            final long[] counts = count.eval(page).longs;
            for (int i = 0, n = page.size; i < n; i++) {
                if (texts[i] == null || starts[i] == Vector.NULL_LONG || counts[i] == Vector.NULL_LONG) {
                    out.texts[i] = null;
                    continue;
                }
                if (counts[i] < 0) {
                    throw new CdbException("negative substring length is not allowed [length=" + counts[i] + "]", -1);
                }
                final int length = texts[i].length();
                final long from = Math.clamp(starts[i] - 1, 0, length);
                final long to = Math.clamp(starts[i] - 1 + counts[i], 0, length);
                out.texts[i] = to <= from ? "" : texts[i].substring((int) from, (int) to);
            }
            return out;
        }, CdbType.VARCHAR, type);
    }

    static VectorFn replace(final List<Bound> args, final CdbType type, final long nowMicros) {
        final VectorFn text = Cast.arg(args.get(0), CdbType.VARCHAR, nowMicros);
        final VectorFn target = Cast.arg(args.get(1), CdbType.VARCHAR, nowMicros);
        final VectorFn replacement = Cast.arg(args.get(2), CdbType.VARCHAR, nowMicros);
        final Vector out = Vector.of(CdbType.VARCHAR, Page.CAPACITY);
        return Cast.exact(page -> {
            final String[] texts = text.eval(page).texts;
            final String[] targets = target.eval(page).texts;
            final String[] replacements = replacement.eval(page).texts;
            for (int i = 0, n = page.size; i < n; i++) {
                if (texts[i] == null || targets[i] == null || replacements[i] == null) {
                    out.texts[i] = null;
                } else {
                    out.texts[i] = targets[i].isEmpty() ? texts[i] : texts[i].replace(targets[i], replacements[i]);
                }
            }
            return out;
        }, CdbType.VARCHAR, type);
    }

    static VectorFn length(final Bound arg, final CdbType type, final long nowMicros) {
        final VectorFn text = Cast.arg(arg, CdbType.VARCHAR, nowMicros);
        final Vector out = Vector.of(CdbType.LONG, Page.CAPACITY);
        return Cast.exact(page -> {
            final String[] texts = text.eval(page).texts;
            for (int i = 0, n = page.size; i < n; i++) {
                out.longs[i] = texts[i] == null ? -1 : texts[i].length();
            }
            return out;
        }, CdbType.LONG, type);
    }

    /** {@code upper} or {@code lower}; a text that does not change is returned as it is, without a copy. */
    static VectorFn changeCase(final Bound arg, final boolean upper, final CdbType type, final long nowMicros) {
        final VectorFn text = Cast.arg(arg, CdbType.VARCHAR, nowMicros);
        final Vector out = Vector.of(CdbType.VARCHAR, Page.CAPACITY);
        final char[][] buffer = {new char[64]};
        return Cast.exact(page -> {
            final String[] texts = text.eval(page).texts;
            for (int i = 0, n = page.size; i < n; i++) {
                final String s = texts[i];
                int first = 0;
                while (s != null && first < s.length() && map(s.charAt(first), upper) == s.charAt(first)) {
                    first++;
                }
                if (s == null || first == s.length()) {
                    out.texts[i] = s;
                    continue;
                }
                if (buffer[0].length < s.length()) {
                    buffer[0] = new char[s.length()];
                }
                final char[] chars = buffer[0];
                s.getChars(0, first, chars, 0);
                for (int k = first; k < s.length(); k++) {
                    chars[k] = map(s.charAt(k), upper);
                }
                out.texts[i] = new String(chars, 0, s.length());
            }
            return out;
        }, CdbType.VARCHAR, type);
    }

    /** {@code to_str(ts, format)}; the format is a constant, compiled once by {@link DateFormat}. */
    static VectorFn toStr(final List<Bound> args, final CdbType type, final long nowMicros) {
        final VectorFn timestamps = Cast.arg(args.get(0), CdbType.TIMESTAMP, nowMicros);
        final DateFormat format = DateFormat.compile(Functions.constantText(args.get(1), "to_str"));
        final StringBuilder sink = new StringBuilder();
        final Vector out = Vector.of(CdbType.VARCHAR, Page.CAPACITY);
        return Cast.exact(page -> {
            final long[] micros = timestamps.eval(page).longs;
            for (int i = 0, n = page.size; i < n; i++) {
                if (micros[i] == Vector.NULL_LONG) {
                    out.texts[i] = null;
                } else {
                    sink.setLength(0);
                    format.format(sink, micros[i]);
                    out.texts[i] = sink.toString();
                }
            }
            return out;
        }, CdbType.VARCHAR, type);
    }

    private static char map(final char c, final boolean upper) {
        return upper ? Character.toUpperCase(c) : Character.toLowerCase(c);
    }
}
