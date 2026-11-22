package dev.marregui.comando.app;

import com.sun.source.tree.BlockTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.LineMap;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.NewClassTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.SourcePositions;
import com.sun.source.util.TreeScanner;
import com.sun.source.util.Trees;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.lang.model.element.Modifier;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// G-10.1's member order, checked over every `src/main/java` and `src/test/java` file of every module: within each
/// class, interface, enum and record body (nested, local and anonymous ones included), no member sits in an earlier
/// band than a member above it. The bands, in order: enum constants; static final, static, final and plain fields,
/// each split public, protected, package-private, private; static initialisers; instance initialisers (the rule names
/// none, so they sit with the initialisers, before the constructors); constructors; public static methods; public
/// methods; protected methods; package-private methods; private static methods; private methods; nested types.
/// Record components are not members; an interface's constants are public static final fields, its methods public
/// unless private, static by their modifier.
///
/// Bands only: the order inside a band (alphabetical, unless an initialiser reads another field or the order carries
/// meaning) is not checked, as whether an order carries meaning cannot be decided from the source. One exception is
/// decided: a field whose initialiser reads, by its simple name, a field of a later band above it is not reported,
/// as moving it up would be an illegal forward reference (a private constant that a public one is built from).
/// Sources are parsed, not attributed, so the test needs no classpath.
///
/// - every file keeps the bands or is listed in `member-order-baseline.txt`, the files that broke them when the check
///   came; a listed file now in order, or gone, fails too, so the list only shrinks
/// - the band model places each kind of member as the rule gives it
class MemberOrderRulesTest {

    // Gradle runs a module's tests in the module's folder
    private static final Path ROOT = Path.of("..");
    private static final String BASELINE = "member-order-baseline.txt";
    private static final String BASELINE_SOURCE = "app/src/test/resources/dev/marregui/comando/app/" + BASELINE;

    /// No main or test source of any module breaks the bands unless the baseline lists it, and every listed file
    /// still breaks them; each failure names the file, its first member out of order, its band and the fix.
    @Test
    void everyFileKeepsTheBandsOrIsListedInTheBaseline() throws IOException {
        final List<Path> files = sourceFiles();
        assertThat(files).as("this test's own file, read from the repository's root")
                .contains(Path.of("app/src/test/java/dev/marregui/comando/app/MemberOrderRulesTest.java"));
        final List<JavaFileObject> sources = new ArrayList<>();
        final Map<String, Inversion> first = new TreeMap<>();
        final JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        try (final StandardJavaFileManager manager = compiler.getStandardFileManager(null, Locale.ROOT,
                StandardCharsets.UTF_8)) {
            manager.getJavaFileObjectsFromPaths(files.stream().map(ROOT::resolve).toList()).forEach(sources::add);
            for (final Inversion inversion : inversions(compiler, manager, sources)) {
                first.putIfAbsent(inversion.path(), inversion);
            }
        }
        final Set<String> baseline = baseline();
        final List<String> problems = new ArrayList<>();
        for (final Inversion inversion : first.values()) {
            if (!baseline.contains(inversion.path())) {
                problems.add(inversion.describe() + ": move it up there, ordering the file with the IDE's arrangement"
                        + " rules set to G-10.1's bands");
            }
        }
        for (final String path : baseline) {
            if (!first.containsKey(path)) {
                problems.add(path + ": keeps the bands now, or is gone: remove its line from " + BASELINE_SOURCE);
            }
        }
        assertThat(problems).withFailMessage("%d files out of step with G-10.1's member order and %s:%n%s",
                problems.size(), BASELINE_SOURCE, String.join(System.lineSeparator(), problems)).isEmpty();
    }

