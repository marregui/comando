package dev.marregui.comando.ui;

import dev.marregui.comando.core.model.PanelId;

import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Where editor text lives between runs of comando: one file per panel, in one folder. */
public interface PanelTexts {

    String load(PanelId panel);

    /** The text changed; the store saves it after a quiet period. */
    void changed(PanelId panel, String text);

    /** The panel closed: a panel of comando's own keeps its text and file; a document's file gets its last text. */
    void forget(PanelId panel);

    /** The panel was removed: its own file is deleted, a document's stays. */
    default void delete(PanelId panel) {
        forget(panel);
    }

    /**
     * Writes the panels now rather than after the quiet period; completes with null once written, or with the
     * reason the write failed.
     */
    default CompletableFuture<String> saveNow() {
        return CompletableFuture.completedFuture(null);
    }

    /** The panel's file, once it has one; it stays known after the panel closed. */
    default Optional<Path> file(PanelId panel) {
        return Optional.empty();
    }

    /**
     * True when the panel was opened on a file outside the panels' folder, a scratchpad document or any file opened:
     * it shows as that file's language, not as the panel's own SQL and prompts.
     */
    default boolean document(PanelId panel) {
        return false;
    }

    /** The folder of the panels' files; empty when text is not kept in files. */
    default Optional<Path> folder() {
        return Optional.empty();
    }

    /**
     * Binds a new panel to an existing file of the folder and loads its text, off the EDT; completes with the reason
     * when it cannot, else empty.
     */
    default CompletableFuture<String> open(PanelId panel, Path file) {
        return CompletableFuture.completedFuture("panel text is not kept in files");
    }

    /**
     * A panel's file got new text from outside comando. {@code take}, called on the EDT, makes it the panel's
     * text and answers true; it answers false while the panel has edits not yet saved, whose save then keeps the
     * outside version next to the file.
     */
    record Outside(PanelId panel, String file, String text, BooleanSupplier take) {
    }

    /** Where outside changes to open panels' files are reported, from the store's thread. */
    default void onOutsideChange(Consumer<Outside> listener) {
    }

    /** A document's file, {@code file} by name, was deleted outside comando while its panel had no unsaved edits. */
    record Deleted(PanelId panel, String file) {
    }

    /**
     * Where deleted documents are reported, from the store's thread: the panel shows a file that is gone. A panel's
     * own file deleted outside is not reported here; its panel writes it again on the next save.
     */
    default void onDeleted(Consumer<Deleted> listener) {
    }
}
