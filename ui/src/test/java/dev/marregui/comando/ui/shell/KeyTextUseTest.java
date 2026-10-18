package dev.marregui.comando.ui.shell;

import dev.marregui.comando.ui.kit.desktop.Platform;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every key the ui module's code names in a string reaches the user through {@code KeyText}, so a desktop other than
 * macOS reads its own keys ("Ctrl+Shift+T", not "⌘⇧T"). A new string with a key written as macOS writes it fails here,
 * on any desktop, unless its statement passes it through {@code KeyText.of} or it is one of the constants below, each
 * read through {@code KeyText} where it is used.
 */
class KeyTextUseTest {

    private static final Path SOURCES = Path.of("src/main/java/dev/marregui/comando/ui");
    /** Constants holding keys, by file and the name they are declared under; each is translated where it is read. */
    private static final Map<String, List<String>> TRANSLATED_WHERE_READ = Map.of(
            "shell/Menus.java", List.of("TIPS =", "MENU_TIPS ="), // Menus.tip and the menu bar's tooltips
            "shell/Keys.java", List.of("PLACES ="), // Keys.markdown
            "widgets/transcript/render/Items.java", List.of("WAITING ="), // the tool card's status
            "kit/format/KeyText.java", List.of("MODIFIERS =")); // the translator's own glyphs

    @AfterEach
    void tearDown() {
        System.clearProperty(Platform.KEYS_PROPERTY);
    }

    @Test
    void everyKeyNamedInAStringGoesThroughKeyText() throws IOException {
        List<String> untranslated = new ArrayList<>();
        try (Stream<Path> files = Files.walk(SOURCES)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".java")).sorted().toList()) {
                String rel = SOURCES.relativize(f).toString().replace('\\', '/');
                Source src = Source.of(Files.readString(f));
                for (int[] lit : src.literals) {
                    String text = src.text.substring(lit[0], lit[1]);
                    if (text.chars().noneMatch(c -> c == '⌘' || c == '⌃' || c == '⌥')) {
                        continue;
                    }
                    String statement = src.statementAround(lit[0], lit[1]);
                    boolean declared = TRANSLATED_WHERE_READ.getOrDefault(rel, List.of()).stream().anyMatch(statement::contains);
                    if (!statement.contains("KeyText.of(") && !declared) {
                        untranslated.add(rel + ":" + src.lineOf(lit[0]) + " " + text.strip().lines().findFirst().orElse(""));
                    }
                }
            }
        }
        assertThat(untranslated).as("strings naming macOS keys that reach the user unchanged on other desktops").isEmpty();
    }

    @Test
    void theConstantsTranslatedWhereReadAreTranslatedThere() {
        System.setProperty(Platform.KEYS_PROPERTY, "linux");
        for (String id : Menus.TIPS.keySet()) {
            assertThat(Menus.tip(id)).as(id).doesNotContain("⌘", "⌃", "⌥");
        }
        assertThat(Keys.markdown(List.of())).doesNotContain("⌘", "⌃", "⌥").contains("Ctrl+Enter in the note");
    }

    @Test
    void theScannerFindsLiteralsAndSkipsComments() {
        Source s = Source.of("""
                // a "⌘T" in a comment
                /* and "⌘W" here */
                String a = "New (⌘T)"; String b = KeyText.of("Close (⌘W)");
                String c = \"""
                    ⌘⏎ in a block
                    \""";
                char d = '"';
                """);
        assertThat(s.literals).hasSize(3);
        assertThat(s.statementAround(s.literals.get(0)[0], s.literals.get(0)[1])).doesNotContain("KeyText");
        assertThat(s.statementAround(s.literals.get(1)[0], s.literals.get(1)[1])).contains("KeyText.of(");
        assertThat(s.text.substring(s.literals.get(2)[0], s.literals.get(2)[1])).contains("⌘⏎ in a block");
    }

    /**
     * A Java file ({@code text}), the same with its comments and the contents of its literals blanked out ({@code code}),
     * and the bounds of its string literals and text blocks.
     */
    private record Source(String text, String code, List<int[]> literals) {

        static Source of(String java) {
            StringBuilder code = new StringBuilder(java);
            List<int[]> literals = new ArrayList<>();
            int i = 0;
            int n = java.length();
            while (i < n) {
                char c = java.charAt(i);
                if (java.startsWith("//", i)) {
                    int end = java.indexOf('\n', i);
                    end = end < 0 ? n : end;
                    blank(code, i, end);
                    i = end;
                } else if (java.startsWith("/*", i)) {
                    int end = java.indexOf("*/", i + 2);
                    end = end < 0 ? n : end + 2;
                    blank(code, i, end);
                    i = end;
                } else if (java.startsWith("\"\"\"", i)) {
                    int end = java.indexOf("\"\"\"", i + 3);
                    end = end < 0 ? n : end;
                    literals.add(new int[]{i + 3, end});
                    i = Math.min(n, end + 3);
                } else if (c == '"') {
                    int j = i + 1;
                    while (j < n && java.charAt(j) != '"') {
                        j += java.charAt(j) == '\\' ? 2 : 1;
                    }
                    literals.add(new int[]{i + 1, Math.min(j, n)});
                    i = j + 1;
                } else if (c == '\'') {
                    int j = i + 1;
                    while (j < n && java.charAt(j) != '\'') {
                        j += java.charAt(j) == '\\' ? 2 : 1;
                    }
                    i = j + 1;
                } else {
                    i++;
                }
            }
            for (int[] lit : literals) {
                blank(code, lit[0], lit[1]);
            }
            return new Source(java, code.toString(), literals);
        }

        private static void blank(StringBuilder code, int from, int to) {
            for (int k = from; k < to; k++) {
                if (code.charAt(k) != '\n') {
                    code.setCharAt(k, ' ');
                }
            }
        }

        /** The code of the statement around a literal: from the last {@code ; { }} before it to the first {@code ;} after it. */
        String statementAround(int from, int to) {
            int start = from;
            while (start > 0 && ";{}".indexOf(code.charAt(start - 1)) < 0) {
                start--;
            }
            int end = code.indexOf(';', to);
            return code.substring(start, end < 0 ? code.length() : end);
        }

        int lineOf(int at) {
            int line = 1;
            for (int k = 0; k < at; k++) {
                if (text.charAt(k) == '\n') {
                    line++;
                }
            }
            return line;
        }
    }
}
