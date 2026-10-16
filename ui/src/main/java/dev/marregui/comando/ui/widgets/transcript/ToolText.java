package dev.marregui.comando.ui.widgets.transcript;

import com.fasterxml.jackson.databind.JsonNode;
import dev.marregui.comando.core.view.Clip;

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
        if (node.isContainerNode()) {
            node.forEach(child -> values(child, out));
        } else if (!node.isNull() && !node.isMissingNode()) {
            out.append(node.asText()).append('\n');
        }
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
            case "Bash" -> in.path("command").asText("");
            case "Read", "Edit", "MultiEdit", "Write" -> in.path("file_path").asText("");
            case "NotebookEdit" -> in.path("notebook_path").asText("");
            case "Grep" -> in.path("pattern").asText("") + (in.hasNonNull("path") ? "  in " + in.path("path").asText() : "");
            case "Glob" -> in.path("pattern").asText("");
            case "WebFetch" -> in.path("url").asText("");
            case "WebSearch" -> in.path("query").asText("");
            case "Agent", "Task" -> in.path("description").asText(in.path("subagent_type").asText(""));
            case "Skill" -> in.path("skill").asText(in.path("command").asText(""));
            case "TodoWrite" -> in.path("todos").size() + " todos";
            case "AskUserQuestion" -> in.path("questions").path(0).path("question").asText("");
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
        return f.getKey() + "=" + (f.getValue().isValueNode() ? f.getValue().asText() : f.getValue().toString());
    }
}
