package dev.marregui.comando.cdb.exec.expr;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;

import dev.marregui.comando.cdb.CdbType;
import dev.marregui.comando.cdb.exec.Page;
import dev.marregui.comando.cdb.exec.Vector;
import dev.marregui.comando.cdb.plan.Bound;

/// Bound expressions built by hand, and values rendered as QuestDB's `/exec` JSON prints them: NULL as `null`,
/// DOUBLE as Java's `Double.toString`, TIMESTAMP as ISO text with six fraction digits. Timestamps are read and
/// printed through `java.time`, independently of the code under test.
final class Exprs {

    /// `now()` of every evaluation.
    static final long NOW_MICROS = micros("2026-11-14T10:49:45.097165Z");

    private static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSSSS'Z'")
            .withZone(ZoneOffset.UTC);

    private Exprs() {
    }

    static Bound lit(final long value) {
        return new Bound.Constant(value, CdbType.LONG);
    }

    static Bound lit(final double value) {
        return new Bound.Constant(value, CdbType.DOUBLE);
    }

    static Bound lit(final boolean value) {
        return new Bound.Constant(value, CdbType.BOOLEAN);
    }

    static Bound lit(final String value) {
        return new Bound.Constant(value, CdbType.VARCHAR);
    }

    static Bound nul(final CdbType type) {
        return new Bound.Constant(null, type);
    }

    static Bound ts(final String iso) {
        return new Bound.Constant(micros(iso), CdbType.TIMESTAMP);
    }

    static Bound col(final int index, final CdbType type) {
        return new Bound.Column(index, type);
    }

    static Bound call(final String function, final CdbType type, final Bound... args) {
        return new Bound.Call(function, List.of(args), type);
    }

    static long micros(final String iso) {
        return ChronoUnit.MICROS.between(Instant.EPOCH, Instant.parse(iso));
    }

    /// `expr` evaluated over a one-row page with no columns, rendered.
    static String eval(final Bound expr) {
        final Vector v = Functions.compile(expr, NOW_MICROS).eval(new Page(new Vector[0], 1));
        return render(v, 0);
    }

    static String render(final Vector v, final int i) {
        if (v.isNull(i)) {
            return "null";
        }
        return switch (v.type) {
            case LONG -> Long.toString(v.longs[i]);
            case TIMESTAMP -> ISO.format(Instant.EPOCH.plus(v.longs[i], ChronoUnit.MICROS));
            case DOUBLE -> Double.toString(v.doubles[i]);
            case BOOLEAN -> Boolean.toString(v.booleans[i]);
            case SYMBOL, VARCHAR -> v.texts[i];
        };
    }
}
