package dev.marregui.comando.ui.shell;

import dev.marregui.comando.ui.kit.action.MenuEntry;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static dev.marregui.comando.ui.kit.action.MenuEntry.SEPARATOR;
import static dev.marregui.comando.ui.kit.action.MenuEntry.menu;

/**
 * The menu bar and the icons of commands, in one place. Five menus: File, Edit and Help where a Mac user looks for
 * them; Panel for the focused panel and what it is bound to (its session first, then its connection, its results and
 * the tools that run in its project); View for the window. Commands in no menu (Go to panel 1–9, the ⌘⌥1–4
 * alternates, the second key for completion) keep their keys and the palette.
 * <p>
 * Icons follow three rules, so each icon means one thing: a submenu of Panel carries the icon of its subject; an item
 * carries an icon when comando shows the same action as a button with that icon; and the Help items carry icons of
 * their own, which nothing else uses. Every other entry has none.
 */
final class Menus {

    /** The models and efforts the Session submenu offers, as the session dialog and the sidebar rows do. */
    static final List<String> MODELS = List.of("fable", "opus", "sonnet", "haiku");
    static final List<String> EFFORTS = List.of("low", "medium", "high", "xhigh", "max");

    /**
     * Items with an icon: the same action is a button with that icon (Run and Stop in the panel header, the tab
     * strip's + and ✕, the plug of a session row and of the header's session and connection); and the Help items.
     */
    static final Map<String, String> ICONS = Map.ofEntries(
            Map.entry("run", "play"),
            Map.entry("run.stop", "stop"),
            Map.entry("panel.new", "add"),
            Map.entry("file.close", "close"),
            Map.entry("session.toggle", "plug"),
            Map.entry("connection.toggle", "plug"),
            Map.entry("help.tutorial", "tutorial"),
            Map.entry("help.keys", "keyboard"),
            Map.entry("help.kit", "kit"),
            Map.entry("help.log", "log"),
            Map.entry("help.dataRoot", "comando-folder"));

    private Menus() {
    }

    /** The icon of command {@code id}, or null. */
    static String icon(String id) {
        return ICONS.get(id);
    }

    /** The menu bar; the embedded server's submenu only when comando has one. */
    static List<MenuEntry.Menu> bar(boolean server) {
        List<Object> connection = new ArrayList<>(List.of("connection.toggle", "connection.schema",
                SEPARATOR, "panel.bindConnection", "connection.dialog"));
        if (server) {
            connection.add(SEPARATOR);
            connection.add(menu("Embedded QuestDB", null, "server.start", "server.stop"));
        }
        return List.of(
                menu("File", null,
                        "panel.new", "panel.openSpec", "panel.reopen",
                        SEPARATOR,
                        "panel.save", "panel.rename", "file.close",
                        SEPARATOR,
                        "view.export"),
                menu("Edit", null,
                        "edit.undo", "edit.redo",
                        SEPARATOR,
                        "edit.cut", "edit.copy", "view.copy", "edit.paste", "edit.selectAll",
                        SEPARATOR,
                        menu("Find", null, "view.find", "view.findNext", "view.findPrevious"),
                        "edit.complete", "panel.preview"),
                menu("Panel", null,
                        "run", "run.claude", "run.sql", "terminal.run", "run.stop",
                        SEPARATOR,
                        menu("Session", "claude",
                                "session.toggle", "session.interrupt", "session.compact",
                                menu("Model", null, MODELS.stream().map(m -> "session.model." + m).toArray()),
                                menu("Effort", null, EFFORTS.stream().map(e -> "session.effort." + e).toArray()),
                                SEPARATOR,
                                "panel.bindSession", "session.dialog", "session.import"),
                        menu("Connection", "database", connection.toArray()),
                        menu("Result", "table",
                                "run.insertResult", "run.keep",
                                SEPARATOR,
                                "run.plot", "run.plotLive", "run.plotStop"),
                        menu("Tools", "tool", "tools.gradle", "terminal.restart", SEPARATOR, "tools.settings", "files.settings")),
                menu("View", null,
                        "view.palette", "focus.editor",
                        SEPARATOR,
                        "view.sidebar", "view.sidePane", "view.files", "view.restoreLayout",
                        SEPARATOR,
                        menu("Lower panel", null,
                                "view.session", "view.approvals", "view.terminal", "view.results",
                                SEPARATOR,
                                "view.nextTab", "view.previousTab", "view.closeLower"),
                        menu("Panels", null, "panel.next", "panel.previous", "view.lastPrompt"),
                        menu("Zoom", null, "view.zoomIn", "view.zoomOut", "view.zoomReset"),
                        SEPARATOR,
                        "view.notices"),
                menu("Help", null,
                        "help.tutorial", "help.keys",
                        SEPARATOR,
                        "help.kit",
                        SEPARATOR,
                        "help.log", "help.dataRoot"));
    }
}
