package dev.marregui.comando.ui.shell;

import dev.marregui.comando.core.model.FolderRoot;
import dev.marregui.comando.core.model.HomeDir;
import dev.marregui.comando.ui.PanelTexts;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * comando's own settings file and its Claude Code kit, as the menus reach them. Every method blocks (a file write, a
 * {@code claude plugin} run): call it off the EDT.
 */
public interface Setup {

    /** What the kit is on this machine. */
    KitOffer kit() throws IOException;

    /** Installs the kit; what was done, one line each. */
    List<String> installKit() throws IOException;

    /** The folders the Files tab gains with the kit. */
    List<FolderRoot> kitFolders();

    /** For a window without them (the replay tests): every call fails. */
    Setup NONE = new Setup() {
        @Override
        public KitOffer kit() throws IOException {
            throw new IOException("no kit in this window");
        }

        @Override
        public List<String> installKit() throws IOException {
            throw new IOException("no kit in this window");
        }

        @Override
        public List<FolderRoot> kitFolders() {
            return List.of();
        }
    };

    /** The kit's question's buttons, by position: the first does the changes; at start, the third stops the offers. */
    int DO = 0;
    int DONT_ASK = 2;

    /** The kit's folders that the Files tab lacks: none of its folders shows that path or has that name. */
    static List<FolderRoot> missingFolders(List<FolderRoot> have, List<FolderRoot> kit) {
        List<FolderRoot> missing = new ArrayList<>();
        for (FolderRoot f : kit) {
            boolean shown = have.stream().anyMatch(h -> h.name().equals(f.name())
                    || PanelTexts.samePath(real(Path.of(h.path())), real(Path.of(f.path()))));
            if (!shown) {
                missing.add(f);
            }
        }
        return List.copyOf(missing);
    }

    /** {@code p} with its links resolved when it exists, so a folder shown through a link is the same folder. */
    private static Path real(Path p) {
        try {
            return p.toRealPath();
        } catch (IOException | RuntimeException e) {
            return p;
        }
    }

    /**
     * The kit as the user is asked about it.
     *
     * @param plugin           the plugin's id, {@code plugin@marketplace}
     * @param version          the version comando carries
     * @param installedVersion the version Claude Code loads, empty when not installed
     * @param enabled          whether the installed kit is enabled
     * @param loadsFrom        the folder Claude Code loads it from, empty when not installed or not reported
     * @param existing         a setup of the user's own; while the kit is not installed or disabled, it keeps the kit out
     * @param changes          what an install would do in Claude Code and its folder, one line each
     * @param folders          what it would add to the Files tab, one line each
     */
    record KitOffer(String plugin, String version, String installedVersion, boolean enabled, String loadsFrom,
                    List<String> existing, List<String> changes, List<String> folders) {

        public boolean installed() {
            return !installedVersion.isEmpty();
        }

        /** A setup of the user's own stands where the kit would go (not installed, or disabled): the answer is to skip. */
        boolean alreadySetUp() {
            return !existing.isEmpty() && (!installed() || !enabled);
        }

        /** Whether there is anything to do. */
        boolean pending() {
            return !alreadySetUp() && (!changes.isEmpty() || !folders.isEmpty());
        }

        KitOffer withFolders(List<String> lines) {
            return new KitOffer(plugin, version, installedVersion, enabled, loadsFrom, existing, changes, List.copyOf(lines));
        }

        /** The question's buttons; {@link #DO} first, {@link #DONT_ASK} third at start; one button when nothing to ask. */
        List<String> buttons(boolean atStart) {
            if (alreadySetUp()) {
                return List.of("Skip, already set up");
            }
            if (!pending()) {
                return List.of("OK");
            }
            String act = !installed() ? "Install" : changes.isEmpty() ? "Add the folders" : "Complete it";
            return atStart ? List.of(act, "Not now", "Don't ask again") : List.of(act, "Cancel");
        }

        /** The dialog's text. */
        String message() {
            List<String> lines = new ArrayList<>();
            if (alreadySetUp()) {
                lines.add("A setup of your own was found: " + String.join(", ", existing) + ".");
                lines.add("comando's Claude Code kit " + version + " would add a second /reflect and /deep-review beside it,");
                lines.add(installed() ? "so comando does not enable it." : "so comando does not install it.");
                return String.join("\n", lines);
            }
            if (installed()) {
                lines.add("comando's Claude Code kit " + installedVersion + " is installed (the plugin " + plugin
                        + (loadsFrom.isEmpty() ? "" : ", loaded from " + HomeDir.shorten(loadsFrom)) + ").");
                if (pending()) {
                    lines.add("");
                    lines.add("To complete it:");
                }
            } else {
                lines.add("comando's Claude Code kit " + version + " sets Claude Code up to work the way comando is built for:");
                lines.add("memory, a knowledge base and a scratchpad kept by /reflect, reviews by /deep-review, agent specs");
                lines.add("with executable acceptance, Gradle runs that queue per checkout. It comes whole: every change below,");
                lines.add("one memory shared by all your projects included, or none.");
                lines.add("");
                lines.add("Installing it will:");
            }
            changes.forEach(c -> lines.add("• " + c));
            folders.forEach(f -> lines.add("• " + f));
            lines.add("");
            String marketplace = plugin.substring(plugin.indexOf('@') + 1);
            lines.add("Existing files are never changed, but for one key added to Claude Code's settings.json. A step that fails undoes");
            lines.add("the ones before it. To remove the kit: claude plugin uninstall " + plugin + ", then claude plugin marketplace remove "
                    + marketplace + "; take autoMemoryDirectory out of settings.json to go back to a memory per project.");
            return String.join("\n", lines);
        }
    }
}
