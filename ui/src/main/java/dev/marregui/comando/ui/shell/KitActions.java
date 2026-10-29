package dev.marregui.comando.ui.shell;

import dev.marregui.comando.core.intent.Intent;
import dev.marregui.comando.core.model.HomeDir;
import dev.marregui.comando.core.model.KitRoot;
import dev.marregui.comando.core.model.PanelId;
import dev.marregui.comando.core.model.SessionSpec;
import dev.marregui.comando.core.model.Status;
import dev.marregui.comando.core.state.AppState;
import dev.marregui.comando.core.state.Notice;
import dev.marregui.comando.core.state.SessionState;
import dev.marregui.comando.ui.kit.async.Edt;
import dev.marregui.comando.ui.kit.async.UiErrands;

import javax.swing.JFrame;
import javax.swing.JOptionPane;
import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

/**
 * The kit in the window: its name and version in the status bar, its upgrade, which a session in the root does when
 * the user says so, and the backups of the brain and of the kit, only on the user's click. The version is read at
 * start and every six hours by the application; git runs off the EDT. EDT only, but for {@link #show}.
 */
final class KitActions {

    private static final System.Logger LOG = System.getLogger(KitActions.class.getName());
    static final String UPGRADE = "Upgrade the kit…";
    static final String BACK_UP = "Back up the brain";
    static final String BACK_UP_KIT = "Back up the kit";

    private final ComandoUi.Dependencies deps;
    private final Supplier<JFrame> frame;
    private BiConsumer<Path, String> release;
    private Setup.Status shown = Setup.Status.NONE;
    /** The newest release a notice said is out, so each is said once. */
    private String announced = "";
    /** The upgrade sent to the kit's session, until the session has reported; null when none. */
    private Upgrade upgrade;
    /** Sessions stopped to start again on the new kit, started once they have stopped. */
    private final Set<String> restarting = new LinkedHashSet<>();

    /** An upgrade under way: the release asked for, and whether the kit's session has taken the prompt. */
    record Upgrade(String tag, boolean running) {

        /**
         * Where the upgrade is once the kit's session is in {@code session}'s state: waiting until the session takes
         * the prompt (a state rendered before the prompt was sent says nothing), then until its turn and the work it
         * left running have ended; empty once it has reported, or stopped.
         */
        Optional<Upgrade> next(SessionState session) {
            boolean working = session != null && (session.isBusy() || session.isWaiting());
            if (!running) {
                return Optional.of(working ? new Upgrade(tag, true) : this);
            }
            return working ? Optional.of(this) : Optional.empty();
        }
    }

    KitActions(ComandoUi.Dependencies deps, Supplier<JFrame> frame) {
        this.deps = deps;
        this.frame = frame;
    }

    /** Where the kit's newer release shows, the Files tab's KIT row (record 119), once the window is built. */
    void attach(BiConsumer<Path, String> release) {
        this.release = release;
        paint();
    }

    /** Shows what git says of the kit now; says once that a newer release is out. Any thread. */
    void show(Setup.Status now) {
        Edt.later(() -> {
            shown = now;
            paint();
            KitRoot root = deps.setup().root();
            Optional<String> newest = now.version().newest();
            if (root.kit().isPresent() && newest.isPresent() && !newest.get().equals(announced)) {
                announced = newest.get();
                deps.intents().dispatch(new Intent.Notify(Notice.Level.INFO, now.version().label(root.name())
                        + ": " + UPGRADE + " in the KIT row's menu of the Files tab or the palette", false));
            }
        });
    }

    private void paint() {
        if (release == null) {
            return;
        }
        KitRoot root = deps.setup().root();
        release.accept(root.kit().isEmpty() ? null : root.dir(), root.kit().isEmpty() ? "" : shown.version().newest().orElse(""));
    }

    /** Whether a newer release of the kit is out. */
    boolean canUpgrade() {
        return deps.setup().root().kit().isPresent() && !shown.version().newer().isEmpty();
    }

    /** Whether the brain is a git repository of its own with a remote. */
    boolean canBackUp() {
        return deps.setup().root().brain().isPresent() && shown.canBackUp();
    }

    /** Whether the kit's root is a git repository with a remote. */
    boolean canBackUpKit() {
        return deps.setup().root().kit().isPresent() && shown.canBackUpKit();
    }

    /** The prompt the kit's session is sent. */
    static String prompt(Path root, String tag) {
        return "Upgrade the kit at " + root + " to " + tag + ", following " + root.resolve("INSTALL.md") + " section Upgrade. Report what changed.";
    }

    /**
     * Asks which release (newest first), then opens the session {@value KitRoot#UPGRADE_SESSION} in the root, making
     * it first, and sends it the upgrade. The session runs on the user's own Claude Code folder, so it can replace the
     * kit.
     */
    void upgrade() {
        KitRoot root = deps.setup().root();
        List<String> tags = shown.version().newer();
        if (root.kit().isEmpty() || tags.isEmpty()) {
            deps.intents().dispatch(new Intent.Notify(Notice.Level.INFO, "The kit has no newer release to upgrade to", false));
            return;
        }
        Object pick = JOptionPane.showInputDialog(frame.get(), "Upgrade the kit " + root.name() + " " + shown.version().shown() + " at "
                        + HomeDir.shorten(root.dir().toString()) + " to:", "Upgrade the kit", JOptionPane.QUESTION_MESSAGE, null,
                tags.toArray(), tags.getFirst());
        if (pick == null) {
            return;
        }
        send(deps.state().get(), root, pick.toString());
    }

