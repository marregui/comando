import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * What the test tasks ran, per module and task, from the JUnit XML Gradle writes: tests, failures, errors and skips,
 * and the name of every test skipped. Printed, and appended to the job summary when {@code GITHUB_STEP_SUMMARY} names
 * one. Skips do not fail the step: the keychain test is opt-in, and the PostgreSQL test needs Docker, which macOS
 * runners lack; the list shows which ran where.
 *
 * <pre>
 *   java ci/TestReport.java
 * </pre>
 */
public final class TestReport {

    private static final Pattern RESULTS = Pattern.compile("/build/test-results/(test|uiTest)/[^/]+\\.xml$");
    private static final Pattern SUITE = Pattern.compile(
            "<testsuite name=\"([^\"]*)\" tests=\"(\\d+)\" skipped=\"(\\d+)\" failures=\"(\\d+)\" errors=\"(\\d+)\"");
    private static final Pattern CASE = Pattern.compile("<testcase name=\"([^\"]*)\" classname=\"([^\"]*)\"[^>]*>\\s*<skipped");

    public static void main(final String[] args) throws IOException {
        final Map<String, long[]> modules = new TreeMap<>();
        final List<String> skipped = new ArrayList<>();
        final Path root = Path.of("");
        List<Path> reports;
        try (Stream<Path> files = Files.walk(root, 6)) {
            reports = files.filter(p -> RESULTS.matcher("/" + p.toString().replace('\\', '/')).find()).sorted().toList();
        }
        for (final Path report : reports) {
            final String xml = Files.readString(report, StandardCharsets.UTF_8);
            final Matcher suite = SUITE.matcher(xml);
            if (!suite.find()) {
                continue;
            }
            final Path relative = root.toAbsolutePath().relativize(report.toAbsolutePath());
            final String task = relative.getName(3).toString();
            final String module = relative.getName(0) + (task.equals("test") ? "" : " (" + task + ")");
            final long[] counts = modules.computeIfAbsent(module, m -> new long[4]);
            for (int i = 0; i < 4; i++) {
                counts[i] += Long.parseLong(suite.group(i + 2));
            }
            final Matcher c = CASE.matcher(xml);
            while (c.find()) {
                skipped.add(c.group(2) + " > " + c.group(1));
            }
        }
        final StringBuilder md = new StringBuilder("### Tests\n\n| Module | Tests | Skipped | Failures | Errors |\n"
                + "|---|---:|---:|---:|---:|\n");
        final long[] total = new long[4];
        for (final Map.Entry<String, long[]> e : modules.entrySet()) {
            final long[] v = e.getValue();
            md.append("| ").append(e.getKey()).append(" | ").append(v[0]).append(" | ").append(v[1]).append(" | ")
                    .append(v[2]).append(" | ").append(v[3]).append(" |\n");
            for (int i = 0; i < 4; i++) {
                total[i] += v[i];
            }
        }
        md.append("| **all** | **").append(total[0]).append("** | ").append(total[1]).append(" | ").append(total[2])
                .append(" | ").append(total[3]).append(" |\n");
        if (!skipped.isEmpty()) {
            md.append("\nSkipped:\n\n");
            for (final String s : skipped) {
                md.append("- `").append(s).append("`\n");
            }
        }
        System.out.print(md);
        final String summary = System.getenv("GITHUB_STEP_SUMMARY");
        if (summary != null && !summary.isEmpty()) {
            Files.writeString(Path.of(summary), md + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND);
        }
        if (modules.isEmpty()) {
            System.out.println("no test results found under */build/test-results/{test,uiTest}/");
            System.exit(1);
        }
    }

    private TestReport() {
    }
}
