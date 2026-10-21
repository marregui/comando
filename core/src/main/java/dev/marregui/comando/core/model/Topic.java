package dev.marregui.comando.core.model;

/**
 * What a panel is about, in one line a small model wrote: what its session works on, or what its text is about. A
 * read that failed has no line and says why.
 *
 * @param line         the line, empty when the read failed
 * @param readAtMillis when the read finished
 * @param problem      why there is no line; empty when the read succeeded
 */
public record Topic(String line, long readAtMillis, String problem) {

    public Topic {
        line = line == null ? "" : line;
        problem = problem == null ? "" : problem;
    }

    public static Topic failed(String problem, long readAtMillis) {
        return new Topic("", readAtMillis, problem);
    }
}
