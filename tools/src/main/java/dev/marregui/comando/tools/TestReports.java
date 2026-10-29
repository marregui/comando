package dev.marregui.comando.tools;

import dev.marregui.comando.core.view.Clip;
import dev.marregui.comando.core.view.ViewSpec;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
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
    static TestReports collect(Path root, long sinceMillis) {
        List<Path> files = new ArrayList<>(walk(root, 8, p -> p.getFileName().toString().startsWith("TEST-") && p.toString().endsWith(".xml")
                && p.toString().contains("test-results") && modifiedSince(p, sinceMillis)));
        files.sort(null);
        if (files.isEmpty()) {
            return NONE;
        }
        List<Suite> suites = new ArrayList<>();
        for (Path f : files) {
            Suite s = parse(f);
            if (s != null) {
                suites.add(s);
            }
        }
        return tree(suites, sources(root, suites));
    }

    private static boolean modifiedSince(Path p, long since) {
        try {
            return Files.getLastModifiedTime(p).toMillis() >= since - 1000;
        } catch (IOException | UncheckedIOException e) {
            return false;
        }
    }

    private static Suite parse(Path file) {
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            DocumentBuilder b = f.newDocumentBuilder();
            Document doc = b.parse(file.toFile());
            Element suite = doc.getDocumentElement();
            List<Case> cases = new ArrayList<>();
            NodeList list = suite.getElementsByTagName("testcase");
            for (int i = 0; i < list.getLength(); i++) {
                Element c = (Element) list.item(i);
                Element failure = first(c, "failure");
                if (failure == null) {
                    failure = first(c, "error");
                }
                cases.add(new Case(c.getAttribute("name"), c.getAttribute("classname"), seconds(c.getAttribute("time")),
                        failure == null ? null : failure.getTextContent(), failure == null ? null : failure.getAttribute("message"),
                        first(c, "skipped") != null));
            }
            return new Suite(suite.getAttribute("name"), cases, seconds(suite.getAttribute("time")));
        } catch (Exception e) {
            return null; // a report being written while the build runs; the next run reads it
        }
    }

    private static Element first(Element parent, String tag) {
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element e && e.getTagName().equals(tag)) {
                return e;
            }
        }
        return null;
    }

    private static double seconds(String s) {
        try {
            return s.isEmpty() ? 0 : Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** Test sources by fully qualified class name, for the suites that failed. */
    private static Map<String, Path> sources(Path root, List<Suite> suites) {
        Map<String, Path> out = new HashMap<>();
        if (suites.stream().noneMatch(s -> s.failed() > 0)) {
            return out;
        }
        for (Path p : walk(root, 16, p -> p.toString().endsWith(".java") && p.toString().contains("/src/test/java/"))) {
            String s = p.toString();
            String fqcn = s.substring(s.indexOf("/src/test/java/") + 15, s.length() - 5).replace('/', '.');
            out.putIfAbsent(fqcn, p);
        }
        return out;
    }

    /**
     * The regular files under {@code root} that {@code keep} accepts. A directory that cannot be read is
     * skipped, not fatal: one unreadable folder in a checkout must not hide every report.
     */
    static List<Path> walk(Path root, int depth, Predicate<Path> keep) {
        List<Path> out = new ArrayList<>();
        try {
            Files.walkFileTree(root, EnumSet.noneOf(FileVisitOption.class), depth, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (attrs.isRegularFile() && keep.test(file)) {
                        out.add(file);
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException e) {
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException | UncheckedIOException e) {
            // what was found stands
        }
        return out;
    }

    private static TestReports tree(List<Suite> suites, Map<String, Path> sources) {
        long total = suites.stream().mapToLong(s -> s.cases().size()).sum();
        long failed = suites.stream().mapToLong(Suite::failed).sum();
        long skipped = suites.stream().mapToLong(Suite::skipped).sum();
        boolean listPassed = total - failed <= MAX_PASSED;
        Map<String, String> failures = new LinkedHashMap<>();
        List<Suite> ordered = new ArrayList<>(suites);
        ordered.sort(Comparator.comparingLong(Suite::failed).reversed().thenComparing(Suite::name));
        List<ViewSpec.TreeNode> suiteNodes = new ArrayList<>();
        for (Suite s : ordered) {
            List<ViewSpec.TreeNode> cases = new ArrayList<>();
            for (Case c : s.cases()) {
                if (c.failure() != null) {
                    String key = simple(c.className()) + "." + c.name();
                    failures.put(key, c.className() + "." + c.name() + "\n" + clip(c.failure()));
                    cases.add(new ViewSpec.TreeNode("✗ " + c.name(), firstLine(c.message() == null || c.message().isEmpty()
                            ? c.failure() : c.message()), link(c, sources), List.of()));
                }
            }
            if (listPassed) {
                for (Case c : s.cases()) {
                    if (c.failure() == null) {
                        cases.add(new ViewSpec.TreeNode((c.skipped() ? "○ " : "✓ ") + c.name(), c.skipped() ? "skipped" : seconds(c.seconds()),
                                List.of()));
                    }
                }
            }
            String detail = s.cases().size() + " tests" + (s.failed() > 0 ? " · " + s.failed() + " failed" : "")
                    + (s.skipped() > 0 ? " · " + s.skipped() + " skipped" : "") + " · " + seconds(s.seconds());
            suiteNodes.add(new ViewSpec.TreeNode(simple(s.name()), detail, List.copyOf(cases)));
        }
        String label = failed > 0 ? failed + " of " + total + " tests failed" : total + " tests passed";
        String detail = (skipped > 0 ? skipped + " skipped · " : "") + suites.size() + " classes";
        return new TestReports(new ViewSpec.TreeNode(label, detail, suiteNodes), failures);
    }

    private static final Pattern FRAME = Pattern.compile("at ([\\w.$]+)\\.[\\w$<>]+\\(([\\w$]+\\.java):(\\d+)\\)");

    /**
     * The link to the test source at the innermost frame of the test's own class, or at no line when no frame names it;
     * empty when the source is not found.
     */
    private static String link(Case c, Map<String, Path> sources) {
        Path src = sources.get(c.className());
        if (src == null) {
            return "";
        }
        int line = 0;
        Matcher m = FRAME.matcher(c.failure());
        while (m.find()) {
            if (m.group(1).equals(c.className()) || m.group(1).startsWith(c.className() + "$")) {
                line = Integer.parseInt(m.group(3));
                break;
            }
        }
        return ViewSpec.TreeNode.fileLink(src.toString(), line);
    }

    private static String simple(String className) {
        int dot = className.lastIndexOf('.');
        return dot < 0 ? className : className.substring(dot + 1);
    }

    private static String firstLine(String s) {
        String t = s.strip();
        int nl = t.indexOf('\n');
        t = nl < 0 ? t : t.substring(0, nl);
        return Clip.keepStart(t, 160);
    }

    private static String clip(String s) {
        String[] lines = s.strip().split("\n");
        if (lines.length <= MAX_STACK_LINES) {
            return s.strip();
        }
        return String.join("\n", List.of(lines).subList(0, MAX_STACK_LINES)) + "\n… " + (lines.length - MAX_STACK_LINES) + " more lines";
    }

    private static String seconds(double s) {
        return String.format(Locale.ROOT, s < 10 ? "%.2f s" : "%.0f s", s);
    }
}
