package dev.marregui.comando.core.json;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// Documents frozen with jackson-databind 3.2.4's compact and pretty output, read and written again without Jackson:
/// `inputs.golden`, `compact.golden` and `pretty.golden` hold one record per document, in the same order, separated
/// by a line holding only the record separator U+001E.
class JsonGoldenTest {

    /// Fewer records than this means the files were truncated or split wrongly.
    private static final int MIN_DOCUMENTS = 200;
    private static final String GOLDEN = "golden/";
    private static final String SEPARATOR = "\n\u001E\n";
    private static final int REPORTED_FAILURES = 10;

    /// Every frozen document reads to a tree whose compact text, pretty text and UTF-8 bytes are Jackson's, from a
    /// string, bytes, a stream and a reader alike; the compact and pretty texts read back to the same compact text.
    /// The trees themselves need not be equal: a double beyond range writes as the string "Infinity", as Jackson's.
    @Test
    void frozenDocumentsWriteAsJacksonWrote() throws IOException {
        final String[] inputs = records("inputs.golden");
        final String[] compact = records("compact.golden");
        // the files hold line feeds; the pretty printer ends lines with the system line separator
        final String[] pretty = Arrays.stream(records("pretty.golden"))
                .map(record -> record.replace("\n", System.lineSeparator())).toArray(String[]::new);
        assertThat(inputs.length).isGreaterThanOrEqualTo(MIN_DOCUMENTS);
        assertThat(compact).hasSameSizeAs(inputs);
        assertThat(pretty).hasSameSizeAs(inputs);
        final List<String> failures = new ArrayList<>();
        for (int i = 0; i < inputs.length; i++) {
            final String input = inputs[i];
            final byte[] bytes = input.getBytes(StandardCharsets.UTF_8);
            final JsonNode tree = Json.readTree(input);
            check(failures, i, "compact", compact[i], Json.write(tree));
            check(failures, i, "pretty", pretty[i], Json.pretty(tree));
            check(failures, i, "toString", compact[i], tree.toString());
            check(failures, i, "toPrettyString", pretty[i], tree.toPrettyString());
            check(failures, i, "bytes", compact[i], new String(Json.writeBytes(tree), StandardCharsets.UTF_8));
            check(failures, i, "from bytes", compact[i], Json.write(Json.readTree(bytes)));
            check(failures, i, "from stream", compact[i], Json.write(Json.readTree(new ByteArrayInputStream(bytes))));
            check(failures, i, "from reader", compact[i], Json.write(Json.readTree(new StringReader(input))));
            check(failures, i, "deepCopy", compact[i], Json.write(tree.deepCopy()));
            check(failures, i, "rewritten", compact[i], Json.write(Json.readTree(compact[i])));
            check(failures, i, "from pretty", compact[i], Json.write(Json.readTree(pretty[i])));
        }
        System.out.println("JsonGoldenTest documents=" + inputs.length + " failures=" + failures.size());
        assertThat(failures.subList(0, Math.min(failures.size(), REPORTED_FAILURES))).isEmpty();
    }

    private static void check(
            final List<String> failures, final int document, final String what, final String expected,
            final String actual) {
        if (!expected.equals(actual)) {
            failures.add("document " + document + " " + what + ": expected " + expected + " but was " + actual);
        }
    }

    private static String[] records(final String name) throws IOException {
        try (InputStream in = JsonGoldenTest.class.getResourceAsStream(GOLDEN + name)) {
            if (in == null) {
                throw new UncheckedIOException(new IOException("golden resource not found [name=" + name + "]"));
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).split(SEPARATOR, -1);
        }
    }
}
