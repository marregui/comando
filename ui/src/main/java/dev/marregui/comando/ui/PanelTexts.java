package dev.marregui.comando.ui;

import dev.marregui.comando.core.model.PanelId;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Where editor text lives between runs of comando: one file per panel, in one folder. */
public interface PanelTexts {

    /**
     * Whether two paths name one file, as the store decides it: absolute, normalised, ignoring case (macOS's default
     * volume). The one rule for "this file is already open", in the store and in the window.
     */
    static boolean samePath(Path a, Path b) {
        return a.toAbsolutePath().normalize().toString().equalsIgnoreCase(b.toAbsolutePath().normalize().toString());
    }

    /** Whether a panel on {@code file} shows a log: read-only, following its end as it grows. */
    static boolean isLog(Path file) {
        return file.getFileName() != null && file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".log");
    }

    /** The title a file gives a panel opened on it: its name without a {@code .md} extension. */
    static String titleOf(Path file) {
        String name = file.getFileName().toString();
        return (name.toLowerCase(Locale.ROOT).endsWith(".md") ? name.substring(0, name.length() - 3) : name).strip();
    }

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

    /**
     * Replaces {@code file}'s text, written whole or not at all, off the caller's thread; completes with null once
     * written, or with the reason it was not. A panel open on the file takes the new text as any change made outside it.
     */
    default CompletableFuture<String> write(Path file, String text) {
        return CompletableFuture.completedFuture("files are not written here");
    }

    /** The panel's file, once it has one; it stays known after the panel closed. */
    default Optional<Path> file(PanelId panel) {
        return Optional.empty();
    }

    /**
     * True when the panel was opened on a file outside the panels' folder, a document from the Files tab or any file opened:
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
     * Binds a new panel to an existing file of the folder and loads its text, off the EDT; completes with what came of
     * it.
     */
    default CompletableFuture<Opened> open(PanelId panel, Path file) {
        return CompletableFuture.completedFuture(Opened.refused("panel text is not kept in files"));
    }

    /** What came of {@link #open}: the panel bound to the file, the file already open in another panel, or a refusal. */
    record Opened(Kind kind, String reason) {

        public enum Kind { BOUND, ALREADY_OPEN, REFUSED }

        public static final Opened BOUND = new Opened(Kind.BOUND, "");

        public static Opened alreadyOpen(String name) {
            return new Opened(Kind.ALREADY_OPEN, name + " is already open");
        }

        /** {@code reason} empty: bound. */
        public static Opened refused(String reason) {
            return reason.isEmpty() ? BOUND : new Opened(Kind.REFUSED, reason);
        }
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

    /**
     * A panel's file, {@code file} by name, was deleted outside comando while the panel had no unsaved edits.
     * {@code remove}, called on the EDT, answers false when edits were typed since: the panel stays, and their save
     * writes the file again.
     */
    record Deleted(PanelId panel, String file, BooleanSupplier remove) {
    }

    /**
     * Where deleted panel files are reported, from the store's thread: a document's file or a panel's own, deleted
     * outside comando while the panel had no unsaved edits; the window then removes the panel.
     */
    default void onDeleted(Consumer<Deleted> listener) {
    }
}
