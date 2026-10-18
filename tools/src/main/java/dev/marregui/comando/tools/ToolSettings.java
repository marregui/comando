package dev.marregui.comando.tools;

import java.nio.file.Files;
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

    /**
     * The settings now: the kit's {@code gradle-serial} when it is there, else the checkout's {@code ./gradlew}.
     *
     * @param kitSerial the {@code gradle-serial} comando keeps in its copy of the kit
     */
    public static ToolSettings of(Path kitSerial) {
        Optional<Path> serial = Files.isExecutable(kitSerial) ? Optional.of(kitSerial) : Optional.empty();
        return new ToolSettings(serial, System.getProperty("java.home"));
    }
}
