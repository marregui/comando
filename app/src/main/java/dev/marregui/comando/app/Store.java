package dev.marregui.comando.app;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.type.TypeReference;
import dev.marregui.comando.core.model.ConnectionSpec;
import dev.marregui.comando.core.model.PanelId;
import dev.marregui.comando.core.model.SessionSpec;
import dev.marregui.comando.core.port.PersistencePort;
import dev.marregui.comando.core.state.PanelState;
import dev.marregui.comando.ui.PanelTexts;
import dev.marregui.comando.ui.PanelTexts.Outside;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * The store files: {@code connections.json}, {@code sessions.json}, {@code panels.json} (each panel's title,
 * bindings and file name) and one Markdown file per panel under {@code panels/} with its text ({@link PanelFiles}).
 * Every write runs on one thread from an immutable snapshot, so a save never races an edit; panel text is saved
 * 800 ms after the first unsaved change, so continuous typing saves about every 800 ms, and only the panels that
 * changed are written.
 */
final class Store implements PersistencePort, PanelTexts, AutoCloseable {

    private static final System.Logger LOG = System.getLogger(Store.class.getName());
    static final long PANEL_DELAY_MILLIS = 800;
    /** How often open panels' files are checked for changes made outside comando. */
    static final long POLL_MILLIS = 1_000;

    /**
     * A panel as stored: its title, bindings and the name of its file. {@code content} is read only, from a
     * {@code panels.json} written before panels had files, and moves into the panel's file at the next save.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record StoredPanel(String id, String title, String connection, String session, String file, String content) {
    }

    /** What the store held at start. */
    record Loaded(List<PanelState> panels, List<ConnectionSpec> connections, List<SessionSpec> sessions) {
    }

