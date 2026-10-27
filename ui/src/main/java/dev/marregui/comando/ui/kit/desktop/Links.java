package dev.marregui.comando.ui.kit.desktop;

import dev.marregui.comando.ui.kit.async.Edt;
import dev.marregui.comando.ui.kit.async.UiErrands;
import dev.marregui.comando.ui.kit.text.LocalLinks;
import dev.marregui.comando.ui.kit.text.NoteLinks;

import java.nio.file.Path;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Follows a link double-clicked anywhere in comando: a web address in the browser; a file, by its path or a
 * {@code [[name]]} note link, in a panel, through the opener the shell installs. One way for every surface: rendered
 * Markdown, the transcript, a tool's result, a tab's card, an editor's text.
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
    private static volatile Function<String, Path> sessionFolders = session -> null;

    private Links() {
    }

    /**
     * How files open, how a link that names no file is told, and the folder a session's links are read from (its
     * working directory): the shell's, once.
     */
    public static void install(Opener opener, Consumer<String> notice, Function<String, Path> sessionFolder) {
        files = opener;
        notices = notice;
        sessionFolders = sessionFolder;
    }

    /** The folder the links in {@code session}'s text are read from; null when the session is not known. */
    public static Path folderOf(String session) {
        return session == null || session.isEmpty() ? null : sessionFolders.apply(session);
    }

    /** Where web addresses go instead of the browser; for tests. */
    public static void browseWith(Consumer<String> browse) {
        browser = browse;
    }

    /** Follows {@code href}, a relative one read from {@code folder} (null: none is); false when it is no link comando follows. */
    public static boolean open(String href, Path folder) {
        return open(href, folder, files);
    }

    /** As {@link #open(String, Path)}, a file opened by {@code opener}. Called on the EDT; the opener runs on it. */
    public static boolean open(String href, Path folder, Opener opener) {
        if (href == null || href.isBlank()) {
            return false;
        }
        if (Browser.isWebLink(href)) {
            browser.accept(href);
            return true;
        }
        if (NoteLinks.is(href)) {
            String name = NoteLinks.name(href);
            String heading = NoteLinks.heading(href);
            UiErrands.start("note-link", () -> {
                Optional<Path> file = NoteLinks.resolve(folder, name);
                String missing = "No file for [[" + name + "]]" + (folder == null ? "" : " under " + NoteLinks.rootOf(folder));
                Edt.later(() -> file.ifPresentOrElse(f -> opener.open(f, heading), () -> notices.accept(missing)));
            });
            return true;
        }
        Optional<Path> file = LocalLinks.file(folder, href);
        if (file.isEmpty()) {
            return false;
        }
        opener.open(file.get(), heading(href));
        return true;
    }

    /** The heading a link names after the file's name, as written; null when it names none. */
    public static String heading(String href) {
        int hash = href.indexOf('#');
        return hash < 0 || hash == href.length() - 1 ? null : href.substring(hash + 1);
    }
}
