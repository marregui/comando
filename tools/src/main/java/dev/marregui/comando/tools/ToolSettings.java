package dev.marregui.comando.tools;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Where the tools are.
 *
 * @param gradleSerial the per-checkout Gradle lock wrapper, used instead of {@code ./gradlew} when present
 * @param jfrq         the jfrq launcher
 * @param scratchpad   the scratchpad's root folder
 * @param javaHome     the JDK for child processes that need one and inherit none (an app started from Finder)
 */
public record ToolSettings(Optional<Path> gradleSerial, Path jfrq, Path scratchpad, String javaHome) {

    public static ToolSettings defaults() {
        String home = System.getProperty("user.home");
        Path serial = Path.of(home, ".claude/plugins/local-acme/bin/gradle-serial");
        String env = System.getenv("COMANDO_JFRQ");
        Path jfrq = env != null && !env.isBlank() ? Path.of(env) : Path.of(home, "workspace/jfr/cli/build/install/jfrq/bin/jfrq");
        return new ToolSettings(Files.isExecutable(serial) ? Optional.of(serial) : Optional.empty(), jfrq,
                Path.of(home, ".claude/SCRATCHPAD"), System.getProperty("java.home"));
    }
}
