package dev.marregui.comando.tools;

import dev.marregui.comando.core.model.HomeDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Where the tools are, as the user set them ({@code tools.json} in comando's folder) or by default.
 *
 * @param gradleSerial the wrapper Gradle runs go through, which queues runs in one checkout; empty runs the checkout's
 *                     {@code ./gradlew}
 * @param javaHome     the JDK for child processes that need one and inherit none (an app started from Finder)
 * @param problem      what was wrong with the user's settings, for the run's console; empty when nothing
 */
public record ToolSettings(Optional<Path> gradleSerial, String javaHome, String problem) {

    /** The value of {@code gradleWrapper} that runs the checkout's {@code ./gradlew} directly. */
    public static final String NO_WRAPPER = "none";

    /**
     * Settings from the user's values, each empty for its default.
     *
     * @param gradleWrapper a wrapper's path; empty for {@code kitSerial}, the kit's {@code gradle-serial}; {@value
     *                      #NO_WRAPPER} for none
     * @param kitSerial     the {@code gradle-serial} comando keeps in its copy of the kit
     * @param problem       what was wrong reading the settings (an unreadable file), empty when nothing
     */
    public static ToolSettings resolve(String gradleWrapper, Path kitSerial, String problem) {
        StringBuilder problems = new StringBuilder(problem == null ? "" : problem);
        Optional<Path> serial;
        String wrapper = gradleWrapper == null ? "" : gradleWrapper.strip();
        if (wrapper.isEmpty()) {
            serial = Files.isExecutable(kitSerial) ? Optional.of(kitSerial) : Optional.empty();
        } else if (wrapper.equals(NO_WRAPPER)) {
            serial = Optional.empty();
        } else {
            Path p = Path.of(HomeDir.expand(wrapper));
            serial = Files.isExecutable(p) ? Optional.of(p) : Optional.empty();
            if (serial.isEmpty()) {
                append(problems, "tools.json: gradleWrapper " + wrapper + " is not an executable file; running ./gradlew");
            }
        }
        return new ToolSettings(serial, System.getProperty("java.home"), problems.toString());
    }

    private static void append(StringBuilder problems, String line) {
        problems.append(problems.isEmpty() ? "" : "\n").append(line);
    }
}
