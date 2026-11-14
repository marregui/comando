package dev.marregui.comando.cdb.sql;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import dev.marregui.comando.cdb.CdbException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/// Parses every statement shape of the corpus: the distinct shapes of the SQL comando sends, one per line after a
/// count and a tab, with `?` for a string and `N` for a number. The file lives outside the repository; the system
/// property `cdb.corpus` names it (the build passes `$CDB_CORPUS`), and the test is skipped when it is not set: a
/// test never reads the user's own folders unasked.
class CorpusParseTest {

    private static final Pattern NUMBER_PLACEHOLDER = Pattern.compile("\\bN\\b");
    private static final int SHAPES = 604;
    private static final Pattern STRING_PLACEHOLDER = Pattern.compile("\\?");

    /// Every shape, with `?` read as `'x'` and `N` as `1`, parses, and there are 604 of them.
    @Test
    void everyShapeParses() throws IOException {
        final String named = System.getProperty("cdb.corpus");
        assumeTrue(named != null && Files.isReadable(Path.of(named)), "corpus not named [property=cdb.corpus]");
        final Path corpus = Path.of(named);
        final List<String> failures = new ArrayList<>();
        int shapes = 0;
        for (final String line : Files.readAllLines(corpus)) {
            if (line.isBlank()) {
                continue;
            }
            shapes++;
            final String shape = line.substring(line.indexOf('\t') + 1);
            final String sql = NUMBER_PLACEHOLDER.matcher(STRING_PLACEHOLDER.matcher(shape).replaceAll("'x'"))
                    .replaceAll("1");
            try {
                Parser.parse(sql);
            } catch (final CdbException e) {
                failures.add(e.getMessage() + " :: " + sql);
            }
        }
        assertThat(failures).isEmpty();
        assertThat(shapes).isEqualTo(SHAPES);
    }
}
