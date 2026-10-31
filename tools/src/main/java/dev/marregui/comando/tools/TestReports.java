package dev.marregui.comando.tools;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import dev.marregui.comando.core.view.Clip;
import dev.marregui.comando.core.view.ViewSpec;

/**
 * JUnit XML reports written by a build: a tree of suites and tests, failed tests first, each failure
 * linked to the test source at the line its stack trace names.
 *
 * @param tree     root labelled with the totals; {@link ViewSpec.TreeNode#NONE} when no report was written
 * @param failures the message and stack of each failed test, by {@code Class.method}
 */
record TestReports(ViewSpec.TreeNode tree, Map<String, String> failures) {

    static final TestReports NONE = new TestReports(ViewSpec.TreeNode.NONE, Map.of());
    /** Passed tests are listed only up to this many in all; failures always are. */
    static final int MAX_PASSED = 2000;
    private static final int MAX_STACK_LINES = 60;

    private record Case(String name, String className, double seconds, String failure, String message, boolean skipped) {
    }

    private record Suite(String name, List<Case> cases, double seconds) {
        long failed() {
            return cases.stream().filter(c -> c.failure() != null).count();
        }

        long skipped() {
            return cases.stream().filter(Case::skipped).count();
        }
    }

    /** The reports under {@code root} written since {@code sinceMillis}, a second of file-time rounding allowed. */
    static TestReports collect(final Path root, final long sinceMillis) {
        final List<Path> files = new ArrayList<>(walk(root, 8, p -> p.getFileName().toString().startsWith("TEST-") && p.toString().endsWith(".xml")
                && p.toString().contains("test-results") && modifiedSince(p, sinceMillis)));
        files.sort(null);
        if (files.isEmpty()) {
            return NONE;
        }
        final List<Suite> suites = new ArrayList<>();
        for (final Path f : files) {
            final Suite s = parse(f);
            if (s != null) {
                suites.add(s);
            }
        }
        return tree(suites, sources(root, suites));
    }

    private static boolean modifiedSince(final Path p, final long since) {
        try {
            return Files.getLastModifiedTime(p).toMillis() >= since - 1000;
        } catch (IOException | UncheckedIOException e) {
            return false;
        }
    }

    private static Suite parse(final Path file) {
        try {
            final DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            final DocumentBuilder b = f.newDocumentBuilder();
            final Document doc = b.parse(file.toFile());
            final Element suite = doc.getDocumentElement();
            final List<Case> cases = new ArrayList<>();
            final NodeList list = suite.getElementsByTagName("testcase");
            for (int i = 0; i < list.getLength(); i++) {
                final Element c = (Element) list.item(i);
                Element failure = first(c, "failure");
                if (failure == null) {
                    failure = first(c, "error");
                }
                cases.add(new Case(c.getAttribute("name"), c.getAttribute("classname"), seconds(c.getAttribute("time")),
                        failure == null ? null : failure.getTextContent(), failure == null ? null : failure.getAttribute("message"),
                        first(c, "skipped") != null));
            }
            return new Suite(suite.getAttribute("name"), cases, seconds(suite.getAttribute("time")));
        } catch (final Exception e) {
            return null; // a report being written while the build runs; the next run reads it
        }
    }

