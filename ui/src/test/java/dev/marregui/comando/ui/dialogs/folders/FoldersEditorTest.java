package dev.marregui.comando.ui.dialogs.folders;

import java.awt.Component;
import java.awt.Container;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import javax.swing.AbstractButton;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.marregui.comando.core.model.FolderRoot;
import dev.marregui.comando.core.model.RepoStatus;
import dev.marregui.comando.ui.kit.vcs.RepoChanges;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/// [FoldersEditor] edits the folders the Files tab shows, checking each before it can be saved.
/// - An unchanged list is valid, clean and saves as it was.
/// - A missing, relative or outside folder, an empty or taken name and an index outside the folder block the save and
///   say why.
/// - The kit's folders the list lacks go first, once, and the user's stay.
/// - Adding chooses the folder first and names it uniquely with its index guessed; a cancelled choice adds nothing.
/// - Removing and moving change only the list, never the disk.
/// - The listing file counts only with the Trash; the facts say which repository holds the folder.
/// - Only folders inside the config folder are picked, and a refused pick changes nothing.
/// - An index named but missing is created at save unless its folder is missing; every field's label explains it.
class FoldersEditorTest {

    private final List<Path> chosen = new ArrayList<>();

    /** Runs {@code f} on the event thread with the editor and answers what it gives. */
    private static <T> T on(final FoldersEditor e, final Function<FoldersEditor, T> f) throws Exception {
        final AtomicReference<T> out = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> out.set(f.apply(e)));
        return out.get();
    }

    /**
     * The folders' window, with the CLI's config folder at the file system's root so that every folder is inside it.
     */
    private FoldersEditor editor(final List<FolderRoot> folders, final RepoChanges repos) throws Exception {
        return editor(folders, Path.of("/"), repos);
    }

    private FoldersEditor editor(final List<FolderRoot> folders, final Path claudeDir, final RepoChanges repos) throws Exception {
        final AtomicReference<FoldersEditor> e = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> e.set(new FoldersEditor(folders, claudeDir, repos, new FoldersEditor.Chooser() {
            @Override
            public Optional<Path> folder(final Component owner, final Path start) {
                return chosen.isEmpty() ? Optional.empty() : Optional.of(chosen.removeFirst());
            }

            @Override
            public Optional<Path> file(final Component owner, final Path start) {
                return folder(owner, start);
            }
        })));
        return e.get();
    }

    /** Waits until no folder is still being looked up. */
    private static void settled(final FoldersEditor e) {
        await().atMost(Duration.ofSeconds(10)).until(() -> on(e, (FoldersEditor x) -> x.problemsOfAll().values().stream()
                .noneMatch(p -> "checking…".equals(p.get(FoldersEditor.PATH)))));
    }

    private static void type(final FoldersEditor e, final String key, final String text) throws Exception {
        SwingUtilities.invokeAndWait(() -> ((JTextField) e.field(key)).setText(text));
    }

    /// Two existing folders, once looked up, give a valid, clean editor whose save returns them unchanged.
    @Test
    void anUnchangedListIsValidCleanAndSavesAsItWas(@TempDir final Path dir) throws Exception {
        final FolderRoot a = new FolderRoot("A", Files.createDirectories(dir.resolve("a")).toString(), "", List.of("dist"), "knowledge");
        final FolderRoot b = new FolderRoot("B", Files.createDirectories(dir.resolve("b")).toString(), "", List.of());
        final FoldersEditor e = editor(List.of(a, b), new RepoChanges());
        settled(e);
        assertThat(on(e, FoldersEditor::valid)).isTrue();
        assertThat(on(e, FoldersEditor::dirty)).isFalse();
        assertThat(on(e, FoldersEditor::folders)).contains(List.of(a, b));
    }

    /// A missing folder, a taken name, a blank name, an index climbing out and a relative path each show their problem
    /// and make the editor invalid with no save; restoring the values makes it valid and clean again.
    @Test
    void aMissingFolderAnEmptyOrTakenNameAndAnIndexOutsideBlockSaveAndSayWhy(@TempDir final Path dir) throws Exception {
        final FolderRoot a = new FolderRoot("A", Files.createDirectories(dir.resolve("a")).toString(), "", List.of());
        final FolderRoot b = new FolderRoot("B", Files.createDirectories(dir.resolve("b")).toString(), "", List.of());
        final FoldersEditor e = editor(List.of(a, b), new RepoChanges());
        settled(e);
        type(e, FoldersEditor.PATH, dir.resolve("gone").toString());
        settled(e);
        assertThat(on(e, (FoldersEditor x) -> x.problemShown(FoldersEditor.PATH))).isEqualTo("no such folder");
        assertThat(on(e, FoldersEditor::valid)).isFalse();
        assertThat(on(e, FoldersEditor::folders)).isEmpty();

        type(e, FoldersEditor.PATH, a.path());
        type(e, FoldersEditor.NAME, "B");
        assertThat(on(e, (FoldersEditor x) -> x.problemShown(FoldersEditor.NAME))).isEqualTo("another folder has this name");
        type(e, FoldersEditor.NAME, " ");
        assertThat(on(e, (FoldersEditor x) -> x.problemShown(FoldersEditor.NAME))).isEqualTo("a name is needed");
        type(e, FoldersEditor.NAME, "A");
        type(e, FoldersEditor.INDEX, "../x.md");
        assertThat(on(e, (FoldersEditor x) -> x.problemShown(FoldersEditor.INDEX))).isEqualTo("must be inside the folder");
        type(e, FoldersEditor.INDEX, "");
        type(e, FoldersEditor.PATH, "relative/path");
        assertThat(on(e, (FoldersEditor x) -> x.problemShown(FoldersEditor.PATH))).isEqualTo("a full path, such as ~/notes");
        type(e, FoldersEditor.PATH, a.path());
        settled(e);
        assertThat(on(e, FoldersEditor::valid)).isTrue();
        assertThat(on(e, FoldersEditor::dirty)).as("back as it was").isFalse();
    }

    /// [FoldersEditor#addFirst] adds only the kit folder the list lacks, in front, keeps the user's, marks the editor
    /// dirty, and adds nothing the second time.
    @Test
    void theKitsFoldersTheListLacksGoFirstAndTheUsersStay(@TempDir final Path dir) throws Exception {
        final FolderRoot memory = new FolderRoot("Memory", Files.createDirectories(dir.resolve("kit/BRAIN/MEMORY")).toString(), "MEMORY.md",
                List.of(), "memory");
        final FolderRoot skills = new FolderRoot("Skills", Files.createDirectories(dir.resolve("kit/skills")).toString(), "", List.of(), "skills");
        final FolderRoot mine = new FolderRoot("Mine", Files.createDirectories(dir.resolve("notes")).toString(), "", List.of());
        final FoldersEditor e = editor(List.of(skills, mine), new RepoChanges());
        settled(e);

        assertThat(on(e, (FoldersEditor x) -> x.addFirst(List.of(memory, skills)))).as("Skills is there already").isEqualTo(1);
        settled(e);
        assertThat(on(e, FoldersEditor::dirty)).isTrue();
        assertThat(on(e, FoldersEditor::folders)).contains(List.of(memory, skills, mine));
        assertThat(on(e, (FoldersEditor x) -> x.addFirst(List.of(memory, skills)))).as("once").isZero();
    }

    /// A chosen folder whose name is taken is named `notes 2`, gets `INDEX.md` as the first index guess found, and is
    /// selected; a cancelled chooser adds nothing.
    @Test
    void addingChoosesTheFolderFirstAndNamesItUniquelyWithItsIndex(@TempDir final Path dir) throws Exception {
        final Path notes = Files.createDirectories(dir.resolve("x").resolve("notes"));
        Files.writeString(notes.resolve("INDEX.md"), "# a\n");
        Files.writeString(notes.resolve("MEMORY.md"), "# m\n");
        final FolderRoot taken = new FolderRoot("notes", Files.createDirectories(dir.resolve("notes")).toString(), "", List.of());
        final FoldersEditor e = editor(List.of(taken), new RepoChanges());
        chosen.add(notes);
        SwingUtilities.invokeAndWait(e::add);
        settled(e);
        final List<FolderRoot> saved = on(e, FoldersEditor::folders).orElseThrow();
        assertThat(saved).hasSize(2);
        assertThat(saved.get(1).name()).isEqualTo("notes 2");
        assertThat(saved.get(1).path()).isEqualTo(notes.toString());
        assertThat(saved.get(1).index()).as("the first guess found").isEqualTo("INDEX.md");
        assertThat(on(e, (FoldersEditor x) -> x.list().getSelectedIndex())).as("the new one is selected").isEqualTo(1);
        assertThat(on(e, FoldersEditor::dirty)).isTrue();

        SwingUtilities.invokeAndWait(e::add);
        assertThat(on(e, (FoldersEditor x) -> x.list().getModel().getSize())).as("a chooser cancelled adds nothing").isEqualTo(2);
    }

    /// Moving the last folder up swaps it, a move past the top is clamped, removing drops it from the list, and its
    /// folder stays on disk.
    @Test
    void removingAndMovingChangeOnlyTheList(@TempDir final Path dir) throws Exception {
        final FolderRoot a = new FolderRoot("A", Files.createDirectories(dir.resolve("a")).toString(), "", List.of());
        final FolderRoot b = new FolderRoot("B", Files.createDirectories(dir.resolve("b")).toString(), "", List.of());
        final FolderRoot c = new FolderRoot("C", Files.createDirectories(dir.resolve("c")).toString(), "", List.of());
        final FoldersEditor e = editor(List.of(a, b, c), new RepoChanges());
        settled(e);
        SwingUtilities.invokeAndWait(() -> e.select("C"));
        SwingUtilities.invokeAndWait(() -> e.move(-1));
        assertThat(on(e, FoldersEditor::folders)).contains(List.of(a, c, b));
        SwingUtilities.invokeAndWait(() -> e.move(-5));
        SwingUtilities.invokeAndWait(e::remove);
        assertThat(on(e, FoldersEditor::folders)).contains(List.of(a, b));
        assertThat(Files.isDirectory(dir.resolve("c"))).as("nothing on disk changes").isTrue();
    }

    /// A listing file with a folder in it is a problem while the Trash is on; turning the Trash off clears the problem
    /// and disables the field.
    @Test
    void theListingFileCountsOnlyWithTheTrash(@TempDir final Path dir) throws Exception {
        final FolderRoot pad = new FolderRoot("Pad", Files.createDirectories(dir.resolve("pad")).toString(), "", List.of(), "scratchpad", true, "ALPHA.md");
        final FoldersEditor e = editor(List.of(pad), new RepoChanges());
        settled(e);
        type(e, FoldersEditor.LISTED_IN, "sub/ALPHA.md");
        assertThat(on(e, (FoldersEditor x) -> x.problemShown(FoldersEditor.LISTED_IN))).isEqualTo("a file name, such as INDEX.md");
        SwingUtilities.invokeAndWait(() -> ((JCheckBox) e.field(FoldersEditor.DELETABLE)).doClick());
        assertThat(on(e, (FoldersEditor x) -> x.problemShown(FoldersEditor.LISTED_IN))).as("no Trash: no listing to check").isEmpty();
        assertThat(on(e, (FoldersEditor x) -> x.field(FoldersEditor.LISTED_IN).isEnabled())).isFalse();
    }

    /// A repository folder and a folder inside it show the repository, branch and uncommitted count; a plain folder
    /// shows `repo: none`, and none of them has a problem.
    @Test
    void theFactsSayWhetherTheFolderIsARepositoryAndWhatItHolds(@TempDir final Path dir) throws Exception {
        final Path repo = Files.createDirectories(dir.resolve("repo"));
        Files.createDirectories(repo.resolve(".git"));
        final Path inside = Files.createDirectories(repo.resolve("docs"));
        final Path plain = Files.createDirectories(dir.resolve("plain"));
        final RepoChanges repos = new RepoChanges();
        SwingUtilities.invokeAndWait(() -> repos.set(List.of(new RepoStatus(repo.toString(), "main",
                List.of(new RepoStatus.FileChange("a", "", RepoStatus.Kind.MODIFIED)), false, ""))));
        final FoldersEditor e = editor(List.of(new FolderRoot("R", repo.toString(), "", List.of()),
                new FolderRoot("D", inside.toString(), "", List.of()), new FolderRoot("P", plain.toString(), "", List.of())), repos);
        settled(e);
        assertThat(on(e, FoldersEditor::factsShown)).isEqualTo("repo: " + repo + " · main · 1 uncommitted");
        SwingUtilities.invokeAndWait(() -> e.select("D"));
        assertThat(on(e, FoldersEditor::factsShown)).as("the repository holding it").isEqualTo("repo: " + repo + " · main · 1 uncommitted");
        SwingUtilities.invokeAndWait(() -> e.select("P"));
        assertThat(on(e, FoldersEditor::factsShown)).isEqualTo("repo: none");
        assertThat(on(e, (FoldersEditor x) -> x.problemsOfAll())).isEqualTo(Map.of());
    }

    /// With a config folder set, its folders are valid, a typed folder outside it shows `must be inside …`, names stay
    /// unique, and the path field is chosen, not typed.
    @Test
    void aFolderMustLieInsideTheFoldersHome(@TempDir final Path dir) throws Exception {
        final Path claude = Files.createDirectories(dir.resolve(".claude"));
        final FolderRoot memory = new FolderRoot("Memory", Files.createDirectories(claude.resolve("MEMORY")).toString(), "", List.of());
        final FolderRoot kb = new FolderRoot("KB", Files.createDirectories(claude.resolve("KB")).toString(), "", List.of());
        final FoldersEditor e = editor(List.of(memory, kb), claude, new RepoChanges());
        settled(e);
        assertThat(on(e, FoldersEditor::folders)).contains(List.of(memory, kb));
        assertThat(on(e, FoldersEditor::dirty)).isFalse();

        type(e, FoldersEditor.PATH, Files.createDirectories(dir.resolve("elsewhere")).toString());
        settled(e);
        assertThat(on(e, (FoldersEditor x) -> x.problemShown(FoldersEditor.PATH))).startsWith("must be inside ");
        type(e, FoldersEditor.PATH, memory.path());
        type(e, FoldersEditor.NAME, "KB");
        assertThat(on(e, (FoldersEditor x) -> x.problemShown(FoldersEditor.NAME))).isEqualTo("another folder has this name");
        assertThat(on(e, (FoldersEditor x) -> ((JTextField) x.field(FoldersEditor.PATH)).isEditable())).as("chosen, not typed").isFalse();
    }

    /// A chosen folder outside the config folder is not added and says so, a chosen index outside the folder is not
    /// taken, and an index inside it is taken relative to the folder, the message then clearing.
    @Test
    void onlyFoldersInsideTheConfigFolderArePickedAndARefusedPickChangesNothing(@TempDir final Path dir) throws Exception {
        final Path claude = Files.createDirectories(dir.resolve(".claude"));
        final FolderRoot memory = new FolderRoot("Memory", Files.createDirectories(claude.resolve("MEMORY")).toString(), "", List.of());
        final FoldersEditor e = editor(List.of(memory), claude, new RepoChanges());
        settled(e);
        chosen.add(Files.createDirectories(dir.resolve("elsewhere")));
        SwingUtilities.invokeAndWait(e::add);
        assertThat(on(e, (FoldersEditor x) -> x.list().getModel().getSize())).as("not added").isEqualTo(1);
        assertThat(on(e, FoldersEditor::messageShown)).endsWith("elsewhere is outside " + claude + ": not added");
        assertThat(on(e, FoldersEditor::dirty)).isFalse();

        chosen.add(dir.resolve("elsewhere").resolve("x.md"));
        SwingUtilities.invokeAndWait(() -> button(e, "Choose a file in the folder").doClick());
        assertThat(on(e, (FoldersEditor x) -> ((JTextField) x.field(FoldersEditor.INDEX)).getText())).as("not taken").isEmpty();
        assertThat(on(e, FoldersEditor::messageShown)).endsWith("x.md is outside the folder: not taken as its index");

        Files.writeString(claude.resolve("MEMORY").resolve("MEMORY.md"), "# Memory\n");
        chosen.add(claude.resolve("MEMORY").resolve("MEMORY.md"));
        SwingUtilities.invokeAndWait(() -> button(e, "Choose a file in the folder").doClick());
        assertThat(on(e, (FoldersEditor x) -> ((JTextField) x.field(FoldersEditor.INDEX)).getText())).as("relative to the folder").isEqualTo("MEMORY.md");
        await().atMost(Duration.ofSeconds(10)).until(() -> on(e, FoldersEditor::messageShown).isEmpty());
    }

    /// A missing index file is a note that Save creates it, leaving the editor valid; one whose folder is missing is a
    /// problem with an error outline, and the editor is invalid.
    @Test
    void anIndexNamedButMissingIsCreatedAtSaveUnlessItsFolderIsMissing(@TempDir final Path dir) throws Exception {
        final Path claude = Files.createDirectories(dir.resolve(".claude"));
        final FolderRoot skills = new FolderRoot("Skills", Files.createDirectories(claude.resolve("skills")).toString(), "", List.of());
        final FoldersEditor e = editor(List.of(skills), claude, new RepoChanges());
        settled(e);
        type(e, FoldersEditor.INDEX, "INDEX.md");
        await().atMost(Duration.ofSeconds(10)).until(() -> on(e, FoldersEditor::messageShown).equals("INDEX.md does not exist yet: Save creates it"));
        assertThat(on(e, FoldersEditor::valid)).as("a note, not a problem").isTrue();

        type(e, FoldersEditor.INDEX, "gone/INDEX.md");
        await().atMost(Duration.ofSeconds(10)).until(() -> on(e, (FoldersEditor x) -> x.problemShown(FoldersEditor.INDEX)).equals("its folder does not exist"));
        assertThat(on(e, FoldersEditor::messageShown)).isEqualTo("Index file: its folder does not exist");
        assertThat(on(e, (FoldersEditor x) -> x.field(FoldersEditor.INDEX).getClientProperty("JComponent.outline"))).isEqualTo("error");
        assertThat(on(e, FoldersEditor::valid)).isFalse();
    }

    /// Each of the eight field labels carries a non-blank tooltip, the index and listing tips naming their rules.
    @Test
    void everyFieldsNameExplainsItUnderTheMouse(@TempDir final Path dir) throws Exception {
        final FoldersEditor e = editor(List.of(new FolderRoot("A", Files.createDirectories(dir.resolve("a")).toString(), "", List.of())),
                new RepoChanges());
        settled(e);
        final Map<String, String> tips = on(e, (FoldersEditor x) -> {
            final Map<String, String> out = new LinkedHashMap<>();
            labels(x.component(), out);
            return out;
        });
        assertThat(tips.keySet()).containsExactly("Name", "Folder", "Index file", "Leave out", "Icon", "Trash", "Listed in", "Group");
        assertThat(tips.values()).allSatisfy(t -> assertThat(t).isNotBlank());
        assertThat(tips.get("Index file")).contains("Named but missing: Save creates it").contains("Empty: the folder has no index");
        assertThat(tips.get("Listed in")).contains("An entry moved to the Trash takes its lines out");
    }

    private static void labels(final Component c, final Map<String, String> out) {
        if (c instanceof JLabel l && l.getLabelFor() != null) {
            out.put(l.getText(), l.getToolTipText());
        }
        if (c instanceof Container k) {
            for (final Component child : k.getComponents()) {
                labels(child, out);
            }
        }
    }

    private static AbstractButton button(final FoldersEditor e, final String tip) {
        final List<AbstractButton> found = new ArrayList<>();
        find(e.component(), tip, found);
        return found.getFirst();
    }

    private static void find(final Component c, final String tip, final List<AbstractButton> found) {
        if (c instanceof AbstractButton b && tip.equals(b.getToolTipText())) {
            found.add(b);
        }
        if (c instanceof Container k) {
            for (final Component child : k.getComponents()) {
                find(child, tip, found);
            }
        }
    }
}
