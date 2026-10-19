package dev.marregui.comando.ui.shell;

import dev.marregui.comando.core.model.SessionSpec;
import dev.marregui.comando.ui.kit.action.MenuEntry;
import dev.marregui.comando.ui.kit.format.KeyText;

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
 * Every item and submenu has an icon and a one-line tooltip: what it does, and one fact its label does not say;
 * macOS shows both in its menu bar.
 */
final class Menus {

    /** The icon of each command that has one: every item of the bar, and commands shown as the same button. */
    static final Map<String, String> ICONS = Map.ofEntries(
            // Files
            Map.entry("panel.openSpec", "open-file"),
            Map.entry("files.settings", "folders"),
            Map.entry("files.repositories", "repositories"),
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
            Map.entry("view.changes", "diff"),
            Map.entry("connection.toggle", "plug"));

    /** What each item of the bar does, as its tooltip: one line. */
    static final Map<String, String> TIPS = Map.ofEntries(
            Map.entry("panel.openSpec", "Open any file in a panel of its own; Markdown opens rendered"),
            Map.entry("files.settings", "Add, remove, reorder and set the Files tab's folders in ~/.claude"),
            Map.entry("files.repositories", "Add, remove, reorder and set the git repositories the Files tab shows"),
            Map.entry("view.export", "Save the view in front: a table as CSV, a plot as PNG, a diff as .diff"),
            Map.entry("edit.undo", "Undo the last change in the focused text"),
            Map.entry("edit.redo", "Redo what Undo undid in the focused text"),
            Map.entry("edit.cut", "Move the selected text to the clipboard"),
            Map.entry("edit.copy", "Copy the selection: text, cells, transcript blocks or a session's details"),
            Map.entry("edit.paste", "Paste the clipboard's text at the caret"),
            Map.entry("edit.selectAll", "Select all of the focused text, table or transcript"),
            Map.entry("view.palette", "Run any command by typing part of its name"),
            Map.entry("terminal.restart", "Start a new shell in the focused panel; what runs in it stops"),
            Map.entry("run.plotStop", "Stop every refreshing plot; the plots stay as they are"),
            Map.entry("server.start", "Start QuestDB on this machine: PostgreSQL wire 8812, console 9000"),
            Map.entry("server.stop", "Stop the embedded QuestDB; its data stays"),
            Map.entry("help.tutorial", "comando in ten minutes, as a document"),
            Map.entry("help.keys", "Every key, in the side pane"),
            Map.entry("help.markdown", "What a panel's Markdown may hold and how its preview shows it"),
            Map.entry("help.kit", "The Claude Code kit: what it holds, what is installed, install or update"),
            Map.entry("help.log", "comando's log, read-only, following its end"),
            Map.entry("help.dataRoot", "Show comando's data root: panels, sessions, settings, logs"));

    /** The tooltips of the submenus, by title. */
    static final Map<String, String> MENU_TIPS = Map.of(
            "Embedded QuestDB", "A QuestDB server comando runs; the connection local QuestDB reaches it",
            "Help", "The tutorial, keys, Markdown, the kit, the log and the data root");

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
        return KeyText.of(TIPS.get(id));
    }

    /** The menu bar; the embedded server's submenu only when comando has one. */
    static List<MenuEntry.Menu> bar(boolean server) {
        List<Object> tools = new ArrayList<>(List.of("view.palette", SEPARATOR, "terminal.restart", "run.plotStop"));
        if (server) {
            tools.add(menu("Embedded QuestDB", "database", "server.start", "server.stop"));
        }
        tools.addAll(List.of(SEPARATOR,
                menu("Help", "question",
                        "help.tutorial", "help.keys", "help.markdown",
                        SEPARATOR,
                        "help.kit",
                        SEPARATOR,
                        "help.log", "help.dataRoot")));
        return List.of(
                menu("Files", null, "panel.openSpec", "files.settings", "files.repositories", SEPARATOR, "view.export"),
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
        SessionSpec.MODELS.forEach(m -> a.put("session.model." + m, "Session ▸ Model"));
        SessionSpec.EFFORTS.forEach(e -> a.put("session.effort." + e, "Session ▸ Effort"));
        put(a, "Connection", "connection.toggle", "connection.schema", "panel.bindConnection", "connection.dialog");
        put(a, "Results", "run.insertResult", "run.keep", "run.plot", "run.plotLive");
        put(a, "Edit", "view.copy", "view.find", "view.findNext", "view.findPrevious", "edit.complete", "edit.completeAlt");
        put(a, "Window", "view.sidebar", "view.sidePane", "view.files", "view.changes", "view.restoreLayout", "view.session", "view.approvals",
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
