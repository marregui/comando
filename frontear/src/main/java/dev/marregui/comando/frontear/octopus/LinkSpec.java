package dev.marregui.comando.frontear.octopus;

import java.util.Objects;
import java.util.Set;

/**
 * A link to the broker at {@code host}:{@code port}: the filters whose messages it carries {@code out} to the peer and
 * {@code in} from it, and how many hops a message may have made before it stops being forwarded. The sets are copied;
 * {@link Mesh#put} checks the values.
 */
public record LinkSpec(String id, String host, int port, Set<String> out, Set<String> in, int hopLimit) {

    /** Copies the filter sets. */
    public LinkSpec {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(host, "host");
        out = Set.copyOf(out);
        in = Set.copyOf(in);
    }
}
