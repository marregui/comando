package dev.marregui.comando.app;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import com.fasterxml.jackson.annotation.JsonInclude;
import tools.jackson.core.type.TypeReference;

import dev.marregui.comando.core.loop.ExecutorStop;
import dev.marregui.comando.core.loop.Threads;
import dev.marregui.comando.core.model.AtomicFiles;
import dev.marregui.comando.core.model.ConnectionSpec;
import dev.marregui.comando.core.model.FolderRoot;
import dev.marregui.comando.core.model.HomeDir;
import dev.marregui.comando.core.model.KitFolders;
import dev.marregui.comando.core.model.KitRoot;
import dev.marregui.comando.core.model.PanelId;
import dev.marregui.comando.core.model.SessionSpec;
import dev.marregui.comando.core.model.VersionedLines;
import dev.marregui.comando.core.port.PersistencePort;
import dev.marregui.comando.core.resource.FolderKind;
import dev.marregui.comando.core.state.PanelState;
import dev.marregui.comando.ui.PanelTexts;
import dev.marregui.comando.ui.PanelTexts.Opened;
import dev.marregui.comando.ui.PanelTexts.Outside;

/**
 * The store files: {@code connections.json}, {@code sessions.json}, {@code folders.json} (the Files tab's folders),
 * {@code panels.json} (each panel's title, bindings and file name, and whether it is closed) and one Markdown file per
 * panel under {@code panels/} with its text ({@link PanelFiles}). A panel of comando's own is kept, closed or open,
 * until it is removed or its file is deleted outside comando; its text stays in memory while it is closed, so it
 * reopens at once. Every write runs on the {@code store} strand from an immutable snapshot, so a save never races an
 * edit; connections go through the {@code store-secrets} strand, for the keychain. Panel text is saved 800 ms after the
 * first unsaved change, so continuous typing saves about every 800 ms, and only the panels that changed are written.
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
     * {@code side} is true for a panel in the right-hand partition, absent for one of the main tabs, so a file without
     * it reads as before (records 133, 141).
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record StoredPanel(
            String id,
            String title,
            String connection,
            String session,
            String file,
            String content,
            Boolean closed,
            Boolean side
    ) {
    }

    /** What the store held at start: the open panels, in tab order, and the closed ones. */
    record Loaded(List<PanelState> panels, List<ConnectionSpec> connections, List<SessionSpec> sessions, List<PanelState> closed) {
        Loaded(final List<PanelState> panels, final List<ConnectionSpec> connections, final List<SessionSpec> sessions) {
            this(panels, connections, sessions, List.of());
        }
    }

    private final JsonFile<List<ConnectionSpec>> connections;
    private final JsonFile<List<SessionSpec>> sessions;
    private final JsonFile<List<FolderRoot>> folders;
    private final JsonFile<List<StoredPanel>> panels;
    /** The kit roots whose folders the Files tab gained at a start, one path a line: each root's are added once. */
    private final Path kitFolders;
    private final ScheduledExecutorService writer = writer();
    /**
     * Connection saves talk to the keychain, which can wait on an unlock prompt for its whole bound; they get a
     * strand of their own, so panel text and sessions are never queued behind a prompt nobody answers.
     */
    private final ExecutorService secretsWriter = Threads.strand("store-secrets");
    private final ConcurrentHashMap<String, String> texts = new ConcurrentHashMap<>();
    /** The files of a later version the user was told of, so each is told once. */
    private final Set<Path> toldRefused = ConcurrentHashMap.newKeySet();

    /**
     * The writer, a timed strand. A delayed write still waiting at shutdown is dropped, not run: {@link #close} writes
     * everything at once, and a retry run later would write into the folder after the store closed. Its thread is
     * virtual, so a daemon: what is queued is written because {@link #close} waits for it, at a quit and in the
     * shutdown hook alike, not because the thread keeps the JVM alive.
     */
    private static ScheduledExecutorService writer() {
        final ScheduledThreadPoolExecutor e = (ScheduledThreadPoolExecutor) Threads.timedStrand("store");
        e.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        return e;
    }
    /** Panels whose text changed since their file was written. */
    private final Set<String> dirty = ConcurrentHashMap.newKeySet();
    /**
     * How many times each panel's text changed: a deletion report removes the panel only when no edit came after it.
     */
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
    /**
     * {@code folders.json} as the store last read or wrote it; null while it is missing. Writer thread, and the start.
     */
    private volatile Stamp foldersSeen;
    /** Whether the last {@link #loadFolders} found no folders file and returned the default list. */
    private volatile boolean foldersDefaulted;
    private final AtomicBoolean panelsPending = new AtomicBoolean();
    private volatile List<PanelState> panelMeta = List.of();
    private volatile List<PanelState> closedMeta = List.of();
    private volatile boolean closed;
    private final Secrets secrets;
    /**
     * Each connection's account in the secret store ({@link #account}), by its name, as of the load and then of the last
     * save; written at the load, before any save, and then by the store-secrets thread only.
     */
    private Map<String, String> secretAccounts = new HashMap<>();
    /**
     * The names whose item under the bare name, the account comando used before an account named its target, was
     * deleted in this run; the store-secrets thread's own.
     */
    private final Set<String> bareDeleted = new HashSet<>();
    /**
     * The connections whose stored password the secret store could not give at start, each as it was read: the target
     * (host, port, database, user) that password was given for. A save keeps such an item only while the connection
     * still names that target; pointed elsewhere, the item is deleted. Whether that delete succeeds or not, the old
     * target's password never follows the connection to the new one at a later start: an item's account names the
     * target it was given for, and a connection reads only its own target's (record 20).
     */
    private final Map<String, ConnectionSpec> unreadSecrets = new ConcurrentHashMap<>();

    Store(final Path home) {
        this(home, Secrets.NONE);
    }

    Store(final Path home, final Secrets secrets) {
        this.secrets = secrets;
        // each file's schema and version: record 133
        connections = new JsonFile<>(home.resolve("connections.json"), "connections", 1, new TypeReference<>() {
        });
        sessions = new JsonFile<>(home.resolve("sessions.json"), "sessions", 1, new TypeReference<>() {
        });
        folders = new JsonFile<>(home.resolve("folders.json"), "folders", 1, new TypeReference<>() {
        });
        panels = new JsonFile<>(home.resolve("panels.json"), "panels", 1, new TypeReference<>() {
        });
        kitFolders = home.resolve("kit-folders");
        files = new PanelFiles(home.resolve("panels"), text -> notices.accept(text));
    }

    /**
     * Where the store reports what the user should know, such as a panel file changed outside comando. What the start
     * found before there was anyone to tell (a panel whose file was deleted while comando was closed) is told now.
     */
    void onNotice(final Consumer<String> notices) {
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
    private void keep(final String text) {
        synchronized (early) {
            if (!listening) {
                early.add(text);
                return;
            }
        }
        notices.accept(text);
    }

    /**
     * Reads the files; a fresh data root gets the defaults: a local QuestDB and a scratch panel with the welcome text.
     */
    Loaded load() {
        final boolean fresh = !connections.exists() && !sessions.exists() && !panels.exists();
        if (fresh) {
            final Loaded seeded = defaults();
            for (final PanelState p : seeded.panels()) {
                texts.putIfAbsent(p.id().value(), Welcome.TEXT);
                dirty.add(p.id().value());
            }
            // a panels folder left without its JSON files: its files are panels too
            final List<PanelState> shut = new ArrayList<>();
            adoptLooseFiles(List.of(), shut);
            panelMeta = seeded.panels();
            closedMeta = List.copyOf(shut);
            pollFiles();
            saveConnections(seeded.connections());
            saveSessions(seeded.sessions());
            schedulePanels();
            return new Loaded(seeded.panels(), seeded.connections(), seeded.sessions(), shut);
        }
        final List<ConnectionSpec> c = orEmpty(connections.read()).stream().map(this::withSecret).toList();
        final Map<String, String> accounts = new HashMap<>();
        c.forEach(x -> accounts.put(x.name(), account(x)));
        secretAccounts = accounts;
        final List<SessionSpec> s = orEmpty(sessions.read());
        tellRefused(connections, sessions);
        // a panel binds to a connection and a session by name: with either file of a later version unread, the bindings
        // are blanked for this run only, and panels.json is kept as it is so a later comando finds them (record 133)
        for (final JsonFile<?> later : List.of(connections, sessions)) {
            if (!later.refused().isEmpty()) {
                panels.refuse(PANELS_JSON + " is kept as it is and not written while comando runs: its panels name what "
                        + later.path().getFileName() + " holds, which is of a later version");
            }
        }
        List<PanelState> p = new ArrayList<>();
        List<PanelState> shut = new ArrayList<>();
        final List<String> gone = new ArrayList<>();
        // read once: the file holds the text of panels whose file could not be written, which grows with it
        final List<StoredPanel> stored = orEmpty(panels.read());
        for (final StoredPanel sp : stored) {
            if (sp.id() == null || sp.id().isBlank()) {
                continue;
            }
            final boolean isClosed = Boolean.TRUE.equals(sp.closed());
            if (fileGone(sp, isClosed)) {
                gone.add(Path.of(sp.file()).getFileName().toString());
                continue;
            }
            final String title = nz(sp.title(), "panel");
            final boolean document = sp.file() != null && Path.of(sp.file()).isAbsolute();
            final boolean side = Boolean.TRUE.equals(sp.side());
            final PanelState panel = new PanelState(new PanelId(sp.id()), title, nz(sp.connection(), ""),
                    nz(sp.session(), ""), document, side, false);
            (isClosed ? shut : p).add(panel);
            texts.put(sp.id(), textOf(sp, title));
        }
        tellRefused(panels);
        final List<String> adopted = adoptLooseFiles(stored, shut);
        if (!adopted.isEmpty()) {
            LOG.log(System.Logger.Level.INFO, "files in panels/ without a panel, listed as closed panels [files=" + adopted + ']');
            notices.accept(String.join(", ", adopted) + (adopted.size() == 1 ? " has" : " have")
                    + " no panel; listed as closed under the sessions, without a session");
        }
        if (!gone.isEmpty()) {
            LOG.log(System.Logger.Level.INFO, "files deleted while comando was closed, their panels dropped [files=" + gone + ']');
            notices.accept(String.join(", ", gone) + (gone.size() == 1 ? " was" : " were")
                    + " deleted while comando was closed; " + (gone.size() == 1 ? "its panel is" : "their panels are") + " gone");
        }
        final int openCount = p.size();
        List<PanelState> all = new ArrayList<>(p);
        all.addAll(shut);
        all = validBindings(all, c, s);
        final List<String> unbound = new ArrayList<>();
        // the open panels come first, so a session bound to an open and a closed panel keeps the open one
        all = oneBindingPerSession(all, unbound);
        p = new ArrayList<>(all.subList(0, openCount));
        shut = new ArrayList<>(all.subList(openCount, all.size()));
        if (!unbound.isEmpty()) {
            LOG.log(System.Logger.Level.INFO, "sessions bound to several panels keep their first [sessions=" + unbound + ']');
            notices.accept("A session has one panel now: " + String.join("; ", unbound));
            schedulePanels();
        }
        if (!p.isEmpty() && p.stream().allMatch(PanelState::side)) {
            // the main tabs keep one panel at least: the last one there may have been a preview, which is not saved
            p.set(0, p.getFirst().withSide(false));
        }
        if (p.isEmpty()) {
            final PanelState first = new PanelState(PanelId.random(), "scratch", c.isEmpty() ? "" : c.getFirst().name(), "");
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
    private String textOf(final StoredPanel sp, final String title) {
        String fromFile = "";
        if (sp.file() != null && !sp.file().isBlank()) {
            try {
                fromFile = files.read(sp.id(), title, sp.file());
            } catch (final IOException | RuntimeException e) {
                LOG.log(System.Logger.Level.ERROR, "cannot read the panel file, the panel saves to another file [file="
                        + files.dir().resolve(sp.file()) + ']', e);
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
    private List<String> adoptLooseFiles(final List<StoredPanel> stored, final List<PanelState> shut) {
        // a stored name names a file when that file exists and is the same file: case-insensitive on APFS by default,
        // exact on a case-sensitive file system, where a stored name differing in case leaves the file loose
        final List<Path> named = new ArrayList<>();
        for (final StoredPanel sp : stored) {
            if (sp.file() != null && !Path.of(sp.file()).isAbsolute()) {
                named.add(files.dir().resolve(sp.file()));
            }
        }
        final List<String> adopted = new ArrayList<>();
        if (!Files.isDirectory(files.dir())) {
            return adopted;
        }
        try (final var listing = Files.list(files.dir())) {
            for (final Path f : listing.sorted().toList()) {
                final String name = f.getFileName().toString();
                if (name.startsWith(".") || !Files.isRegularFile(f) || named.stream().anyMatch(n -> sameFile(n, f))) {
                    continue;
                }
                final PanelId id = PanelId.random();
                final String title = PanelFiles.title(f);
                try {
                    texts.put(id.value(), files.read(id.value(), title, name));
                    shut.add(new PanelState(id, title, "", ""));
                    adopted.add(name);
                } catch (final IOException | RuntimeException e) {
                    LOG.log(System.Logger.Level.WARNING, "cannot read a file of panels/, left alone [file=" + f + ']', e);
                }
            }
        } catch (final IOException e) {
            LOG.log(System.Logger.Level.WARNING, "cannot list panels/", e);
        }
        return adopted;
    }

    private static boolean sameFile(final Path a, final Path b) {
        try {
            return Files.exists(a) && Files.isSameFile(a, b);
        } catch (final IOException e) {
            return false;
        }
    }

    /**
     * A file deleted while comando was closed, with no unsaved text kept for it, leaves nothing to show: a document's,
     * or a closed panel's. An open panel of comando's own whose file is missing stays, and gets an empty file: an
     * older comando never wrote a blank panel's file.
     */
    private boolean fileGone(final StoredPanel sp, final boolean isClosed) {
        if (sp.content() != null || sp.file() == null || sp.file().isBlank()) {
            return false;
        }
        final Path f = Path.of(sp.file());
        return (f.isAbsolute() || isClosed) && !Files.exists(f.isAbsolute() ? f : files.dir().resolve(f));
    }

    /** Drops bindings to names the files no longer have. */
    private static List<PanelState> validBindings(final List<PanelState> panels, final List<ConnectionSpec> c, final List<SessionSpec> s) {
        final List<String> cn = c.stream().map(ConnectionSpec::name).toList();
        final List<String> sn = s.stream().map(SessionSpec::name).toList();
        final List<PanelState> out = new ArrayList<>(panels.size());
        for (final PanelState p : panels) {
            out.add(p.withConnection(cn.contains(p.connection()) ? p.connection() : "")
                    .withSession(sn.contains(p.session()) ? p.session() : ""));
        }
        return out;
    }

    /**
     * A session keeps its first panel, in panel order; a later panel bound to it keeps its text and loses the binding,
     * told in {@code unbound} as "work stays in one; two is detached".
     */
    static List<PanelState> oneBindingPerSession(final List<PanelState> panels, final List<String> unbound) {
        final Map<String, String> first = new HashMap<>();
        final Map<String, List<String>> detached = new LinkedHashMap<>();
        final List<PanelState> out = new ArrayList<>(panels.size());
        for (final PanelState p : panels) {
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
        final ConnectionSpec local = ConnectionSpec.questDbDefaults("local QuestDB");
        final PanelState scratch = new PanelState(PanelId.random(), "scratch", local.name(), "");
        return new Loaded(List.of(scratch), List.of(local), List.of());
    }

    @Override
    public void saveConnections(final List<ConnectionSpec> specs) {
        final List<ConnectionSpec> copy = List.copyOf(specs);
        if (closed) {
            return;
        }
        try {
            // a connections.json of a later version is kept as it is, and so are the secret store's items it names
            // (record 133): nothing is moved, written or deleted there while it is
            secretsWriter.execute(() -> write(connections, secrets.available() && connections.refused().isEmpty() ? toSecretStore(copy) : copy));
        } catch (final RejectedExecutionException e) {
            LOG.log(System.Logger.Level.WARNING, "store closed, connections write dropped");
        }
    }

    /**
     * A password stored in the file is empty when the secret store holds it, under the account of the connection's name
     * and target: an item given for another target is never read for this one.
     */
    private ConnectionSpec withSecret(final ConnectionSpec spec) {
        if (!secrets.available() || !spec.password().isEmpty()) {
            return spec;
        }
        try {
            return secrets.read(account(spec)).map(spec::withPassword).orElse(spec);
        } catch (final Secrets.Unreadable e) {
            // unknown is not "no password": remember it, so the next save does not delete the stored item
            LOG.log(System.Logger.Level.WARNING, "connection password not loaded [connection=" + spec.name() + ']', e);
            unreadSecrets.put(spec.name(), spec);
            return spec;
        }
    }

    /**
     * Moves passwords to the secret store and returns the specs to write, without the passwords it took.
     * A password the store refuses stays in the file rather than being lost. Runs on the {@code store-secrets} strand.
     */
    private List<ConnectionSpec> toSecretStore(final List<ConnectionSpec> specs) {
        final Map<String, String> accounts = new HashMap<>();
        final List<ConnectionSpec> out = new ArrayList<>(specs.size());
        for (final ConnectionSpec s : specs) {
            final String account = account(s);
            final String before = secretAccounts.get(s.name());
            final ConnectionSpec unread = unreadSecrets.get(s.name());
            accounts.put(s.name(), account);
            if (s.password().isEmpty()) {
                if (unread == null || !unread.sameTarget(s)) {
                    secrets.delete(before != null ? before : account);
                    unreadSecrets.remove(s.name());
                }
                out.add(s);
            } else {
                final boolean stored = secrets.write(account, s.password());
                if (stored) {
                    unreadSecrets.remove(s.name());
                }
                if (before != null && !before.equals(account)) {
                    // the item of the target the connection named before
                    secrets.delete(before);
                }
                out.add(stored ? s.withPassword("") : s);
            }
        }
        // an item under the bare name names no target, so it is never read; it is deleted once a run
        for (final String name : accounts.keySet()) {
            if (bareDeleted.add(name) && !accounts.containsValue(name)) {
                secrets.delete(name);
            }
        }
        secretAccounts.forEach((gone, account) -> {
            if (!accounts.containsKey(gone) && !unreadSecrets.containsKey(gone)) {
                secrets.delete(account);
            }
        });
        secretAccounts = accounts;
        return out;
    }

    /**
     * The account in the secret store of {@code spec}'s password: the connection's name and the target the password was
     * given for, {@code name user@host:port/database}, each part URL-encoded so no two connections share one. A
     * connection pointed elsewhere has another account, so the item of its old target, should deleting it fail (a locked
     * keychain), is never read for the new one (record 20).
     */
    static String account(final ConnectionSpec spec) {
        return encoded(spec.name()) + ' ' + encoded(spec.user()) + '@' + encoded(spec.host()) + ':' + spec.port() + '/'
                + encoded(spec.database());
    }

    private static String encoded(final String part) {
        return URLEncoder.encode(part, StandardCharsets.UTF_8);
    }

    @Override
    public void saveSessions(final List<SessionSpec> specs) {
        submit(() -> write(sessions, List.copyOf(specs)));
    }

    /**
     * The Files tab's folders; without a readable {@code folders.json}, the default: Claude Code's folder {@code
     * claudeDir} (an unreadable file is moved aside). A list the user emptied stays empty: no folder is a choice.
     */
    List<FolderRoot> loadFolders(final Path claudeDir) {
        final List<FolderRoot> read = folders.read();
        tellRefused(folders);
        foldersSeen = Stamp.of(folders.path());
        foldersDefaulted = read == null;
        if (read == null) {
            return FolderRoot.defaults(claudeDir);
        }
        return valid(read);
    }

    /**
     * The folders at a start on a kit's root. At the first start on a root, when {@code loaded} shows none of the kit's
     * folders, the kit's go first and are saved; the user's own entries stay. Once per root, so a folder the user takes
     * out stays out; the folders' window puts them back. With no folders file the default list is not the user's: the
     * kit's folders replace it, since its one folder is the kit's own root and would otherwise count as the kit already
     * shown. At every start, a kit folder that still shows where the kit put it last follows the kit and the brain to
     * where they put it now ({@link #follow}): a brain moved by {@code COMANDO_BRAIN} or the manifest, or another kit,
     * takes its folders with it (Miguel's decision D10 of the first-release review).
     */
    List<FolderRoot> withKitFolders(final List<FolderRoot> loaded, final KitRoot root) {
        final List<FolderRoot> kit = root.folders();
        final String key = root.dir().toString();
        final VersionedLines.Read done = kitRootsDone();
        // a file of a later comando is kept as it is: the kit's folders are not added again behind its back
        if (!done.refused().isEmpty()) {
            if (toldRefused.add(kitFolders)) {
                notices.accept(done.refused());
            }
            return loaded;
        }
        if (kit.isEmpty()) {
            return loaded;
        }
        final KitPlaces before = KitPlaces.of(done.lines());
        // with no folders file the user never chose a list: the kit's folders apply even on a root recorded before, so
        // a home whose root was recorded while the default list hid them gets them
        final boolean first = foldersDefaulted || !before.roots().contains(key);
        final List<FolderRoot> placed = first ? KitFolders.atStart(foldersDefaulted ? List.of() : loaded, kit) : loaded;
        final List<FolderRoot> merged = follow(placed, before.paths(), kit);
        final KitPlaces now = before.with(key, kit);
        if (!now.equals(before)) {
            try {
                Files.createDirectories(kitFolders.getParent());
                AtomicFiles.writeString(kitFolders, VersionedLines.text(KIT_FOLDERS, KIT_FOLDERS_VERSION, now.lines()));
            } catch (final IOException e) {
                // added again at the next start: the user's list is still kept
                LOG.log(System.Logger.Level.WARNING, "cannot write [file=" + kitFolders + ']', e);
            }
        }
        if (!placed.equals(loaded)) {
            LOG.log(System.Logger.Level.INFO, "the Files tab gains the kit's folders [root=" + key + ", folders="
                    + KitFolders.missing(loaded, kit).stream().map(FolderRoot::name).toList() + ']');
        }
        if (!merged.equals(placed)) {
            LOG.log(System.Logger.Level.INFO, "the kit's folders follow the kit and the brain [root=" + key + ", folders="
                    + merged.stream().filter(f -> !placed.contains(f)).map(FolderRoot::name).toList() + ']');
        }
        if (!merged.equals(loaded)) {
            saveFolders(merged);
        }
        return merged;
    }

    /**
     * {@code have} with each folder that shows where the kit put its folder of that name last ({@code last}, by name)
     * moved to where the kit puts it now; a folder the user pointed elsewhere, or that the kit no longer has, stays as
     * it is.
     */
    static List<FolderRoot> follow(final List<FolderRoot> have, final Map<String, String> last, final List<FolderRoot> kit) {
        final Map<String, String> now = new HashMap<>();
        kit.forEach(k -> now.put(k.name(), k.path()));
        final List<FolderRoot> out = new ArrayList<>(have.size());
        for (final FolderRoot f : have) {
            final String was = last.get(f.name());
            final String to = now.get(f.name());
            final boolean moves = was != null && to != null && !samePath(was, to) && samePath(f.path(), was);
            out.add(moves ? new FolderRoot(f.name(), to, f.index(), f.excludes(), f.icon(), f.deletable(), f.listedIn(), f.group()) : f);
        }
        return List.copyOf(out);
    }

    private static boolean samePath(final String a, final String b) {
        return Path.of(HomeDir.expand(a)).toAbsolutePath().normalize().equals(Path.of(HomeDir.expand(b)).toAbsolutePath().normalize());
    }

    /** {@code kit-folders}: the kit roots whose folders were added once, and where the kit put each folder last. */
    private VersionedLines.Read kitRootsDone() {
        try {
            return VersionedLines.read(kitFolders, KIT_FOLDERS_VERSION);
        } catch (final IOException e) {
            LOG.log(System.Logger.Level.WARNING, "cannot read [file=" + kitFolders + ']', e);
            return new VersionedLines.Read(List.of(), "");
        }
    }

    /**
     * {@code kit-folders}' format and version (record 133): a kit root a line, the roots whose folders the Files tab
     * gained once; and, since version 2, a line {@code folder<TAB>name<TAB>path} for each folder of the kit comando ran
     * on last, where the kit put it. A file of version 1 has roots only.
     */
    static final String KIT_FOLDERS = "kit-folders";
    static final int KIT_FOLDERS_VERSION = 2;

    /**
     * What {@code kit-folders} holds: the roots whose folders were added once, in order, and the path of each folder of
     * the kit comando ran on last, by name.
     */
    record KitPlaces(List<String> roots, Map<String, String> paths) {

        /** The start of a line that records a folder's place. */
        static final String FOLDER = "folder\t";

        KitPlaces {
            roots = List.copyOf(roots);
            paths = Collections.unmodifiableMap(new LinkedHashMap<>(paths));
        }

        /** The places as {@code kit-folders}' lines give them. */
        static KitPlaces of(final List<String> lines) {
            final List<String> roots = new ArrayList<>();
            final Map<String, String> paths = new LinkedHashMap<>();
            for (final String l : lines) {
                if (!l.startsWith(FOLDER)) {
                    roots.add(l);
                    continue;
                }
                final String[] parts = l.substring(FOLDER.length()).split("\t", 2);
                if (parts.length == 2) {
                    paths.put(parts[0], parts[1]);
                }
            }
            return new KitPlaces(roots, paths);
        }

        /**
         * These places with {@code root} recorded and the folders' places replaced by {@code kit}'s; a folder whose name
         * or path holds a tab or a line break is not recorded, and so never follows.
         */
        KitPlaces with(final String root, final List<FolderRoot> kit) {
            final List<String> r = new ArrayList<>(roots);
            if (!r.contains(root)) {
                r.add(root);
            }
            final Map<String, String> p = new LinkedHashMap<>();
            for (final FolderRoot k : kit) {
                if (recordable(k.name()) && recordable(k.path())) {
                    p.put(k.name(), k.path());
                }
            }
            return new KitPlaces(r, p);
        }

        private static boolean recordable(final String text) {
            return text.indexOf('\t') < 0 && text.indexOf('\n') < 0 && text.indexOf('\r') < 0 && text.strip().equals(text);
        }

        /** The lines of {@code kit-folders}: the roots, then the folders' places. */
        List<String> lines() {
            final List<String> out = new ArrayList<>(roots);
            paths.forEach((name, path) -> out.add(FOLDER + name + '\t' + path));
            return out;
        }
    }

    /** The entries of a hand-edited {@code folders.json} that meet the folders' rules; the user is told of the rest. */
    private List<FolderRoot> valid(final List<FolderRoot> read) {
        // a name used once, a full path, an index inside the folder
        final List<FolderRoot> valid = new ArrayList<>();
        final List<String> dropped = new ArrayList<>();
        for (final FolderRoot f : read) {
            if (f == null) {
                continue;
            }
            try {
                final FolderRoot checked = FolderKind.INSTANCE.fromAttributes(FolderKind.INSTANCE.toAttributes(f));
                if (valid.stream().anyMatch(v -> v.name().equals(checked.name()))) {
                    throw new IllegalArgumentException("the name is used by another folder");
                }
                valid.add(checked);
            } catch (final IllegalArgumentException e) {
                dropped.add((f.name().isBlank() ? "a folder without a name" : f.name()) + " (" + e.getMessage() + ")");
            }
        }
        if (!dropped.isEmpty()) {
            LOG.log(System.Logger.Level.WARNING, "folders.json entries left out [entries=" + dropped + ']');
            notices.accept("folders.json: left out " + String.join("; ", dropped));
        }
        return List.copyOf(valid);
    }

    @Override
    public void saveFolders(final List<FolderRoot> specs) {
        submit(() -> {
            write(folders, List.copyOf(specs));
            foldersSeen = Stamp.of(folders.path());
            createIndexes(specs, indexRoots(KitRoot.current())).forEach(f -> LOG.log(System.Logger.Level.INFO, "created the index [file=" + f + ']'));
        });
    }

    /**
     * Tells the user of each file kept as it is because a later comando wrote it (record 133), once, as it is read at
     * the start.
     */
    @SafeVarargs
    private void tellRefused(final JsonFile<? extends List<?>>... files) {
        for (final JsonFile<? extends List<?>> f : files) {
            if (!f.refused().isEmpty() && toldRefused.add(f.path())) {
                notices.accept(f.refused());
            }
        }
    }

    /**
     * Where saving the folders creates a missing index file: the kit's folder and its brain, which may lie anywhere
     * (D10); nowhere without a kit, so a user's own Claude Code folder never gains a {@code CLAUDE.md} that every session
     * of theirs would then load.
     */
    static List<Path> indexRoots(final KitRoot root) {
        if (root.kit().isEmpty()) {
            return List.of();
        }
        final List<Path> roots = new ArrayList<>(List.of(root.dir()));
        root.brain().ifPresent(roots::add);
        return List.copyOf(roots);
    }

    /** As {@link #createIndexes(List, List)} for the folders inside {@code claudeDir} alone. */
    static List<Path> createIndexes(final List<FolderRoot> specs, final Path claudeDir) {
        return createIndexes(specs, List.of(claudeDir));
    }

    /**
     * Creates the index file each folder inside one of {@code roots} (the kit's folder, the brain) names but does not
     * have, when the file's own folder exists: a title and how the file is kept, which {@code /reflect} then fills.
     * Folders elsewhere and files that exist are left as they are; a file that cannot be written is logged. Answers the
     * files created.
     */
    static List<Path> createIndexes(final List<FolderRoot> specs, final List<Path> roots) {
        final List<Path> inside = roots.stream().map(r -> r.toAbsolutePath().normalize()).toList();
        final List<Path> created = new ArrayList<>();
        for (final FolderRoot f : specs) {
            final Path dir = Path.of(f.path()).toAbsolutePath().normalize();
            final Optional<Path> index = f.indexFile().map(i -> i.toAbsolutePath().normalize());
            if (inside.stream().noneMatch(dir::startsWith) || index.isEmpty() || !index.get().startsWith(dir)
                    || Files.exists(index.get()) || !Files.isDirectory(index.get().getParent())) {
                continue;
            }
            try {
                Files.writeString(index.get(), "# " + f.name() + "\n\nThe index of `" + HomeDir.shorten(dir.toString())
                        + "`: one line per entry, `- [name](path) — what it holds`. `/reflect` keeps it current.\n",
                        StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
                created.add(index.get());
            } catch (final IOException | RuntimeException e) {
                LOG.log(System.Logger.Level.WARNING, "cannot create the index [file=" + index.get() + ']', e);
            }
        }
        return created;
    }

    /**
     * Where the folders go when the user changed {@code folders.json}: once a second at most, from the writer thread.
     */
    void onFoldersChanged(final Consumer<List<FolderRoot>> listener) {
        foldersChanged = listener;
    }

    /**
     * Reads {@code folders.json} again when it changed since the store last read or wrote it: a change of the user's,
     * in a panel or another editor. A file that does not read keeps the tab's folders and says why; a missing one keeps
     * them silently.
     */
    private void checkFolders() {
        final Stamp now = Stamp.of(folders.path());
        if (now == null || now.equals(foldersSeen)) {
            return;
        }
        foldersSeen = now;
        List<FolderRoot> read;
        try {
            read = folders.readAsIs();
        } catch (final IOException e) {
            notices.accept("folders.json does not read (" + e.getMessage().lines().findFirst().orElse("")
                    + "); the sidebar keeps its folders");
            return;
        }
        foldersChanged.accept(valid(orEmpty(read)));
    }

    /** A file's size and time of change, to know it changed without reading it. */
    private record Stamp(long modified, long size) {
        static Stamp of(final Path file) {
            try {
                return new Stamp(Files.getLastModifiedTime(file).toMillis(), Files.size(file));
            } catch (final IOException e) {
                return null;
            }
        }
    }

    @Override
    public void savePanels(final List<PanelState> panels, final List<PanelState> closed) {
        panelMeta = List.copyOf(panels);
        closedMeta = List.copyOf(closed);
        schedulePanels();
    }

    @Override
    public String load(final PanelId panel) {
        return texts.getOrDefault(panel.value(), "");
    }

    @Override
    public void changed(final PanelId panel, final String text) {
        texts.put(panel.value(), text);
        edits.merge(panel.value(), 1L, Long::sum);
        dirty.add(panel.value());
        schedulePanels();
    }

    /**
     * The panel closed. A panel of comando's own keeps its text here and its file; the save that follows writes what
     * changed. A document's last text reaches its file, and comando lets go of it. The reducer forgets a panel before
     * it drops it from the list, so its title is known.
     */
    @Override
    public void forget(final PanelId panel) {
        final String id = panel.value();
        if (!files.document(id)) {
            schedulePanels();
            return;
        }
        final List<PanelState> meta = panelMeta;
        final PanelState was = meta.stream().filter(p -> p.id().value().equals(id)).findFirst()
                .orElse(new PanelState(panel, "panel", "", ""));
        final List<String> open = meta.stream().map(p -> p.id().value()).toList();
        submit(() -> {
            final String text = texts.getOrDefault(id, "");
            try {
                if (dirty.remove(id) && (files.file(id).isPresent() || !text.isBlank())) {
                    try {
                        files.name(id, was.title(), reserved(open));
                        files.write(id, text);
                    } catch (final IOException | RuntimeException e) {
                        // naming (a rename's move, the folder's listing) or writing failed: the text waits in
                        // panels.json under the stored name, a document's absolute path, so it comes back on its own
                        // file, never in panels/
                        final String name = files.storedName(id).orElse(null);
                        unsaved.put(id, new StoredPanel(id, was.title(), was.connection(), was.session(), name,
                                text, null, side(was)));
                        LOG.log(System.Logger.Level.ERROR, "cannot save the closed panel's file [panel=" + was.title() + ']', e);
                        notices.accept("Cannot write " + (name == null ? "the file" : name) + " for the closed panel " + was.title() + ": "
                                + reason(e) + "; comando keeps trying, and the panel comes back at the next start if it still cannot");
                        return;
                    }
                }
            } catch (final RuntimeException e) {
                LOG.log(System.Logger.Level.ERROR, "cannot save the closed panel's file [panel=" + id + ']', e);
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
    public void delete(final PanelId panel) {
        final String id = panel.value();
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
            } catch (final IOException | RuntimeException e) {
                LOG.log(System.Logger.Level.ERROR, "cannot delete the removed panel's file [panel=" + id + ']', e);
                notices.accept("Cannot delete the removed panel's file: " + reason(e));
            }
        });
        schedulePanels();
    }

    @Override
    public Optional<Path> file(final PanelId panel) {
        return files.file(panel.value());
    }

    @Override
    public boolean document(final PanelId panel) {
        return files.document(panel.value());
    }

    @Override
    public Optional<Path> folder() {
        return Optional.of(files.dir());
    }

    @Override
    public void onOutsideChange(final Consumer<Outside> listener) {
        outsideChanges = listener;
    }

    @Override
    public void onDeleted(final Consumer<Deleted> listener) {
        deletions = listener;
    }

    /** Checks open panels' files and {@code folders.json} each second on the writer thread: no check races a write. */
    private void pollFiles() {
        try {
            writer.scheduleWithFixedDelay(this::checkFiles, POLL_MILLIS, POLL_MILLIS, TimeUnit.MILLISECONDS);
        } catch (final RejectedExecutionException e) {
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
        final List<PanelState> shut = closedMeta;
        final List<PanelState> all = new ArrayList<>(panelMeta);
        all.addAll(shut);
        for (final PanelState p : all) {
            final String id = p.id().value();
            if (dirty.contains(id)) {
                continue;
            }
            try {
                final String name = files.file(id).map(f -> f.getFileName().toString()).orElse("");
                if (files.deleted(id)) {
                    // asked on the EDT: an edit after this report keeps the panel, and its save writes the file again;
                    // counted rather than read from dirty, which an autosave clears before the EDT may ask
                    final long seen = edits.getOrDefault(id, 0L);
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
                final long seen = edits.getOrDefault(id, 0L);
                files.outside(id).ifPresent(o -> outsideChanges.accept(new Outside(p.id(), name, o.text(), () -> take(id, o, seen))));
            } catch (final IOException | RuntimeException e) {
                LOG.log(System.Logger.Level.WARNING, "cannot check the panel's file [panel=" + p.title() + ']', e);
            }
        }
    }

    /**
     * On the EDT: the panel takes its file's outside text, unless it was edited after the outside version was seen,
     * saved or not (an autosave clears {@code dirty} before the EDT may ask), or has edits not yet saved.
     */
    private boolean take(final String id, final PanelFiles.Outside o, final long seen) {
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
    public CompletableFuture<Opened> open(final PanelId panel, final Path file) {
        final CompletableFuture<Opened> done = new CompletableFuture<>();
        try {
            writer.execute(() -> {
                try {
                    final String name = file.getFileName().toString();
                    final Set<String> live = new HashSet<>(opening);
                    panelMeta.forEach(p -> live.add(p.id().value()));
                    closedMeta.forEach(p -> live.add(p.id().value()));
                    // checked and bound on the writer thread, so two opens of one file cannot both bind it
                    for (final String other : live) {
                        if (files.file(other).map(f -> PanelTexts.samePath(f, file)).orElse(false)) {
                            done.complete(Opened.alreadyOpen(name));
                            return;
                        }
                    }
                    final String id = panel.value();
                    final PanelFiles.Result r = files.bind(id, file);
                    if (r.error().isEmpty()) {
                        opening.add(id);
                        texts.put(id, r.text());
                        // a closed panel's text that never reached this file is newer than the file: the panel takes it
                        final String stored = files.storedName(id).orElse("");
                        unsaved.values().stream().filter(u -> stored.equalsIgnoreCase(u.file())).findFirst().ifPresent(u -> {
                            texts.put(id, u.content());
                            dirty.add(id);
                            unsaved.remove(u.id());
                            schedulePanels();
                        });
                    }
                    done.complete(Opened.refused(r.error()));
                } catch (final RuntimeException e) {
                    done.complete(Opened.refused(reason(e)));
                }
            });
        } catch (final RejectedExecutionException e) {
            done.complete(Opened.refused("comando is closing"));
        }
        return done;
    }

    /**
     * Writes a file the diff window edited, on the writer thread, so it never races a panel's write of the same file.
     */
    @Override
    public CompletableFuture<String> write(final Path file, final String text) {
        final CompletableFuture<String> done = new CompletableFuture<>();
        try {
            writer.execute(() -> {
                try {
                    PanelFiles.replace(file.toAbsolutePath().normalize(), text);
                    done.complete(null);
                } catch (final IOException | RuntimeException e) {
                    LOG.log(System.Logger.Level.ERROR, "cannot write [file=" + file + ']', e);
                    done.complete(reason(e));
                }
            });
        } catch (final RejectedExecutionException e) {
            done.complete("comando is closing");
        }
        return done;
    }

    @Override
    public CompletableFuture<String> saveNow() {
        final CompletableFuture<String> done = new CompletableFuture<>();
        if (closed) {
            done.complete("comando is closing");
            return done;
        }
        try {
            writer.execute(() -> {
                try {
                    persistPanels();
                    done.complete(null);
                } catch (final IOException | RuntimeException e) {
                    LOG.log(System.Logger.Level.ERROR, "cannot save the panels", e);
                    done.complete(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
                }
            });
        } catch (final RejectedExecutionException e) {
            done.complete("comando is closing");
        }
        return done;
    }

    private void schedulePanels() {
        schedulePanels(PANEL_DELAY_MILLIS);
    }

    private void schedulePanels(final long delayMillis) {
        if (panelsPending.compareAndSet(false, true)) {
            try {
                writer.schedule(this::writePanels, delayMillis, TimeUnit.MILLISECONDS);
            } catch (final RejectedExecutionException e) {
                panelsPending.set(false);
            }
        }
    }

    /** The ids whose file names are taken: the given ones and the closed panels still to be written. Writer thread. */
    private List<String> reserved(final List<String> ids) {
        final List<String> all = new ArrayList<>(ids);
        all.addAll(unsaved.keySet());
        return all;
    }

    /** {@code side} as stored: true for a panel in the right-hand partition, absent for one of the main tabs. */
    private static Boolean side(final PanelState p) {
        return p.side() ? Boolean.TRUE : null;
    }

    /** An exception's message, else its type's name. */
    private static String reason(final Exception e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }

    private void writePanels() {
        try {
            persistPanels();
        } catch (final IOException | RuntimeException e) {
            LOG.log(System.Logger.Level.ERROR, "cannot save the panels", e);
        }
    }

    /**
     * Names each panel's file, open or closed (a new panel gets one, a renamed one moves it), writes the files whose
     * text changed and a new panel's empty one, then {@code panels.json}. A panel whose file fails keeps its text in
     * {@code panels.json}, which the next start prefers, stays unsaved, and is tried again in 5 s; the user is told
     * once per run of failures. The others are still written; the first failure is thrown at the end.
     */
    private void persistPanels() throws IOException {
        panelsPending.set(false);
        final List<PanelState> shut = closedMeta;
        final List<PanelState> meta = new ArrayList<>(panelMeta);
        meta.addAll(shut);
        final List<String> open = reserved(meta.stream().map(p -> p.id().value()).toList());
        opening.removeAll(open);
        final List<StoredPanel> out = new ArrayList<>(meta.size() + unsaved.size());
        IOException failed = null;
        for (final PanelState p : meta) {
            final String id = p.id().value();
            // a preview is not kept: its text stays dirty, written once the preview is pinned
            if (removed.contains(id) || p.preview()) {
                continue;
            }
            final Boolean isClosed = shut.contains(p) ? Boolean.TRUE : null;
            String name = files.file(id).map(f -> f.getFileName().toString()).orElse(null);
            String content = null;
            try {
                name = files.name(id, p.title(), open);
                if (dirty.remove(id) || files.unwritten(id)) {
                    final String text = texts.getOrDefault(id, "");
                    try {
                        files.write(id, text);
                    } catch (final IOException e) {
                        dirty.add(id);
                        content = text;
                        throw e;
                    }
                }
                failing.remove(id);
            } catch (final IOException e) {
                LOG.log(System.Logger.Level.ERROR, "cannot save the panel's file [panel=" + p.title() + ']', e);
                if (failing.add(id)) {
                    notices.accept("Cannot write " + (name == null ? "the file of " + p.title() : name) + ": " + reason(e)
                            + "; the text is kept in panels.json and tried again every few seconds");
                }
                if (content == null && dirty.contains(id)) {
                    content = texts.getOrDefault(id, "");
                }
                failed = failed == null ? e : failed;
            }
            out.add(new StoredPanel(id, p.title(), p.connection(), p.session(), name, content, isClosed, side(p)));
        }
        for (final Iterator<StoredPanel> it = unsaved.values().iterator(); it.hasNext(); ) {
            final StoredPanel u = it.next();
            try {
                files.name(u.id(), u.title(), open);
                files.write(u.id(), u.content());
                it.remove();
            } catch (final IOException e) {
                out.add(new StoredPanel(u.id(), u.title(), u.connection(), u.session(),
                        files.storedName(u.id()).orElse(null), u.content(), null, u.side()));
                failed = failed == null ? e : failed;
            }
        }
        try {
            // a panels.json kept as it is (record 133) is not written, nor retried: the panels' own files still are
            if (panels.refused().isEmpty()) {
                panels.write(out);
            }
            failing.remove(PANELS_JSON);
        } catch (final IOException e) {
            LOG.log(System.Logger.Level.ERROR, "cannot save [file=" + panels.path() + ']', e);
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

    private void submit(final Runnable task) {
        if (closed) {
            return;
        }
        try {
            writer.execute(task);
        } catch (final RejectedExecutionException e) {
            LOG.log(System.Logger.Level.WARNING, "store closed, write dropped");
        }
    }

    /**
     * Writes a settings file; a failure is told, and the next change writes the whole list again. A file of a later
     * version is not written: the start told the user why (record 133).
     */
    private <T> void write(final JsonFile<T> file, final T value) {
        if (!file.refused().isEmpty()) {
            return;
        }
        try {
            file.write(value);
        } catch (final IOException e) {
            LOG.log(System.Logger.Level.ERROR, "cannot save [file=" + file.path() + ']', e);
            notices.accept("Cannot write " + file.path().getFileName() + ": " + reason(e) + "; it is written again with the next change");
        }
    }

    private static <T> List<T> orEmpty(final List<T> list) {
        return list == null ? List.of() : list;
    }

    private static String nz(final String s, final String fallback) {
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
        // no connection save is taken from here on; the one queued drains below
        secretsWriter.shutdown();
        ExecutorStop.drain("store", Duration.ofSeconds(5), Map.of("store", writer));
        // one keychain call's bound plus a margin: a connection saved just before quitting still lands
        ExecutorStop.drain("store", Duration.ofSeconds(Secrets.Keychain.TIMEOUT_SECONDS + 1), Map.of("store-secrets", secretsWriter));
    }
}
