package dev.marregui.comando.ui.widgets.transcript;

import dev.marregui.comando.core.model.HomeDir;
import dev.marregui.comando.core.view.Clip;
import dev.marregui.comando.core.view.ViewSpec;
import tools.jackson.databind.JsonNode;

import java.nio.file.Files;
import java.nio.file.Path;

/** Reads a tool call's JSON input for display: its one-line summary and its values. */
public final class ToolText {

    private static final int SUMMARY_CHARS = 140;

    private ToolText() {
    }

    /** Every string, number and boolean in {@code node}, decoded and one per line: what an open card shows. */
    public static String values(JsonNode node) {
        StringBuilder out = new StringBuilder();
        values(node, out);
        return out.toString();
    }

    private static void values(JsonNode node, StringBuilder out) {
        if (node.isContainer()) {
            node.forEach(child -> values(child, out));
        } else if (!node.isNull() && !node.isMissingNode()) {
            out.append(node.asString("")).append('\n');
        }
    }

    /**
     * The link a call's summary opens (record 108): the file a file tool works on, the address a fetch reads, a skill's
     * {@code SKILL.md} in the user's skills folder when it is there; empty when the call names none.
     */
    public static String target(String tool, JsonNode in) {
        String path = switch (tool) {
            case "Read", "Edit", "MultiEdit", "Write" -> in.path("file_path").asString("");
            case "NotebookEdit" -> in.path("notebook_path").asString("");
            default -> "";
        };
        if (!path.isBlank()) {
            return ViewSpec.TreeNode.FILE_SCHEME + path.strip();
        }
        if (tool.equals("WebFetch")) {
            String url = in.path("url").asString("").strip();
            return url.regionMatches(true, 0, "http://", 0, 7) || url.regionMatches(true, 0, "https://", 0, 8) ? url : "";
        }
        if (tool.equals("Skill")) {
            Path file = skillFile(in.path("skill").asString(in.path("command").asString("")).strip(), skillsRoot());
            return file == null ? "" : ViewSpec.TreeNode.FILE_SCHEME + file;
        }
        return "";
    }

    /** The user's skills folder: the kit root's {@code skills}, the folder the sessions run on. */
    static Path skillsRoot() {
        return HomeDir.claudeDir().resolve("skills");
    }

    /**
     * {@code name}'s {@code SKILL.md} under {@code root}, null when it is not there or the name is a plugin's
     * ({@code a:b}).
     */
    static Path skillFile(String name, Path root) {
        String bare = name.startsWith("/") ? name.substring(1) : name;
        if (bare.isEmpty() || bare.startsWith(".") || !bare.matches("[\\w.\\-]+")) {
            return null;
        }
        Path file = root.resolve(bare).resolve("SKILL.md");
        return Files.isRegularFile(file) ? file : null;
    }

    public static String displayName(String tool) {
        if (tool.startsWith("mcp__")) {
            String rest = tool.substring(5);
            int sep = rest.indexOf("__");
            return sep < 0 ? rest : rest.substring(0, sep) + " · " + rest.substring(sep + 2);
        }
        return tool;
    }

    public static String summary(String tool, JsonNode in) {
        String s = switch (tool) {
            case "Bash" -> in.path("command").asString("");
            case "Read", "Edit", "MultiEdit", "Write" -> in.path("file_path").asString("");
            case "NotebookEdit" -> in.path("notebook_path").asString("");
            case "Grep" -> in.path("pattern").asString("") + (in.hasNonNull("path") ? "  in " + in.path("path").asString("") : "");
            case "Glob" -> in.path("pattern").asString("");
            case "WebFetch" -> in.path("url").asString("");
            case "WebSearch" -> in.path("query").asString("");
            case "Agent", "Task" -> in.path("description").asString(in.path("subagent_type").asString(""));
            case "Skill" -> in.path("skill").asString(in.path("command").asString(""));
            case "TodoWrite" -> in.path("todos").size() + " todos";
            case "AskUserQuestion" -> in.path("questions").path(0).path("question").asString("");
            case "ExitPlanMode" -> "plan ready";
            default -> firstValue(in);
        };
        s = s.strip();
        int eol = s.indexOf('\n');
        if (eol >= 0) {
            s = s.substring(0, eol) + " …";
        }
        return Clip.keepStart(s, SUMMARY_CHARS);
    }

    private static String firstValue(JsonNode in) {
        var it = in.properties().iterator();
        if (!it.hasNext()) {
            return "";
        }
        var f = it.next();
        return f.getKey() + "=" + (f.getValue().isValueNode() && !f.getValue().isNull() ? f.getValue().asString("") : f.getValue().toString());
    }
}
