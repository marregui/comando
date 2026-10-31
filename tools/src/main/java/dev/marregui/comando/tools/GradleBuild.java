package dev.marregui.comando.tools;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Finds the build a directory belongs to and the command that runs tasks in it. */
final class GradleBuild {

    private GradleBuild() {
    }

    /** The nearest directory at or above {@code dir} with an executable {@code gradlew}. */
    static Optional<Path> root(final Path dir) {
        for (Path d = dir.toAbsolutePath().normalize(); d != null; d = d.getParent()) {
            if (Files.isExecutable(d.resolve("gradlew"))) {
                return Optional.of(d);
            }
        }
        return Optional.empty();
    }

    /**
     * {@code <gradle-serial> <root> <tasks>} when the wrapper is there, else {@code <root>/gradlew --console=plain
     * <tasks>}; the tasks split as {@link Args} splits them.
     */
    static List<String> command(final Path root, final String tasks, final Optional<Path> gradleSerial) {
        final List<String> cmd = new ArrayList<>();
        if (gradleSerial.isPresent()) {
            cmd.add(gradleSerial.get().toString());
            cmd.add(root.toString());
        } else {
            cmd.add(root.resolve("gradlew").toString());
            cmd.add("--console=plain");
        }
        cmd.addAll(Args.split(tasks));
        return cmd;
    }
}
