package dev.marregui.comando.ui.kit.desktop;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import dev.marregui.comando.ui.kit.async.UiErrands;

/**
 * Opens a file at a line in IntelliJ IDEA through its command-line launcher, or in the system's default
 * application when no launcher is found. Launches on the window's {@code external-editor} errand; never blocks the
 * caller.
 */
public final class ExternalEditor {

    private static final System.Logger LOG = System.getLogger(ExternalEditor.class.getName());

    private ExternalEditor() {
    }

    /**
     * The {@code idea} launcher: Toolbox's script, then {@code /usr/local/bin} and {@code /opt/homebrew/bin}, which
     * the PATH of an app started from Finder lacks, then the PATH; empty when none is executable.
     */
    public static Optional<Path> launcher() {
        final String home = System.getProperty("user.home");
        final List<Path> candidates = new ArrayList<>(List.of(
                Path.of(home, "Library/Application Support/JetBrains/Toolbox/scripts/idea"),
                Path.of("/usr/local/bin/idea"),
                Path.of("/opt/homebrew/bin/idea")));
        candidates.addAll(Platform.onPath("idea"));
        return candidates.stream().filter(Files::isExecutable).findFirst();
    }

    /** The command that opens {@code file} at {@code line}; {@code line} below 1 opens it at the top. */
    static List<String> command(final Optional<Path> launcher, final Path file, final int line) {
        if (launcher.isPresent()) {
            final List<String> cmd = new ArrayList<>(List.of(launcher.get().toString()));
            if (line > 0) {
                cmd.addAll(List.of("--line", Integer.toString(line)));
            }
            cmd.add(file.toString());
            return cmd;
        }
        return List.of("open", file.toString());
    }

    public static void open(final Path file, final int line) {
        final List<String> cmd = command(launcher(), file, line);
        UiErrands.start("external-editor", () -> {
            try {
                new ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
            } catch (final IOException e) {
                LOG.log(System.Logger.Level.WARNING, "cannot open [file=" + file + ", command=" + cmd + ']', e);
            }
        });
    }
}
