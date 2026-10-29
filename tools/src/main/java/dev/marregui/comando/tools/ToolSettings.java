package dev.marregui.comando.tools;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Where the tools are, by convention: nothing to set.
 *
 * @param gradleSerial the wrapper Gradle runs go through, which queues runs in one checkout; empty runs the checkout's
 *                     {@code ./gradlew}
 * @param javaHome     the JDK for child processes that need one and inherit none (an app started from Finder)
 */
public record ToolSettings(Optional<Path> gradleSerial, String javaHome) {

    /** The wrapper's name on the {@code PATH}. */
    static final String GRADLE_SERIAL = "gradle-serial";

    /**
     * The settings now: a {@code gradle-serial} on comando's {@code PATH} when there is one, else the checkout's
     * {@code ./gradlew}.
     */
    public static ToolSettings now() {
        return of(System.getenv("PATH"));
    }

    /** With {@code path} as the {@code PATH}, its folders separated as the platform separates them. */
    static ToolSettings of(String path) {
        Optional<Path> serial = Optional.empty();
        for (String dir : (path == null ? "" : path).split(File.pathSeparator)) {
            if (dir.isBlank()) {
                continue;
            }
            try {
                Path candidate = Path.of(dir).resolve(GRADLE_SERIAL);
                if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) {
                    serial = Optional.of(candidate);
                    break;
                }
            } catch (InvalidPathException e) {
                // a PATH entry that is no path names no tool
            }
        }
        return new ToolSettings(serial, System.getProperty("java.home"));
    }
}
