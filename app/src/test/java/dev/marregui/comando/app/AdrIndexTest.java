package dev.marregui.comando.app;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// `docs/adr/README.md` is the index of the records, and each row repeats what its record says of itself: a status
/// copied by hand drifts, so the index is checked against the records.
/// - every record has one row, and every row names a record that exists
/// - a row's title, date, deciders and status are the record's heading and header, word for word
class AdrIndexTest {

    private static final Path ADR = Path.of("../docs/adr");
    private static final Pattern ROW = Pattern.compile("^\\| \\[(\\d+)]\\((\\d{4}-[^)]+\\.md)\\) \\| (.*)$");
    private static final Pattern HEADING = Pattern.compile("^# (\\d+)\\. (.+)$");

    /** What a record says of itself, or what the index says of it: title, date, deciders, status. */
    private record Entry(String title, String date, String decidedBy, String status) {
    }

    /// Every record file has exactly one index row, and every index row links a record file.
    @Test
    void everyRecordHasOneRowAndEveryRowARecord() throws IOException {
        final List<String> files = records();
        final List<String> linked = new ArrayList<>(index().keySet());
        assertThat(files).as("records in docs/adr").isNotEmpty();
        assertThat(linked).as("the index's rows, by the file each links").containsExactlyInAnyOrderElementsOf(files);
    }

    /// Each row's title, date, deciders and status equal the record's heading and its header row.
    @Test
    void eachRowSaysWhatItsRecordSays() throws IOException {
        final Map<String, Entry> index = index();
        final List<String> drifted = new ArrayList<>();
        for (final String file : records()) {
            final Entry own = own(ADR.resolve(file));
            final Entry row = index.get(file);
            if (row != null && !row.equals(own)) {
                drifted.add(file + "\n  record: " + own + "\n  index:  " + row);
            }
        }
        assertThat(drifted).as("index rows that differ from their record; copy the record's text into the row").isEmpty();
    }

    private static List<String> records() throws IOException {
        try (Stream<Path> s = Files.list(ADR)) {
            return s.map(p -> p.getFileName().toString()).filter(n -> n.matches("\\d{4}-.+\\.md")).sorted().toList();
        }
    }

    /** The index's rows by the file each links; a file linked twice keeps the first row and fails the first test. */
    private static Map<String, Entry> index() throws IOException {
        final Map<String, Entry> rows = new TreeMap<>();
        final List<String> twice = new ArrayList<>();
        for (final String line : Files.readAllLines(ADR.resolve("README.md"))) {
            final Matcher m = ROW.matcher(line);
            if (!m.matches()) {
                continue;
            }
            final List<String> cells = cells(m.group(3));
            assertThat(cells).as("a row has title, date, deciders and status: " + line).hasSize(4);
            final Entry e = new Entry(cells.get(0), cells.get(1), cells.get(2), cells.get(3));
            if (rows.putIfAbsent(m.group(2), e) != null) {
                twice.add(m.group(2));
            }
        }
        assertThat(twice).as("records with more than one index row").isEmpty();
        return rows;
    }

    /** A record's title from its heading, and its date, status and deciders from the row under `| Date |`. */
    private static Entry own(final Path file) throws IOException {
        final List<String> lines = Files.readAllLines(file);
        final Matcher h = HEADING.matcher(lines.getFirst());
        assertThat(h.matches()).as(file + " starts with '# N. Title'").isTrue();
        final int header = lines.indexOf("| Date | Status | Decided by |");
        assertThat(header).as(file + " has the header table").isGreaterThanOrEqualTo(0);
        final List<String> cells = cells(lines.get(header + 2).substring(1));
        assertThat(cells).as(file + "'s header row").hasSize(3);
        return new Entry(h.group(2).strip(), cells.get(0), cells.get(2), cells.get(1));
    }

    /** The cells of a Markdown table row given without its leading `|`. */
    private static List<String> cells(final String rest) {
        String body = rest.strip();
        if (body.endsWith("|")) {
            body = body.substring(0, body.length() - 1);
        }
        final List<String> out = new ArrayList<>();
        for (final String c : body.split(" \\| ", -1)) {
            out.add(c.strip());
        }
        return out;
    }
}
