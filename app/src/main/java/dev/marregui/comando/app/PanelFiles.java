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
 * Each panel's text as a file of its own. A panel's own file is Markdown in one folder, named after the panel's
 * title; it is written when the panel is created, follows the panel's renames, stays while the panel is closed, and
 * goes only when the panel is removed or the file is deleted outside comando. A panel opened on a file elsewhere
 * (a scratchpad document) is bound to that path: comando never renames or deletes it. No file is overwritten after an
 * edit made outside comando: {@link #outside} reports the outside text so the panel can take it, and a write that finds an outside
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
    /**
     * Panel id to its file: a name in the folder, or an absolute path for a file elsewhere; kept after the panel
     * closed, so a reopened panel finds its file.
     */
    private final Map<String, String> names = new ConcurrentHashMap<>();
    /** Panel id to the title its file is named after. */
    private final Map<String, String> titles = new ConcurrentHashMap<>();
    private final Map<String, Stamp> stamps = new ConcurrentHashMap<>();
    /** Outside versions already reported and not yet taken or overwritten, so each is reported once. */
    private final Map<String, Stamp> reported = new ConcurrentHashMap<>();
    /** Panels whose file was deleted outside comando and reported: never written again until the panel is removed. */
    private final Set<String> gone = ConcurrentHashMap.newKeySet();

    PanelFiles(Path dir, Consumer<String> notices) {
        this.dir = dir;
        this.notices = notices;
    }

    Path dir() {
        return dir;
    }

    Optional<Path> file(String id) {
        return Optional.ofNullable(names.get(id)).map(this::path);
    }

    /** The stored name of a panel's file: its name in the folder, or its absolute path elsewhere. */
    Optional<String> storedName(String id) {
        return Optional.ofNullable(names.get(id));
    }

    /** True when the panel is bound to a file elsewhere rather than to a file of the folder. */
    boolean document(String id) {
        String name = names.get(id);
        return name != null && !owned(name);
    }

    private Path path(String name) {
        Path p = Path.of(name);
        return p.isAbsolute() ? p : dir.resolve(name);
    }

    /** A file of the folder, which comando names, renames and deletes when its panel is removed; not a file elsewhere. */
    private static boolean owned(String name) {
        return !Path.of(name).isAbsolute();
    }

    /** Binds a stored panel to its file and returns the file's text; empty when the file is missing. */
    String read(String id, String title, String name) throws IOException {
        Path f = path(name);
        boolean exists = Files.isRegularFile(f);
        // the stamp from before the read: an editor writing the file while it is read leaves another stamp, so the
        // next check reads the whole file again rather than the panel keeping a part of it
        Stamp before = exists ? stamp(f) : null;
        if (!owned(name)) {
            // a document stays bound to its path even when it cannot be read: its text is never moved into the folder
            names.put(id, name);
            titles.put(id, title);
        }
        // a panel's own file that cannot be read leaves the panel unbound, so the next save takes another name instead
        String text = exists ? Files.readString(f, StandardCharsets.UTF_8) : "";
        names.put(id, name);
        titles.put(id, title);
        if (exists) {
            stamps.put(id, before);
        } else {
            stamps.remove(id);
        }
        return text;
    }

    /**
     * Binds a new panel to an existing file and returns its text, or empty with the reason when it is gone or
     * unreadable. A file of the folder is bound by its name; a file elsewhere by its absolute path.
     */
    Result bind(String id, Path file) {
        Path f = file.toAbsolutePath().normalize();
        if (!Files.isRegularFile(f)) {
            return Result.refused(f.getFileName() + " no longer exists");
        }
        String name = f.getParent().equals(dir.toAbsolutePath().normalize()) ? f.getFileName().toString() : f.toString();
        try {
            String text = read(id, title(f), name);
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
        if (current != null && (title.equals(titles.get(id)) || !owned(current))) {
            // a file elsewhere keeps its name whatever the panel is called
            titles.put(id, title);
            return current;
        }
        String next = free(safe(title), id, open);
        if (current != null && !current.equals(next)) {
            Path from = path(current);
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
        Path f = path(name);
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

    /**
     * True once when a panel's file that comando wrote or read is gone: its panel is removed, so the file is not
     * written again by {@link #unwritten}; a save of edits made before the deletion was seen still writes it.
     */
    boolean deleted(String id) {
        String name = names.get(id);
        if (name == null || !stamps.containsKey(id) || Files.exists(path(name))) {
            return false;
        }
        stamps.remove(id);
        reported.remove(id);
        gone.add(id);
        return true;
    }

    /** A panel's own file named and never written or read: a new panel's, written at once, empty or not. */
    boolean unwritten(String id) {
        String name = names.get(id);
        return name != null && owned(name) && !stamps.containsKey(id) && !gone.contains(id) && !Files.exists(path(name));
    }

    /** The panel was removed: its own file is deleted, a file elsewhere stays; comando forgets both. */
    void remove(String id) throws IOException {
        String name = names.remove(id);
        titles.remove(id);
        stamps.remove(id);
        reported.remove(id);
        gone.remove(id);
        if (name != null && owned(name)) {
            Files.deleteIfExists(path(name));
        }
    }

    /**
     * Writes a panel's text; an edit made outside comando that no panel took is kept aside first. The text goes to a
     * temporary file before the check, so only the instant between the check and the replacing move is left for an
     * outside write to land in unseen: without a lock every editor shares, no check can close it.
     */
    void write(String id, String text) throws IOException {
        String name = names.get(id);
        if (name == null) {
            throw new IllegalStateException("panel " + id + " has no file");
        }
        Path f = path(name);
        if (!Files.isDirectory(f.getParent())) {
            Files.createDirectories(f.getParent()); // on a folder that exists it throws and catches inside the JDK
        }
        Path tmp = Files.createTempFile(f.getParent(), "." + f.getFileName(), ".tmp");
        try {
            Files.writeString(tmp, text, StandardCharsets.UTF_8);
            if (Files.exists(f) && !stamp(f).equals(stamps.get(id))) {
                Path aside = f.resolveSibling(stem(f.getFileName().toString()) + " (outside comando " + LocalDateTime.now().format(ASIDE) + ")"
                        + extension(f.getFileName().toString()));
                Files.move(f, aside);
                LOG.log(System.Logger.Level.WARNING, "panel file changed outside comando, kept aside [file=" + f + ", aside=" + aside + "]");
                notices.accept(f.getFileName() + " changed outside comando; that version is kept as " + aside.getFileName());
            }
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
        gone.remove(id);
    }

    /** {@code base}, else {@code base 2}, {@code base 3}…: not a file in the folder, nor the name of another open panel. */
    private String free(String base, String id, Collection<String> open) throws IOException {
        Set<String> taken = new HashSet<>();
        for (String other : open) {
            String n = other.equals(id) ? null : names.get(other);
            if (n != null && owned(n)) {
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
        int dot = name.lastIndexOf('.');
        return dot <= 0 ? name : name.substring(0, dot);
    }

    private static String extension(String name) {
        int dot = name.lastIndexOf('.');
        return dot <= 0 ? "" : name.substring(dot);
    }

    private static Stamp stamp(Path f) throws IOException {
        BasicFileAttributes a = Files.readAttributes(f, BasicFileAttributes.class);
        return new Stamp(a.lastModifiedTime().toMillis(), a.size());
    }
}
