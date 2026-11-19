package dev.marregui.comando.cdb;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// `CdbServe --restore`: a backup made by `BACKUP TO` restored into a data root that is absent or empty, the tables
/// and rows the same as the backup's, and every other root refused untouched; every folder under a temporary one,
/// never the machine's store.
class CdbServeRestoreTest {

    @TempDir
    private Path root;

    private Path backup;

    /// A backup of two tables, one with a TTL and dedup keys, one without a designated timestamp.
    @BeforeEach
    void backUp() {
        backup = root.resolve("backup");
        try (final Cdb cdb = Cdb.open(root.resolve("source"))) {
            cdb.run("CREATE TABLE metrics (ts TIMESTAMP, host SYMBOL, v DOUBLE) TIMESTAMP(ts) PARTITION BY DAY"
                    + " TTL 2 DAYS DEDUP UPSERT KEYS(ts, host)");
            cdb.run("INSERT INTO metrics VALUES ('2026-11-14T10:00:00.000000Z', 'a', 1.5),"
                    + " ('2026-11-15T10:00:00.000000Z', NULL, NULL)");
            cdb.run("CREATE TABLE loose (x LONG, s VARCHAR)");
            cdb.run("INSERT INTO loose VALUES (2, 'b'), (1, NULL)");
            cdb.run("BACKUP TO '" + backup + "'");
        }
    }

    /// A root that does not exist is restored: the same tables and rows as the backup, the tables answered.
    @Test
    void restoresIntoAnAbsentRoot() {
        final Path data = root.resolve("data");
        assertThat(CdbServe.restore(backup, data)).containsExactly("loose", "metrics");
        assertSameData(data);
        assertThat(siblings()).containsExactlyInAnyOrder("backup", "data", "source");
    }

    /// An empty folder as the root is restored into the same way.
    @Test
    void restoresIntoAnEmptyRoot() throws IOException {
        final Path data = Files.createDirectory(root.resolve("data"));
        assertThat(CdbServe.restore(backup, data)).containsExactly("loose", "metrics");
        assertSameData(data);
        assertThat(siblings()).containsExactlyInAnyOrder("backup", "data", "source");
    }

    /// A root that holds anything, or is a file, is refused and left as it was.
    @Test
    void refusesARootThatIsNotEmpty() throws IOException {
        final Path data = Files.createDirectory(root.resolve("data"));
        Files.writeString(data.resolve("kept"), "kept");
        final Path file = Files.writeString(root.resolve("file"), "kept");
        for (final Path refused : List.of(data, file)) {
            assertThatThrownBy(() -> CdbServe.restore(backup, refused))
                    .isInstanceOf(CdbException.class)
                    .hasMessage("refuse to restore into a data root that is not empty [root=" + refused + ']');
        }
        assertThat(names(data)).containsExactly("kept");
        assertThat(Files.size(file)).isEqualTo(4L);
        assertThat(siblings()).containsExactlyInAnyOrder("backup", "data", "file", "source");
    }

    /// A backup that is not a folder is refused, and no root is made.
    @Test
    void refusesAMissingBackup() {
        final Path missing = root.resolve("missing");
        final Path data = root.resolve("data");
        assertThatThrownBy(() -> CdbServe.restore(missing, data))
                .isInstanceOf(CdbException.class)
                .hasMessage("cannot restore from a backup that is not a folder [backup=" + missing + ']');
        assertThat(siblings()).containsExactlyInAnyOrder("backup", "source");
    }

    /// A root inside the backup is refused, and the backup stays as it was.
    @Test
    void refusesARootInsideTheBackup() {
        final List<String> before = names(backup);
        final Path inside = backup.resolve("data");
        assertThatThrownBy(() -> CdbServe.restore(backup, inside))
                .isInstanceOf(CdbException.class)
                .hasMessage("refuse to restore into a folder inside the backup [backup=" + backup + ", root=" + inside
                        + ']');
        assertThat(names(backup)).isEqualTo(before);
    }

    /// A backup a store holds open cannot be opened as a store, so it is refused, and no root is made.
    @Test
    void refusesABackupAStoreHolds() {
        final Path data = root.resolve("data");
        try (final Cdb _ = Cdb.open(backup)) {
            assertThatThrownBy(() -> CdbServe.restore(backup, data)).isInstanceOf(CdbException.class);
        }
        assertThat(siblings()).containsExactlyInAnyOrder("backup", "source");
    }

    /// `main` with `--restore` restores, prints one line naming the tables and returns, so no server is started;
    /// without both folders it is refused.
    @Test
    void mainRestoresAndReturns() throws Exception {
        final Path data = root.resolve("data");
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        final PrintStream was = System.out;
        System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
        try {
            CdbServe.main(new String[] {"--restore", backup.toString(), data.toString()});
        } finally {
            System.setOut(was);
        }
        assertThat(out.toString(StandardCharsets.UTF_8).lines().toList()).containsExactly("cdb restored [backup="
                + backup + ", root=" + data + ", tables=[loose, metrics]]");
        assertSameData(data);
        assertThatThrownBy(() -> CdbServe.main(new String[] {"--restore", backup.toString()}))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CdbServe.main(new String[] {"--restore", backup.toString(), data.toString(), "x"}))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private void assertSameData(final Path data) {
        try (final Cdb from = Cdb.open(backup); final Cdb to = Cdb.open(data)) {
            assertThat(to.tables()).isEqualTo(from.tables());
            for (final String table : from.tables()) {
                assertThat(text(to.run("SHOW CREATE TABLE " + table))).as(table)
                        .isEqualTo(text(from.run("SHOW CREATE TABLE " + table)));
                assertThat(text(to.run("SELECT * FROM " + table))).as(table)
                        .isEqualTo(text(from.run("SELECT * FROM " + table)))
                        .isNotEmpty();
            }
        }
    }

    // every cell of the result as text, row after row; what the two stores answer is compared, not its form
    private static List<String> text(final CdbResult result) {
        final List<String> cells = new ArrayList<>();
        for (int row = 0; row < result.rowCount(); row++) {
            for (int column = 0; column < result.columnCount(); column++) {
                cells.add(result.isNull(row, column) ? "null" : switch (result.type(column)) {
                    case BOOLEAN -> String.valueOf(result.getBoolean(row, column));
                    case DOUBLE -> String.valueOf(result.getDouble(row, column));
                    case LONG, TIMESTAMP -> String.valueOf(result.getLong(row, column));
                    case SYMBOL, VARCHAR -> result.getText(row, column);
                });
            }
        }
        return cells;
    }

    private List<String> siblings() {
        return names(root);
    }

    private static List<String> names(final Path folder) {
        try (final Stream<Path> entries = Files.list(folder)) {
            return entries.map(p -> p.getFileName().toString()).sorted().toList();
        } catch (final IOException e) {
            throw new AssertionError("cannot list folder [folder=" + folder + ']', e);
        }
    }
}
