package dev.marregui.comando.app;

import com.fasterxml.jackson.core.type.TypeReference;
import dev.marregui.comando.core.model.ConnectionSpec;
import dev.marregui.comando.core.model.PanelId;
import dev.marregui.comando.core.model.SessionSpec;
import dev.marregui.comando.core.port.PersistencePort;
import dev.marregui.comando.core.state.PanelState;
import dev.marregui.comando.ui.PanelTexts;
import dev.marregui.comando.ui.PromptHistory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The store files: {@code connections.json}, {@code sessions.json} and {@code panels.json} (each panel's
 * bindings and text). Every write runs on one thread from an immutable snapshot, so a save never races an
 * edit; panel text is saved after 800 ms without changes.
 */
final class Store implements PersistencePort, PanelTexts, PromptHistory, AutoCloseable {

    private static final System.Logger LOG = System.getLogger(Store.class.getName());
    static final long PANEL_DELAY_MILLIS = 800;

    /** A panel as stored: bindings and text together. */
    record StoredPanel(String id, String title, String connection, String session, String content) {
    }

    /** What the store held at start. */
    record Loaded(List<PanelState> panels, List<ConnectionSpec> connections, List<SessionSpec> sessions) {
    }

    private final JsonFile<List<ConnectionSpec>> connections;
    private final JsonFile<List<SessionSpec>> sessions;
    private final JsonFile<List<StoredPanel>> panels;
    private final JsonFile<Map<String, List<String>>> prompts;
    /** Prompts sent, by session, oldest first; read once at start, written after a quiet period. */
    private final ConcurrentHashMap<String, List<String>> promptHistory = new ConcurrentHashMap<>();
    private final AtomicBoolean promptsPending = new AtomicBoolean();
    static final int PROMPTS_KEPT = 200;
    private final ScheduledExecutorService writer =
            Executors.newSingleThreadScheduledExecutor(r -> Thread.ofPlatform().name("store").daemon(false).unstarted(r));
    /**
     * Connection saves talk to the keychain, which can wait on an unlock prompt for its whole bound; they get a
     * thread of their own, so panel text and sessions are never queued behind a prompt nobody answers.
     */
    private final ExecutorService secretsWriter =
            Executors.newSingleThreadExecutor(r -> Thread.ofPlatform().name("store-secrets").daemon(false).unstarted(r));
    private final ConcurrentHashMap<String, String> texts = new ConcurrentHashMap<>();
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
        prompts = new JsonFile<>(home.resolve("prompts.json"), new TypeReference<>() {
        });
        try {
            Map<String, List<String>> read = prompts.read();
            if (read != null) {
                read.forEach((k, v) -> promptHistory.put(k, new ArrayList<>(v == null ? List.of() : v)));
            }
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "cannot read the prompt history: " + e.getMessage());
        }
    }

    // ---- PromptHistory

    @Override
    public List<String> load(String session) {
        List<String> h = promptHistory.get(session);
        if (h == null) {
            return List.of();
        }
        synchronized (h) {
            return List.copyOf(h);
        }
    }

    @Override
    public void add(String session, String prompt) {
        List<String> h = promptHistory.computeIfAbsent(session, k -> new ArrayList<>());
        synchronized (h) {
            h.remove(prompt);
            h.add(prompt);
            while (h.size() > PROMPTS_KEPT) {
                h.removeFirst();
            }
        }
        if (promptsPending.compareAndSet(false, true)) {
            try {
                writer.schedule(this::writePrompts, PANEL_DELAY_MILLIS, TimeUnit.MILLISECONDS);
            } catch (java.util.concurrent.RejectedExecutionException e) {
                promptsPending.set(false);
            }
        }
    }

    private void writePrompts() {
        promptsPending.set(false);
        Map<String, List<String>> out = new java.util.TreeMap<>();
        promptHistory.forEach((k, v) -> {
            synchronized (v) {
                out.put(k, List.copyOf(v));
            }
        });
        write(prompts, out);
    }

    /** Reads the files; a fresh data root is seeded from {@code legacy} (the old ~/QUESTS) or defaults. */
    Loaded load(Legacy legacy) {
        boolean fresh = !connections.exists() && !sessions.exists() && !panels.exists();
        if (fresh) {
            Loaded seeded = legacy.read().orElseGet(Store::defaults);
            for (PanelState p : seeded.panels()) {
                texts.putIfAbsent(p.id().value(), legacy.textOf(p.id()).orElse(""));
            }
            panelMeta = seeded.panels();
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
            p.add(new PanelState(new PanelId(sp.id()), nz(sp.title(), "panel"), nz(sp.connection(), ""), nz(sp.session(), "")));
            texts.put(sp.id(), nz(sp.content(), ""));
        }
        p = validBindings(p, c, s);
        if (p.isEmpty()) {
            PanelState first = new PanelState(PanelId.random(), "scratch", c.isEmpty() ? "" : c.getFirst().name(), "");
            texts.put(first.id().value(), Welcome.TEXT);
            p = List.of(first);
        }
        panelMeta = p;
        return new Loaded(p, c, s);
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
        } catch (java.util.concurrent.RejectedExecutionException e) {
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
        schedulePanels();
    }

    @Override
    public void forget(PanelId panel) {
        texts.remove(panel.value());
        schedulePanels();
    }

    @Override
    public java.util.concurrent.CompletableFuture<String> saveNow() {
        java.util.concurrent.CompletableFuture<String> done = new java.util.concurrent.CompletableFuture<>();
        if (closed) {
            done.complete("comando is closing");
            return done;
        }
        try {
            writer.execute(() -> {
                panelsPending.set(false);
                List<StoredPanel> out = new ArrayList<>();
                for (PanelState p : panelMeta) {
                    out.add(new StoredPanel(p.id().value(), p.title(), p.connection(), p.session(), texts.getOrDefault(p.id().value(), "")));
                }
                try {
                    panels.write(out);
                    done.complete(null);
                } catch (IOException | RuntimeException e) {
                    LOG.log(System.Logger.Level.ERROR, "cannot save [file=" + panels.path() + "]: " + e.getMessage(), e);
                    done.complete(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException e) {
            done.complete("comando is closing");
        }
        return done;
    }

    // ---- writing

    private void schedulePanels() {
        if (panelsPending.compareAndSet(false, true)) {
            try {
                writer.schedule(this::writePanels, PANEL_DELAY_MILLIS, TimeUnit.MILLISECONDS);
            } catch (java.util.concurrent.RejectedExecutionException e) {
                panelsPending.set(false);
            }
        }
    }

    private void writePanels() {
        panelsPending.set(false);
        List<StoredPanel> out = new ArrayList<>();
        for (PanelState p : panelMeta) {
            out.add(new StoredPanel(p.id().value(), p.title(), p.connection(), p.session(), texts.getOrDefault(p.id().value(), "")));
        }
        write(panels, out);
    }

    private void submit(Runnable task) {
        if (closed) {
            return;
        }
        try {
            writer.execute(task);
        } catch (java.util.concurrent.RejectedExecutionException e) {
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
        if (promptsPending.get()) {
            writer.execute(this::writePrompts);
        }
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