    private final JsonFile<List<ConnectionSpec>> connections;
    private final JsonFile<List<SessionSpec>> sessions;
    private final JsonFile<List<StoredPanel>> panels;
    private final ScheduledExecutorService writer =
            Executors.newSingleThreadScheduledExecutor(r -> Thread.ofPlatform().name("store").daemon(false).unstarted(r));
    /**
     * Connection saves talk to the keychain, which can wait on an unlock prompt for its whole bound; they get a
     * thread of their own, so panel text and sessions are never queued behind a prompt nobody answers.
     */
    private final ExecutorService secretsWriter =
            Executors.newSingleThreadExecutor(r -> Thread.ofPlatform().name("store-secrets").daemon(false).unstarted(r));
    private final ConcurrentHashMap<String, String> texts = new ConcurrentHashMap<>();
    /** Panels whose text changed since their file was written. */
    private final Set<String> dirty = ConcurrentHashMap.newKeySet();
    private final PanelFiles files;
    private volatile Consumer<String> notices = text -> {
    };
    private volatile Consumer<Outside> outsideChanges = change -> {
    };
    private final AtomicBoolean panelsPending = new AtomicBoolean();
    private volatile List<PanelState> panelMeta = List.of();
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
        panels = new JsonFile<>(home.resolve("panels.json"), new TypeReference<>() {
        });
        files = new PanelFiles(home.resolve("panels"), text -> notices.accept(text));
    }

    /** Where the store reports what the user should know, such as a panel file changed outside comando. */
    void onNotice(Consumer<String> notices) {
        this.notices = notices;
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
            panelMeta = seeded.panels();
            pollFiles();
            saveConnections(seeded.connections());
            saveSessions(seeded.sessions());
            schedulePanels();
            return seeded;
        }
        List<ConnectionSpec> c = orEmpty(connections.read()).stream().map(this::withSecret).toList();
        secretNames = new HashSet<>(c.stream().map(ConnectionSpec::name).toList());
        List<SessionSpec> s = orEmpty(sessions.read());
        List<PanelState> p = new ArrayList<>();
        for (StoredPanel sp : orEmpty(panels.read())) {
            if (sp.id() == null || sp.id().isBlank()) {
                continue;
            }
            String title = nz(sp.title(), "panel");
            p.add(new PanelState(new PanelId(sp.id()), title, nz(sp.connection(), ""), nz(sp.session(), "")));
            texts.put(sp.id(), textOf(sp, title));
        }
        p = validBindings(p, c, s);
        if (p.isEmpty()) {
            PanelState first = new PanelState(PanelId.random(), "scratch", c.isEmpty() ? "" : c.getFirst().name(), "");
            texts.put(first.id().value(), Welcome.TEXT);
            dirty.add(first.id().value());
            p = List.of(first);
        }
        panelMeta = p;
        if (!dirty.isEmpty()) {
            schedulePanels();
        }
        pollFiles();
        return new Loaded(p, c, s);
    }

    /**
     * A stored panel's text: from its file, else from {@code panels.json} as written before panels had files, which
     * the next save moves into a file. A file that cannot be read keeps its text out of the editor and is left alone.
     */
    private String textOf(StoredPanel sp, String title) {
        if (sp.file() == null || sp.file().isBlank()) {
            dirty.add(sp.id());
            return nz(sp.content(), "");
        }
        try {
            return files.read(sp.id(), title, sp.file());
        } catch (IOException | RuntimeException e) {
            LOG.log(System.Logger.Level.ERROR, "cannot read the panel file, the panel starts empty and saves to another file [file="
                    + files.dir().resolve(sp.file()) + "]: " + e.getMessage());
            notices.accept("Cannot read " + sp.file() + "; the panel " + title + " starts empty and saves to another file");
            return "";
        }
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

    @Override
    public void savePanels(List<PanelState> panels) {
        panelMeta = List.copyOf(panels);
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
        dirty.add(panel.value());
        schedulePanels();
    }

    /** The panel closed: its last text reaches its file, which stays unless blank. */
    @Override
    public void forget(PanelId panel) {
        String id = panel.value();
        submit(() -> {
            String text = texts.getOrDefault(id, "");
            try {
                if (dirty.remove(id) && files.file(id).isPresent()) {
                    files.write(id, text);
                }
                files.closed(id, text);
            } catch (IOException | RuntimeException e) {
                LOG.log(System.Logger.Level.ERROR, "cannot save the closed panel's file [panel=" + id + "]: " + e.getMessage(), e);
            }
            texts.remove(id);
        });
        schedulePanels();
    }

    @Override
    public Optional<Path> file(PanelId panel) {
        return files.file(panel.value());
    }

    @Override
    public Optional<Path> folder() {
        return Optional.of(files.dir());
    }

    @Override
    public void onOutsideChange(Consumer<Outside> listener) {
        outsideChanges = listener;
    }

    /** Checks the open panels' files every second, on the writer thread, so a check never races a write. */
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
     * Reports each open panel's file written outside comando since comando last wrote, read or took it. A panel
     * with unsaved edits is skipped: its save keeps the outside version aside. A file deleted outside is reported
     * once and written again on the panel's next save.
     */
    private void checkFiles() {
        if (closed) {
            return;
        }
        for (PanelState p : panelMeta) {
            String id = p.id().value();
            if (dirty.contains(id)) {
                continue;
            }
            try {
                String name = files.file(id).map(f -> f.getFileName().toString()).orElse("");
                if (files.deleted(id)) {
                    notices.accept(name + " was deleted outside comando; the panel keeps its text and writes the file again on the next edit or ⌘S");
                    continue;
                }
                files.outside(id).ifPresent(o -> outsideChanges.accept(new Outside(p.id(), name, o.text(), () -> take(id, o))));
            } catch (IOException | RuntimeException e) {
                LOG.log(System.Logger.Level.WARNING, "cannot check the panel's file [panel=" + p.title() + "]: " + e.getMessage());
            }
        }
    }

    /** On the EDT: the panel takes its file's outside text, unless it has edits not yet saved. */
    private boolean take(String id, PanelFiles.Outside o) {
        if (dirty.contains(id)) {
            return false;
        }
        texts.put(id, o.text());
        submit(() -> files.accept(id, o.stamp()));
        return true;
    }

    @Override
    public String open(PanelId panel, Path file) {
        PanelFiles.Result r = files.bind(panel.value(), file);
        if (r.error().isEmpty()) {
            texts.put(panel.value(), r.text());
        }
        return r.error();
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
        if (panelsPending.compareAndSet(false, true)) {
            try {
                writer.schedule(this::writePanels, PANEL_DELAY_MILLIS, TimeUnit.MILLISECONDS);
            } catch (RejectedExecutionException e) {
                panelsPending.set(false);
            }
        }
    }

    private void writePanels() {
        try {
            persistPanels();
        } catch (IOException | RuntimeException e) {
            LOG.log(System.Logger.Level.ERROR, "cannot save the panels: " + e.getMessage(), e);
        }
    }

    /**
     * Names each open panel's file (a new panel gets one, a renamed one moves it), writes the files whose text
     * changed, then {@code panels.json}. A panel whose file fails stays unsaved for the next save, and the others
     * are still written; the first failure is thrown at the end.
     */
    private void persistPanels() throws IOException {
        panelsPending.set(false);
        List<PanelState> meta = panelMeta;
        List<String> open = meta.stream().map(p -> p.id().value()).toList();
        List<StoredPanel> out = new ArrayList<>(meta.size());
        IOException failed = null;
        for (PanelState p : meta) {
            String id = p.id().value();
            String name = files.file(id).map(f -> f.getFileName().toString()).orElse(null);
            try {
                name = files.name(id, p.title(), open);
                if (dirty.remove(id)) {
                    try {
                        files.write(id, texts.getOrDefault(id, ""));
                    } catch (IOException e) {
                        dirty.add(id);
                        throw e;
                    }
                }
            } catch (IOException e) {
                LOG.log(System.Logger.Level.ERROR, "cannot save the panel's file [panel=" + p.title() + "]: " + e.getMessage(), e);
                failed = failed == null ? e : failed;
            }
            out.add(new StoredPanel(id, p.title(), p.connection(), p.session(), name, null));
        }
        panels.write(out);
        if (failed != null) {
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

    private static <T> void write(JsonFile<T> file, T value) {
        try {
            file.write(value);
        } catch (IOException e) {
            LOG.log(System.Logger.Level.ERROR, "cannot save [file=" + file.path() + "]: " + e.getMessage(), e);
        }
    }

    private static <T> List<T> orEmpty(List<T> list) {
        return list == null ? List.of() : list;
    }

    private static String nz(String s, String fallback) {
        return s == null ? fallback : s;
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
