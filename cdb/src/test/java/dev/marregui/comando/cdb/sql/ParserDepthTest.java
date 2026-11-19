package dev.marregui.comando.cdb.sql;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import dev.marregui.comando.cdb.CdbException;
import dev.marregui.comando.cdb.plan.Planner;

import static org.assertj.core.api.Assertions.assertThat;

/// Expressions and queries nested or chained up to the parser's depth limit parse and plan on a 1 MiB thread stack;
/// past it they are refused with a CdbException, never a stack overflow.
class ParserDepthTest {

    // the JVM's default platform-thread stack on 64-bit macOS and Linux
    private static final long STACK_BYTES = 1L << 20;
    private static final Duration JOIN = Duration.ofSeconds(30);

    /// Each shape at the limit parses and plans; one more level is refused, naming the limit.
    @Test
    void depthIsLimited() {
        final int limit = Parser.MAX_DEPTH;
        for (final Shape shape : Shape.values()) {
            final Throwable within = onSmallStack(() -> Planner.plan(Parser.parse(shape.sql(limit - 1)), _ -> null,
                    0L));
            assertThat(within).as("%s at the limit", shape).isNull();
            final Throwable past = onSmallStack(() -> Parser.parse(shape.sql(limit + 1)));
            assertThat(past).as("%s past the limit", shape).isInstanceOf(CdbException.class)
                    .hasMessageContaining("depth limit [limit=" + limit);
            final Throwable far = onSmallStack(() -> Parser.parse(shape.sql(100 * limit)));
            assertThat(far).as("%s far past the limit", shape).isInstanceOf(CdbException.class);
        }
    }

    /// The SQL shapes that nest: parentheses, NOT, unary minus, operator chains, set operations and subqueries.
    private enum Shape {
        PARENTHESES, NOT, MINUS, SUM, AND, COMPARISON, CAST, UNION, SUBQUERY;

        // a statement of about `n` levels of this shape
        String sql(final int n) {
            return switch (this) {
                case PARENTHESES -> "SELECT " + "(".repeat(n - 1) + "1" + ")".repeat(n - 1);
                case NOT -> "SELECT " + "NOT ".repeat(n - 1) + "true";
                case MINUS -> "SELECT " + "- ".repeat(n - 1) + "1";
                case SUM -> "SELECT 1" + " + 1".repeat(n - 1);
                case AND -> "SELECT true" + " AND true".repeat(n - 2);
                case COMPARISON -> "SELECT true" + " = true".repeat(n - 1);
                case CAST -> "SELECT 1" + "::LONG".repeat(n - 1);
                case UNION -> "SELECT 1" + " UNION ALL SELECT 1".repeat(n - 1);
                case SUBQUERY -> "SELECT * FROM (".repeat(n - 1) + "SELECT 1" + ")".repeat(n - 1);
            };
        }
    }

    // what `work` threw on a named platform thread with a 1 MiB stack, null when it returned
    private static Throwable onSmallStack(final Runnable work) {
        final AtomicReference<Throwable> thrown = new AtomicReference<>();
        final Thread thread = new Thread(null, () -> {
            try {
                work.run();
            } catch (final Throwable t) {
                thrown.set(t);
            }
        }, "parser-depth-test", STACK_BYTES);
        thread.start();
        try {
            thread.join(JOIN);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted waiting for the parse", e);
        }
        assertThat(thread.isAlive()).as("parse finished within %s", JOIN).isFalse();
        return thrown.get();
    }
}
