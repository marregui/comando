package dev.marregui.comando.frontear.octopus;

/** Which way a link's filters carry messages. */
public enum Direction {
    /** From the local broker to the peer. */
    OUT,
    /** From the peer to the local broker. */
    IN
}
