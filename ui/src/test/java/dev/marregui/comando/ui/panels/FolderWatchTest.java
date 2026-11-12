package dev.marregui.comando.ui.panels;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import dev.marregui.comando.core.intent.Intent;

import static org.assertj.core.api.Assertions.assertThat;

/// [FolderWatch]: the folder tabs' shares sent as one replace-all [Intent.WatchFolders].
/// - the union of the shares, in the order the parts were made, each folder once, open subfolders merged
/// - nothing is sent while the union stays the same, unless a part forces it and the union is not empty
class FolderWatchTest {

    /// Two parts' folders go out as one union; a part's change replaces only its share; an unchanged union is not
    /// sent again, a forced one is, and a forced empty union is not.
    @Test
    void thePartsGoOutAsOneUnionSentOnlyWhenItChanges() {
        final List<Intent> sent = new ArrayList<>();
        final FolderWatch watch = new FolderWatch(sent::add);
        final FolderWatch.Part kit = watch.part();
        final FolderWatch.Part brain = watch.part();

        brain.watch(List.of("Memory"), Map.of("Memory", List.of("/b/MEMORY/archive")), false);
        kit.watch(List.of("Kit"), Map.of("Kit", List.of()), false);
        assertThat(sent).last().isEqualTo(new Intent.WatchFolders(List.of("Kit", "Memory"),
                Map.of("Kit", List.of(), "Memory", List.of("/b/MEMORY/archive"))));

        final int before = sent.size();
        kit.watch(List.of("Kit"), Map.of("Kit", List.of()), false);
        assertThat(sent).as("the same union").hasSize(before);
        kit.watch(List.of("Kit"), Map.of("Kit", List.of()), true);
        assertThat(sent).as("forced").hasSize(before + 1);

        kit.watch(List.of(), Map.of(), false);
        assertThat(sent).last()
                .isEqualTo(new Intent.WatchFolders(List.of("Memory"), Map.of("Memory", List.of("/b/MEMORY/archive"))));
        brain.watch(List.of(), Map.of(), false);
        assertThat(sent).last().isEqualTo(new Intent.WatchFolders(List.of(), Map.of()));
        final int empty = sent.size();
        brain.watch(List.of(), Map.of(), true);
        assertThat(sent).as("a forced empty union reads nothing").hasSize(empty);
    }
}
