package io.questdb.desktop.ui.editor;

import io.questdb.desktop.GTk;
import io.questdb.desktop.model.StoreEntry;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class Content extends StoreEntry {
    private static final @NotNull String ATTR_NAME = "content";

    public Content() {
        this("default");
    }

    public Content(final @NotNull String name) {
        super(name);
        setAttr(ATTR_NAME, GTk.BANNER);
    }

    @SuppressWarnings("unused")
    public Content(final @NotNull StoreEntry other) {
        super(other);
    }

    @Override
    public final @NotNull String getUniqueId() {
        return getName();
    }

    public @Nullable String getContent() {
        return getAttr(ATTR_NAME);
    }

    public void setContent(final @NotNull String content) {
        setAttr(ATTR_NAME, content);
    }
}
