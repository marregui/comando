package dev.marregui.comando.core.state;

import java.util.Objects;

/**
 * Work a session started that runs on after the turn that started it may have ended: a background agent, a shell
 * command, a workflow. Claude Code reports its start and its end; until the end the session is waiting on it.
 *
 * @param id            Claude Code's task id
 * @param kind          Claude Code's task type ({@code local_agent}, {@code local_bash}, ...), empty when not reported
 * @param description   what the task does, as Claude Code describes it
 * @param startedMillis when comando heard of its start
 */
public record BackgroundTask(String id, String kind, String description, long startedMillis) {

    public BackgroundTask {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(description, "description");
    }
}
