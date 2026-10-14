package dev.marregui.comando.app;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Each panel's text as a Markdown file of its own in one folder, named after the panel's title. A file follows its
 * panel's renames, outlives the panel when it holds text, and is never overwritten after an edit made outside
 * comando: {@link #outside} reports the outside text so the panel can take it, and a write that finds an outside
 * edit nobody took keeps it next to the file under another name. Writes go to a temporary file moved over the old
 * one, as the store files do.
 * <p>
 * The store's writer thread calls every method that writes; {@link #bind} and {@link #file} are called from the
 * EDT, and only read or claim a file that already exists, which a new name never takes.
 */
final class PanelFiles {

    private static final System.Logger LOG = System.getLogger(PanelFiles.class.getName());
    static final String EXTENSION = ".md";
    static final int NAME_CHARS = 80;
    private static final DateTimeFormatter ASIDE = DateTimeFormatter.ofPattern("yyyy-MM-dd HHmmss", Locale.ROOT);

    /** A file as comando last wrote or read it; another stamp on disk means someone else wrote it. */
    record Stamp(long modifiedMillis, long size) {
    }

    /** A file's text written outside comando, with the stamp to {@link #accept} once a panel took it. */
    record Outside(String text, Stamp stamp) {
    }

    private final Path dir;
    private final Consumer<String> notices;
    /** Panel id to its file's name, also after the panel closed, so a reopened panel finds its file. */
    private final Map<String, String> names = new ConcurrentHashMap<>();
    /** Panel id to the title its file is named after. */
    private final Map<String, String> titles = new ConcurrentHashMap<>();
    private final Map<String, Stamp> stamps = new ConcurrentHashMap<>();
    /** Outside versions already reported and not yet taken or overwritten, so each is reported once. */
    private final Map<String, Stamp> reported = new ConcurrentHashMap<>();

    PanelFiles(Path dir, Consumer<String> notices) {
        this.dir = dir;
        this.notices = notices;
    }

    Path dir() {
        return dir;
    }

    Optional<Path> file(String id) {
        return Optional.ofNullable(names.get(id)).map(dir::resolve);
    }

    /** Binds a stored panel to its file and returns the file's text; empty when the file is missing. */
    String read(String id, String title, String name) throws IOException {
        Path f = dir.resolve(name);
        boolean exists = Files.isRegularFile(f);
        // a file that cannot be read leaves the panel unbound, so the next save takes another name instead of it
        String text = exists ? Files.readString(f, StandardCharsets.UTF_8) : "";
        names.put(id, name);
        titles.put(id, title);
        if (exists) {
            stamps.put(id, stamp(f));
        } else {
            stamps.remove(id);
        }
        return text;
    }

    /**
     * Binds a new panel to an existing file of the folder and returns its text, or empty with the reason when
     * the file is elsewhere or unreadable.
     */
    Result bind(String id, Path file) {
        Path f = file.toAbsolutePath().normalize();
        if (!f.getParent().equals(dir.toAbsolutePath().normalize())) {
            return Result.refused("Specs live in " + dir + "; " + f.getFileName() + " is elsewhere");
        }
        try {
            String text = read(id, title(f), f.getFileName().toString());
            return new Result(text, "");
        } catch (IOException e) {
            return Result.refused("Cannot read " + f.getFileName() + ": " + e.getMessage());
        }
    }

    /** The title a file gives a panel opened on it: its name without the extension. */
    static String title(Path file) {
        String name = file.getFileName().toString();
        return (name.toLowerCase(Locale.ROOT).endsWith(EXTENSION) ? name.substring(0, name.length() - EXTENSION.length()) : name).strip();
    }

    /** Text read for a panel, or the reason it could not be. */
    record Result(String text, String error) {
        static Result refused(String error) {
            return new Result("", error);
        }
    }

    /**
     * The file name of an open panel with this title: a new panel gets a free name, a renamed one moves its file
     * to a free name for the new title. {@code open} lists the open panels' ids, whose names are taken.
     */
    String name(String id, String title, Collection<String> open) throws IOException {
        String current = names.get(id);
        if (current != null && title.equals(titles.get(id))) {
            return current;
        }
        String next = free(safe(title), id, open);
        if (current != null && !current.equals(next)) {
            Path from = dir.resolve(current);
            if (Files.exists(from)) {
                Files.createDirectories(dir);
                Files.move(from, dir.resolve(next));
            }
        }
        names.put(id, next);
        titles.put(id, title);
        return next;
    }

    /**
     * The text of a panel's file when someone else wrote it since comando's last write, read or {@link #accept},
     * once per outside version; empty when unchanged, missing, already reported, or written to while being read.
     */
    Optional<Outside> outside(String id) throws IOException {
        String name = names.get(id);
        if (name == null) {
            return Optional.empty();
        }
        Path f = dir.resolve(name);
        if (!Files.isRegularFile(f)) {
            return Optional.empty();
        }
        Stamp now = stamp(f);
        if (now.equals(stamps.get(id)) || now.equals(reported.get(id))) {
            return Optional.empty();
        }
        String text = Files.readString(f, StandardCharsets.UTF_8);
        // an editor still writing changes the stamp under the read: the next look gets the whole text
        if (!stamp(f).equals(now)) {
            return Optional.empty();
        }
        reported.put(id, now);
        return Optional.of(new Outside(text, now));
    }

    /** A panel took the outside text: its file is as comando knows it again. */
    void accept(String id, Stamp stamp) {
        stamps.put(id, stamp);
        reported.remove(id);
    }

    /** True once when a panel's file that comando wrote or read is gone; the next write creates it again. */
    boolean deleted(String id) {
        String name = names.get(id);
        if (name == null || !stamps.containsKey(id) || Files.exists(dir.resolve(name))) {
            return false;
        }
        stamps.remove(id);
        reported.remove(id);
        return true;
    }

    /** Writes a panel's text; an edit made outside comando that no panel took is kept aside first. */
    void write(String id, String text) throws IOException {
        String name = names.get(id);
        if (name == null) {
            throw new IllegalStateException("panel " + id + " has no file");
        }
        Files.createDirectories(dir);
        Path f = dir.resolve(name);
        if (Files.exists(f) && !stamp(f).equals(stamps.get(id))) {
            Path aside = dir.resolve(stem(name) + " (outside comando " + LocalDateTime.now().format(ASIDE) + ")" + EXTENSION);
            Files.move(f, aside);
            LOG.log(System.Logger.Level.WARNING, "panel file changed outside comando, kept aside [file=" + f + ", aside=" + aside + "]");
            notices.accept(name + " changed outside comando; that version is kept as " + aside.getFileName());
        }
        Path tmp = Files.createTempFile(dir, "." + name, ".tmp");
        try {
            Files.writeString(tmp, text, StandardCharsets.UTF_8);
            try {
                Files.move(tmp, f, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
        stamps.put(id, stamp(f));
        reported.remove(id);
    }

    /** The panel closed: its file stays when it holds text, and is deleted when blank and unchanged outside. */
    void closed(String id, String text) throws IOException {
        String name = names.get(id);
        if (name == null || !text.isBlank()) {
            return;
        }
        Path f = dir.resolve(name);
        if (Files.exists(f) && stamp(f).equals(stamps.get(id))) {
            Files.delete(f);
            stamps.remove(id);
        }
    }

    /** {@code base}, else {@code base 2}, {@code base 3}…: not a file in the folder, nor the name of another open panel. */
    private String free(String base, String id, Collection<String> open) throws IOException {
        Set<String> taken = new HashSet<>();
        for (String other : open) {
            String n = other.equals(id) ? null : names.get(other);
            if (n != null) {
                taken.add(n.toLowerCase(Locale.ROOT));
            }
        }
        String own = names.get(id);
        if (Files.isDirectory(dir)) {
            try (var files = Files.list(dir)) {
                files.map(p -> p.getFileName().toString())
                        .filter(n -> !n.equals(own))
                        .forEach(n -> taken.add(n.toLowerCase(Locale.ROOT)));
            }
        }
        // the folder may be on a case-insensitive file system, as APFS is by default
        String candidate = base + EXTENSION;
        for (int i = 2; taken.contains(candidate.toLowerCase(Locale.ROOT)); i++) {
            candidate = base + " " + i + EXTENSION;
        }
        return candidate;
    }

    /** A title made a file name: no path separators, colons or control characters, no leading dot, at most 80 characters. */
    static String safe(String title) {
        StringBuilder sb = new StringBuilder(title.length());
        for (int i = 0; i < title.length(); i++) {
            char c = title.charAt(i);
            sb.append(c == '/' || c == '\\' || c == ':' || Character.isISOControl(c) ? '-' : c);
        }
        String s = sb.toString().strip().replaceAll("\\s+", " ");
        while (s.startsWith(".")) {
            s = s.substring(1).strip();
        }
        if (s.length() > NAME_CHARS) {
            s = s.substring(0, NAME_CHARS).strip();
        }
        return s.isEmpty() ? "panel" : s;
    }

    private static String stem(String name) {
        return name.endsWith(EXTENSION) ? name.substring(0, name.length() - EXTENSION.length()) : name;
    }

    private static Stamp stamp(Path f) throws IOException {
        BasicFileAttributes a = Files.readAttributes(f, BasicFileAttributes.class);
        return new Stamp(a.lastModifiedTime().toMillis(), a.size());
    }
}