    /// A body in the rule's order passes, enum constants with bodies, interface methods, record components, a compact
    /// constructor and a constant built from a private one of a later band included; each member out of order is
    /// reported at its line with the first member above it of a later band, in anonymous bodies too.
    @Test
    void theBandModelPlacesEachKindOfMember() throws IOException {
        final String clean = """
                package p;

                enum Colour {
                    RED, GREEN {
                        @Override
                        public String toString() {
                            return "g";
                        }
                    };

                    private static final int SLOT = 8;
                    public static final int ROW = SLOT * 4;
                    private static final int A = 1;
                    private static final int B = 2;
                    static int c;
                    final int d = 0;
                    private int e;

                    static {
                        c = 3;
                    }

                    Colour() {
                    }

                    public static Colour first() {
                        return RED;
                    }

                    public int d() {
                        return d;
                    }

                    protected int a() {
                        return A;
                    }

                    int e() {
                        return e;
                    }

                    private static int b() {
                        return B;
                    }

                    private int row() {
                        return ROW;
                    }

                    interface Shade {
                        int DEPTH = 1;

                        static Shade none() {
                            return null;
                        }

                        int depth();

                        default int twice() {
                            return depth() * 2;
                        }

                        private int half() {
                            return depth() / 2;
                        }
                    }

                    record Pair(int left, int right) {
                        static final Pair ZERO = new Pair(0, 0);

                        Pair {
                            assert left <= right;
                        }

                        int width() {
                            return right - left;
                        }
                    }
                }
                """;
        final String bad = """
                package p;

                class Bad {
                    private static final int P = 1;
                    public static final int Q = 2;

                    private void helper() {
                    }

                    public void api() {
                    }

                    static final int LATE = 1;

                    class Inner {
                    }

                    void afterInner() {
                        new Object() {
                            private int x;
                            public static final int Y = 2;
                        };
                    }

                    private void last() {
                    }
                }
                """;
        final JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        try (final StandardJavaFileManager manager = compiler.getStandardFileManager(null, Locale.ROOT,
                StandardCharsets.UTF_8)) {
            assertThat(inversions(compiler, manager, List.of(source("Colour.java", clean)))).isEmpty();
            assertThat(inversions(compiler, manager, List.of(source("Bad.java", bad))).stream()
                    .map(Inversion::describe)).containsExactly(
                    "Bad.java:5: Q (public static final field) belongs above P (private static final field, line 4)",
                    "Bad.java:10: api (public method) belongs above helper (private method, line 7)",
                    "Bad.java:13: LATE (package-private static final field) belongs above P (private static final"
                            + " field, line 4)",
                    "Bad.java:18: afterInner (package-private method) belongs above helper (private method, line 7)",
                    "Bad.java:21: Y (public static final field) belongs above x (private field, line 20)",
                    "Bad.java:25: last (private method) belongs above Inner (nested type, line 15)");
        }
    }

    /** Every `.java` file under a module's `src/main/java` and `src/test/java`, relative to the root, in path order. */
    private static List<Path> sourceFiles() throws IOException {
        final List<Path> roots;
        try (final Stream<Path> modules = Files.list(ROOT)) {
            roots = modules.flatMap(m -> Stream.of(m.resolve("src/main/java"), m.resolve("src/test/java")))
                    .filter(Files::isDirectory).sorted().toList();
        }
        final List<Path> files = new ArrayList<>();
        for (final Path root : roots) {
            try (final Stream<Path> walk = Files.walk(root)) {
                walk.filter(p -> p.getFileName().toString().endsWith(".java")).filter(Files::isRegularFile)
                        .map(ROOT::relativize).sorted().forEach(files::add);
            }
        }
        return files;
    }

