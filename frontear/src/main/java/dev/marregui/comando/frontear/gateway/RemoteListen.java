package dev.marregui.comando.frontear.gateway;

import java.net.InetSocketAddress;
import java.security.cert.Certificate;
import java.time.Duration;
import java.util.Objects;
import java.util.function.Function;
import javax.net.ssl.SSLContext;

/**
 * The network listener of a gateway node (ADR 0189 §2.1): TLS 1.3 on {@code bind}, one address, never the wildcard,
 * each client authenticated by its certificate through {@code ssl}, and named by {@code machineOf}, the machine its
 * certificate's key is pinned to, or null for a key no machine is paired with. {@code machine} is this machine's id;
 * {@code maxConnections} the remote connections held at once, one past it closed unanswered; {@code handshake} how
 * long a client has to finish its TLS handshake.
 */
public record RemoteListen(InetSocketAddress bind, SSLContext ssl, Function<Certificate, String> machineOf,
        String machine, int maxConnections, Duration handshake) {

    /**
     * Refuses a wildcard or unresolved {@code bind}, an empty {@code machine}, and a bound that is not positive; a
     * handshake bound must fit a socket timeout's {@code int} milliseconds.
     */
    public RemoteListen {
        Objects.requireNonNull(bind, "bind");
        Objects.requireNonNull(ssl, "ssl");
        Objects.requireNonNull(machineOf, "machineOf");
        Objects.requireNonNull(machine, "machine");
        Objects.requireNonNull(handshake, "handshake");
        if (bind.isUnresolved() || bind.getAddress().isAnyLocalAddress()) {
            throw new IllegalArgumentException("remote listener needs one address [bind=" + bind + ']');
        }
        if (machine.isEmpty()) {
            throw new IllegalArgumentException("machine id is empty");
        }
        if (maxConnections < 1) {
            throw new IllegalArgumentException("max connections not positive [maxConnections=" + maxConnections
                    + ']');
        }
        if (handshake.compareTo(Duration.ofMillis(1)) < 0 || handshake.toMillis() > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("handshake bound out of range [handshake=" + handshake + ']');
        }
    }

    /** The handshake bound as a socket timeout. */
    int handshakeMillis() {
        return (int) handshake.toMillis();
    }
}
