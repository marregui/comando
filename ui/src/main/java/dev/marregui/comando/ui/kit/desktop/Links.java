package dev.marregui.comando.ui.kit.desktop;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;

import org.jetbrains.annotations.TestOnly;

import dev.marregui.comando.ui.kit.async.Edt;
import dev.marregui.comando.ui.kit.async.UiErrands;
import dev.marregui.comando.ui.kit.text.LocalLinks;
import dev.marregui.comando.ui.kit.text.NoteLinks;

/**
 * Follows a link double-clicked anywhere in comando: a web address, and a local HTML page, in the browser; any other
 * file, by its path or a {@code [[name]]} note link, in a panel, through the opener the shell installs. One way for
 * every surface: rendered Markdown, the transcript, a tool's result, a tab's card, an editor's text. A page opened from
 * the sidebar is not a link: it opens in a panel, to edit.
 */
public final class Links {

    /** Opens a file of this machine in a panel, at the heading named after {@code #} when not null. */
    @FunctionalInterface
    public interface Opener {
        void open(Path file, String heading);
    }

    private static volatile Opener files = (f, heading) -> {
    };
    private static volatile Consumer<String> notices = text -> {
    };
    private static volatile Consumer<String> browser = Browser::open;
    // written only by tests, read by whichever thread follows a link
    private static volatile Consumer<Path> pages = Browser::openFile;
    private static volatile Function<String, Path> sessionFolders = session -> null;

    private Links() {
    }

    /**
     * How files open, how a link that names no file is told, and the folder a session's links are read from (its
     * working directory): the shell's, once.
     */
    public static void install(final Opener opener, final Consumer<String> notice, final Function<String, Path> sessionFolder) {
        files = opener;
        notices = notice;
        sessionFolders = sessionFolder;
    }

    /** The folder the links in {@code session}'s text are read from; null when the session is not known. */
    public static Path folderOf(final String session) {
        return session == null || session.isEmpty() ? null : sessionFolders.apply(session);
    }

    /** Where web addresses go instead of the browser; for tests. */
    @TestOnly
    public static void browseWith(final Consumer<String> browse) {
        browser = browse;
    }

    /** Where local pages go instead of the browser; for tests. */
    @TestOnly
    public static void browsePagesWith(final Consumer<Path> browse) {
        pages = browse;
    }

    /** Whether {@code file} is a page a browser shows: its name ends in {@code .html} or {@code .htm}, in any case. */
    public static boolean isPage(final Path file) {
        final Path name = file.getFileName();
        final String n = name == null ? "" : name.toString().toLowerCase(Locale.ROOT);
        return n.endsWith(".html") || n.endsWith(".htm");
    }

    /** Opens the local page {@code file} in the browser; returns at once. */
    public static void browsePage(final Path file) {
        pages.accept(file);
    }

    /**
     * Follows {@code href}, a relative one read from {@code folder} (may be null); false when comando does not follow
     * it.
     */
    public static boolean open(final String href, final Path folder) {
        return open(href, folder, files);
    }

    /** As {@link #open(String, Path)}, a file opened by {@code opener}. Called on the EDT; the opener runs on it. */
    public static boolean open(final String href, final Path folder, final Opener opener) {
        if (href == null || href.isBlank()) {
            return false;
        }
        if (Browser.isWebLink(href)) {
            browser.accept(href);
            return true;
        }
        if (NoteLinks.is(href)) {
            final String name = NoteLinks.name(href);
            final String heading = NoteLinks.heading(href);
            UiErrands.start("note-link", () -> {
                final Optional<Path> file = NoteLinks.resolve(folder, name);
                final String missing = "No file for [[" + name + "]]" + (folder == null ? "" : " under " + NoteLinks.rootOf(folder));
                Edt.later(() -> file.ifPresentOrElse(f -> follow(f, heading, opener), () -> notices.accept(missing)));
            });
            return true;
        }
        final Optional<Path> file = LocalLinks.file(folder, href);
        if (file.isEmpty()) {
            return false;
        }
        follow(file.get(), heading(href), opener);
        return true;
    }

    /** A page to the browser, any other file to {@code opener} at {@code heading}. */
    private static void follow(final Path file, final String heading, final Opener opener) {
        if (isPage(file)) {
            pages.accept(file);
        } else {
            opener.open(file, heading);
        }
    }

    /** The heading a link names after the file's name, as written; null when it names none. */
    public static String heading(final String href) {
        final int hash = href.indexOf('#');
        return hash < 0 || hash == href.length() - 1 ? null : href.substring(hash + 1);
    }
}