    /** The paths the baseline lists, one per line; blank lines and `#` comments are skipped. */
    private static Set<String> baseline() throws IOException {
        try (final InputStream in = MemberOrderRulesTest.class.getResourceAsStream(BASELINE)) {
            assertThat(in).as(BASELINE_SOURCE).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).lines().map(String::strip)
                    .filter(l -> !l.isEmpty() && !l.startsWith("#")).collect(Collectors.toCollection(TreeSet::new));
        }
    }

    /**
     * Every member out of its band in the given sources, by path and line. Parses only: a source that does not parse
     * fails the test with javac's diagnostics.
     */
    private static List<Inversion> inversions(final JavaCompiler compiler, final StandardJavaFileManager manager,
            final List<? extends JavaFileObject> sources) throws IOException {
        final DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        final JavacTask task = (JavacTask) compiler.getTask(null, manager, diagnostics, List.of("-proc:none"), null,
                sources);
        final Iterable<? extends CompilationUnitTree> units = task.parse();
        assertThat(diagnostics.getDiagnostics().stream().filter(d -> d.getKind() == Diagnostic.Kind.ERROR)
                .map(d -> d.getSource().getName() + ":" + d.getLineNumber() + ": " + d.getMessage(Locale.ROOT)))
                .as("sources that do not parse").isEmpty();
        final SourcePositions positions = Trees.instance(task).getSourcePositions();
        final List<Inversion> found = new ArrayList<>();
        for (final CompilationUnitTree unit : units) {
            new BodyScanner(name(unit.getSourceFile()), unit, positions, found).scan(unit, null);
        }
        found.sort(Comparator.comparing(Inversion::path).thenComparingLong(i -> i.member().line()));
        return found;
    }

    /** A file's path relative to the repository's root, or an in-memory source's own name. */
    private static String name(final JavaFileObject file) {
        final URI uri = file.toUri();
        if ("file".equals(uri.getScheme())) {
            return ROOT.toAbsolutePath().normalize().relativize(Path.of(uri)).toString();
        }
        return uri.getPath().substring(1);
    }

    private static JavaFileObject source(final String name, final String text) {
        return new SimpleJavaFileObject(URI.create("string:///" + name), JavaFileObject.Kind.SOURCE) {
            @Override
            public CharSequence getCharContent(final boolean ignoreEncodingErrors) {
                return text;
            }
        };
    }

    /** G-10.1's bands in the rule's order; a field band is split again by access. */
    private enum Band {
        ENUM_CONSTANT("enum constant"),
        STATIC_FINAL_FIELD("static final field"),
        STATIC_FIELD("static field"),
        FINAL_FIELD("final field"),
        FIELD("field"),
        STATIC_INITIALISER("static initialiser"),
        INSTANCE_INITIALISER("instance initialiser"),
        CONSTRUCTOR("constructor"),
        PUBLIC_STATIC_METHOD("public static method"),
        PUBLIC_METHOD("public method"),
        PROTECTED_METHOD("protected method"),
        PACKAGE_METHOD("package-private method"),
        PRIVATE_STATIC_METHOD("private static method"),
        PRIVATE_METHOD("private method"),
        NESTED_TYPE("nested type");

        private final String label;

        Band(final String label) {
            this.label = label;
        }

        boolean isField() {
            return this == STATIC_FINAL_FIELD || this == STATIC_FIELD || this == FINAL_FIELD || this == FIELD;
        }
    }

    /** A field's access, in the order G-10.1 gives inside each field band. */
    private enum Access {
        PUBLIC("public"),
        PROTECTED("protected"),
        PACKAGE("package-private"),
        PRIVATE("private");

        private final String label;

        Access(final String label) {
            this.label = label;
        }

        static Access of(final Set<Modifier> modifiers) {
            if (modifiers.contains(Modifier.PUBLIC)) {
                return PUBLIC;
            }
            if (modifiers.contains(Modifier.PROTECTED)) {
                return PROTECTED;
            }
            return modifiers.contains(Modifier.PRIVATE) ? PRIVATE : PACKAGE;
        }
    }

    /** Where a member belongs: its band, and its access where the band is a field band. */
    private record Place(Band band, Access access) {

        int rank() {
            return band.ordinal() * Access.values().length + (band.isField() ? access.ordinal() : 0);
        }

        String label() {
            return band.isField() ? access.label + " " + band.label : band.label;
        }
    }

    /** One member of a body: its tree, its name for a message, its place and its line. */
    private record Member(Tree tree, String name, Place place, long line) {
    }

    /** A member below one of a later band: the fix moves it above that one. */
    private record Inversion(String path, Member member, Member later) {

        String describe() {
            return path + ":" + member.line() + ": " + member.name() + " (" + member.place().label()
                    + ") belongs above " + later.name() + " (" + later.place().label() + ", line " + later.line() + ")";
        }
    }

    /** Checks every type body of one compilation unit, the nested, local and anonymous ones included. */
    private static final class BodyScanner extends TreeScanner<Void, Void> {

        private final String path;
        private final CompilationUnitTree unit;
        private final SourcePositions positions;
        private final List<Inversion> found;

        BodyScanner(final String path, final CompilationUnitTree unit, final SourcePositions positions,
                final List<Inversion> found) {
            this.path = path;
            this.unit = unit;
            this.positions = positions;
            this.found = found;
        }

        @Override
        public Void visitClass(final ClassTree type, final Void unused) {
            final LineMap lines = unit.getLineMap();
            final List<Member> above = new ArrayList<>();
            for (final Tree tree : type.getMembers()) {
                final Optional<Place> place = place(type, tree);
                if (place.isEmpty()) {
                    continue;
                }
                final Member member = new Member(tree, memberName(type, tree), place.get(),
                        lines.getLineNumber(positions.getStartPosition(unit, tree)));
                above.stream().filter(m -> m.place().rank() > member.place().rank()).findFirst()
                        .filter(later -> !forcedBy(member, above))
                        .ifPresent(later -> found.add(new Inversion(path, member, later)));
                above.add(member);
            }
            return super.visitClass(type, unused);
        }

        /** The member's place in its body; empty for a record component, which is no member. */
        private static Optional<Place> place(final ClassTree type, final Tree tree) {
            final boolean isInterface = type.getKind() == Tree.Kind.INTERFACE
                    || type.getKind() == Tree.Kind.ANNOTATION_TYPE;
            return switch (tree) {
                case VariableTree field -> fieldPlace(type, isInterface, field);
                case BlockTree block -> Optional.of(new Place(block.isStatic() ? Band.STATIC_INITIALISER
                        : Band.INSTANCE_INITIALISER, Access.PACKAGE));
                case MethodTree method -> Optional.of(new Place(methodBand(isInterface, method), Access.PACKAGE));
                case ClassTree _ -> Optional.of(new Place(Band.NESTED_TYPE, Access.PACKAGE));
                default -> Optional.empty();
            };
        }

        private static Optional<Place> fieldPlace(final ClassTree type, final boolean isInterface,
                final VariableTree field) {
            if (isInterface) {
                return Optional.of(new Place(Band.STATIC_FINAL_FIELD, Access.PUBLIC));
            }
            final Set<Modifier> modifiers = field.getModifiers().getFlags();
            final boolean isStatic = modifiers.contains(Modifier.STATIC);
            if (type.getKind() == Tree.Kind.RECORD && !isStatic) {
                return Optional.empty();
            }
            // an enum constant is the one initialiser in an enum allowed to construct the enum itself
            if (type.getKind() == Tree.Kind.ENUM && field.getInitializer() instanceof NewClassTree created
                    && created.getIdentifier() instanceof IdentifierTree id
                    && id.getName().contentEquals(type.getSimpleName())) {
                return Optional.of(new Place(Band.ENUM_CONSTANT, Access.PUBLIC));
            }
            final boolean isFinal = modifiers.contains(Modifier.FINAL);
            final Band band = isStatic ? (isFinal ? Band.STATIC_FINAL_FIELD : Band.STATIC_FIELD)
                    : (isFinal ? Band.FINAL_FIELD : Band.FIELD);
            return Optional.of(new Place(band, Access.of(modifiers)));
        }

        private static Band methodBand(final boolean isInterface, final MethodTree method) {
            if (method.getName().contentEquals("<init>")) {
                return Band.CONSTRUCTOR;
            }
            final Set<Modifier> modifiers = method.getModifiers().getFlags();
            final boolean isStatic = modifiers.contains(Modifier.STATIC);
            final Access access = isInterface && !modifiers.contains(Modifier.PRIVATE) ? Access.PUBLIC
                    : Access.of(modifiers);
            return switch (access) {
                case PUBLIC -> isStatic ? Band.PUBLIC_STATIC_METHOD : Band.PUBLIC_METHOD;
                case PROTECTED -> Band.PROTECTED_METHOD;
                case PACKAGE -> Band.PACKAGE_METHOD;
                case PRIVATE -> isStatic ? Band.PRIVATE_STATIC_METHOD : Band.PRIVATE_METHOD;
            };
        }

        private static String memberName(final ClassTree type, final Tree tree) {
            return switch (tree) {
                case VariableTree field -> field.getName().toString();
                case MethodTree method -> method.getName().contentEquals("<init>")
                        ? type.getSimpleName().toString() : method.getName().toString();
                case ClassTree nested -> nested.getSimpleName().toString();
                default -> "initialiser";
            };
        }

        /**
         * Whether the member is a field whose initialiser reads, by simple name, a field of a later band above it:
         * moving it up would be a forward reference javac refuses.
         */
        private static boolean forcedBy(final Member member, final List<Member> above) {
            if (!(member.tree() instanceof VariableTree field) || field.getInitializer() == null) {
                return false;
            }
            final Set<String> read = new HashSet<>();
            new TreeScanner<Void, Void>() {
                @Override
                public Void visitIdentifier(final IdentifierTree id, final Void unused) {
                    read.add(id.getName().toString());
                    return null;
                }
            }.scan(field.getInitializer(), null);
            return above.stream().anyMatch(m -> m.tree() instanceof VariableTree
                    && m.place().rank() > member.place().rank() && read.contains(m.name()));
        }
    }
}
