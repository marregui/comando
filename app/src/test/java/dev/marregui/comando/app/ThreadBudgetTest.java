package dev.marregui.comando.app;

import dev.marregui.comando.core.intent.Intent;
import dev.marregui.comando.core.loop.StateLoop;
import dev.marregui.comando.core.loop.Threads;
import dev.marregui.comando.core.model.SessionSpec;
import dev.marregui.comando.core.model.Status;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

/// comando's own platform threads stay within their budget. Opens the real window, so this runs in
/// `./gradlew :app:uiTest`.
/// - the reducer and the watchdog, plus one reader per running session, since a process pipe keeps an operating-system
///   thread even when read from a virtual thread
/// - strands, errands and timers add none, and no strand of comando's is alive once it has closed
/// - the JVM's, the toolkit's, the libraries' and the JDK's process reapers are not comando's and are left out by name
@EnabledIfSystemProperty(named = "comando.uiTests", matches = "true")
class ThreadBudgetTest {

    static final Set<String> SINGLETONS = Set.of("comando-reducer", "watchdog");
    static final String SESSION_READER = "claude-out-";
    /**
     * More sessions than cores: were a session's reader a virtual thread, its pipe read would add a carrier past them.
     */
    static final int SESSIONS = Runtime.getRuntime().availableProcessors() + 4;
    /** The strands and timed strands comando's parts own, by the name each gives the thread kit. */
    static final List<String> STRANDS = List.of("kit-check", "store", "store-secrets", "peer-broker", "peer-messages", "claude-timer",
            "plan-usage", "subagent-tail", "topic-reader", "db-timer", "peers", "folder-poll", "change-read", "repo-write", "mermaid",
            "local-images");
    /** A session's strand: this followed by the session's name. */
    static final String SESSION_STRAND = "claude-strand-";
    /** How long after an executor says it has terminated its thread may still be ending. */
    private static final Duration STRAND_EXIT = Duration.ofMillis(500);

    /** Threads that are not comando's: the JVM's, the toolkit's, the libraries' and the test runner's. */
    private static final List<String> NOT_COMANDO = List.of("Reference Handler", "Finalizer", "Signal Dispatcher", "Notification Thread",
            "Attach Listener", "Common-Cleaner", "DestroyJavaVM", "AWT-", "AppKit", "Java2D", "TimerQueue", "Image ", "process reaper",
            "ForkJoinPool", "VirtualThread-unblocker", "HTTP-Dispatcher", "idle-timeout-task", "JNA Cleaner", "Timer-",
            "Test worker", "/127.0.0.1", "surefire", "junit", "awaitility");

    @TempDir
    Path home;

