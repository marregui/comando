package dev.marregui.comando.core.model;

import java.util.Objects;

/**
 * A changed file's two versions, to be compared side by side: as in {@code HEAD} and as in the working tree.
 *
 * @param root   the repository's top folder
 * @param change the file and how it changed
 * @param before its text in {@code HEAD}, "" when it was not there
 * @param after  its text in the working tree, "" when it is gone
 * @param note   why the texts are not compared (a binary file, too large, unreadable), or ""
 */
public record ChangeTexts(String root, RepoStatus.FileChange change, String before, String after, String note) {

    public ChangeTexts {
        Objects.requireNonNull(root, "root");
        Objects.requireNonNull(change, "change");
        before = before == null ? "" : before;
        after = after == null ? "" : after;
        note = note == null ? "" : note;
    }

    public static ChangeTexts noted(String root, RepoStatus.FileChange change, String note) {
        return new ChangeTexts(root, change, "", "", note);
    }
}
