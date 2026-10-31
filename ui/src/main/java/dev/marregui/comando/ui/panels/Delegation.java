package dev.marregui.comando.ui.panels;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import dev.marregui.comando.core.transcript.Block;
import dev.marregui.comando.core.transcript.Transcript;
import dev.marregui.comando.ui.kit.format.Json;
import dev.marregui.comando.ui.widgets.transcript.ToolText;

/**
 * A session's delegation, as its card outlines it (record 109): the sub-agents its own turns started, the latest
 * {@link #SHOWN}, each with its state and how many tool calls ran inside it at any depth. Read from the session's
 * transcript, so the loaded window bounds it.
 */
final class Delegation {

    /** The sub-agents an outline shows, the latest. */
    static final int SHOWN = 8;

    /**
     * One sub-agent: its Agent call's id, what the call says it does, its state, whether it runs in the background, and
     * the tool calls inside it.
     */
    record Line(String toolUseId, String description, Block.ToolStatus status, boolean background, int tools) {

        /** The card's line: a mark for the state, the description, and the tool count once there is any. */
        String text() {
            final String mark = switch (status) {
                case RUNNING -> "▸ ";
                case DONE -> "✓ ";
                case ERROR -> "✗ ";
            };
            final String state = switch (status) {
                case RUNNING -> "running";
                case DONE -> "done";
                case ERROR -> "failed";
            };
            return mark + description + " · " + state + (background && status == Block.ToolStatus.RUNNING ? " in background" : "") + (tools > 0 ? " · " + tools + (tools == 1 ? " tool" : " tools") : "");
        }
    }

    private Delegation() {
    }

    /**
     * The sub-agents the session's own turns started in {@code t}, oldest first, the latest {@link #SHOWN}. A call that
     * launched its sub-agent into the background reads running while {@code running} holds the sub-agent's id (the
     * session's {@code local_agent} tasks), as its card does (record 64).
     */
    static List<Line> of(final Transcript t, final Set<String> running) {
        if (t == null) {
            return List.of();
        }
        final Map<String, Block.ToolCall> agents = new LinkedHashMap<>();
        final Map<String, String> parentOf = new HashMap<>();
        final List<Block.ToolCall> calls = new ArrayList<>();
        for (int i = 0; i < t.size(); i++) {
            if (t.get(i) instanceof Block.ToolCall c) {
                calls.add(c);
                parentOf.put(c.toolUseId(), c.parent());
                if (c.isSubagent() && c.parent().isEmpty()) {
                    agents.put(c.toolUseId(), c);
                }
            }
        }
        final Map<String, Integer> tools = new HashMap<>();
        for (final Block.ToolCall c : calls) {
            // up the parents to the session's own sub-agent the call ran in, if any; a cycle cannot be, but is bounded
            String up = c.parent();
            for (int depth = 0; !up.isEmpty() && depth < 64; depth++) {
                if (agents.containsKey(up)) {
                    tools.merge(up, 1, Integer::sum);
                    break;
                }
                up = parentOf.getOrDefault(up, "");
            }
        }
        final List<Line> out = new ArrayList<>();
        for (final Block.ToolCall a : agents.values()) {
            final String description = ToolText.summary(a.name(), Json.parse(a.inputJson()));
            final String launched = a.status() == Block.ToolStatus.DONE ? Block.ToolCall.launchedAgent(a.resultText()) : "";
            final boolean background = !launched.isEmpty();
            final Block.ToolStatus status = background && running.contains(launched) ? Block.ToolStatus.RUNNING : a.status();
            out.add(new Line(a.toolUseId(), description.isEmpty() ? a.toolUseId() : description, status, background,
                    tools.getOrDefault(a.toolUseId(), 0)));
        }
        return out.size() <= SHOWN ? out : List.copyOf(out.subList(out.size() - SHOWN, out.size()));
    }
}
