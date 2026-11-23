package dev.marregui.comando.ui.panels;

import com.sun.security.auth.module.UnixSystem;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.util.List;

import dev.marregui.comando.core.model.FolderRoot;
import dev.marregui.comando.core.state.AppState;
import dev.marregui.comando.core.state.SessionState;

/**
 * Which local HTML pages may run their scripts: those in the places comando's own work makes or keeps them (record
 * 190). A script on a {@code file:} page can read any file on this machine, so a page from anywhere else loads with its
 * scripts off. A session working in a broad folder, the home folder say, trusts every page under it.
 */
final class PageTrust {

    /**
     * Claude Code's temporary folder of this user, where its sessions keep their scratch files:
     * {@code /tmp/claude-<uid>}, and the same folder as macOS names it through {@code /private/tmp}; empty off Unix.
     */
    private static final List<Path> CLAUDE_TEMP = claudeTemp();

    private PageTrust() {
    }

    /**
     * Whether {@code file} lies in a folder the Files tab lists in {@code s}, in a session's working directory, or in
     * Claude Code's temporary folder of this user.
     */
    static boolean trusted(final Path file, final AppState s) {
        final Path f = file.toAbsolutePath().normalize();
        for (final FolderRoot root : s.folders()) {
            if (under(f, root.path())) {
                return true;
            }
        }
        for (final SessionState session : s.sessions()) {
            if (under(f, session.spec().workDir())) {
                return true;
            }
        }
        for (final Path temp : CLAUDE_TEMP) {
            if (f.startsWith(temp)) {
                return true;
            }
        }
        return false;
    }

    /** Whether {@code f} lies under the folder {@code root} names; never for a blank name or the file system's root. */
    private static boolean under(final Path f, final String root) {
        if (root.isBlank()) {
            return false;
        }
        final Path r = Path.of(root).toAbsolutePath().normalize();
        return r.getNameCount() > 0 && f.startsWith(r);
    }

    private static List<Path> claudeTemp() {
        if (!"/".equals(FileSystems.getDefault().getSeparator())) {
            return List.of();
        }
        final String folder = "claude-" + new UnixSystem().getUid();
        return List.of(Path.of("/tmp", folder), Path.of("/private/tmp", folder));
    }
}
