package dev.marregui.comando.core.model;

import java.util.List;
import java.util.Objects;

/**
 * One search: literal text, ASCII letters in either case.
 *
 * @param text          what to find; not blank
 * @param folders       the folders to look in, empty for none
 * @param conversations whether the stored conversations are searched too
 */
public record SearchQuery(String text, List<FolderRoot> folders, boolean conversations) {

    public SearchQuery {
        Objects.requireNonNull(text, "text");
        if (text.isBlank()) {
            throw new IllegalArgumentException("text is blank");
        }
        folders = List.copyOf(folders);
    }
}
