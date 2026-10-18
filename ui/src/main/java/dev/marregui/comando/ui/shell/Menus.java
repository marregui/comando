package dev.marregui.comando.ui.shell;

import dev.marregui.comando.ui.kit.action.MenuEntry;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static dev.marregui.comando.ui.kit.action.MenuEntry.SEPARATOR;
import static dev.marregui.comando.ui.kit.action.MenuEntry.menu;

/**
 * The menu bar, the icons and tooltips of its entries, and where every other command belongs. The bar holds only what
 * has no key and no control in the window, and what macOS expects in a menu: Files, Edit (the clipboard and undo) and
 * Tools, which ends in Help. Every other command keeps its key, its control and the palette, and is listed by its
 * area ("Session", "Results") in the palette and the keyboard shortcuts.
 * <p>
 * Every item and submenu has an icon and a tooltip that says what it does, where its result goes and what else does
 * the same; macOS shows both in its menu bar.
 */
final class Menus {

    /** The models and efforts a session row's chips offer, as the palette does. */
    static final List<String> MODELS = List.of("fable", "opus", "sonnet", "haiku");
    static final List<String> EFFORTS = List.of("low", "medium", "high", "xhigh", "max");

    /** The icon of each command that has one: every item of the bar, and commands shown as the same button. */
    static final Map<String, String> ICONS = Map.ofEntries(
            // Files
            Map.entry("panel.openSpec", "open-file"),
            Map.entry("files.settings", "folders"),
            Map.entry("view.export", "export"),
            // Edit
            Map.entry("edit.undo", "undo"),
            Map.entry("edit.redo", "redo"),
            Map.entry("edit.cut", "cut"),
            Map.entry("edit.copy", "copy"),
            Map.entry("edit.paste", "paste"),
            Map.entry("edit.selectAll", "select-all"),
            // Tools
            Map.entry("view.palette", "palette"),
            Map.entry("terminal.restart", "terminal-restart"),
            Map.entry("run.plotStop", "chart-stop"),
            Map.entry("server.start", "play"),
            Map.entry("server.stop", "stop"),
            Map.entry("tools.settings", "settings"),
            Map.entry("help.tutorial", "tutorial"),
            Map.entry("help.keys", "keyboard"),
            Map.entry("help.markdown", "markdown"),
            Map.entry("help.kit", "kit"),
            Map.entry("help.log", "log"),
            Map.entry("help.dataRoot", "comando-folder"),
            // in no menu: the icon of the button that does the same, shown in the palette
            Map.entry("run", "play"),
            Map.entry("run.stop", "stop"),
            Map.entry("panel.new", "add"),
            Map.entry("file.close", "close"),
            Map.entry("session.toggle", "plug"),
            Map.entry("connection.toggle", "plug"));

    /** What each item of the bar does, as its tooltip. */
    static final Map<String, String> TIPS = Map.ofEntries(
            Map.entry("panel.openSpec", "Opens a file chosen anywhere in a panel of its own, to read and edit; the panel that has it already comes"
                    + " forward. Markdown opens rendered. A binary file, or one too large for a panel, shows in the side pane. The Files tab"
                    + " opens its files with a double click."),
            Map.entry("files.settings", "Opens folders.json, the Files tab's list of folders, in a panel. Add, remove or reorder folders there;"
                    + " the tab follows the file as it is saved."),
            Map.entry("view.export", "Saves the view in front to a file you choose: a table as CSV, a plot as PNG, a diff as a .diff file,"
                    + " Markdown as Markdown."),
            Map.entry("edit.undo", "Undoes the last change in the text that has the keyboard: an editor, a field or a note."),
            Map.entry("edit.redo", "Does again what Undo undid in the text that has the keyboard."),
            Map.entry("edit.cut", "Moves the selected text to the clipboard."),
            Map.entry("edit.copy", "Copies the selection to the clipboard: text, table cells, transcript blocks, or a session's details in the"
                    + " Sessions tab."),
            Map.entry("edit.paste", "Puts the clipboard's text where the caret is."),
            Map.entry("edit.selectAll", "Selects all of the text, table or transcript that has the keyboard."),
            Map.entry("view.palette", "Lists every command that can run now, with its key and where it belongs. Type a few letters to narrow"
                    + " the list; Enter runs the one selected. The way to reach a command that is in no menu."),
            Map.entry("terminal.restart", "Ends the focused panel's shell and starts a new one in the working directory of the panel's"
                    + " session; what ran in it stops. The Terminal tab shows it."),
            Map.entry("run.plotStop", "Stops every plot that refreshes itself (Plot, refreshing…: ⌘⇧J). The plots stay as they are."),
            Map.entry("server.start", "Starts a QuestDB server in the questdb folder of comando's data root, on QuestDB's default ports"
                    + " (PostgreSQL wire 8812, console 9000). It listens on every interface with QuestDB's public default login."),
            Map.entry("server.stop", "Stops the embedded QuestDB server; its data stays in the questdb folder. It also stops when comando quits."),
            Map.entry("tools.settings", "Opens tools.json in a panel: the Gradle wrapper that Run Gradle tasks (⌘⌥G) runs."),
            Map.entry("help.tutorial", "comando in ten minutes, opened as a document: from an empty window to a database, a Claude session,"
                    + " approvals and plots."),
            Map.entry("help.keys", "The table of every key, in the side pane: each command's, then those of approval cards, the find bar,"
                    + " the transcript, the sessions list, the Files tab and the dialogs."),
            Map.entry("help.markdown", "What a panel's Markdown may hold and what its preview shows, in the side pane beside the work: text,"
                    + " lists, tables, links, pictures, code and Mermaid diagrams, and how a panel tells a prompt from SQL."),
            Map.entry("help.kit", "The Claude Code kit comando ships: what it holds (memory, knowledge base, scratchpad, /reflect), what is"
                    + " installed here, and an install when anything is missing or older."),
            Map.entry("help.log", "Opens comando's log in a panel, read-only, following its end: what started and stopped, errors, and the"
                    + " times the window stopped responding."),
            Map.entry("help.dataRoot", "Shows comando's data root in the Finder: panels, sessions, connections, settings, logs, caches and"
                    + " the embedded QuestDB's data."));

