package dev.marregui.comando.app;

import dev.marregui.comando.core.intent.Intent;
import dev.marregui.comando.core.loop.StateLoop;
import dev.marregui.comando.core.model.SessionSpec;
import dev.marregui.comando.core.model.Status;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * comando's own platform threads stay within their budget: the reducer and the watchdog, plus one reader per
 * running session (a process pipe keeps an operating-system thread even when read from a virtual thread). Strands,
 * errands and timers add none. The JVM's, the toolkit's, the libraries' and the JDK's process reapers are not
 * comando's and are left out by name. Opens the real window, so this runs in {@code ./gradlew :app:uiTest}.
 */
@EnabledIfSystemProperty(named = "comando.uiTests", matches = "true")
class ThreadBudgetTest {

    static final Set<String> SINGLETONS = Set.of("comando-reducer", "watchdog");
    static final String SESSION_READER = "claude-out-";
    /** More sessions than cores: were a session's reader a virtual thread, its pipe read would add a carrier past them. */
    static final int SESSIONS = Runtime.getRuntime().availableProcessors() + 4;

    /** Threads that are not comando's: the JVM's, the toolkit's, the libraries' and the test runner's. */
    private static final List<String> NOT_COMANDO = List.of("Reference Handler", "Finalizer", "Signal Dispatcher", "Notification Thread",
            "Attach Listener", "Common-Cleaner", "DestroyJavaVM", "AWT-", "AppKit", "Java2D", "TimerQueue", "Image ", "process reaper",
            "ForkJoinPool", "VirtualThread-unblocker", "HTTP-Dispatcher", "idle-timeout-task", "JNA Cleaner", "Timer-",
            "Test worker", "/127.0.0.1", "surefire", "junit", "awaitility");

    @TempDir
    Path home;

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
        Awaitility.await("nothing of comando's left after close").atMost(Duration.ofSeconds(20))
                .untilAsserted(() -> assertThat(comandos(before)).as("comando's platform threads after close").isEmpty());
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
