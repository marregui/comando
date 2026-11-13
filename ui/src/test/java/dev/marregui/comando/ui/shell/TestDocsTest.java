package dev.marregui.comando.ui.shell;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// G-11.7, checked over every module's tests: each `*Test.java` under a module's `src/test/java` carries a `///`
/// comment right above its top-level type and above every `@Test`, `@ParameterizedTest`, `@RepeatedTest` and
/// `@TestFactory` method, annotations allowed between; a `/** */` comment there is a defect. A module is a folder of
/// the repository with a `src/test/java`, so a new module is covered without a change here.
/// - the repository's tests have no miss; a failure lists each as `path:line: what is missing`
/// - the checker accepts the shapes the rule allows and reports each shape it forbids
class TestDocsTest {

    // Gradle runs a module's tests in the module's folder
    private static final Path ROOT = Path.of("..");
    private static final Pattern TEST_ANNOTATION =
            Pattern.compile("@(?:[\\w.]+\\.)?(?:Test|ParameterizedTest|RepeatedTest|TestFactory)\\b.*");
    // a top-level type starts in the first column; a nested one is indented
    private static final Pattern TOP_LEVEL_TYPE = Pattern.compile(
            "(?:(?:public|abstract|final|sealed|non-sealed|strictfp)\\s+)*(?:class|record|interface|enum|@interface)"
                    + "\\s+\\w+.*");

    /// Every `*Test.java` of every module documents its class and test methods with `///`; the failure names each
    /// miss on its own line.
    @Test
    void everyTestClassAndTestMethodIsDocumentedWithMarkdownComments() throws IOException {
        final List<Path> files = testFiles();
        assertThat(files).as("this test's own file, read from the repository's root")
                .contains(Path.of("ui/src/test/java/dev/marregui/comando/ui/shell/TestDocsTest.java"));
        final List<String> misses = new ArrayList<>();
        for (final Path file : files) {
            misses.addAll(defects(file.toString(), Files.readAllLines(ROOT.resolve(file))));
        }
        assertThat(misses).withFailMessage("%d misses of G-11.7, a /// comment on each test:%n%s", misses.size(),
                String.join(System.lineSeparator(), misses)).isEmpty();
    }

    /// A `///` comment counts with annotations between it and the declaration, a multi-line one among them, and
    /// text-block lines are not read as code; a missing comment, a `/** */` one, a blank line between, a test after
    /// an annotation whose text block spans lines, and a file with no top-level type are each reported at their line.
    @Test
    void theCheckerTellsAMarkdownCommentFromAMissingOrJavadocOne() {
        final List<String> documented = List.of(
                "package p;",
                "",
                "/// Proves one and two.",
                "@ExtendWith(OnEdt.class)",
                "final class GoodTest {",
                "",
                "    /// One.",
                "    @ParameterizedTest",
                "    @CsvSource({",
                "            \"a, (b\",",
                "            \"c, d\"",
                "    })",
                "    void one(final String a, final String b) {",
                "        final String s = \"\"\"",
                "                @Test",
                "                \"\"\";",
                "    }",
                "",
                "    /// Two.",
                "    @Test @Timeout(1)",
                "    void two() {",
                "    }",
                "}");
        assertThat(defects("GoodTest.java", documented)).isEmpty();

        final List<String> undocumented = List.of(
                "package p;",
                "",
                "/** Javadoc, not Markdown. */",
                "class BadTest {",
                "",
                "    @Test",
                "    void none() {",
                "    }",
                "",
                "    /**",
                "     * Javadoc.",
                "     */",
                "    @RepeatedTest(2)",
                "    void javadoc() {",
                "    }",
                "",
                "    /// Parted from its method by a blank line.",
                "",
                "    @TestFactory",
                "    Stream<DynamicTest> parted() {",
                "        return Stream.empty();",
                "    }",
                "",
                "    /// A table in a text block.",
                "    @ParameterizedTest",
                "    @CsvSource(delimiter = '|', textBlock = \"\"\"",
                "            a (b | 1",
                "            c    | 2",
                "            \"\"\")",
                "    void table(final String s, final int n) {",
                "    }",
                "",
                "    @Test",
                "    void afterTheTable() {",
                "    }",
                "}");
        assertThat(defects("BadTest.java", undocumented)).containsExactly(
                "BadTest.java:4: the top-level type has a /** */ comment, needs ///",
                "BadTest.java:6: the test method has no /// comment",
                "BadTest.java:13: the test method has a /** */ comment, needs ///",
                "BadTest.java:19: the test method has no /// comment",
                "BadTest.java:33: the test method has no /// comment");
        assertThat(defects("NoTypeTest.java", List.of("package p;")))
                .containsExactly("NoTypeTest.java:1: no top-level type");
    }

