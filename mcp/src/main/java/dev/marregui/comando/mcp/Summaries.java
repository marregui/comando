package dev.marregui.comando.mcp;

import java.util.Iterator;
import java.util.Map;

import tools.jackson.databind.JsonNode;

/** One line describing a permission request, for cards, lists and notifications. */
final class Summaries {

    private static final int MAX = 160;

    private Summaries() {
    }

    static String of(final String tool, final JsonNode input) {
        String s = switch (tool) {
            case "Bash" -> input.path("command").asString("");
            case "Edit", "MultiEdit", "Write", "NotebookEdit", "Read" -> tool + ' ' + input.path("file_path").asString(input.path("notebook_path").asString(""));
            case "WebFetch" -> "Fetch " + input.path("url").asString("");
            case "WebSearch" -> "Search " + input.path("query").asString("");
            case "AskUserQuestion" -> input.path("questions").path(0).path("question").asString("Questions for you");
            case "ExitPlanMode" -> "Plan ready for review";
            default -> tool + firstArgument(input);
        };
        s = s.strip().replace('\n', ' ');
        return s.length() <= MAX ? s : s.substring(0, MAX) + '…';
    }

    private static String firstArgument(final JsonNode input) {
        final Iterator<Map.Entry<String, JsonNode>> fields = input.properties().iterator();
        if (!fields.hasNext()) {
            return "";
        }
        final Map.Entry<String, JsonNode> f = fields.next();
        return " " + f.getKey() + '=' + (f.getValue().isValueNode() && !f.getValue().isNull() ? f.getValue().asString("") : f.getValue().toString());
    }
}
