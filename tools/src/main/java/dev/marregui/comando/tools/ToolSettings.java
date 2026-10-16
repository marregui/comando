package dev.marregui.comando.tools;

import dev.marregui.comando.core.model.HomeDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Where the tools are, as the user set them ({@code tools.json} in comando's folder) or by default.
 *
 * @param gradleSerial the wrapper Gradle runs go through, which queues runs in one checkout; empty runs the checkout's
 *                     {@code ./gradlew}
 * @param jfrq         the jfrq launcher, empty when none was found
 * @param javaHome     the JDK for child processes that need one and inherit none (an app started from Finder)
 * @param problem      what was wrong with the user's settings, for the run's console; empty when nothing
 */
public record ToolSettings(Optional<Path> gradleSerial, Optional<Path> jfrq, String javaHome, String problem) {

    /** The value of {@code gradleWrapper} that runs the checkout's {@code ./gradlew} directly. */
    public static final String NO_WRAPPER = "none";

    /**
     * Settings from the user's values, each empty for its default.
     *
     * @param gradleWrapper a wrapper's path; empty for {@code kitSerial}, the kit's {@code gradle-serial}; {@value
     *                      #NO_WRAPPER} for none
     * @param jfrq          jfrq's path; empty for {@code COMANDO_JFRQ}, else {@code jfrq} on the {@code PATH}
     * @param kitSerial     the {@code gradle-serial} comando keeps in its copy of the kit
     * @param jfrqVariable  the value of {@code COMANDO_JFRQ}, null when unset
     * @param pathVariable  the {@code PATH} to look for jfrq on
     * @param problem       what was wrong reading the settings (an unreadable file), empty when nothing
     */
    public static ToolSettings resolve(String gradleWrapper, String jfrq, Path kitSerial, String jfrqVariable, String pathVariable,
                                       String problem) {
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
        String j = jfrq == null ? "" : jfrq.strip();
        Optional<Path> launcher;
        if (!j.isEmpty()) {
            launcher = Optional.of(Path.of(HomeDir.expand(j)));
        } else if (jfrqVariable != null && !jfrqVariable.isBlank()) {
            launcher = Optional.of(Path.of(HomeDir.expand(jfrqVariable.strip())));
        } else {
            launcher = onPath("jfrq", pathVariable);
        }
        return new ToolSettings(serial, launcher, System.getProperty("java.home"), problems.toString());
    }

    /** The first executable {@code name} in the folders of {@code pathVariable}. */
    static Optional<Path> onPath(String name, String pathVariable) {
        if (pathVariable == null) {
            return Optional.empty();
        }
        for (String dir : pathVariable.split(File.pathSeparator)) {
            if (!dir.isBlank()) {
                Path p = Path.of(dir, name);
                if (Files.isExecutable(p) && Files.isRegularFile(p)) {
                    return Optional.of(p);
                }
            }
        }
        return Optional.empty();
    }

    private static void append(StringBuilder problems, String line) {
        problems.append(problems.isEmpty() ? "" : "\n").append(line);
    }
}