    /// With the window open and sessions running, comando's platform threads number at most the reducer, the watchdog
    /// and one pipe reader per session, the virtual threads' carriers stay bounded while the sessions idle, and neither
    /// a strand of comando's nor one of its platform threads is left after the window closes.
    @Test
    void comandosOwnThreadsAreTheReducerTheWatchdogAndOneReaderPerRunningSession() throws Exception {
        Path claude = home.resolve("fake-claude.sh");
        Files.writeString(claude, """
                #!/bin/sh
                # stands in for claude -p: init and a result for each prompt, until its input ends
                while IFS= read -r line; do
                  case "$line" in
                    *'"type":"user"'*)
                      printf '%s\\n' '{"type":"system","subtype":"init","session_id":"fake-1","model":"fake","cwd":"/tmp","claude_code_version":"0.0.1","permissionMode":"default","capabilities":[]}'
                      printf '%s\\n' '{"type":"result","subtype":"success","is_error":false,"total_cost_usd":0,"duration_ms":1,"num_turns":1,"usage":{"input_tokens":1}}'
                      ;;
                  esac
                done
                """);
        claude.toFile().setExecutable(true);
        Set<Long> before = platformThreads().keySet();
        App app = new App(new AppConfig(home.resolve("data"), claude.toString(), home.resolve("projects"), false), "budget", () -> {
        });
        try {
            app.start();
            StateLoop loop = app.loop();
            Awaitility.await("the window").atMost(Duration.ofSeconds(20)).until(() -> !comandos(before).isEmpty());
            for (int i = 0; i < SESSIONS; i++) {
                loop.dispatch(new Intent.SaveSession("", SessionSpec.fresh("s" + i, home.toString())));
                loop.dispatch(new Intent.StartSession("s" + i));
            }
            Awaitility.await("every session running").atMost(Duration.ofSeconds(30))
                    .until(() -> loop.state().sessions().stream().filter(x -> x.status() == Status.OPEN).count() == SESSIONS);
            // the start-up errands, the first ticks and the first reads run meanwhile
            Awaitility.await().pollDelay(Duration.ofSeconds(3)).atMost(Duration.ofSeconds(4)).until(() -> true);

            TreeMap<String, Integer> running = comandos(before);
            assertThat(running.keySet()).as("comando's platform threads with %d sessions: %s", SESSIONS, running)
                    .allMatch(name -> SINGLETONS.contains(name) || name.startsWith(SESSION_READER));
            assertThat(running.values().stream().mapToInt(Integer::intValue).sum()).as("%s", running).isLessThanOrEqualTo(SINGLETONS.size() + SESSIONS);
            assertThat(carriers()).as("the virtual threads' carriers with %d sessions idle", SESSIONS)
                    .isLessThanOrEqualTo(Runtime.getRuntime().availableProcessors());

            for (int i = 0; i < SESSIONS; i++) {
                loop.dispatch(new Intent.StopSession("s" + i));
            }
            Awaitility.await("the sessions' readers gone").atMost(Duration.ofSeconds(20))
                    .until(() -> comandos(before).keySet().stream().noneMatch(n -> n.startsWith(SESSION_READER)));
        } finally {
            app.close();
        }
        // each owner waits for its strands in close, so they are gone when it returns; the bound covers only a
        // strand's thread ending just after its executor says it has terminated
        Awaitility.await("no strand of comando's left after close").atMost(STRAND_EXIT)
                .untilAsserted(() -> assertThat(strands()).as("comando's strands alive after close").isEmpty());
        Awaitility.await("nothing of comando's left after close").atMost(Duration.ofSeconds(20))
                .untilAsserted(() -> assertThat(comandos(before)).as(() -> "comando's platform threads after close, "
                        + "each with where it stands, to name the code that started it:\n" + stacks(before)).isEmpty());
    }

    /** The virtual threads of comando's strands and timed strands still alive, by name; the JDK lists none of them. */
    private static List<String> strands() {
        final List<String> names = new ArrayList<>(STRANDS);
        for (int i = 0; i < SESSIONS; i++) {
            names.add(SESSION_STRAND + "s" + i);
        }
        return names.stream().filter(n -> !Threads.alive(n).isEmpty()).toList();
    }

    /** The virtual threads' scheduler's carriers: one per core at most while none is held in file or pipe I/O. */
    private static long carriers() {
        return platformThreads().values().stream().filter(n -> n.matches("ForkJoinPool-\\d+-worker-\\d+")).count();
    }

    /** comando's platform threads started since {@code before}, by name, with how many share it. */
    private static TreeMap<String, Integer> comandos(Set<Long> before) {
        TreeMap<String, Integer> out = new TreeMap<>();
        platformThreads().forEach((id, name) -> {
            if (!before.contains(id) && NOT_COMANDO.stream().noneMatch(name::startsWith)) {
                out.merge(name, 1, Integer::sum);
            }
        });
        return out;
    }

    /**
     * Each thread {@link #comandos} counts: its class, whether a daemon, its state and its top frames. A thread named
     * by the JDK's default ({@code Thread-5}) says nothing of its owner; its frames do.
     */
    private static String stacks(Set<Long> before) {
        StringBuilder out = new StringBuilder();
        Thread.getAllStackTraces().forEach((t, frames) -> {
            if (t.isAlive() && !t.isVirtual() && !before.contains(t.threadId())
                    && NOT_COMANDO.stream().noneMatch(t.getName()::startsWith)) {
                out.append(t.getName()).append(" (").append(t.getClass().getName()).append(t.isDaemon() ? ", daemon, " : ", ")
                        .append(t.getState()).append(")\n");
                for (int i = 0; i < Math.min(12, frames.length); i++) {
                    out.append("    at ").append(frames[i]).append('\n');
                }
            }
        });
        return out.toString();
    }

    /** Every live platform thread by id; virtual threads are not listed by the JDK. */
    private static TreeMap<Long, String> platformThreads() {
        TreeMap<Long, String> out = new TreeMap<>();
        for (Thread t : Thread.getAllStackTraces().keySet()) {
            if (t.isAlive() && !t.isVirtual()) {
                out.put(t.threadId(), t.getName());
            }
        }
        return out;
    }
}
