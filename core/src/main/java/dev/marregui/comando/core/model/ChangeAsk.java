package dev.marregui.comando.core.model;

/** Why a changed file's two versions are asked for, which decides whether and how the comparison window shows them. */
public enum ChangeAsk {
    /** The user opened the file: the window shows it and takes the keyboard. */
    OPEN,
    /** The selection moved to the file: an open window moves to it, a closed one stays closed. */
    FOLLOW,
    /** The window reads the file it shows again: shown only if the window still shows that file. */
    REFRESH
}
