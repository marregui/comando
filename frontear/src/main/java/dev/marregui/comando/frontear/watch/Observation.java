package dev.marregui.comando.frontear.watch;

/**
 * One thing a node did, as its {@link Watch} saw it: {@code seq} is its place in the watch's sequence, from 1, and
 * {@code atMillis} the wall-clock time the watch took it, for display only.
 */
public sealed interface Observation permits Published, LinkStep, ForceRound {

    /** The event's place in its watch's sequence, from 1, gap-free across the kinds. */
    long seq();

    /** When the watch took the event, by the node's clock, in milliseconds since the epoch. */
    long atMillis();
}
