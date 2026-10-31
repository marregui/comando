package dev.marregui.comando.db;

import java.lang.reflect.Proxy;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Types;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import dev.marregui.comando.core.loop.Threads;

import static org.assertj.core.api.Assertions.assertThat;

/// [ColumnarResult] lets readers on other threads read the rows below the count while the writer appends and grows
/// its chunk tables.
class ColumnarResultTest {

    private static final int ROWS = ColumnarResult.CHUNK * 20;

    /// The writer appends 20 chunks of rows, growing every column's chunk table from 4 to 32, while a reader reads the
    /// first and the last rows below each count it sees; every value read is the one written. A smoke test, not a
    /// proof of the memory model's ordering: a missing acquire load may pass here on this JIT and this machine.
    @Test
    void aReaderSeesTheRowsBelowTheCountWhileTheWriterGrowsPastFourChunks() throws Exception {
        final ColumnarResult result = ColumnarResult.of(1L, metaData());
        final Rows source = new Rows();
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        final AtomicLong reads = new AtomicLong();
        final Thread reader = Threads.fork("columnar-reader", () -> {
            try {
                int seen = 0;
                while (seen < ROWS) {
                    seen = result.rowCount();
                    if (seen > 0) {
                        check(result, 0);
                        check(result, Math.min(seen - 1, ColumnarResult.CHUNK - 1));
                        check(result, seen - 1);
                        reads.incrementAndGet();
                    }
                }
            } catch (final Throwable t) {
                failure.set(t);
            }
        });
        for (int row = 0; row < ROWS; row++) {
            source.row = row;
            result.append(source.resultSet);
        }
        assertThat(reader.join(Duration.ofSeconds(10))).isTrue();
        assertThat(failure.get()).isNull();
        assertThat(reads.get()).isPositive();
        assertThat(result.rowCount()).isEqualTo(ROWS);
        check(result, ROWS - 1);
    }

    /** Asserts the values of {@code row} as {@link Rows} writes them. */
    private static void check(final ColumnarResult result, final int row) {
        if (result.getLong(row, 0) != row || result.getDouble(row, 1) != row * 0.5) {
            throw new AssertionError("row " + row + " reads " + result.getLong(row, 0) + ", "
                    + result.getDouble(row, 1));
        }
        final boolean nullText = row % 7 == 0;
        if (result.isNull(row, 2) != nullText || !nullText && !result.getText(row, 2).equals("r" + row)) {
            throw new AssertionError("row " + row + " reads text " + result.getText(row, 2));
        }
    }

    private static ResultSetMetaData metaData() {
        final String[] names = {"id", "half", "label"};
        final int[] types = {Types.BIGINT, Types.DOUBLE, Types.VARCHAR};
        return (ResultSetMetaData) Proxy.newProxyInstance(ColumnarResultTest.class.getClassLoader(),
                new Class<?>[]{ResultSetMetaData.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "getColumnCount" -> names.length;
                    case "getColumnLabel" -> names[(Integer) args[0] - 1];
                    case "getColumnTypeName" -> "t" + types[(Integer) args[0] - 1];
                    case "getColumnType" -> types[(Integer) args[0] - 1];
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    /** A result set whose current row is {@link #row}: the id, half of it, and a label, SQL NULL every 7th row. */
    private static final class Rows {

        private int row;
        private boolean lastNull;
        private final ResultSet resultSet = (ResultSet) Proxy.newProxyInstance(
                ColumnarResultTest.class.getClassLoader(), new Class<?>[]{ResultSet.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getLong" -> {
                        lastNull = false;
                        yield (long) row;
                    }
                    case "getDouble" -> {
                        lastNull = false;
                        yield row * 0.5;
                    }
                    case "getString" -> {
                        lastNull = row % 7 == 0;
                        yield lastNull ? null : "r" + row;
                    }
                    case "wasNull" -> lastNull;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }
}
