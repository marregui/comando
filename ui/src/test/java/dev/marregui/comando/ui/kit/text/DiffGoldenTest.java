package dev.marregui.comando.ui.kit.text;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// [SplitDiff#chunks] and [Diffs#unified] give, for each frozen case under `diff-golden/`, the differences and the
/// unified text java-diff-utils 4.18 gave for it: 110 seeded random pairs of small-alphabet line sequences and 29
/// edges (empty and identical sides, trailing newlines, changes at the ends, hunks exactly as far apart as two
/// contexts and one line more).
/// - The differences are the same ones, and they turn the first text's lines into the second's.
/// - The unified text is the same, line for line, headers and hunk headers included.
class DiffGoldenTest {

    /// The files of cases under `diff-golden/`, each a run of cases.
    static final List<String> FILES = List.of("random.golden", "edges.golden");

    /// Every golden case diffs to its frozen differences and unified text, and its differences turn before into after.
    @Test
    void everyGoldenCaseDiffsAsFrozen() {
        final List<Case> cases = cases();
        assertThat(cases).hasSize(139);
        for (final Case c : cases) {
            final List<String> a = SplitDiff.lines(c.before());
            final List<String> b = SplitDiff.lines(c.after());
            assertThat(SplitDiff.chunks(a, b)).as(c.name()).isEqualTo(c.chunks());
            assertThat(apply(a, b, c.chunks())).as(c.name() + " applied").isEqualTo(b);
            assertThat(Diffs.unified(c.path(), c.before(), c.after())).as(c.name()).isEqualTo(c.unified());
        }
    }

    /// The golden cases of every file, in order.
    static List<Case> cases() {
        final List<Case> out = new ArrayList<>();
        for (final String file : FILES) {
            final String[] lines = read("diff-golden/" + file).split("\n", -1);
            int at = 0;
            while (at < lines.length && lines[at].startsWith("case ")) {
                final String name = field(lines[at++], "case");
                final String path = field(lines[at++], "path");
                final int beforeLines = Integer.parseInt(field(lines[at++], "before"));
                final String before = String.join("\n", Arrays.asList(lines).subList(at, at + beforeLines));
                at += beforeLines;
                final int afterLines = Integer.parseInt(field(lines[at++], "after"));
                final String after = String.join("\n", Arrays.asList(lines).subList(at, at + afterLines));
                at += afterLines;
                final int chunkCount = Integer.parseInt(field(lines[at++], "chunks"));
                final List<SplitDiff.Chunk> chunks = new ArrayList<>();
                for (int i = 0; i < chunkCount; i++) {
                    final int[] r = Arrays.stream(lines[at++].split(" ")).mapToInt(Integer::parseInt).toArray();
                    chunks.add(new SplitDiff.Chunk(r[0], r[1], r[2], r[3]));
                }
                final int unifiedLines = Integer.parseInt(field(lines[at++], "unified"));
                final List<String> unified = List.copyOf(Arrays.asList(lines).subList(at, at + unifiedLines));
                at += unifiedLines;
                out.add(new Case(file + ": " + name, path, before, after, chunks, unified));
            }
            assertThat(lines[at]).as(file + " ends after its last case").isEmpty();
            assertThat(at).isEqualTo(lines.length - 1);
        }
        return out;
    }

    /// The lines `chunks` make of `a`, each chunk's lines taken from `b`, each chunk checked to land where it says on
    /// `b`.
    static List<String> apply(final List<String> a, final List<String> b, final List<SplitDiff.Chunk> chunks) {
        final List<String> out = new ArrayList<>();
        int kept = 0;
        for (final SplitDiff.Chunk c : chunks) {
            out.addAll(a.subList(kept, c.leftStart()));
            assertThat(out).as("lines before a chunk").hasSize(c.rightStart());
            out.addAll(b.subList(c.rightStart(), c.rightEnd()));
            kept = c.leftEnd();
        }
        out.addAll(a.subList(kept, a.size()));
        return out;
    }

    private static String field(final String line, final String key) {
        assertThat(line).startsWith(key + " ");
        return line.substring(key.length() + 1);
    }

    private static String read(final String resource) {
        try (final InputStream in = DiffGoldenTest.class.getResourceAsStream(resource)) {
            assertThat(in).as(resource).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /// A frozen case: two texts, the path their diff names (empty for none), and what the library gave for them.
    record Case(
            String name,
            String path,
            String before,
            String after,
            List<SplitDiff.Chunk> chunks,
            List<String> unified) {
    }
}
