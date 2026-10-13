package dev.marregui.comando.ui.kit.desktop;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Opens a file at a line in IntelliJ IDEA through its command-line launcher, or in the system's default
 * application when no launcher is found. Launches on a virtual thread; never blocks the caller.
 */
public final class ExternalEditor {

    private static final System.Logger LOG = System.getLogger(ExternalEditor.class.getName());

    private ExternalEditor() {
    }

    /** The {@code idea} launcher: Toolbox scripts first, then the PATH an app started from Finder has. */
    public static Optional<Path> launcher() {
        String home = System.getProperty("user.home");
        List<Path> candidates = new ArrayList<>(List.of(
                Path.of(home, "Library/Application Support/JetBrains/Toolbox/scripts/idea"),
                Path.of("/usr/local/bin/idea"),
                Path.of("/opt/homebrew/bin/idea")));
        String path = System.getenv("PATH");
        if (path != null) {
            for (String dir : path.split(java.io.File.pathSeparator)) {
                if (!dir.isEmpty()) {
                    candidates.add(Path.of(dir, "idea"));
                }
            }
        }
        return candidates.stream().filter(Files::isExecutable).findFirst();
    }

    /** The command that opens {@code file} at {@code line}; {@code line} below 1 opens it at the top. */
    static List<String> command(Optional<Path> launcher, Path file, int line) {
        if (launcher.isPresent()) {
            List<String> cmd = new ArrayList<>(List.of(launcher.get().toString()));
            if (line > 0) {
                cmd.addAll(List.of("--line", Integer.toString(line)));
            }
            cmd.add(file.toString());
            return cmd;
        }
        return List.of("open", file.toString());
    }

    public static void open(Path file, int line) {
        List<String> cmd = command(launcher(), file, line);
        Thread.ofVirtual().name("external-editor").start(() -> {
            try {
                new ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
            } catch (IOException e) {
                LOG.log(System.Logger.Level.WARNING, "cannot open " + file + " [command=" + cmd + "]: " + e.getMessage());
            }
        });
    }
}