    /** Every `*Test.java` under a module's `src/test/java`, relative to the repository's root, in path order. */
    private static List<Path> testFiles() throws IOException {
        final List<Path> sources;
        try (final Stream<Path> modules = Files.list(ROOT)) {
            sources = modules.map(m -> m.resolve("src/test/java")).filter(Files::isDirectory).sorted().toList();
        }
        final List<Path> files = new ArrayList<>();
        for (final Path source : sources) {
            try (final Stream<Path> walk = Files.walk(source)) {
                walk.filter(p -> p.getFileName().toString().endsWith("Test.java")).filter(Files::isRegularFile)
                        .map(ROOT::relativize).sorted().forEach(files::add);
            }
        }
        return files;
    }

    /**
     * The misses of one test file, in line order. Reads lines, not a syntax tree: a comment or an annotation is told
     * by how its line starts, which the build's formatting keeps; an annotation's arguments and a text block end
     * where their parentheses and quotes close, however many lines they span.
     */
    private static List<String> defects(final String path, final List<String> lines) {
        final List<String> misses = new ArrayList<>();
        Doc doc = Doc.NONE;
        boolean inComment = false;
        boolean javadoc = false;
        boolean typeSeen = false;
        // an annotation's arguments or a text block still open at the end of the line before
        Code open = Code.CLOSED;
        for (int i = 0; i < lines.size(); i++) {
            final String raw = lines.get(i);
            final String line = raw.strip();
            if (inComment) {
                if (line.contains("*/")) {
                    inComment = false;
                    doc = javadoc ? Doc.JAVADOC : Doc.NONE;
                }
            } else if (open.isOpen()) {
                final Code next = scan(line, open);
                open = open.depth() > 0 ? next : new Code(0, next.inTextBlock());
            } else if (line.startsWith("///")) {
                doc = Doc.MARKDOWN;
            } else if (line.startsWith("/*")) {
                javadoc = line.startsWith("/**") && !line.startsWith("/**/");
                inComment = !line.substring(2).contains("*/");
                doc = !inComment && javadoc ? Doc.JAVADOC : Doc.NONE;
            } else if (!typeSeen && TOP_LEVEL_TYPE.matcher(raw).matches()) {
                typeSeen = true;
                missing(misses, path, i, "the top-level type", doc);
                doc = Doc.NONE;
            } else if (line.startsWith("@")) {
                if (TEST_ANNOTATION.matcher(line).matches()) {
                    missing(misses, path, i, "the test method", doc);
                }
                open = scan(line, Code.CLOSED);
            } else {
                // a statement's parentheses are not followed across lines, only a text block it opens
                open = new Code(0, scan(line, Code.CLOSED).inTextBlock());
                doc = Doc.NONE;
            }
        }
        if (!typeSeen) {
            misses.add(path + ":1: no top-level type");
        }
        return misses;
    }

    private static void missing(final List<String> misses, final String path, final int index, final String what,
            final Doc doc) {
        if (doc != Doc.MARKDOWN) {
            misses.add(path + ":" + (index + 1) + ": " + what
                    + (doc == Doc.JAVADOC ? " has a /** */ comment, needs ///" : " has no /// comment"));
        }
    }

    /**
     * What is still open after one more line: the parentheses opened and not closed, never below zero, and whether a
     * text block is open. String and character literals, text blocks and a trailing `//` comment are left out.
     */
    private static Code scan(final String line, final Code before) {
        int depth = before.depth();
        boolean inTextBlock = before.inTextBlock();
        char quote = 0;
        for (int i = 0; i < line.length(); i++) {
            final char c = line.charAt(i);
            if (inTextBlock || quote != 0) {
                if (c == '\\') {
                    i++;
                } else if (inTextBlock && line.startsWith("\"\"\"", i)) {
                    inTextBlock = false;
                    i += 2;
                } else if (c == quote) {
                    quote = 0;
                }
            } else if (line.startsWith("\"\"\"", i)) {
                inTextBlock = true;
                i += 2;
            } else if (line.startsWith("//", i)) {
                break;
            } else if (c == '"' || c == '\'') {
                quote = c;
            } else if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth = Math.max(0, depth - 1);
            }
        }
        return new Code(depth, inTextBlock);
    }

    /** What sits right above a declaration: no doc comment, a Markdown one or a Javadoc one. */
    private enum Doc {
        NONE, MARKDOWN, JAVADOC
    }

    /** The parentheses and the text block a line leaves open for the next one. */
    private record Code(int depth, boolean inTextBlock) {

        static final Code CLOSED = new Code(0, false);

        boolean isOpen() {
            return depth > 0 || inTextBlock;
        }
    }
}
