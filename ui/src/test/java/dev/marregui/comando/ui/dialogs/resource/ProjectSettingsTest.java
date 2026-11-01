package dev.marregui.comando.ui.dialogs.resource;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.marregui.comando.core.model.HomeDir;

import static org.assertj.core.api.Assertions.assertThat;

/// [ProjectSettings]: what a session's working folder's own `.claude` settings add to the kit's.
/// - a folder without them adds nothing
/// - the files found are named; a permission mode set in them overrides the kit's, the local file's winning
/// - a file that does not read still counts as there, with no mode
/// - a folder whose `.claude` is the kit has the kit's own settings, nothing on top
class ProjectSettingsTest {

    @TempDir
    Path work;

    /// No folder, and a folder without `.claude` settings, give an empty line.
    @Test
    void aFolderWithoutProjectSettingsAddsNothing() {
        assertThat(ProjectSettings.line("")).isEmpty();
        assertThat(ProjectSettings.line(work.toString())).isEmpty();
    }

    /// `settings.json` with no mode is named; `settings.local.json` setting `acceptEdits` over the shared file's `plan`
    /// is named with its mode, which overrides the kit's.
    @Test
    void theFilesFoundAreNamedAndTheirPermissionModeOverridesTheKits() throws Exception {
        final Path claude = Files.createDirectories(work.resolve(".claude"));
        Files.writeString(claude.resolve("settings.json"), "{\"model\": \"opus\"}");
        final String where = HomeDir.shorten(claude.toString());
        assertThat(ProjectSettings.line(work.toString())).isEqualTo("Plus the project's " + where + "/settings.json on top of the kit's");
        Files.writeString(claude.resolve("settings.json"), "{\"permissions\": {\"defaultMode\": \"plan\"}}");
        Files.writeString(claude.resolve("settings.local.json"), "{\"permissions\": {\"defaultMode\": \"acceptEdits\"}}");
        assertThat(ProjectSettings.line(work.toString())).isEqualTo("Plus the project's " + where
                + "/settings.json and settings.local.json on top of the kit's; its permissions: acceptEdits (settings.local.json)"
                + " overrides the kit's");
    }

    /// A session working in the kit's parent folder, whose `.claude` is the kit, is told its settings files are the
    /// kit's own, never that they override the kit's.
    @Test
    void aFolderWhoseClaudeFolderIsTheKitHasNoProjectSettings() throws Exception {
        final Path claude = Files.createDirectories(work.resolve(".claude"));
        Files.writeString(claude.resolve("settings.json"), "{\"permissions\": {\"defaultMode\": \"plan\"}}");
        assertThat(ProjectSettings.line(work.toString(), work.resolve("x/../.claude"))).isEqualTo("The working folder's "
                + HomeDir.shorten(claude.toString()) + "/settings.json is the kit's own: no project settings on top");
        assertThat(ProjectSettings.line(work.toString(), work.resolve("kit"))).as("another kit").startsWith("Plus the project's ");
    }

    /// A `settings.local.json` that is no JSON is named, and sets no mode.
    @Test
    void aFileThatDoesNotReadCountsAsThereWithNoMode() throws Exception {
        final Path claude = Files.createDirectories(work.resolve(".claude"));
        Files.writeString(claude.resolve("settings.local.json"), "{ not json");
        assertThat(ProjectSettings.line(work.toString())).endsWith("/settings.local.json on top of the kit's");
    }
}
