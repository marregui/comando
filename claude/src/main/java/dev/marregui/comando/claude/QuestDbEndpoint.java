package dev.marregui.comando.claude;

import java.net.URI;
import java.util.Optional;

/** The machine's QuestDB as the metrics sink reaches it (record 146). */
public interface QuestDbEndpoint {

    /**
     * The live server's HTTP base URI ({@code http://127.0.0.1:9000}), starting it when none runs; empty when it cannot
     * be had now, the cause logged. Blocks for the whole of a start, minutes at most, its waits each bounded by the
     * implementation: never on the EDT.
     */
    Optional<URI> ensureUp();

    /** The live server's HTTP base URI, read now without starting anything; empty when none runs. */
    Optional<URI> live();
}
