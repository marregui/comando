package dev.marregui.comando.ui.shell;

import dev.marregui.comando.core.model.FolderRoot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SetupTest {

    private static Setup.KitOffer offer(String installed, List<String> existing, List<String> changes) {
        return offer(installed, true, existing, changes);
    }

    private static Setup.KitOffer offer(String installed, boolean enabled, List<String> existing, List<String> changes) {
        return new Setup.KitOffer("comando@comando", "1.0.0", installed, !installed.isEmpty() && enabled,
                installed.isEmpty() ? "" : "/x/kit", existing, changes, List.of());
    }

    @Test
    void anOfferListsEveryChangeAndHowToUndoIt() {
        Setup.KitOffer o = offer("", List.of(), List.of("Register ~/.comando/kit", "Create ~/.claude/KB/ALPHA.md"))
                .withFolders(List.of("Add Knowledge base (~/.claude/KB) to the Files tab"));

        assertThat(o.pending()).isTrue();
        assertThat(o.message()).startsWith("comando's Claude Code kit 1.0.0 sets Claude Code up")
                .contains("It comes whole: every change below,\none memory shared by all your projects included, or none.")
                .contains("Installing it will:\n• Register ~/.comando/kit\n• Create ~/.claude/KB/ALPHA.md\n"
                        + "• Add Knowledge base (~/.claude/KB) to the Files tab\n")
                .endsWith("To remove the kit: claude plugin uninstall comando@comando, then claude plugin marketplace remove comando;"
                        + " take autoMemoryDirectory out of settings.json to go back to a memory per project.");
        assertThat(o.buttons(true)).containsExactly("Install", "Not now", "Don't ask again");
        assertThat(o.buttons(true).get(Setup.DO)).isEqualTo("Install");
        assertThat(o.buttons(true).get(Setup.DONT_ASK)).isEqualTo("Don't ask again");
        assertThat(o.buttons(false)).containsExactly("Install", "Cancel");
    }

    @Test
    void aSetupOfTheUsersOwnIsSkippedWithNothingToInstall() {
        Setup.KitOffer o = offer("", List.of("~/.claude/skills/reflect", "~/.claude/commands/deep-review.md"), List.of())
                .withFolders(List.of("Add Scratchpad (~/.claude/SCRATCHPAD) to the Files tab"));

        assertThat(o.alreadySetUp()).isTrue();
        assertThat(o.pending()).as("not even the folders").isFalse();
        assertThat(o.buttons(true)).containsExactly("Skip, already set up");
        assertThat(o.buttons(false)).containsExactly("Skip, already set up");
        assertThat(o.message()).isEqualTo("""
                A setup of your own was found: ~/.claude/skills/reflect, ~/.claude/commands/deep-review.md.
                comando's Claude Code kit 1.0.0 would add a second /reflect and /deep-review beside it,
                so comando does not install it.""");
    }

    @Test
    void aDisabledKitIsNotEnabledBesideASetupOfTheUsersOwn() {
        Setup.KitOffer o = offer("1.1.0", false, List.of("~/.claude/skills/reflect"), List.of());

        assertThat(o.alreadySetUp()).isTrue();
        assertThat(o.pending()).isFalse();
        assertThat(o.buttons(false)).containsExactly("Skip, already set up");
        assertThat(o.message()).endsWith("so comando does not enable it.");
    }

    @Test
    void aFolderShownThroughALinkIsTheSameFolder(@TempDir Path tmp) throws Exception {
        Path kb = Files.createDirectories(tmp.resolve("claude/KB"));
        Path link = Files.createSymbolicLink(tmp.resolve("kb-link"), kb);
        FolderRoot kit = new FolderRoot("Knowledge base", link.toString(), "ALPHA.md", List.of());

        assertThat(Setup.missingFolders(List.of(new FolderRoot("Mine", kb.toString(), "", List.of())), List.of(kit))).isEmpty();
    }

    @Test
    void anInstalledKitSaysWhereItLoadsFromAndOffersOnlyWhatIsMissing() {
        Setup.KitOffer done = offer("1.1.0", List.of("~/.claude/skills/reflect"), List.of());
        assertThat(done.alreadySetUp()).as("installed by the user anyway").isFalse();
        assertThat(done.pending()).isFalse();
        assertThat(done.buttons(false)).containsExactly("OK");
        assertThat(done.message()).startsWith("comando's Claude Code kit 1.1.0 is installed (the plugin comando@comando, loaded from /x/kit).\n\nExisting");

        Setup.KitOffer disabled = offer("1.1.0", List.of(), List.of("Enable the plugin comando@comando for your user"));
        assertThat(disabled.buttons(false)).containsExactly("Complete it", "Cancel");
        assertThat(disabled.message()).contains("To complete it:\n• Enable the plugin comando@comando for your user").doesNotContain("Installing");

        Setup.KitOffer folders = offer("1.1.0", List.of(), List.of()).withFolders(List.of("Add Scratchpad (~/s) to the Files tab"));
        assertThat(folders.pending()).isTrue();
        assertThat(folders.buttons(true)).containsExactly("Add the folders", "Not now", "Don't ask again");
    }

    @Test
    void theKitsFoldersAreAddedUnlessAFolderShowsTheirPathOrHasTheirName() {
        FolderRoot kb = new FolderRoot("Knowledge base", "/u/.claude/KB", "ALPHA.md", List.of());
        FolderRoot pad = new FolderRoot("Scratchpad", "/u/.claude/SCRATCHPAD", "README.md", List.of());

        assertThat(Setup.missingFolders(List.of(), List.of(kb, pad))).containsExactly(kb, pad);
        assertThat(Setup.missingFolders(List.of(new FolderRoot("Mine", "/u/.claude/kb/", "", List.of())), List.of(kb, pad)))
                .as("the same folder under another name, as the volume compares paths").containsExactly(pad);
        assertThat(Setup.missingFolders(List.of(new FolderRoot("Scratchpad", "/elsewhere", "", List.of())), List.of(kb, pad)))
                .as("the name is taken").containsExactly(kb);
    }
}
