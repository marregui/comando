package dev.marregui.comando.ui.kit.theme;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every size of text is a step of the type scale and every inset is on the 4-point grid (record 102), as every colour
 * comes from the theme ({@code ColourRulesTest}): the ui module's sources, outside the theme and About's own design
 * (record 60), add or multiply no size of their own and set no inset of 5, 6, 7, 9, 10 or 11 pixels. Up to 3 pixels
 * nudge a line or an icon into place and are allowed.
 */
class TypeAndSpacingRulesTest {

    private static final Path SOURCES = Path.of("src/main/java/dev/marregui/comando/ui");
    private static final List<String> OWN_DESIGN = List.of("kit/theme/", "shell/About.java");
    /** A size moved by a number, on a line that makes a font: {@code getSize2D() - 1f}, {@code getSize2D() * 1.25f}. */
    private static final Pattern SIZE_OFFSET = Pattern.compile("getSize2D\\(\\)\\s*[-+*]\\s*[0-9]");
    /** A size given in points: {@code deriveFont(12f)}, {@code deriveFont(Font.BOLD, 12f)}. */
    private static final Pattern SIZE_LITERAL = Pattern.compile("deriveFont\\((?:Font\\.[A-Z_]+\\s*,\\s*)?[0-9.]+f\\)");
    private static final Pattern INSETS = Pattern.compile("(?:createEmptyBorder|new EmptyBorder|new Insets)\\(([^()]*)\\)");
    private static final Pattern NUMBER = Pattern.compile("(?<![\\w.])([0-9]+)(?![\\w.])");
    /** Insets off the grid on purpose, by file and the text of the call. */
    private static final Map<String, String> ALIGNED = Map.of(
            // a field's hint starts where the field's text does: FlatLaf's text margin is 6
            "dialogs/resource/ResourceDialog.java", "new Insets(0, 6, 2, 3)");

    @Test
    void everyTextSizeIsAStepOfTheScale() throws IOException {
        List<String> off = new ArrayList<>();
        scan((rel, n, line) -> {
            boolean makesAFont = Stream.of("deriveFont(", "setFont(", "new Font(", "TintTitle(").anyMatch(line::contains);
            if (makesAFont && SIZE_OFFSET.matcher(line).find() || SIZE_LITERAL.matcher(line).find()) {
                off.add(rel + ":" + n + " " + line.strip());
            }
        });
        assertThat(off).as("sizes off the type scale: use Theme.font or Theme.size").isEmpty();
    }

    @Test
    void everyInsetIsOnTheGrid() throws IOException {
        List<String> off = new ArrayList<>();
        scan((rel, n, line) -> {
            Matcher m = INSETS.matcher(line);
            while (m.find()) {
                if (m.group().equals(ALIGNED.get(rel))) {
                    continue;
                }
                Matcher num = NUMBER.matcher(m.group(1));
                while (num.find()) {
                    int v = Integer.parseInt(num.group(1));
                    if (v > 3 && v % 4 != 0) {
                        off.add(rel + ":" + n + " " + m.group());
                        break;
                    }
                }
            }
        });
        assertThat(off).as("insets off the grid: use Theme.SPACE_*").isEmpty();
    }

    @Test
    void theScaleAndTheGridHoldTheirSteps() {
        assertThat(Theme.size(13f, Theme.TextSize.SMALL)).isEqualTo(12f);
        assertThat(Theme.size(17f, Theme.TextSize.BODY)).as("a zoomed text's body is itself").isEqualTo(17f);
        assertThat(Theme.size(12f, Theme.TextSize.SMALL)).as("to the half point: Linux's 12 pt base").isEqualTo(11f);
        assertThat(Theme.HEADING_SCALE[0] * Theme.BASE_SIZE).isEqualTo(Theme.TextSize.TITLE.pt);
        assertThat(Theme.rowHeight(16)).isEqualTo(24);
        assertThat(Theme.rowHeight(17)).isEqualTo(28);
        for (int space : new int[]{Theme.SPACE_XS, Theme.SPACE_S, Theme.SPACE_M, Theme.SPACE_L, Theme.GAP, Theme.PAD}) {
            assertThat(space % 4).isZero();
        }
    }

    private interface Line {
        void check(String rel, int n, String line);
    }

    private static void scan(Line check) throws IOException {
        try (Stream<Path> files = Files.walk(SOURCES)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".java")).sorted().toList()) {
                String rel = SOURCES.relativize(f).toString().replace('\\', '/');
                if (OWN_DESIGN.stream().anyMatch(rel::startsWith)) {
                    continue;
                }
                List<String> lines = Files.readAllLines(f);
                for (int i = 0; i < lines.size(); i++) {
                    check.check(rel, i + 1, lines.get(i));
                }
            }
        }
    }
}
