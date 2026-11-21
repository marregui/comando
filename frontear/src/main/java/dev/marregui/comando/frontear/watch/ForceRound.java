package dev.marregui.comando.frontear.watch;

/**
 * One round of link log {@code log} (named by its link's id): {@code records} appends, {@code bytes} of frames
 * written and forced in {@code nanos}.
 */
public record ForceRound(long seq, long atMillis, String log, int records, long bytes, long nanos)
        implements Observation {
}
