package dev.marregui.comando.cdb.exec.expr;

import java.util.List;

import dev.marregui.comando.cdb.CdbType;
import dev.marregui.comando.cdb.exec.Page;
import dev.marregui.comando.cdb.exec.Vector;
import dev.marregui.comando.cdb.plan.Bound;

/**
 * {@code like} and {@code ilike}: {@code %} matches any run of characters, {@code _} one character, {@code \} makes the
 * next character literal, and every other character, {@code .} and {@code [} included, is itself. The pattern matches
 * the whole text; {@code ilike} compares lower-cased characters. A NULL text or pattern does not match.
 */
final class Like {

    private static final byte LITERAL = 0;
    private static final byte ONE = 1;
    private static final byte ANY = 2;

    private char[] chars = new char[16];
    private byte[] kinds = new byte[16];
    private int length;

    private Like() {
    }

    static VectorFn compile(final List<Bound> args, final boolean ignoreCase, final CdbType type, final long nowMicros) {
        final VectorFn text = Cast.arg(args.get(0), CdbType.VARCHAR, nowMicros);
        final Like pattern = new Like();
        final VectorFn patterns;
        if (args.get(1) instanceof Bound.Constant(Object value, CdbType _)) {
            patterns = null;
            pattern.of(value == null ? null : value.toString(), ignoreCase);
        } else {
            patterns = Cast.arg(args.get(1), CdbType.VARCHAR, nowMicros);
        }
        final Vector out = Vector.of(CdbType.BOOLEAN, Page.CAPACITY);
        return Cast.exact(page -> {
            final String[] texts = text.eval(page).texts;
            final String[] rowPatterns = patterns == null ? null : patterns.eval(page).texts;
            for (int i = 0, n = page.size; i < n; i++) {
                if (rowPatterns != null) {
                    pattern.of(rowPatterns[i], ignoreCase);
                }
                out.booleans[i] = texts[i] != null && pattern.matches(texts[i], ignoreCase);
            }
            return out;
        }, CdbType.BOOLEAN, type);
    }

    /** Re-points this matcher at {@code pattern}; a null pattern matches nothing. */
    void of(final String pattern, final boolean ignoreCase) {
        length = -1;
        if (pattern == null) {
            return;
        }
        if (chars.length < pattern.length()) {
            chars = new char[pattern.length()];
            kinds = new byte[pattern.length()];
        }
        int n = 0;
        for (int i = 0; i < pattern.length(); i++) {
            final char c = pattern.charAt(i);
            if (c == '\\' && i + 1 < pattern.length()) {
                i++;
                kinds[n] = LITERAL;
                chars[n++] = fold(pattern.charAt(i), ignoreCase);
            } else if (c == '%') {
                kinds[n++] = ANY;
            } else if (c == '_') {
                kinds[n++] = ONE;
            } else {
                kinds[n] = LITERAL;
                chars[n++] = fold(c, ignoreCase);
            }
        }
        length = n;
    }

    /** True when the whole of {@code text} matches; a {@code %} backtracks to the last one seen. */
    boolean matches(final CharSequence text, final boolean ignoreCase) {
        if (length < 0) {
            return false;
        }
        final int n = text.length();
        int t = 0;
        int p = 0;
        int star = -1;
        int resume = 0;
        while (t < n) {
            if (p < length && (kinds[p] == ONE || (kinds[p] == LITERAL && chars[p] == fold(text.charAt(t), ignoreCase)))) {
                t++;
                p++;
            } else if (p < length && kinds[p] == ANY) {
                star = p++;
                resume = t;
            } else if (star >= 0) {
                p = star + 1;
                t = ++resume;
            } else {
                return false;
            }
        }
        while (p < length && kinds[p] == ANY) {
            p++;
        }
        return p == length;
    }

    private static char fold(final char c, final boolean ignoreCase) {
        return ignoreCase ? Character.toLowerCase(c) : c;
    }
}
