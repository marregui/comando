package dev.marregui.comando.ui.panels;

import dev.marregui.comando.core.intent.Intent;
import dev.marregui.comando.core.model.PanelId;
import dev.marregui.comando.core.schema.SchemaRegistry;
import dev.marregui.comando.core.state.AppState;
import dev.marregui.comando.core.state.PanelState;
import dev.marregui.comando.ui.PanelTexts;
import org.junit.jupiter.api.Test;

import javax.swing.SwingUtilities;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

class WorkspaceTest {

    private static final PanelId P = new PanelId("p");

    /** Panel text in memory that hands its outside-change listener to the test. */
    private static final class Texts implements PanelTexts {
        final AtomicInteger changes = new AtomicInteger();
        Consumer<Outside> listener;
        Consumer<Deleted> deletions;

        @Override
        public void onDeleted(Consumer<Deleted> listener) {
            this.deletions = listener;
        }

        @Override
        public String load(PanelId panel) {
            return "SELECT 1;";
        }

        @Override
        public void changed(PanelId panel, String text) {
            changes.incrementAndGet();
        }

        @Override
        public void forget(PanelId panel) {
        }

        @Override
        public void onOutsideChange(Consumer<Outside> listener) {
            this.listener = listener;
        }
    }

    @Test
    void aFileChangedOutsideShowsInItsPanelUnlessThePanelHasUnsavedEdits() throws Exception {
        Texts texts = new Texts();
        List<Intent> intents = new CopyOnWriteArrayList<>();
        Workspace[] w = new Workspace[1];
        SwingUtilities.invokeAndWait(() -> {
            w[0] = new Workspace(intents::add, texts, new SchemaRegistry());
            w[0].render(AppState.initial(List.of(new PanelState(P, "spec", "", "")), List.of(), List.of()));
        });
        assertThat(texts.listener).as("the workspace listens for outside changes").isNotNull();

        SwingUtilities.invokeAndWait(() -> w[0].takeOutside(new PanelTexts.Outside(P, "spec.md", "SELECT 2;", () -> false)));
        assertThat(w[0].focused().editor().text()).as("unsaved edits win; their save keeps the outside text aside").isEqualTo("SELECT 1;");

        SwingUtilities.invokeAndWait(() -> w[0].takeOutside(new PanelTexts.Outside(P, "spec.md", "SELECT 2;", () -> true)));
        assertThat(w[0].focused().editor().text()).isEqualTo("SELECT 2;");
        assertThat(texts.changes).as("a reload is not an edit to save").hasValue(0);
        assertThat(intents).filteredOn(Intent.Notify.class::isInstance).extracting(i -> ((Intent.Notify) i).text())
                .containsExactly("Reloaded spec.md: it changed outside comando");
    }

    @Test
    void aDeletedDocumentClosesItsPanelWithNothingToReopen() throws Exception {
        PanelId doc = new PanelId("d");
        Texts texts = new Texts();
        List<Intent> intents = new CopyOnWriteArrayList<>();
        PanelState spec = new PanelState(P, "spec", "", "");
        Workspace[] w = new Workspace[1];
        SwingUtilities.invokeAndWait(() -> {
            w[0] = new Workspace(intents::add, texts, new SchemaRegistry());
            w[0].render(AppState.initial(List.of(spec, new PanelState(doc, "ALPHA", "", "")), List.of(), List.of()));
        });
        assertThat(texts.deletions).as("the workspace listens for deleted documents").isNotNull();

        SwingUtilities.invokeAndWait(() -> w[0].closeDeleted(new PanelTexts.Deleted(doc, "ALPHA.md")));
        assertThat(intents).containsExactly(new Intent.ClosePanel(doc));
        SwingUtilities.invokeAndWait(() -> w[0].render(AppState.initial(List.of(spec), List.of(), List.of())));
        assertThat(w[0].hasClosed()).as("⌘⇧T has nothing to reopen: the file is gone").isFalse();
        assertThat(intents).filteredOn(Intent.Notify.class::isInstance).extracting(i -> ((Intent.Notify) i).text())
                .containsExactly("Closed ALPHA: ALPHA.md was deleted outside comando");

        SwingUtilities.invokeAndWait(() -> w[0].closeDeleted(new PanelTexts.Deleted(doc, "ALPHA.md")));
        assertThat(intents).as("a panel already gone is not closed twice").filteredOn(Intent.ClosePanel.class::isInstance).hasSize(1);
    }
}
