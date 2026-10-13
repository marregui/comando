package dev.marregui.comando.mcp;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Iterator;
import java.util.Map;

/** One line describing a permission request, for cards, lists and notifications. */
final class Summaries {

    private static final int MAX = 160;

    private Summaries() {
    }

    static String of(String tool, JsonNode input) {
        String s = switch (tool) {
            case "Bash" -> input.path("command").asText("");
            case "Edit", "MultiEdit", "Write", "NotebookEdit", "Read" -> tool + ' ' + input.path("file_path").asText(input.path("notebook_path").asText(""));
            case "WebFetch" -> "Fetch " + input.path("url").asText("");
            case "WebSearch" -> "Search " + input.path("query").asText("");
            case "AskUserQuestion" -> input.path("questions").path(0).path("question").asText("Questions for you");
            case "ExitPlanMode" -> "Plan ready for review";
            default -> tool + firstArgument(input);
        };
        s = s.strip().replace('\n', ' ');
        return s.length() <= MAX ? s : s.substring(0, MAX) + '…';
    }

    private static String firstArgument(JsonNode input) {
        Iterator<Map.Entry<String, JsonNode>> fields = input.properties().iterator();
        if (!fields.hasNext()) {
            return "";
        }
        Map.Entry<String, JsonNode> f = fields.next();
        return " " + f.getKey() + '=' + (f.getValue().isValueNode() ? f.getValue().asText() : f.getValue().toString());
    }
}
