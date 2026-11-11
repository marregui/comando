package dev.marregui.comando.core.logbook;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * One open task of the scratchpad's {@code INDEX.md}, as the Logbook tab shows it: a {@code #### N. title} heading
 * under {@code ## Open} and the {@code **Key:**} lines below it (the scratchpad README §2).
 *
 * @param number    the task's number, stable for its life
 * @param title     the heading's text after the number
 * @param topic     the topic folder of the {@code ###} heading above it, such as {@code harness/}; empty when none
 * @param ask       the {@code **Ask:**} line; empty when absent
 * @param state     the {@code **State:**} line; empty when absent
 * @param next      the {@code **Next:**} line; empty when absent
 * @param documents the paths in backticks on the {@code **Documents:**} line, as written, relative to the day folder
 * @param needsYou  the decisions the task holds for the user: each sub-item of a {@code **Needs you:**} line, or its
 *                  text when it has no sub-items
 * @param markdown  the task's whole section, heading included, as the index holds it
 * @param started   the start date the heading's attribution gives, {@code YYYY-MM-DD}; empty when it gives none
 * @param by        who asked for the task, from the heading's attribution; empty when it names no one
 * @param from      the heading's attribution as written, without its parentheses; empty when it has none
 * @param tickets   the Linear ticket ids the task names on its {@code **Ticket:**} line and at the start of its
 *                  heading, each once, in that order
 */
public record LogbookTask(
        int number,
        String title,
        String topic,
        String ask,
        String state,
        String next,
        List<String> documents,
        List<String> needsYou,
        String markdown,
        String started,
        String by,
        String from,
        List<String> tickets
) {

    public LogbookTask {
        Objects.requireNonNull(title, "title");
        topic = topic == null ? "" : topic;
        ask = ask == null ? "" : ask;
        state = state == null ? "" : state;
        next = next == null ? "" : next;
        documents = documents == null ? List.of() : List.copyOf(documents);
        needsYou = needsYou == null ? List.of() : List.copyOf(needsYou);
        markdown = markdown == null ? "" : markdown;
        started = started == null ? "" : started;
        by = by == null ? "" : by;
        from = from == null ? "" : from;
        tickets = tickets == null ? List.of() : List.copyOf(tickets);
    }

    /** The title without its trailing attribution, as a row shows it; the whole title when it has none. */
    public String name() {
        final int open = from.isEmpty() ? -1 : IndexParser.attributionStart(title);
        return open < 0 ? title : title.substring(0, open).strip();
    }

    /**
     * Whether the task waits on the user: it holds a decision for him, or its State begins {@code waiting on Miguel}
     * (README §2: an open task's State begins {@code open}, {@code waiting on <whom>} or {@code in progress}).
     */
    public boolean isWaitingOnUser() {
        return !needsYou.isEmpty() || state.toLowerCase(Locale.ROOT).startsWith("waiting on miguel");
    }
}
