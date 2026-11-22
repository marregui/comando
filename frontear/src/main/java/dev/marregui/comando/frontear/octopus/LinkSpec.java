package dev.marregui.comando.frontear.octopus;

import java.util.Objects;
import java.util.Set;

/**
 * A link to the gateway at {@code host}:{@code port}: the filters whose messages it carries {@code out} to the peer and
 * {@code in} from it, how many hops a message may have made before it stops being forwarded, and whether it connects
 * over TLS ({@link Links#tls}). The sets are copied; {@link Octopus#put} checks the values.
 */
public record LinkSpec(String id, String host, int port, Set<String> out, Set<String> in, int hopLimit, boolean tls) {

    /** Copies the filter sets. */
    public LinkSpec {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(host, "host");
        out = Set.copyOf(out);
        in = Set.copyOf(in);
    }

    /** A link over plain TCP. */
    public LinkSpec(final String id, final String host, final int port, final Set<String> out, final Set<String> in,
            final int hopLimit) {
        this(id, host, port, out, in, hopLimit, false);
    }
}
