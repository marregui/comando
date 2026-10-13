package dev.marregui.comando.ui;

import dev.marregui.comando.core.model.PanelId;

/** Where editor text lives between runs of comando: the store file of panel content. */
public interface PanelTexts {

    String load(PanelId panel);

    /** The text changed; the store saves it after a quiet period. */
    void changed(PanelId panel, String text);

    void forget(PanelId panel);
}
