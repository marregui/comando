package dev.marregui.comando.core.reduce;

import java.util.List;

import org.junit.jupiter.api.Test;

import dev.marregui.comando.core.effect.Effect;
import dev.marregui.comando.core.intent.Intent;
import dev.marregui.comando.core.model.ChangeAsk;
import dev.marregui.comando.core.model.ChangeTexts;
import dev.marregui.comando.core.model.FolderRoot;
import dev.marregui.comando.core.model.RepoStatus;
import dev.marregui.comando.core.state.AppState;
import dev.marregui.comando.core.state.ViewState;
import dev.marregui.comando.core.view.Placement;
import dev.marregui.comando.core.view.ViewSpec;
import dev.marregui.comando.testkit.FakeClock;
import dev.marregui.comando.testkit.States;

import static org.assertj.core.api.Assertions.assertThat;

/// [Reducer] routes folder and repository intents to effects while preserving repository-path safety and watch state.
///
/// - Changes visibility controls repository watching
/// - folder saves detect concurrent replacements and duplicate names
/// - repository actions reject paths outside their root
class ReducerChangesTest {

    private final Reducer reducer = new Reducer(new FakeClock());
    private AppState state = States.twoPanels();
    private List<Effect> effects = List.of();

    private void apply(final Intent intent) {
        final Transition t = reducer.apply(state, intent);
        state = t.state();
        effects = t.effects();
    }

    private long changesView() {
        return state.views().stream().filter(v -> v.spec() instanceof ViewSpec.Changes).map(ViewState::id).findFirst().orElseThrow();
    }

    /// Adding folders and opening or closing the Changes view emits `WatchRepos` with the current folders and
    /// active-view flag only when either changes.
    @Test
    void everyFoldersRepositoriesAreWatchedOftenWhileTheChangesViewIsOpen() {
        final FolderRoot kb = new FolderRoot("KB", "/kb", "", List.of());
        final FolderRoot notes = new FolderRoot("Notes", "/n", "", List.of());
        apply(new Intent.FoldersRead(List.of(kb)));
        assertThat(effects).as("the folders changed").containsExactly(new Effect.WatchRepos(List.of(kb), false));

        apply(new Intent.ShowView("", ViewSpec.Changes.TITLE, new ViewSpec.Changes(), Placement.SIDE));
        assertThat(effects).containsExactly(new Effect.WatchRepos(List.of(kb), true));

        apply(new Intent.FoldersRead(List.of(kb, notes)));
        assertThat(effects).as("open: a folder added is watched").containsExactly(new Effect.WatchRepos(List.of(kb, notes), true));

        apply(new Intent.ShowView("", ViewSpec.Changes.TITLE, new ViewSpec.Changes(), Placement.SIDE));
        assertThat(effects).as("shown again: already watched").isEmpty();

        apply(new Intent.CloseView(changesView()));
        assertThat(effects).containsExactly(new Effect.WatchRepos(List.of(kb, notes), false));
    }

    /// Saving a distinct folder list persists it and describes a changed base, whereas duplicate names leave folders
    /// untouched with an error notice.
    @Test
    void theFoldersWindowsListReplacesTheFoldersAndIsSavedSayingWhenItReplacedAChangeMadeMeanwhile() {
        final FolderRoot kb = new FolderRoot("KB", "/kb", "", List.of());
        final FolderRoot notes = new FolderRoot("Notes", "/n", "", List.of());
        apply(new Intent.FoldersRead(List.of(kb)));
        apply(new Intent.SaveFolders(List.of(notes, kb), List.of(kb)));
        assertThat(state.folders()).containsExactly(notes, kb);
        assertThat(effects).containsExactly(new Effect.SaveFolders(List.of(notes, kb)), new Effect.WatchRepos(List.of(notes, kb), false));
        assertThat(state.notice().text()).isEqualTo("Saved the Files tab's folders");

        apply(new Intent.SaveFolders(List.of(kb), List.of(kb)));
        assertThat(state.notice().text()).contains("replacing a change to folders.json");

        final AppState before = state;
        apply(new Intent.SaveFolders(List.of(kb, new FolderRoot("KB", "/other", "", List.of())), List.of(kb)));
        assertThat(state.folders()).as("one name twice: nothing saved").isEqualTo(before.folders());
        assertThat(effects).noneMatch(x -> x instanceof Effect.SaveFolders);
        assertThat(state.notice().text()).isEqualTo("Two folders are called KB: nothing saved");
    }

    /// Repository updates and change reads produce display effects without replacing the current application state.
    @Test
    void reposAndChangesPassToTheirPortsAndLeaveTheStateAsItWas() {
        final AppState before = state;
        final RepoStatus repo = new RepoStatus("/r", "main", List.of(new RepoStatus.FileChange("a", "", RepoStatus.Kind.MODIFIED)), false, "");
        apply(new Intent.ReposLoaded(List.of(repo)));
        assertThat(effects).containsExactly(new Effect.ShowRepos(List.of(repo)));

        final RepoStatus.FileChange c = repo.changes().getFirst();
        apply(new Intent.OpenChange("/r", c, ChangeAsk.FOLLOW));
        assertThat(effects).containsExactly(new Effect.ReadChange("/r", c, ChangeAsk.FOLLOW));

        final ChangeTexts texts = new ChangeTexts("/r", c, "1\n", "2\n", "");
        apply(new Intent.ChangeRead(texts, ChangeAsk.OPEN));
        assertThat(effects).containsExactly(new Effect.ShowChange(texts, ChangeAsk.OPEN));
        assertThat(state).isSameAs(before);
    }

    /// Rollback, removal, and unstage forward valid repository-relative paths, while empty, escaping, root, and
    /// relative-root requests are refused.
    @Test
    void rollingBackRemovingAndUnstagingGoToTheToolsOnlyForPathsInsideTheRepository() {
        final RepoStatus.FileChange a = new RepoStatus.FileChange("src/A.java", "", RepoStatus.Kind.MODIFIED);
        apply(new Intent.RollBack("/r", List.of(a)));
        assertThat(effects).containsExactly(new Effect.RollBack("/r", List.of(a)));
        apply(new Intent.RemoveFiles("/r", List.of("notes/x.md")));
        assertThat(effects).containsExactly(new Effect.RemoveFiles("/r", List.of("notes/x.md")));
        apply(new Intent.Unstage("/r", List.of("src/A.java")));
        assertThat(effects).containsExactly(new Effect.Unstage("/r", List.of("src/A.java")));

        apply(new Intent.RemoveFiles("/r", List.of()));
        assertThat(effects).as("nothing asked, nothing done").isEmpty();
        for (final String bad : List.of("../elsewhere.txt", "/etc/hosts", ".")) {
            apply(new Intent.RemoveFiles("/r", List.of(bad)));
            assertThat(effects).as(bad + ": nothing reaches the tools").noneMatch(Effect.RemoveFiles.class::isInstance);
            assertThat(state.notice().text()).as(bad).contains("not a file of the repository");
        }
        apply(new Intent.RollBack("relative", List.of(a)));
        assertThat(effects).noneMatch(Effect.RollBack.class::isInstance);
        assertThat(state.notice().text()).contains("no repository's top folder");
    }
}
