package dev.marregui.comando.ui.shell;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.marregui.comando.core.intent.Intent;
import dev.marregui.comando.core.model.KitJob;
import dev.marregui.comando.core.model.KitJobs;
import dev.marregui.comando.core.model.KitRoot;
import dev.marregui.comando.core.state.Notice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/// [JobActions]: the kit's jobs reach `kit.py` only as the manifest holds them when the user acts (record 117).
/// - A list that cannot be read opens no window and says why, so no Save can hand `kit.py` an empty list.
/// - Apply pending changes hands over the jobs read at that moment, not the list read a minute or hours before.
/// - Apply with a list that cannot be read hands over nothing and says why.
/// - A look reads the jobs through `kit.py` only when the manifest or the runner's state changed, not when a log grew.
class JobActionsTest {

    private static final KitRoot.Manifest KIT = new KitRoot.Manifest("marregui", "BRAIN", List.of(), List.of(), List.of(), List.of());

    @BeforeAll
    static void headless() {
        System.setProperty("java.awt.headless", "true");
    }

    private final List<Intent> sent = new CopyOnWriteArrayList<>();
    private final List<List<KitJob>> saved = new CopyOnWriteArrayList<>();
    private final AtomicInteger read = new AtomicInteger();

    private static KitJobs list(final String... names) {
        return new KitJobs(true, Arrays.stream(names).map(n -> new KitJobs.Entry(
                new KitJob(n, "", List.of("MON"), "07:30", List.of("bin/" + n), Map.of(), "", true),
                true, true, true, false, "", null, "2026-11-02T07:30+01:00", "")).toList(), "");
    }

    /** Actions on a kit whose reads answer {@code reads} in turn, the last one again and again. */
    private JobActions actions(final Path tmp, final KitJobs... reads) {
        final KitRoot root = new KitRoot(tmp, Optional.of(KIT), tmp);
        final Setup setup = new Setup() {
            @Override
            public KitRoot root() {
                return root;
            }

            @Override
            public Status status() {
                return Status.NONE;
            }

            @Override
            public String backUpBrain() {
                return "";
            }

            @Override
            public KitJobs jobs() {
                return reads[Math.min(read.getAndIncrement(), reads.length - 1)];
            }

            @Override
            public JobsDone saveJobs(final List<KitJob> jobs) throws IOException {
                saved.add(jobs);
                return new JobsDone(true, "");
            }
        };
        return new JobActions(new ComandoUi.Dependencies(sent::add, () -> null, null, null, null, null, null, null, List::of, null, null, setup,
                "test", null, null, () -> {
        }), () -> null, p -> { });
    }

    private List<String> notices(final Notice.Level level) {
        return sent.stream().filter(i -> i instanceof Intent.Notify n && n.level() == level).map(i -> ((Intent.Notify) i).text()).toList();
    }

    /// Configure with a list `kit.py` could not read shows no window (none can be built here: a window would fail the
    /// errand) and says why in an error notice; nothing is saved.
    @Test
    void anUnreadableListOpensNoWindowAndSaysWhy(@TempDir final Path tmp) {
        final JobActions a = actions(tmp, new KitJobs(true, List.of(), "kit.py: job 'curator': time '7.45' is not HH:MM"));
        a.configure();
        await().atMost(Duration.ofSeconds(10)).until(() -> !notices(Notice.Level.ERROR).isEmpty());
        assertThat(notices(Notice.Level.ERROR).getFirst()).startsWith("Kit jobs cannot be read: kit.py: job 'curator'");
        assertThat(saved).isEmpty();
        assertThat(a.jobs().error()).isNotEmpty();
    }

    /// Apply reads the jobs again and hands `kit.py` those, not the list the row last showed.
    @Test
    void applyHandsOverTheJobsAsReadNow(@TempDir final Path tmp) {
        final JobActions a = actions(tmp, list("a"), list("a", "b"));
        a.refresh();
        await().atMost(Duration.ofSeconds(10)).until(() -> a.jobs().jobs().size() == 1);
        a.apply();
        await().atMost(Duration.ofSeconds(10)).until(() -> !saved.isEmpty());
        assertThat(saved.getFirst()).extracting(KitJob::name).containsExactly("a", "b");
        await().atMost(Duration.ofSeconds(10)).until(() -> !notices(Notice.Level.INFO).isEmpty());
    }

    /// Apply with a list that cannot be read hands over nothing and says why.
    @Test
    void applyWithAnUnreadableListSavesNothing(@TempDir final Path tmp) {
        final JobActions a = actions(tmp, new KitJobs(true, List.of(), "no python3"));
        a.apply();
        await().atMost(Duration.ofSeconds(10)).until(() -> !notices(Notice.Level.ERROR).isEmpty());
        assertThat(notices(Notice.Level.ERROR).getFirst()).isEqualTo("The kit jobs were not applied: no python3");
        assertThat(saved).isEmpty();
    }

    /// A look on an unchanged kit reads nothing; a changed manifest, a run recorded or a job started is read once; a log
    /// that grew is not.
    @Test
    void aLookReadsTheJobsOnlyWhenTheManifestOrTheRunnersStateChanged(@TempDir final Path tmp) throws IOException {
        final Path manifest = Files.writeString(tmp.resolve(KitRoot.FILE), "{}");
        final Path state = Files.createDirectories(tmp.resolve(JobActions.STATE));
        final Path runs = Files.writeString(state.resolve("a.tsv"), "start\texit\n");
        final Path log = Files.writeString(state.resolve("a.log"), "one\n");
        final JobActions a = actions(tmp, list("a"));

        a.readIfChanged();
        assertThat(read).as("the first look reads").hasValue(1);
        a.readIfChanged();
        assertThat(read).as("nothing changed").hasValue(1);

        Files.writeString(log, "one\ntwo\n");
        // later by more than any filesystem's time resolution, so only the exclusion keeps the look from reading
        Files.setLastModifiedTime(log, FileTime.fromMillis(Files.getLastModifiedTime(log).toMillis() + 5_000));
        a.readIfChanged();
        assertThat(read).as("only a log grew").hasValue(1);

        Files.writeString(runs, "start\texit\n2026-11-03T17:00\t0\n");
        a.readIfChanged();
        assertThat(read).as("a run was recorded").hasValue(2);

        Files.writeString(state.resolve("a.pid"), "4242");
        a.readIfChanged();
        a.readIfChanged();
        assertThat(read).as("a job started, read once").hasValue(3);

        Files.writeString(manifest, "{\"jobs\": []}");
        a.readIfChanged();
        assertThat(read).as("the manifest changed").hasValue(4);
    }
}
