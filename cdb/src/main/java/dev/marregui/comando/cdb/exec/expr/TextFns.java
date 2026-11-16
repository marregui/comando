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
                out.setText(i, sink.toString());
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
            final Vector texts = text.eval(page);
            final Vector starts = start.eval(page);
            final Vector counts = count.eval(page);
            for (int i = 0, n = page.size; i < n; i++) {
                final String s = texts.textAt(i);
                if (s == null || starts.getLong(i) == Vector.NULL_LONG || counts.getLong(i) == Vector.NULL_LONG) {
                    out.setText(i, null);
                    continue;
                }
                final long take = counts.getLong(i);
                if (take < 0) {
                    throw new CdbException("negative substring length is not allowed [length=" + take + "]", -1);
                }
                final int length = s.length();
                final long from = Math.clamp(starts.getLong(i) - 1, 0, length);
                final long to = Math.clamp(starts.getLong(i) - 1 + take, 0, length);
                out.setText(i, to <= from ? "" : s.substring((int) from, (int) to));
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
            final Vector texts = text.eval(page);
            final Vector targets = target.eval(page);
            final Vector replacements = replacement.eval(page);
            for (int i = 0, n = page.size; i < n; i++) {
                final String s = texts.textAt(i);
                final String t = targets.textAt(i);
                final String r = replacements.textAt(i);
                if (s == null || t == null || r == null) {
                    out.setText(i, null);
                } else {
                    out.setText(i, t.isEmpty() ? s : s.replace(t, r));
                }
            }
            return out;
        }, CdbType.VARCHAR, type);
    }

    static VectorFn length(final Bound arg, final CdbType type, final long nowMicros) {
        final VectorFn text = Cast.arg(arg, CdbType.VARCHAR, nowMicros);
        final Vector out = Vector.of(CdbType.LONG, Page.CAPACITY);
        return Cast.exact(page -> {
            final Vector texts = text.eval(page);
            for (int i = 0, n = page.size; i < n; i++) {
                out.setLong(i, texts.isNull(i) ? -1 : texts.textAt(i).length());
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
            final Vector texts = text.eval(page);
            for (int i = 0, n = page.size; i < n; i++) {
                final String s = texts.textAt(i);
                int first = 0;
                while (s != null && first < s.length() && map(s.charAt(first), upper) == s.charAt(first)) {
                    first++;
                }
                if (s == null || first == s.length()) {
                    out.setText(i, s);
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
                out.setText(i, new String(chars, 0, s.length()));
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
            final Vector micros = timestamps.eval(page);
            for (int i = 0, n = page.size; i < n; i++) {
                if (micros.getLong(i) == Vector.NULL_LONG) {
                    out.setText(i, null);
                } else {
                    sink.setLength(0);
                    format.format(sink, micros.getLong(i));
                    out.setText(i, sink.toString());
                }
            }
            return out;
        }, CdbType.VARCHAR, type);
    }

    private static char map(final char c, final boolean upper) {
        return upper ? Character.toUpperCase(c) : Character.toLowerCase(c);
    }
}
