package dev.marregui.comando.ui;

import dev.marregui.comando.core.model.PanelId;
import java.util.concurrent.CompletableFuture;

/** Where editor text lives between runs of comando: the store file of panel content. */
public interface PanelTexts {

    String load(PanelId panel);

    /** The text changed; the store saves it after a quiet period. */
    void changed(PanelId panel, String text);

    void forget(PanelId panel);

    /**
     * Writes the panels now rather than after the quiet period; completes with null once written, or with the
     * reason the write failed.
     */
    default CompletableFuture<String> saveNow() {
        return CompletableFuture.completedFuture(null);
    }
}