    /** Sends the upgrade to {@code tag} to the kit's session, made in the root when there is none. */
    void send(AppState s, KitRoot root, String tag) {
        String name = KitRoot.UPGRADE_SESSION;
        Optional<SessionState> session = s.session(name);
        if (session.isPresent() && !root.isRoot(session.get().spec().workDir())) {
            deps.intents().dispatch(new Intent.Notify(Notice.Level.ERROR, "The session " + name + " works in " + session.get().spec().workDir()
                    + "; the kit's upgrade runs in " + HomeDir.shorten(root.dir().toString()) + ": rename or remove that session", false,
                    Notice.session(name)));
            return;
        }
        if (session.isEmpty()) {
            deps.intents().dispatch(new Intent.SaveSession("", SessionSpec.fresh(name, root.dir().toString())));
        }
        deps.intents().dispatch(new Intent.OpenSession(name, s.focused(), PanelId.random()));
        deps.intents().dispatch(new Intent.SessionCommand(name, prompt(root.dir(), tag)));
        upgrade = new Upgrade(tag, false);
        LOG.log(System.Logger.Level.INFO, "kit upgrade sent [tag=" + tag + ", root=" + root.dir() + ']');
    }

    /**
     * Follows the kit's session until it has reported, then offers the restarts; starts each restarted one once
     * stopped.
     */
    void render(AppState s) {
        if (upgrade != null) {
            SessionState kit = s.session(KitRoot.UPGRADE_SESSION).orElse(null);
            Optional<Upgrade> next = upgrade.next(kit);
            if (next.isEmpty()) {
                String tag = upgrade.tag();
                upgrade = null;
                reported(s, tag, kit != null && kit.status().isUsable());
            } else {
                upgrade = next.get();
            }
        }
        for (String name : List.copyOf(restarting)) {
            SessionState x = s.session(name).orElse(null);
            if (x == null || x.status() == Status.FAILED) {
                restarting.remove(name);
            } else if (x.status() == Status.CLOSED) {
                restarting.remove(name);
                deps.intents().dispatch(new Intent.StartSession(name));
            }
        }
    }

    /** The sessions running on the old kit: every open one but the kit's own; busy ones are left to the user. */
    static List<String> toRestart(AppState s) {
        return s.sessions().stream().filter(x -> x.status() == Status.OPEN && !x.isBusy() && !x.name().equals(KitRoot.UPGRADE_SESSION))
                .map(SessionState::name).toList();
    }

    private void reported(AppState s, String tag, boolean answered) {
        refresh();
        if (!answered) {
            deps.intents().dispatch(new Intent.Notify(Notice.Level.WARN, "The kit's session stopped before it reported on the upgrade to " + tag,
                    false, Notice.session(KitRoot.UPGRADE_SESSION)));
            return;
        }
        List<String> names = toRestart(s);
        if (names.isEmpty() || frame.get() == null) {
            return;
        }
        Object[] options = {"Restart them", "Not now"};
        int pick = JOptionPane.showOptionDialog(frame.get(), "The kit's session has reported on the upgrade to " + tag + ".\nRestart the "
                        + (names.size() == 1 ? "session" : names.size() + " sessions") + " running on the old kit (" + String.join(", ", names)
                        + ")? Each resumes its conversation on the new kit; a session busy now is left as it is.",
                "Kit upgraded", JOptionPane.DEFAULT_OPTION, JOptionPane.QUESTION_MESSAGE, null, options, options[0]);
        if (pick == 0) {
            restart(names);
        }
    }

    /** Stops each session; each starts again once it has stopped, resuming its conversation. */
    void restart(List<String> names) {
        for (String name : names) {
            restarting.add(name);
            deps.intents().dispatch(new Intent.StopSession(name));
        }
    }

    /** Reads the kit's version again, off the EDT. */
    void refresh() {
        UiErrands.start("kit-status", () -> show(deps.setup().status()));
    }

    /** Commits and pushes the brain, off the EDT; the notice names the commit, or why it failed. Never on its own. */
    void backUp() {
        deps.setup().root().brain().ifPresent(brain -> backUp("Brain", brain, "brain-backup", () -> deps.setup().backUpBrain()));
    }

    /** Commits and pushes the kit's root, off the EDT, as {@link #backUp()} does the brain. */
    void backUpKit() {
        KitRoot root = deps.setup().root();
        if (root.kit().isPresent()) {
            backUp("Kit", root.dir(), "kit-backup", () -> deps.setup().backUpKit());
        }
    }

    /** What a backup does: {@code IOException} when a step of git fails. */
    private interface Backup {
        String run() throws IOException;
    }

    private void backUp(String what, Path dir, String thread, Backup backup) {
        String where = HomeDir.shorten(dir.toString());
        UiErrands.start(thread, () -> {
            try {
                String done = backup.run();
                deps.intents().dispatch(new Intent.Notify(Notice.Level.INFO, what + " at " + where + ": " + done, false));
            } catch (IOException | RuntimeException e) {
                deps.intents().dispatch(Intent.Notify.logged(LOG, Notice.Level.ERROR, "The " + what.toLowerCase(Locale.ROOT) + " at " + where
                        + " was not backed up: " + e.getMessage(), e));
            }
        });
    }
}
