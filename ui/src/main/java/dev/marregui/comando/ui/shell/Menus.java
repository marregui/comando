package dev.marregui.comando.ui.shell;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import dev.marregui.comando.core.model.SessionSpec;
import dev.marregui.comando.ui.kit.action.MenuEntry;
import dev.marregui.comando.ui.kit.format.KeyText;
import dev.marregui.comando.ui.kit.icon.Icons;
import dev.marregui.comando.ui.kit.theme.GroundIcon;
import dev.marregui.comando.ui.kit.theme.NamedTheme;

import static dev.marregui.comando.ui.kit.action.MenuEntry.SEPARATOR;
import static dev.marregui.comando.ui.kit.action.MenuEntry.menu;

/**
 * The menu bar, the icons and tooltips of its entries, and where every other command belongs. The bar holds only what
 * has no key and no control in the window, and what macOS expects in a menu: after the application menu macOS draws,
 * Edit (the clipboard and undo) and Help, the palette and the help, flat. The theme, the shell, the refreshing plots,
 * the embedded server and the Edge are buttons at the tab row's right end instead ({@link #corner}). Every other
 * command keeps its key, its control and the palette, and is listed by its area ("Session", "Results") in the palette
 * and the keyboard shortcuts.
 * <p>
 * Every item has an icon and a one-line tooltip: what it does, and one fact its label does not say; macOS shows both
 * in its menu bar.
 */
final class Menus {

    /** The icon of each command that has one: every item of the bar, and commands shown as the same button. */
    static final Map<String, String> ICONS = Map.ofEntries(
            // Edit
            Map.entry("edit.undo", "undo"),
            Map.entry("edit.redo", "redo"),
            Map.entry("edit.cut", "cut"),
            Map.entry("edit.copy", "copy"),
            Map.entry("edit.paste", "paste"),
            Map.entry("edit.selectAll", "select-all"),
            // Help
            Map.entry("view.palette", "palette"),
            Map.entry("help.tutorial", "tutorial"),
            Map.entry("help.keys", "keyboard"),
            Map.entry("help.markdown", "markdown"),
            Map.entry("help.log", "log"),
            Map.entry("help.edgeLog", "log"),
            Map.entry("help.questDbLog", "log"),
            Map.entry("help.dataRoot", "comando-folder"),
            // the corner's buttons
            Map.entry("terminal.start", "terminal-start"),
            Map.entry("terminal.stop", "terminal-stop"),
            Map.entry("run.plotStop", "chart-stop"),
            Map.entry("server.start", "database-start"),
            Map.entry("server.stop", "database-stop"),
            Map.entry("edge.start", "edge-start"),
            Map.entry("edge.stop", "edge-stop"),
            // in no menu: the icon of the button or tab-menu item that does the same, shown in the palette
            Map.entry("panel.openSpec", "open-file"),
            Map.entry("files.settings", "folders"),
            Map.entry("files.jobs", "sidebar/jobs"),
            Map.entry("kit.upgrade", "kit"),
            Map.entry("kit.backup", "export"),
            Map.entry("kit.backupKit", "export"),
            Map.entry("view.export", "export"),
            Map.entry("run", "play"),
            Map.entry("run.stop", "stop"),
            Map.entry("panel.new", "add"),
            Map.entry("file.close", "close"),
            Map.entry("session.toggle", "plug"),
            Map.entry("view.changes", "diff"),
            Map.entry("connection.toggle", "plug"),
            Map.entry("terminal.restart", "terminal-restart"));

    /** What each item of the bar and each button of the corner does, as its tooltip: one line. */
    static final Map<String, String> TIPS = Map.ofEntries(
            Map.entry("edit.undo", "Undo the last change in the focused text"),
            Map.entry("edit.redo", "Redo what Undo undid in the focused text"),
            Map.entry("edit.cut", "Move the selected text to the clipboard"),
            Map.entry("edit.copy", "Copy the selection: text, cells, transcript blocks or a session's details"),
            Map.entry("edit.paste", "Paste the clipboard's text at the caret"),
            Map.entry("edit.selectAll", "Select all of the focused text, table or transcript"),
            Map.entry("view.palette", "Run any command by typing part of its name"),
            Map.entry("terminal.start", "Start the focused panel's shell, in its session's folder"),
            Map.entry("terminal.stop", "Stop the focused panel's shell; what runs in it stops"),
            Map.entry("run.plotStop", "Stop every refreshing plot; the plots stay as they are"),
            Map.entry("server.start", "Start QuestDB on this machine: PostgreSQL wire 8812, console 9000"),
            Map.entry("server.stop", "Stop the embedded QuestDB; its data stays"),
            Map.entry("edge.start", "Start the comando Edge, one per machine: MQTT 18883, WebSocket 18800, UI 18080"),
            Map.entry("edge.stop", "Stop the comando Edge this comando started; its data stays"),
            Map.entry("help.tutorial", "comando in ten minutes, as a document"),
            Map.entry("help.keys", "Every key, in the side pane"),
            Map.entry("help.markdown", "What a panel's Markdown may hold and how its preview shows it"),
            Map.entry("help.log", "comando's log, read-only, following its end"),
            Map.entry("help.edgeLog", "The comando Edge's hivemq.log in this instance's data root"),
            Map.entry("help.questDbLog", "QuestDB's log of the day, in this instance's data root"),
            Map.entry("help.dataRoot", "Show comando's data root: panels, sessions, settings, logs"));

