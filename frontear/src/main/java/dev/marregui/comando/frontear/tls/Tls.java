package dev.marregui.comando.frontear.tls;

import java.security.GeneralSecurityException;
import java.time.Duration;
import java.util.Objects;
import javax.net.ssl.KeyManager;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;

/** The TLS of frontear across machines (ADR 0189): TLS 1.3 only, both sides authenticated by a pinned key. */
public final class Tls {

    /**
     * The one protocol: a socket made from {@link #context} enables it alone; the context's trust manager refuses a
     * peer in any other session too, so a side that leaves the JDK's defaults on still ends in TLS 1.3 or nothing.
     */
    public static final String PROTOCOL = "TLSv1.3";

    /**
     * A session timeout past the 7 days RFC 8446 (section 4.6.1) allows a TLS 1.3 ticket: the JDK then issues no
     * ticket as a server and keeps none as a client, so no session is resumed. A resumed session shows no
     * certificate, so it would skip the pin check and let in a peer whose pin was removed since.
     */
    private static final int NO_TICKETS_SECONDS = (int) Duration.ofDays(7).toSeconds() + 1;

    private Tls() {
    }

    /**
     * A context with {@code key} as its key, as a client and as a server, and a trust manager that accepts exactly the
     * peers whose key is in {@code pins} at their handshake. Every connection makes a full handshake: none resumes
     * an earlier session, so a pin removed refuses the peer at its next connection.
     */
    public static SSLContext context(final MachineKey key, final Pins pins) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(pins, "pins");
        try {
            final SSLContext context = SSLContext.getInstance(PROTOCOL);
            context.init(new KeyManager[] {new MachineKeyManager(key)},
                    new TrustManager[] {new PinningTrustManager(pins)}, null);
            context.getServerSessionContext().setSessionTimeout(NO_TICKETS_SECONDS);
            context.getClientSessionContext().setSessionTimeout(NO_TICKETS_SECONDS);
            return context;
        } catch (final GeneralSecurityException e) {
            throw new IllegalStateException("this JDK has no TLS context [protocol=" + PROTOCOL + ']', e);
        }
    }
}