    private static Element first(final Element parent, final String tag) {
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element e && e.getTagName().equals(tag)) {
                return e;
            }
        }
        return null;
    }

    private static double seconds(final String s) {
        try {
            return s.isEmpty() ? 0 : Double.parseDouble(s);
        } catch (final NumberFormatException e) {
            return 0;
        }
    }

    /** Test sources by fully qualified class name, for the suites that failed. */
    private static Map<String, Path> sources(final Path root, final List<Suite> suites) {
        final Map<String, Path> out = new HashMap<>();
        if (suites.stream().noneMatch(s -> s.failed() > 0)) {
            return out;
        }
        for (final Path p : walk(root, 16, p -> p.toString().endsWith(".java") && p.toString().contains("/src/test/java/"))) {
            final String s = p.toString();
            final String fqcn = s.substring(s.indexOf("/src/test/java/") + 15, s.length() - 5).replace('/', '.');
            out.putIfAbsent(fqcn, p);
        }
        return out;
    }

    /**
     * The regular files under {@code root} that {@code keep} accepts. A directory that cannot be read is
     * skipped, not fatal: one unreadable folder in a checkout must not hide every report.
     */
    static List<Path> walk(final Path root, final int depth, final Predicate<Path> keep) {
        final List<Path> out = new ArrayList<>();
        try {
            Files.walkFileTree(root, EnumSet.noneOf(FileVisitOption.class), depth, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(final Path file, final BasicFileAttributes attrs) {
                    if (attrs.isRegularFile() && keep.test(file)) {
                        out.add(file);
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(final Path file, final IOException e) {
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException | UncheckedIOException e) {
            // what was found stands
        }
        return out;
    }

    private static TestReports tree(final List<Suite> suites, final Map<String, Path> sources) {
        final long total = suites.stream().mapToLong(s -> s.cases().size()).sum();
        final long failed = suites.stream().mapToLong(Suite::failed).sum();
        final long skipped = suites.stream().mapToLong(Suite::skipped).sum();
        final boolean listPassed = total - failed <= MAX_PASSED;
        final Map<String, String> failures = new LinkedHashMap<>();
        final List<Suite> ordered = new ArrayList<>(suites);
        ordered.sort(Comparator.comparingLong(Suite::failed).reversed().thenComparing(Suite::name));
        final List<ViewSpec.TreeNode> suiteNodes = new ArrayList<>();
        for (final Suite s : ordered) {
            final List<ViewSpec.TreeNode> cases = new ArrayList<>();
            for (final Case c : s.cases()) {
                if (c.failure() != null) {
                    final String key = simple(c.className()) + "." + c.name();
                    failures.put(key, c.className() + "." + c.name() + "\n" + clip(c.failure()));
                    cases.add(new ViewSpec.TreeNode("✗ " + c.name(), firstLine(c.message() == null || c.message().isEmpty()
                            ? c.failure() : c.message()), link(c, sources), List.of()));
                }
            }
            if (listPassed) {
                for (final Case c : s.cases()) {
                    if (c.failure() == null) {
                        cases.add(new ViewSpec.TreeNode((c.skipped() ? "○ " : "✓ ") + c.name(), c.skipped() ? "skipped" : seconds(c.seconds()),
                                List.of()));
                    }
                }
            }
            final String detail = s.cases().size() + " tests" + (s.failed() > 0 ? " · " + s.failed() + " failed" : "")
                    + (s.skipped() > 0 ? " · " + s.skipped() + " skipped" : "") + " · " + seconds(s.seconds());
            suiteNodes.add(new ViewSpec.TreeNode(simple(s.name()), detail, List.copyOf(cases)));
        }
        final String label = failed > 0 ? failed + " of " + total + " tests failed" : total + " tests passed";
        final String detail = (skipped > 0 ? skipped + " skipped · " : "") + suites.size() + " classes";
        return new TestReports(new ViewSpec.TreeNode(label, detail, suiteNodes), failures);
    }

    private static final Pattern FRAME = Pattern.compile("at ([\\w.$]+)\\.[\\w$<>]+\\(([\\w$]+\\.java):(\\d+)\\)");

    /**
     * The link to the test source at the innermost frame of the test's own class, or at no line when no frame names it;
     * empty when the source is not found.
     */
    private static String link(final Case c, final Map<String, Path> sources) {
        final Path src = sources.get(c.className());
        if (src == null) {
            return "";
        }
        int line = 0;
        final Matcher m = FRAME.matcher(c.failure());
        while (m.find()) {
            if (m.group(1).equals(c.className()) || m.group(1).startsWith(c.className() + "$")) {
                line = Integer.parseInt(m.group(3));
                break;
            }
        }
        return ViewSpec.TreeNode.fileLink(src.toString(), line);
    }

    private static String simple(final String className) {
        final int dot = className.lastIndexOf('.');
        return dot < 0 ? className : className.substring(dot + 1);
    }

    private static String firstLine(final String s) {
        String t = s.strip();
        final int nl = t.indexOf('\n');
        t = nl < 0 ? t : t.substring(0, nl);
        return Clip.keepStart(t, 160);
    }

    private static String clip(final String s) {
        final String[] lines = s.strip().split("\n");
        if (lines.length <= MAX_STACK_LINES) {
            return s.strip();
        }
        return String.join("\n", List.of(lines).subList(0, MAX_STACK_LINES)) + "\n… " + (lines.length - MAX_STACK_LINES) + " more lines";
    }

    private static String seconds(final double s) {
        return String.format(Locale.ROOT, s < 10 ? "%.2f s" : "%.0f s", s);
    }
}