    /**
     * Where each command in no menu belongs, named in the palette and the keyboard shortcuts; the themes are those
     * offered when it is asked, so it is asked once the theme files are read (record 126).
     */
    static Map<String, String> areas() {
        // the themes' swatches, painted, named as their commands are
        for (final NamedTheme t : NamedTheme.values()) {
            Icons.put(t.commandId(), new GroundIcon(t));
        }
        return areasOf();
    }

    private Menus() {
    }

    /** The icon of command {@code id}, or null; a theme's command is named as its swatch is. */
    static String icon(final String id) {
        return id.startsWith("theme.") ? id : ICONS.get(id);
    }

    /** The tooltip of command {@code id}, or null. */
    static String tip(final String id) {
        for (final NamedTheme t : NamedTheme.values()) {
            if (t.commandId().equals(id)) {
                return t.file.map(f -> t.label + ", from " + f.getFileName() + "; kept across restarts")
                        .orElse(t.ground.label + " with " + t.tint.label.toLowerCase(Locale.ROOT) + "; kept across restarts");
            }
        }
        return KeyText.of(TIPS.get(id));
    }

    /** The menu bar after the application menu macOS draws: Edit, then Help: the palette, then the help. */
    static List<MenuEntry.Menu> bar() {
        return List.of(
                menu("Edit", null, "edit.undo", "edit.redo", SEPARATOR, "edit.cut", "edit.copy", "edit.paste", "edit.selectAll"),
                menu("Help", null, "view.palette", SEPARATOR, "help.tutorial", "help.keys", "help.markdown", "help.log", "help.edgeLog", "help.questDbLog", "help.dataRoot"));
    }

    /**
     * The buttons left of the theme's swatch, each its commands, shown by the first that can run: the shell, the
     * refreshing plots, then the embedded server and the Edge, each only when comando has one.
     */
    static List<List<String>> corner(final boolean server, final boolean edge) {
        final List<List<String>> buttons = new ArrayList<>(List.of(List.of("terminal.start", "terminal.stop"), List.of("run.plotStop")));
        if (server) {
            buttons.add(List.of("server.start", "server.stop"));
        }
        if (edge) {
            buttons.add(List.of("edge.start", "edge.stop"));
        }
        return List.copyOf(buttons);
    }

    /** The Connections tab's menu: the embedded server's start and stop, then the Edge's, each when comando has one. */
    static List<String> toggles(final boolean server, final boolean edge) {
        final List<String> ids = new ArrayList<>();
        if (server) {
            ids.addAll(List.of("server.start", "server.stop"));
        }
        if (edge) {
            ids.addAll(List.of("edge.start", "edge.stop"));
        }
        return List.copyOf(ids);
    }

    private static Map<String, String> areasOf() {
        final Map<String, String> a = new HashMap<>();
        put(a, "Panels", "panel.new", "panel.reopen", "panel.openSpec", "panel.save", "panel.rename", "file.close", "panel.next", "panel.previous",
                "panel.preview", "focus.editor");
        for (int i = 1; i <= 9; i++) {
            a.put("panel." + i, "Panels");
        }
        put(a, "Run", "run", "run.claude", "run.sql", "terminal.run", "run.stop", "tools.gradle", "terminal.start", "terminal.stop", "terminal.restart");
        put(a, "Session", "session.toggle", "session.interrupt", "session.compact", "transcript.clear", "panel.bindSession", "session.dialog",
                "session.import", "session.checkpointThreshold");
        SessionSpec.MODELS.forEach(m -> a.put("session.model." + m, "Session ▸ Model"));
        SessionSpec.EFFORTS.forEach(e -> a.put("session.effort." + e, "Session ▸ Effort"));
        put(a, "Connection", "server.start", "server.stop", "edge.start", "edge.stop", "connection.toggle", "connection.schema", "panel.bindConnection", "connection.dialog");
        put(a, "Results", "run.insertResult", "run.keep", "run.plot", "run.plotLive", "run.plotStop");
        put(a, "Edit", "view.copy", "view.find", "view.findNext", "view.findPrevious", "edit.complete", "edit.completeAlt");
        put(a, "Files", "files.settings", "files.jobs");
        put(a, "Kit", "kit.upgrade", "kit.backup", "kit.backupKit");
        put(a, "Window", "view.export", "view.sidebar", "view.sidePane", "view.files", "view.changes", "view.restoreLayout", "view.session", "view.approvals",
                "view.terminal", "view.results", "view.nextTab", "view.previousTab", "view.closeLower", "view.zoomIn", "view.zoomOut",
                "view.zoomReset", "view.notices", "view.session.alt", "view.approvals.alt", "view.terminal.alt", "view.results.alt");
        put(a, "comando", "app.about", "app.quit");
        for (final NamedTheme t : NamedTheme.values()) {
            a.put(t.commandId(), "Theme");
        }
        return Map.copyOf(a);
    }

    private static void put(final Map<String, String> areas, final String area, final String... ids) {
        for (final String id : ids) {
            areas.put(id, area);
        }
    }
}