    /** The tooltips of the submenus, by title. */
    static final Map<String, String> MENU_TIPS = Map.of(
            "Embedded QuestDB", "A QuestDB server comando runs itself, with its data in comando's data root; the connection local QuestDB"
                    + " reaches it.",
            "Help", "The tutorial, the keyboard and Markdown sheets, the Claude Code kit, the log and the data root.");

    /** Where each command in no menu belongs, named in the palette and the keyboard shortcuts. */
    static final Map<String, String> AREAS = areas();

    private Menus() {
    }

    /** The icon of command {@code id}, or null. */
    static String icon(String id) {
        return ICONS.get(id);
    }

    /** The tooltip of command {@code id}, or null. */
    static String tip(String id) {
        return TIPS.get(id);
    }

    /** The menu bar; the embedded server's submenu only when comando has one. */
    static List<MenuEntry.Menu> bar(boolean server) {
        List<Object> tools = new ArrayList<>(List.of("view.palette", SEPARATOR, "terminal.restart", "run.plotStop"));
        if (server) {
            tools.add(menu("Embedded QuestDB", "database", "server.start", "server.stop"));
        }
        tools.addAll(List.of(SEPARATOR, "tools.settings", SEPARATOR,
                menu("Help", "question",
                        "help.tutorial", "help.keys", "help.markdown",
                        SEPARATOR,
                        "help.kit",
                        SEPARATOR,
                        "help.log", "help.dataRoot")));
        return List.of(
                menu("Files", null, "panel.openSpec", "files.settings", SEPARATOR, "view.export"),
                menu("Edit", null, "edit.undo", "edit.redo", SEPARATOR, "edit.cut", "edit.copy", "edit.paste", "edit.selectAll"),
                menu("Tools", null, tools.toArray()));
    }

    private static Map<String, String> areas() {
        Map<String, String> a = new HashMap<>();
        put(a, "Panels", "panel.new", "panel.reopen", "panel.save", "panel.rename", "file.close", "panel.next", "panel.previous",
                "panel.preview", "focus.editor");
        for (int i = 1; i <= 9; i++) {
            a.put("panel." + i, "Panels");
        }
        put(a, "Run", "run", "run.claude", "run.sql", "terminal.run", "run.stop", "tools.gradle");
        put(a, "Session", "session.toggle", "session.interrupt", "session.compact", "panel.bindSession", "session.dialog",
                "session.import");
        MODELS.forEach(m -> a.put("session.model." + m, "Session ▸ Model"));
        EFFORTS.forEach(e -> a.put("session.effort." + e, "Session ▸ Effort"));
        put(a, "Connection", "connection.toggle", "connection.schema", "panel.bindConnection", "connection.dialog");
        put(a, "Results", "run.insertResult", "run.keep", "run.plot", "run.plotLive");
        put(a, "Edit", "view.copy", "view.find", "view.findNext", "view.findPrevious", "edit.complete", "edit.completeAlt");
        put(a, "Window", "view.sidebar", "view.sidePane", "view.files", "view.restoreLayout", "view.session", "view.approvals",
                "view.terminal", "view.results", "view.nextTab", "view.previousTab", "view.closeLower", "view.zoomIn", "view.zoomOut",
                "view.zoomReset", "view.notices", "view.session.alt", "view.approvals.alt", "view.terminal.alt", "view.results.alt");
        put(a, "comando", "app.about", "app.quit");
        return Map.copyOf(a);
    }

    private static void put(Map<String, String> areas, String area, String... ids) {
        for (String id : ids) {
            areas.put(id, area);
        }
    }
}
