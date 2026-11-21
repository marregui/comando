package dev.marregui.comando.cdb.exec.expr;

import java.nio.charset.StandardCharsets;
import java.util.List;

import dev.marregui.comando.cdb.CdbException;
import dev.marregui.comando.cdb.CdbType;
import dev.marregui.comando.cdb.exec.Page;
import dev.marregui.comando.cdb.exec.Vector;
import dev.marregui.comando.cdb.json.Json;
import dev.marregui.comando.cdb.json.JsonPath;
import dev.marregui.comando.cdb.plan.Bound;

/**
 * The JSON functions, over any text read as JSON: {@code json_extract(text, path)} and {@code text ->> key} give
 * VARCHAR, a string value's decoded contents or any other value's JSON text as the document holds it, NULL for JSON
 * {@code null}; {@code text -> key} gives the value's JSON text as JSON; {@code json_exists(text, path)} is true when
 * the path names a value, {@code null} included. A path that names nothing, NULL and a text that is not JSON up to
 * the value give NULL (false for {@code json_exists}). Each row is one forward scan of its bytes in place, which stops
 * at the value.
 */
final class JsonFns {

    private JsonFns() {
    }

    /** {@code json_extract(text, path)}. */
    static VectorFn extract(final List<Bound> args, final CdbType type, final long nowMicros) {
        return compile(args.get(0), path(args.get(1), "json_extract"), Result.TEXT, type, nowMicros);
    }

    /** {@code json_exists(text, path)}. */
    static VectorFn exists(final List<Bound> args, final CdbType type, final long nowMicros) {
        return compile(args.get(0), path(args.get(1), "json_exists"), Result.EXISTS, type, nowMicros);
    }

    /** {@code text -> key} (JSON) or {@code text ->> key} (VARCHAR): a member's name or an element's index. */
    static VectorFn field(
            final List<Bound> args,
            final boolean asText,
            final CdbType type,
            final long nowMicros
    ) {
        final String function = asText ? "->>" : "->";
        final JsonPath path = switch (args.get(1)) {
            case Bound.Constant(final Long index, final CdbType _) -> JsonPath.element(index);
            case Bound.Constant(final String name, final CdbType _) -> JsonPath.member(name);
            default -> throw new CdbException("argument must be a text or integer constant [function=" + function
                    + ']', -1);
        };
        return compile(args.get(0), path, asText ? Result.TEXT : Result.JSON, type, nowMicros);
    }

    /** {@code CAST(text AS JSON)}: each value checked as one JSON text, else an error. */
    static void castInto(final Vector in, final Vector out, final int n, final Json json, final StringBuilder sink) {
        for (int i = 0; i < n; i++) {
            if (in.isNull(i)) {
                out.setNull(i);
                continue;
            }
            byte[] a = in.type.isText() ? in.utf8(i) : null;
            int from = a == null ? 0 : in.utf8Start(i);
            int to = a == null ? 0 : from + in.utf8Length(i);
            if (a == null) {
                a = Cast.asText(in, i, sink).getBytes(StandardCharsets.UTF_8);
                to = a.length;
                from = 0;
            }
            final int invalidAt = json.invalidAt(a, from, to);
            if (invalidAt != Json.VALID) {
                throw new CdbException("cannot cast value to JSON [type=" + in.type + ", at=" + (invalidAt - from)
                        + ']', -1);
            }
            out.setText(i, a, from, to - from);
        }
    }

    private static JsonPath path(final Bound arg, final String function) {
        return JsonPath.parse(Functions.constantText(arg, function));
    }

    private static VectorFn compile(
            final Bound text,
            final JsonPath path,
            final Result result,
            final CdbType type,
            final long nowMicros
    ) {
        final VectorFn source = Functions.compile(text, nowMicros);
        final CdbType produced = switch (result) {
            case TEXT -> CdbType.VARCHAR;
            case JSON -> CdbType.JSON;
            case EXISTS -> CdbType.BOOLEAN;
        };
        final Vector out = Vector.of(produced, Page.CAPACITY);
        final Json json = new Json();
        return Cast.exact(page -> {
            final Vector in = source.eval(page);
            for (int i = 0, n = page.size; i < n; i++) {
                byte[] a = in.utf8(i);
                final int from = a == null ? 0 : in.utf8Start(i);
                int to = a == null ? 0 : from + in.utf8Length(i);
                if (a == null && !in.isNull(i)) {
                    // a SYMBOL held as its dictionary key
                    a = in.textAt(i).getBytes(StandardCharsets.UTF_8);
                    to = a.length;
                }
                final long span = a == null ? Json.ABSENT : json.find(a, from, to, path);
                if (result == Result.EXISTS) {
                    out.setBoolean(i, span != Json.ABSENT);
                } else if (span == Json.ABSENT || result == Result.TEXT && Json.isNull(a, Json.start(span))) {
                    out.setNull(i);
                } else {
                    put(out, i, a, Json.start(span), Json.end(span), result, json);
                }
            }
            return out;
        }, produced, type);
    }

    private static void put(
            final Vector out,
            final int i,
            final byte[] a,
            final int start,
            final int end,
            final Result result,
            final Json json
    ) {
        if (result == Result.JSON || !Json.isString(a, start)) {
            out.setText(i, a, start, end - start);
            return;
        }
        final int n = json.unescape(a, start + 1, end - 1);
        if (n == Json.RAW) {
            out.setText(i, a, start + 1, end - start - 2);
        } else {
            out.setText(i, json.buffer(), 0, n);
        }
    }

    private enum Result {
        TEXT,
        JSON,
        EXISTS
    }
}
