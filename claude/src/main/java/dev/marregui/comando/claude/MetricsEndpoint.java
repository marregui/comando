package dev.marregui.comando.claude;

import java.net.URI;
import java.util.Optional;

/** The machine's cdb as the metrics sink reaches it (record 146). */
public interface MetricsEndpoint {

    /**
     * The live server's HTTP base URI ({@code http://127.0.0.1:9000}), starting it when none runs; empty when it cannot
     * be had now, the cause logged. Blocks for the whole of a start, minutes at most, its waits each bounded by the
     * implementation: never on the EDT.
     */
    Optional<URI> ensureUp();

    /** The live server's HTTP base URI, read now without starting anything; empty when none runs. */
    Optional<URI> live();

    /**
     * The owner secret the server asks for, sent as {@code Authorization: Bearer} on every request to it; empty when
     * there is none to send. Read now, as the server may have made a new one since the last call: a file read, never
     * on the EDT.
     */
    Optional<String> secret();
}
