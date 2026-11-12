package dev.marregui.comando.ui.panels;

import java.util.Optional;
import java.util.function.Function;

import dev.marregui.comando.core.model.FolderRoot;

/**
 * The sidebar's three folder tabs and which configured folder each holds (record 152): Scratchpad the folders with the
 * scratchpad's icon, Brain the other folders of the group {@code Brain}, Kit every other folder. A folder the
 * Scratchpad tab holds is shown as a group of its own, so its row reads SCRATCHPAD as the kit's reads KIT.
 */
enum FilesShelf implements Function<FolderRoot, Optional<FolderRoot>> {
    KIT("Kit", "files-kit", "the kit's folders and their git changes; double-click a file to open it"),
    BRAIN("Brain", "files-brain", "the brain's folders and their git changes; double-click a file to open it"),
    SCRATCHPAD("Scratchpad", "files-scratchpad", "the scratchpad and its git changes; double-click a file to open it");

    /** The group whose folders, but the scratchpad's, the Brain tab holds. */
    static final String BRAIN_GROUP = "Brain";
    /** The icon of a scratchpad's folder ({@link FolderRoot#ICONS}). */
    static final String SCRATCHPAD_ICON = "scratchpad";

    private final String icon;
    private final String tip;
    private final String title;

    FilesShelf(final String title, final String icon, final String tip) {
        this.title = title;
        this.icon = icon;
        this.tip = tip;
    }

    /** {@code f} as this tab shows it, or empty when another tab holds it. */
    @Override
    public Optional<FolderRoot> apply(final FolderRoot f) {
        if (of(f) != this) {
            return Optional.empty();
        }
        return this == SCRATCHPAD
                ? Optional.of(new FolderRoot(f.name(), f.path(), f.index(), f.excludes(), f.icon(), f.deletable(),
                        f.listedIn(), title))
                : Optional.of(f);
    }

    /** The shelf that holds {@code f}. */
    static FilesShelf of(final FolderRoot f) {
        if (f.icon().equals(SCRATCHPAD_ICON)) {
            return SCRATCHPAD;
        }
        return f.group().equalsIgnoreCase(BRAIN_GROUP) ? BRAIN : KIT;
    }

    /** The tab's name. */
    String title() {
        return title;
    }

    String icon() {
        return icon;
    }

    String tip() {
        return tip;
    }
}
