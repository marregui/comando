package dev.marregui.comando.app;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.type.TypeReference;
import dev.marregui.comando.core.model.ConnectionSpec;
import dev.marregui.comando.core.model.FolderRoot;
import dev.marregui.comando.core.model.PanelId;
import dev.marregui.comando.core.model.SessionSpec;
import dev.marregui.comando.core.port.PersistencePort;
import dev.marregui.comando.core.resource.FolderKind;
import dev.marregui.comando.core.state.PanelState;
import dev.marregui.comando.ui.PanelTexts;
import dev.marregui.comando.ui.PanelTexts.Opened;
import dev.marregui.comando.ui.PanelTexts.Outside;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * The store files: {@code connections.json}, {@code sessions.json}, {@code folders.json} (the Files tab's folders),
 * {@code panels.json} (each panel's title,
 * bindings and file name, and whether it is closed) and one Markdown file per panel under {@code panels/} with its
 * text ({@link PanelFiles}). A panel of comando's own is kept, closed or open, until it is removed or its file is
 * deleted outside comando; its text stays in memory while it is closed, so it reopens at once.
 * Every write runs on one thread from an immutable snapshot, so a save never races an edit; panel text is saved
 * 800 ms after the first unsaved change, so continuous typing saves about every 800 ms, and only the panels that
 * changed are written.
 */
final class Store implements PersistencePort, PanelTexts, AutoCloseable {

    private static final System.Logger LOG = System.getLogger(Store.class.getName());
    static final long PANEL_DELAY_MILLIS = 800;
    /** How often open panels' files are checked for changes made outside comando. */
    static final long POLL_MILLIS = 1_000;
    /** How soon a panel file that could not be written is tried again. */
    static final long RETRY_MILLIS = 5_000;

    /**
     * A panel as stored: its title, bindings and the name of its file; {@code closed} is true for a closed panel of
     * comando's own, absent for an open one. {@code content} is written only when the panel's file could not be, and
     * read from a {@code panels.json} written before panels had files; it moves into the file at the next save.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record StoredPanel(String id, String title, String connection, String session, String file, String content, Boolean closed) {
    }

    /** What the store held at start: the open panels, in tab order, and the closed ones. */
    record Loaded(List<PanelState> panels, List<ConnectionSpec> connections, List<SessionSpec> sessions, List<PanelState> closed) {
        Loaded(List<PanelState> panels, List<ConnectionSpec> connections, List<SessionSpec> sessions) {
            this(panels, connections, sessions, List.of());
        }
    }

    private final JsonFile<List<ConnectionSpec>> connections;
    private final JsonFile<List<SessionSpec>> sessions;
    private final JsonFile<List<FolderRoot>> folders;
    private final JsonFile<List<StoredPanel>> panels;
    private final ScheduledExecutorService writer = writer();
    /**
     * Connection saves talk to the keychain, which can wait on an unlock prompt for its whole bound; they get a
     * thread of their own, so panel text and sessions are never queued behind a prompt nobody answers.
     */
    private final ExecutorService secretsWriter =
            Executors.newSingleThreadExecutor(r -> Thread.ofPlatform().name("store-secrets").daemon(false).unstarted(r));
    private final ConcurrentHashMap<String, String> texts = new ConcurrentHashMap<>();

    /**
     * The writer thread. A delayed write still waiting at shutdown is dropped, not run: {@link #close} writes
     * everything at once, and a retry run later would write into the folder after the store closed.
     */
    private static ScheduledExecutorService writer() {
        ScheduledThreadPoolExecutor e = new ScheduledThreadPoolExecutor(1, r -> Thread.ofPlatform().name("store").daemon(false).unstarted(r));
        e.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        return e;
    }
    /** Panels whose text changed since their file was written. */
    private final Set<String> dirty = ConcurrentHashMap.newKeySet();
    /** How many times each panel's text changed: a deletion report removes the panel only when no edit came after it. */
    private final ConcurrentHashMap<String, Long> edits = new ConcurrentHashMap<>();
    /** Panels whose last file write failed; the user is told once per run of failures. Writer thread only. */
    private final Set<String> failing = new HashSet<>();
    /** The key in {@link #failing} for {@code panels.json} itself; panel ids are UUIDs. */
    private static final String PANELS_JSON = "panels.json";
    /**
     * Closed panels whose last write failed, with their text: kept in {@code panels.json} and tried again at every
     * save, so a panel that never lands comes back at the next start. Writer thread only.
     */
    private final Map<String, StoredPanel> unsaved = new LinkedHashMap<>();
    /**
     * Removed panels: a save that still lists one (its list update not yet arrived) neither names nor writes its file
     * again. Ids are random, so the set only grows by the panels removed in this run.
     */
    private final Set<String> removed = ConcurrentHashMap.newKeySet();
    /** Panels bound to a file by {@link #open} and not yet in the panel list. Writer thread only. */
    private final Set<String> opening = new HashSet<>();
    private final PanelFiles files;
    /** Notices raised before {@link #onNotice} is called, at the start, kept until a listener takes them. */
    private final List<String> early = new ArrayList<>();
    /** Whether {@link #onNotice} was called; guarded by {@link #early}. */
    private boolean listening;
    private volatile Consumer<String> notices = this::keep;
    private volatile Consumer<Outside> outsideChanges = change -> {
    };
    private volatile Consumer<Deleted> deletions = deleted -> {
    };
    /** Where {@code folders.json} goes when the user changed it; the Files tab follows. */
    private volatile Consumer<List<FolderRoot>> foldersChanged = f -> {
    };
    /** {@code folders.json} as the store last read or wrote it; null while it is missing. Writer thread, and the start. */
    private volatile Stamp foldersSeen;
    private final AtomicBoolean panelsPending = new AtomicBoolean();
    private volatile List<PanelState> panelMeta = List.of();
    private volatile List<PanelState> closedMeta = List.of();
    private volatile boolean closed;
    private final Secrets secrets;
    /** Names whose passwords the secret store holds, as of the last save; written by the store-secrets thread only. */
    private Set<String> secretNames = new HashSet<>();
    /** Connections whose password the secret store could not be asked for; their items are never deleted. */
    private final Set<String> unreadSecrets = ConcurrentHashMap.newKeySet();

    Store(Path home) {
        this(home, Secrets.NONE);
    }

    Store(Path home, Secrets secrets) {
        this.secrets = secrets;
        connections = new JsonFile<>(home.resolve("connections.json"), new TypeReference<>() {
        });
        sessions = new JsonFile<>(home.resolve("sessions.json"), new TypeReference<>() {
        });
        folders = new JsonFile<>(home.resolve("folders.json"), new TypeReference<>() {
        });
        panels = new JsonFile<>(home.resolve("panels.json"), new TypeReference<>() {
        });
        files = new PanelFiles(home.resolve("panels"), text -> notices.accept(text));
    }

    /**
     * Where the store reports what the user should know, such as a panel file changed outside comando. What the start
     * found before there was anyone to tell (a panel whose file was deleted while comando was closed) is told now.
     */
    void onNotice(Consumer<String> notices) {
        List<String> told;
        synchronized (early) {
            this.notices = notices;
            listening = true;
            told = List.copyOf(early);
            early.clear();
        }
        told.forEach(notices);
    }

    /** The first listener: keeps a notice until {@link #onNotice}, or hands it on when a reader saw it late. */
    private void keep(String text) {
        synchronized (early) {
            if (!listening) {
                early.add(text);
                return;
            }
        }
        notices.accept(text);
    }

    /** Reads the files; a fresh data root gets the defaults: a local QuestDB and a scratch panel with the welcome text. */
    Loaded load() {
        boolean fresh = !connections.exists() && !sessions.exists() && !panels.exists();
        if (fresh) {
            Loaded seeded = defaults();
            for (PanelState p : seeded.panels()) {
                texts.putIfAbsent(p.id().value(), Welcome.TEXT);
                dirty.add(p.id().value());
            }
            // a panels folder left without its JSON files: its files are panels too
            List<PanelState> shut = new ArrayList<>();
            adoptLooseFiles(List.of(), shut);
            panelMeta = seeded.panels();
            closedMeta = List.copyOf(shut);
            pollFiles();
            saveConnections(seeded.connections());
            saveSessions(seeded.sessions());
            schedulePanels();
            return new Loaded(seeded.panels(), seeded.connections(), seeded.sessions(), shut);
        }
        List<ConnectionSpec> c = orEmpty(connections.read()).stream().map(this::withSecret).toList();
        secretNames = new HashSet<>(c.stream().map(ConnectionSpec::name).toList());
        List<SessionSpec> s = orEmpty(sessions.read());
        List<PanelState> p = new ArrayList<>();
        List<PanelState> shut = new ArrayList<>();
        List<String> gone = new ArrayList<>();
        for (StoredPanel sp : orEmpty(panels.read())) {
            if (sp.id() == null || sp.id().isBlank()) {
                continue;
            }
            boolean isClosed = Boolean.TRUE.equals(sp.closed());
            if (fileGone(sp, isClosed)) {
                gone.add(Path.of(sp.file()).getFileName().toString());
                continue;
            }
            String title = nz(sp.title(), "panel");
            boolean document = sp.file() != null && Path.of(sp.file()).isAbsolute();
            PanelState panel = new PanelState(new PanelId(sp.id()), title, nz(sp.connection(), ""), nz(sp.session(), ""), document);
            (isClosed ? shut : p).add(panel);
            texts.put(sp.id(), textOf(sp, title));
        }
        List<String> adopted = adoptLooseFiles(orEmpty(panels.read()), shut);
        if (!adopted.isEmpty()) {
            LOG.log(System.Logger.Level.INFO, "files in panels/ without a panel, listed as closed panels " + adopted);
            notices.accept(String.join(", ", adopted) + (adopted.size() == 1 ? " has" : " have")
                    + " no panel; listed as closed in the Panels tab");
        }
        if (!gone.isEmpty()) {
            LOG.log(System.Logger.Level.INFO, "files deleted while comando was closed, their panels dropped " + gone);
            notices.accept(String.join(", ", gone) + (gone.size() == 1 ? " was" : " were")
                    + " deleted while comando was closed; " + (gone.size() == 1 ? "its panel is" : "their panels are") + " gone");
        }
        int openCount = p.size();
        List<PanelState> all = new ArrayList<>(p);
        all.addAll(shut);
        all = validBindings(all, c, s);
        List<String> unbound = new ArrayList<>();
        // the open panels come first, so a session bound to an open and a closed panel keeps the open one
        all = oneBindingPerSession(all, unbound);
        p = new ArrayList<>(all.subList(0, openCount));
        shut = new ArrayList<>(all.subList(openCount, all.size()));
        if (!unbound.isEmpty()) {
            LOG.log(System.Logger.Level.INFO, "sessions bound to several panels keep their first " + unbound);
            notices.accept("A session has one panel now: " + String.join("; ", unbound));
            schedulePanels();
        }
        if (p.isEmpty()) {
            PanelState first = new PanelState(PanelId.random(), "scratch", c.isEmpty() ? "" : c.getFirst().name(), "");
            texts.put(first.id().value(), Welcome.TEXT);
            dirty.add(first.id().value());
            p = List.of(first);
        }
        panelMeta = List.copyOf(p);
        closedMeta = List.copyOf(shut);
        // dirty text, and an open panel whose file is missing (a blank panel of an older comando) gets one now
        schedulePanels();
        pollFiles();
        return new Loaded(p, c, s, shut);
    }

    /**
     * A stored panel's text: from its file, else from {@code panels.json} as written before panels had files, which
     * the next save moves into a file. A file that cannot be read keeps its text out of the editor and is left alone.
     */
    private String textOf(StoredPanel sp, String title) {
        String fromFile = "";
        if (sp.file() != null && !sp.file().isBlank()) {
            try {
                fromFile = files.read(sp.id(), title, sp.file());
            } catch (IOException | RuntimeException e) {
                LOG.log(System.Logger.Level.ERROR, "cannot read the panel file, the panel saves to another file [file="
                        + files.dir().resolve(sp.file()) + "]: " + e.getMessage());
                notices.accept("Cannot read " + sp.file() + "; the panel " + title + " saves to another file");
            }
        }
        // content is written only when the panel's file could not be, or before panels had files: the newer text
        if (sp.content() != null) {
            dirty.add(sp.id());
            return sp.content();
        }
        return fromFile;
    }

    /**
     * Every file of the panels folder is a panel: one no stored panel names (a closed panel's file from before closed
     * panels were kept, or a file put there by hand) becomes a closed panel titled after it, with no bindings.
     * Temporary and hidden files are left alone. Returns the adopted files' names.
     */
    private List<String> adoptLooseFiles(List<StoredPanel> stored, List<PanelState> shut) {
        // a stored name names a file when that file exists and is the same file: case-insensitive on APFS by default,
        // exact on a case-sensitive file system, where a stored name differing in case leaves the file loose
        List<Path> named = new ArrayList<>();
        for (StoredPanel sp : stored) {
            if (sp.file() != null && !Path.of(sp.file()).isAbsolute()) {
                named.add(files.dir().resolve(sp.file()));
            }
        }
        List<String> adopted = new ArrayList<>();
        if (!Files.isDirectory(files.dir())) {
            return adopted;
        }
        try (var listing = Files.list(files.dir())) {
            for (Path f : listing.sorted().toList()) {
                String name = f.getFileName().toString();
                if (name.startsWith(".") || !Files.isRegularFile(f) || named.stream().anyMatch(n -> sameFile(n, f))) {
                    continue;
                }
                PanelId id = PanelId.random();
                String title = PanelFiles.title(f);
                try {
                    texts.put(id.value(), files.read(id.value(), title, name));
                    shut.add(new PanelState(id, title, "", ""));
                    adopted.add(name);
                } catch (IOException | RuntimeException e) {
                    LOG.log(System.Logger.Level.WARNING, "cannot read a file of panels/, left alone [file=" + f + "]: " + e.getMessage());
                }
            }
        } catch (IOException e) {
            LOG.log(System.Logger.Level.WARNING, "cannot list panels/: " + e.getMessage());
        }
        return adopted;
    }

    private static boolean sameFile(Path a, Path b) {
        try {
            return Files.exists(a) && Files.isSameFile(a, b);
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * A file deleted while comando was closed, with no unsaved text kept for it, leaves nothing to show: a document's,
     * or a closed panel's. An open panel of comando's own whose file is missing stays, and gets an empty file: an
     * older comando never wrote a blank panel's file.
     */
    private boolean fileGone(StoredPanel sp, boolean isClosed) {
        if (sp.content() != null || sp.file() == null || sp.file().isBlank()) {
            return false;
        }
        Path f = Path.of(sp.file());
        return (f.isAbsolute() || isClosed) && !Files.exists(f.isAbsolute() ? f : files.dir().resolve(f));
    }

    /** Drops bindings to names the files no longer have. */
    private static List<PanelState> validBindings(List<PanelState> panels, List<ConnectionSpec> c, List<SessionSpec> s) {
        List<String> cn = c.stream().map(ConnectionSpec::name).toList();
        List<String> sn = s.stream().map(SessionSpec::name).toList();
        List<PanelState> out = new ArrayList<>(panels.size());
        for (PanelState p : panels) {
            out.add(p.withConnection(cn.contains(p.connection()) ? p.connection() : "")
                    .withSession(sn.contains(p.session()) ? p.session() : ""));
        }
        return out;
    }

    /**
     * A session keeps its first panel, in panel order; a later panel bound to it keeps its text and loses the binding,
     * told in {@code unbound} as "edge stays in one; two is detached".
     */
    static List<PanelState> oneBindingPerSession(List<PanelState> panels, List<String> unbound) {
        Map<String, String> first = new HashMap<>();
        Map<String, List<String>> detached = new LinkedHashMap<>();
        List<PanelState> out = new ArrayList<>(panels.size());
        for (PanelState p : panels) {
            if (p.session().isEmpty() || first.putIfAbsent(p.session(), p.title()) == null) {
                out.add(p);
            } else {
                detached.computeIfAbsent(p.session(), k -> new ArrayList<>()).add(p.title());
                out.add(p.withSession(""));
            }
        }
        detached.forEach((session, titles) -> unbound.add(session + " stays in " + first.get(session) + "; "
                + String.join(", ", titles) + (titles.size() == 1 ? " is" : " are") + " detached"));
        return out;
    }

    static Loaded defaults() {
        ConnectionSpec local = ConnectionSpec.questDbDefaults("local QuestDB");
        PanelState scratch = new PanelState(PanelId.random(), "scratch", local.name(), "");
        return new Loaded(List.of(scratch), List.of(local), List.of());
    }

    // ---- PersistencePort

    @Override
    public void saveConnections(List<ConnectionSpec> specs) {
        List<ConnectionSpec> copy = List.copyOf(specs);
        if (closed) {
            return;
        }
        try {
            secretsWriter.execute(() -> write(connections, secrets.available() ? toSecretStore(copy) : copy));
        } catch (RejectedExecutionException e) {
            LOG.log(System.Logger.Level.WARNING, "store closed, connections write dropped");
        }
    }

    /** A password stored in the file is empty when the secret store holds it. */
    private ConnectionSpec withSecret(ConnectionSpec spec) {
        if (!secrets.available() || !spec.password().isEmpty()) {
            return spec;
        }
        try {
            return secrets.read(spec.name()).map(spec::withPassword).orElse(spec);
        } catch (Secrets.Unreadable e) {
            // unknown is not "no password": remember it, so the next save does not delete the stored item
            LOG.log(System.Logger.Level.WARNING, "connection password not loaded [connection=" + spec.name() + "]: " + e.getMessage());
            unreadSecrets.add(spec.name());
            return spec;
        }
    }

    /**
     * Moves passwords to the secret store and returns the specs to write, without the passwords it took.
     * A password the store refuses stays in the file rather than being lost. Runs on the store thread.
     */
    private List<ConnectionSpec> toSecretStore(List<ConnectionSpec> specs) {
        Set<String> names = new HashSet<>();
        List<ConnectionSpec> out = new ArrayList<>(specs.size());
        for (ConnectionSpec s : specs) {
            names.add(s.name());
            if (s.password().isEmpty()) {
                if (!unreadSecrets.contains(s.name())) {
                    secrets.delete(s.name());
                }
                out.add(s);
            } else {
                boolean stored = secrets.write(s.name(), s.password());
                if (stored) {
                    unreadSecrets.remove(s.name());
                }
                out.add(stored ? s.withPassword("") : s);
            }
        }
        for (String gone : secretNames) {
            if (!names.contains(gone) && !unreadSecrets.contains(gone)) {
                secrets.delete(gone);
            }
        }
        secretNames = names;
        return out;
    }

    @Override
    public void saveSessions(List<SessionSpec> specs) {
        submit(() -> write(sessions, List.copyOf(specs)));
    }

    /**
     * The Files tab's folders; without a readable {@code folders.json}, the default: Claude Code's folder {@code
     * claudeDir} (an unreadable file is moved aside). A list the user emptied stays empty: no folder is a choice.
     */
    List<FolderRoot> loadFolders(Path claudeDir) {
        List<FolderRoot> read = folders.read();
        foldersSeen = Stamp.of(folders.path());
        if (read == null) {
            return FolderRoot.defaults(claudeDir);
        }
        return valid(read);
    }

    /** The entries of a hand-edited {@code folders.json} that meet the folders' rules; the user is told of the rest. */
    private List<FolderRoot> valid(List<FolderRoot> read) {
        // a name used once, a full path, an index inside the folder
        List<FolderRoot> valid = new ArrayList<>();
        List<String> dropped = new ArrayList<>();
        for (FolderRoot f : read) {
            if (f == null) {
                continue;
            }
            try {
                FolderRoot checked = FolderKind.INSTANCE.fromAttributes(FolderKind.INSTANCE.toAttributes(f));
                if (valid.stream().anyMatch(v -> v.name().equals(checked.name()))) {
                    throw new IllegalArgumentException("the name is used by another folder");
                }
                valid.add(checked);
            } catch (IllegalArgumentException e) {
                dropped.add((f.name().isBlank() ? "a folder without a name" : f.name()) + " (" + e.getMessage() + ")");
            }
        }
        if (!dropped.isEmpty()) {
            LOG.log(System.Logger.Level.WARNING, "folders.json entries left out " + dropped);
            notices.accept("folders.json: left out " + String.join("; ", dropped));
        }
        return List.copyOf(valid);
    }

    @Override
    public void saveFolders(List<FolderRoot> specs) {
        submit(() -> {
            write(folders, List.copyOf(specs));
            foldersSeen = Stamp.of(folders.path());
        });
    }

    /** Where the folders go when the user changed {@code folders.json}: once a second at most, from the writer thread. */
    void onFoldersChanged(Consumer<List<FolderRoot>> listener) {
        foldersChanged = listener;
    }

    /**
     * Reads {@code folders.json} again when it changed since the store last read or wrote it: a change of the user's,
     * in a panel or another editor. A file that does not read keeps the tab's folders and says why; a missing one keeps
     * them silently.
     */
    private void checkFolders() {
        Stamp now = Stamp.of(folders.path());
        if (now == null || now.equals(foldersSeen)) {
            return;
        }
        foldersSeen = now;
        List<FolderRoot> read;
        try {
            read = folders.readAsIs();
        } catch (IOException e) {
            notices.accept("folders.json does not read (" + e.getMessage().lines().findFirst().orElse("") + "); the Files tab keeps its folders");
            return;
        }
        foldersChanged.accept(valid(orEmpty(read)));
    }

    /** A file's size and time of change, to know it changed without reading it. */
    private record Stamp(long modified, long size) {
        static Stamp of(Path file) {
            try {
                return new Stamp(Files.getLastModifiedTime(file).toMillis(), Files.size(file));
            } catch (IOException e) {
                return null;
            }
        }
    }

    @Override
    public void savePanels(List<PanelState> panels, List<PanelState> closed) {
        panelMeta = List.copyOf(panels);
        closedMeta = List.copyOf(closed);
        schedulePanels();
    }

    // ---- PanelTexts

    @Override
    public String load(PanelId panel) {
        return texts.getOrDefault(panel.value(), "");
    }

    @Override
    public void changed(PanelId panel, String text) {
        texts.put(panel.value(), text);
        edits.merge(panel.value(), 1L, Long::sum);
        dirty.add(panel.value());
        schedulePanels();
    }

    /**
     * The panel closed. A panel of comando's own keeps its text here and its file; the save that follows writes what
     * changed. A document's last text reaches its file, and comando lets go of it. The reducer forgets a panel before it
     * drops it from the list, so its title is known.
     */
    @Override
    public void forget(PanelId panel) {
        String id = panel.value();
        if (!files.document(id)) {
            schedulePanels();
            return;
        }
        List<PanelState> meta = panelMeta;
        PanelState was = meta.stream().filter(p -> p.id().value().equals(id)).findFirst()
                .orElse(new PanelState(panel, "panel", "", ""));
        List<String> open = meta.stream().map(p -> p.id().value()).toList();
        submit(() -> {
            String text = texts.getOrDefault(id, "");
            try {
                if (dirty.remove(id) && (files.file(id).isPresent() || !text.isBlank())) {
                    try {
                        files.name(id, was.title(), reserved(open));
                        files.write(id, text);
                    } catch (IOException | RuntimeException e) {
                        // naming (a rename's move, the folder's listing) or writing failed: the text waits in panels.json
                        // the stored name: a document's absolute path, so it comes back on its own file, never in panels/
                        String name = files.storedName(id).orElse(null);
                        unsaved.put(id, new StoredPanel(id, was.title(), was.connection(), was.session(), name, text, null));
                        LOG.log(System.Logger.Level.ERROR, "cannot save the closed panel's file [panel=" + was.title() + "]: " + e.getMessage(), e);
                        notices.accept("Cannot write " + (name == null ? "the file" : name) + " for the closed panel " + was.title() + ": "
                                + reason(e) + "; comando keeps trying, and the panel comes back at the next start if it still cannot");
                        return;
                    }
                }
            } catch (RuntimeException e) {
                LOG.log(System.Logger.Level.ERROR, "cannot save the closed panel's file [panel=" + id + "]: " + e.getMessage(), e);
            } finally {
                failing.remove(id);
                opening.remove(id);
                texts.remove(id);
            }
        });
        schedulePanels();
    }

    /** The panel was removed: its own file is deleted, a document's stays; its text and stamps are dropped. */
    @Override
    public void delete(PanelId panel) {
        String id = panel.value();
        removed.add(id);
        submit(() -> {
            dirty.remove(id);
            edits.remove(id);
            failing.remove(id);
            opening.remove(id);
            unsaved.remove(id);
            texts.remove(id);
            try {
                files.remove(id);
            } catch (IOException | RuntimeException e) {
                LOG.log(System.Logger.Level.ERROR, "cannot delete the removed panel's file [panel=" + id + "]: " + e.getMessage(), e);
                notices.accept("Cannot delete the removed panel's file: " + reason(e));
            }
        });
        schedulePanels();
    }

    @Override
    public Optional<Path> file(PanelId panel) {
        return files.file(panel.value());
    }

    @Override
    public boolean document(PanelId panel) {
        return files.document(panel.value());
    }

    @Override
    public Optional<Path> folder() {
        return Optional.of(files.dir());
    }

    @Override
    public void onOutsideChange(Consumer<Outside> listener) {
        outsideChanges = listener;
    }

    @Override
    public void onDeleted(Consumer<Deleted> listener) {
        deletions = listener;
    }

    /** Checks the open panels' files and {@code folders.json} every second, on the writer thread, so a check never races a write. */
    private void pollFiles() {
        try {
            writer.scheduleWithFixedDelay(this::checkFiles, POLL_MILLIS, POLL_MILLIS, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException e) {
            LOG.log(System.Logger.Level.WARNING, "store closed, panel files not watched");
        }
    }

    /** Runs one check of the open panels' files now, on the writer thread; for tests. */
    CompletableFuture<Void> checkFilesNow() {
        return CompletableFuture.runAsync(this::checkFiles, writer);
    }

    /**
     * Reports each panel's file written outside comando since comando last wrote, read or took it. A panel with
     * unsaved edits is skipped: its save keeps the outside version aside, and writes a deleted file again. A file
     * deleted outside is reported once, for its panel to be removed. A closed panel takes an outside version at once:
     * no editor shows it.
     */
    private void checkFiles() {
        if (closed) {
            return;
        }
        checkFolders();
        List<PanelState> shut = closedMeta;
        List<PanelState> all = new ArrayList<>(panelMeta);
        all.addAll(shut);
        for (PanelState p : all) {
            String id = p.id().value();
            if (dirty.contains(id)) {
                continue;
            }
            try {
                String name = files.file(id).map(f -> f.getFileName().toString()).orElse("");
                if (files.deleted(id)) {
                    // asked on the EDT: an edit after this report keeps the panel, and its save writes the file again;
                    // counted rather than read from dirty, which an autosave clears before the EDT may ask
                    long seen = edits.getOrDefault(id, 0L);
                    deletions.accept(new Deleted(p.id(), name, () -> edits.getOrDefault(id, 0L) == seen));
                    continue;
                }
                if (shut.contains(p)) {
                    files.outside(id).ifPresent(o -> {
                        texts.put(id, o.text());
                        files.accept(id, o.stamp());
                    });
                    continue;
                }
                long seen = edits.getOrDefault(id, 0L);
                files.outside(id).ifPresent(o -> outsideChanges.accept(new Outside(p.id(), name, o.text(), () -> take(id, o, seen))));
            } catch (IOException | RuntimeException e) {
                LOG.log(System.Logger.Level.WARNING, "cannot check the panel's file [panel=" + p.title() + "]: " + e.getMessage());
            }
        }
    }

    /**
     * On the EDT: the panel takes its file's outside text, unless it was edited after the outside version was seen,
     * saved or not (an autosave clears {@code dirty} before the EDT may ask), or has edits not yet saved.
     */
    private boolean take(String id, PanelFiles.Outside o, long seen) {
        if (dirty.contains(id) || edits.getOrDefault(id, 0L) != seen) {
            return false;
        }
        texts.put(id, o.text());
        submit(() -> files.accept(id, o.stamp()));
        return true;
    }

    /**
     * Binds a new panel to a file of the folder on the writer thread: the read stays off the EDT, and comes after a
     * closing panel's last write to the same file, which is queued before it.
     */
    @Override
    public CompletableFuture<Opened> open(PanelId panel, Path file) {
        CompletableFuture<Opened> done = new CompletableFuture<>();
        try {
            writer.execute(() -> {
                try {
                    String name = file.getFileName().toString();
                    Set<String> live = new HashSet<>(opening);
                    panelMeta.forEach(p -> live.add(p.id().value()));
                    closedMeta.forEach(p -> live.add(p.id().value()));
                    // checked and bound on the writer thread, so two opens of one file cannot both bind it
                    for (String other : live) {
                        if (files.file(other).map(f -> PanelTexts.samePath(f, file)).orElse(false)) {
                            done.complete(Opened.alreadyOpen(name));
                            return;
                        }
                    }
                    String id = panel.value();
                    PanelFiles.Result r = files.bind(id, file);
                    if (r.error().isEmpty()) {
                        opening.add(id);
                        texts.put(id, r.text());
                        // a closed panel's text that never reached this file is newer than the file: the panel takes it
                        String stored = files.storedName(id).orElse("");
                        unsaved.values().stream().filter(u -> stored.equalsIgnoreCase(u.file())).findFirst().ifPresent(u -> {
                            texts.put(id, u.content());
                            dirty.add(id);
                            unsaved.remove(u.id());
                            schedulePanels();
                        });
                    }
                    done.complete(Opened.refused(r.error()));
                } catch (RuntimeException e) {
                    done.complete(Opened.refused(reason(e)));
                }
            });
        } catch (RejectedExecutionException e) {
            done.complete(Opened.refused("comando is closing"));
        }
        return done;
    }

    @Override
    public CompletableFuture<String> saveNow() {
        CompletableFuture<String> done = new CompletableFuture<>();
        if (closed) {
            done.complete("comando is closing");
            return done;
        }
        try {
            writer.execute(() -> {
                try {
                    persistPanels();
                    done.complete(null);
                } catch (IOException | RuntimeException e) {
                    LOG.log(System.Logger.Level.ERROR, "cannot save the panels: " + e.getMessage(), e);
                    done.complete(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
                }
            });
        } catch (RejectedExecutionException e) {
            done.complete("comando is closing");
        }
        return done;
    }

    // ---- writing

    private void schedulePanels() {
        schedulePanels(PANEL_DELAY_MILLIS);
    }

    private void schedulePanels(long delayMillis) {
        if (panelsPending.compareAndSet(false, true)) {
            try {
                writer.schedule(this::writePanels, delayMillis, TimeUnit.MILLISECONDS);
            } catch (RejectedExecutionException e) {
                panelsPending.set(false);
            }
        }
    }

    /** The ids whose file names are taken: the given ones and the closed panels still waiting to be written. Writer thread. */
    private List<String> reserved(List<String> ids) {
        List<String> all = new ArrayList<>(ids);
        all.addAll(unsaved.keySet());
        return all;
    }

    /** One file, whatever the spelling: absolute, normalised, ignoring case as APFS does by default. */
    private static String reason(Exception e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }

    private void writePanels() {
        try {
            persistPanels();
        } catch (IOException | RuntimeException e) {
            LOG.log(System.Logger.Level.ERROR, "cannot save the panels: " + e.getMessage(), e);
        }
    }

    /**
     * Names each panel's file, open or closed (a new panel gets one, a renamed one moves it), writes the files whose
     * text changed and a new panel's empty one, then {@code panels.json}. A panel whose file fails keeps its text in {@code panels.json}, which the
     * next start prefers, stays unsaved, and is tried again in 5 s; the user is told once per run of failures. The
     * others are still written; the first failure is thrown at the end.
     */
    private void persistPanels() throws IOException {
        panelsPending.set(false);
        List<PanelState> shut = closedMeta;
        List<PanelState> meta = new ArrayList<>(panelMeta);
        meta.addAll(shut);
        List<String> open = reserved(meta.stream().map(p -> p.id().value()).toList());
        opening.removeAll(open);
        List<StoredPanel> out = new ArrayList<>(meta.size() + unsaved.size());
        IOException failed = null;
        for (PanelState p : meta) {
            String id = p.id().value();
            if (removed.contains(id)) {
                continue;
            }
            Boolean isClosed = shut.contains(p) ? Boolean.TRUE : null;
            String name = files.file(id).map(f -> f.getFileName().toString()).orElse(null);
            String content = null;
            try {
                name = files.name(id, p.title(), open);
                if (dirty.remove(id) || files.unwritten(id)) {
                    String text = texts.getOrDefault(id, "");
                    try {
                        files.write(id, text);
                    } catch (IOException e) {
                        dirty.add(id);
                        content = text;
                        throw e;
                    }
                }
                failing.remove(id);
            } catch (IOException e) {
                LOG.log(System.Logger.Level.ERROR, "cannot save the panel's file [panel=" + p.title() + "]: " + e.getMessage(), e);
                if (failing.add(id)) {
                    notices.accept("Cannot write " + (name == null ? "the file of " + p.title() : name) + ": " + reason(e)
                            + "; the text is kept in panels.json and tried again every few seconds");
                }
                if (content == null && dirty.contains(id)) {
                    content = texts.getOrDefault(id, "");
                }
                failed = failed == null ? e : failed;
            }
            out.add(new StoredPanel(id, p.title(), p.connection(), p.session(), name, content, isClosed));
        }
        for (Iterator<StoredPanel> it = unsaved.values().iterator(); it.hasNext(); ) {
            StoredPanel u = it.next();
            try {
                files.name(u.id(), u.title(), open);
                files.write(u.id(), u.content());
                it.remove();
            } catch (IOException e) {
                out.add(new StoredPanel(u.id(), u.title(), u.connection(), u.session(), files.storedName(u.id()).orElse(null), u.content(), null));
                failed = failed == null ? e : failed;
            }
        }
        try {
            panels.write(out);
            failing.remove(PANELS_JSON);
        } catch (IOException e) {
            LOG.log(System.Logger.Level.ERROR, "cannot save [file=" + panels.path() + "]: " + e.getMessage(), e);
            if (failing.add(PANELS_JSON)) {
                notices.accept("Cannot write panels.json: " + reason(e) + "; the panel list is tried again every few seconds");
            }
            failed = failed == null ? e : failed;
        }
        if (failed != null) {
            if (!closed) {
                schedulePanels(RETRY_MILLIS);
            }
            throw failed;
        }
    }

    private void submit(Runnable task) {
        if (closed) {
            return;
        }
        try {
            writer.execute(task);
        } catch (RejectedExecutionException e) {
            LOG.log(System.Logger.Level.WARNING, "store closed, write dropped");
        }
    }

    /** Writes a settings file; a failure is told, and the next change writes the whole list again. */
    private <T> void write(JsonFile<T> file, T value) {
        try {
            file.write(value);
        } catch (IOException e) {
            LOG.log(System.Logger.Level.ERROR, "cannot save [file=" + file.path() + "]: " + e.getMessage(), e);
            notices.accept("Cannot write " + file.path().getFileName() + ": " + reason(e) + "; it is written again with the next change");
        }
    }

    private static <T> List<T> orEmpty(List<T> list) {
        return list == null ? List.of() : list;
    }

    private static String nz(String s, String fallback) {
        return s == null ? fallback : s;
    }

    /** The writer thread ran its last task and ended; for tests. */
    boolean writerTerminated() {
        return writer.isTerminated();
    }

    /** Writes the panels now and stops the writer after the queued writes. Idempotent. */
    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        writer.execute(this::writePanels);
        writer.shutdown();
        secretsWriter.shutdown();
        try {
            if (!writer.awaitTermination(5, TimeUnit.SECONDS)) {
                LOG.log(System.Logger.Level.WARNING, "store writes still pending at exit");
            }
            // one keychain call's bound plus a margin: a connection saved just before quitting still lands
            if (!secretsWriter.awaitTermination(Secrets.Keychain.TIMEOUT_SECONDS + 1, TimeUnit.SECONDS)) {
                LOG.log(System.Logger.Level.WARNING, "connections write still pending at exit (keychain not answering)");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
