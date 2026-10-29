package dev.marregui.comando.ui.kit.text;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * Wiki-style links, {@code [[name]]}, {@code [[name#heading]]} and {@code [[name|label]]}, as Obsidian and Claude
 * Code's memory files write them: a link names a note, not a path. A rendered or detected one carries the href
 * {@code comando-note:<name>#<heading>}; {@link #resolve} finds the file it names.
 */
public final class NoteLinks {

    public static final String SCHEME = "comando-note:";

    /** At most this many entries are visited looking for a note: a link in a huge tree gives up rather than stall. */
    static final int MAX_VISITED = 50_000;
    /** How much of a file is read for its front matter's {@code name:}. */
    private static final int HEAD_BYTES = 4096;

    private NoteLinks() {
    }

    /** The href of a link to {@code name}, at {@code heading} when not null or blank. */
    public static String href(String name, String heading) {
        return SCHEME + name.strip() + (heading == null || heading.isBlank() ? "" : "#" + heading.strip());
    }

    public static boolean is(String href) {
        return href != null && href.startsWith(SCHEME);
    }

    /** The note an href names, without its heading. */
    public static String name(String href) {
        String rest = href.substring(SCHEME.length());
        int hash = rest.indexOf('#');
        return hash < 0 ? rest : rest.substring(0, hash);
    }

    /** The heading an href names, as written; null when none. */
    public static String heading(String href) {
        int hash = href.indexOf('#', SCHEME.length());
        return hash < 0 || hash == href.length() - 1 ? null : href.substring(hash + 1);
    }

    /**
     * The file {@code name} names, read from {@code folder}: within the git repository that holds the folder, else the
     * folder itself, the first of: a file named {@code name} or {@code name.md}; a Markdown file whose front matter
     * says {@code name: <name>}; a file named so with {@code -} and {@code _} swapped. File names match in any letter
     * case; of several, the one nearest the folder wins, then the first by path. A name with a {@code /} is tried
     * first as a path from the folder, then from the root. Reads the disk: never on the EDT.
     */
    public static Optional<Path> resolve(Path folder, String name) {
        if (folder == null || name == null || name.isBlank()) {
            return Optional.empty();
        }
        String wanted = name.strip();
        Path from = folder.toAbsolutePath().normalize();
        Path root = root(from);
        if (wanted.contains("/")) {
            for (Path base : List.of(from, root)) {
                for (String n : List.of(wanted, wanted + ".md")) {
                    Path p = base.resolve(n).normalize();
                    if (Files.isRegularFile(p)) {
                        return Optional.of(p);
                    }
                }
            }
            wanted = wanted.substring(wanted.lastIndexOf('/') + 1);
            if (wanted.isEmpty()) {
                return Optional.empty();
            }
        }
        List<Path> files = files(root);
        String key = wanted.toLowerCase(Locale.ROOT);
        Optional<Path> named = nearest(from, files, f -> named(f, key));
        if (named.isPresent()) {
            return named;
        }
        String declaredAs = wanted;
        Optional<Path> declared = nearest(from, files, f -> markdown(f) && declaredAs.equals(declaredName(f)));
        if (declared.isPresent()) {
            return declared;
        }
        String swapped = swap(key);
        return swapped.equals(key) ? Optional.empty() : nearest(from, files, f -> named(f, swapped));
    }

    /** Where a note link read from {@code folder} is looked for. Reads the disk. */
    public static Path rootOf(Path folder) {
        return root(folder.toAbsolutePath().normalize());
    }

    /** The git repository that holds {@code folder}: the nearest folder with a {@code .git}; else {@code folder}. */
    static Path root(Path folder) {
        for (Path p = folder; p != null; p = p.getParent()) {
            if (Files.exists(p.resolve(".git"))) {
                return p;
            }
        }
        return folder;
    }

    private static boolean named(Path f, String key) {
        String n = f.getFileName().toString().toLowerCase(Locale.ROOT);
        return n.equals(key) || n.equals(key + ".md");
    }

    private static boolean markdown(Path f) {
        String n = f.getFileName().toString().toLowerCase(Locale.ROOT);
        return n.endsWith(".md") || n.endsWith(".markdown");
    }

    private static String swap(String s) {
        StringBuilder b = new StringBuilder(s.length());
        for (char c : s.toCharArray()) {
            b.append(c == '-' ? '_' : c == '_' ? '-' : c);
        }
        return b.toString();
    }

    private static Optional<Path> nearest(Path from, List<Path> files, Predicate<Path> test) {
        return files.stream().filter(test)
                .min(Comparator.<Path>comparingInt(f -> distance(from, f)).thenComparing(Path::toString));
    }

    /** How many folders apart {@code file} is from {@code folder}: up to their common folder, then down to its own. */
    static int distance(Path folder, Path file) {
        Path parent = file.getParent();
        int common = 0;
        int n = Math.min(folder.getNameCount(), parent.getNameCount());
        while (common < n && folder.getName(common).equals(parent.getName(common))) {
            common++;
        }
        return folder.getNameCount() - common + parent.getNameCount() - common;
    }

    /** The files under {@code root}, its hidden folders and {@code node_modules} left out. */
    private static List<Path> files(Path root) {
        List<Path> out = new ArrayList<>();
        int[] visited = {0};
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    if (++visited[0] > MAX_VISITED) {
                        return FileVisitResult.TERMINATE;
                    }
                    String n = dir.getFileName() == null ? "" : dir.getFileName().toString();
                    boolean skip = !dir.equals(root) && (n.startsWith(".") || n.equals("node_modules"));
                    return skip ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (attrs.isRegularFile()) {
                        out.add(file);
                    }
                    return ++visited[0] > MAX_VISITED ? FileVisitResult.TERMINATE : FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException e) {
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            // what was found before the failure is still searched
        }
        return out;
    }

    /** The {@code name:} a Markdown file's front matter declares; null when it has none. */
    static String declaredName(Path file) {
        byte[] head;
        try (InputStream in = Files.newInputStream(file)) {
            head = in.readNBytes(HEAD_BYTES);
        } catch (IOException e) {
            return null;
        }
        return FrontMatter.split(new String(head, StandardCharsets.UTF_8)).value("name");
    }
}
