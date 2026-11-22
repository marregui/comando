package dev.marregui.comando.frontear.tls;

import java.io.EOFException;
import java.io.IOException;
import java.net.InetAddress;
import java.nio.file.Path;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.X509ExtendedTrustManager;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.marregui.comando.core.loop.ExecutorStop;
import dev.marregui.comando.core.loop.Threads;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// Two machines' contexts in one JVM, a real handshake on `127.0.0.1`: each side must pin the other, a pin removed
/// refuses the next handshake, the session is TLS 1.3 and a peer that offers only TLS 1.2 is refused, and the pins
/// are a validated copy.
class TlsTest {

    /// How long one step between two sockets on this machine may take before the test fails.
    private static final Duration WAIT = Duration.ofSeconds(10);
    /// The byte the server writes once its handshake is done, so the client hears of a refusal of its certificate.
    private static final int OK = 0x5a;
    /// The JDK's default session timeout, one day: within the 7 days a TLS 1.3 ticket may live, so a context set
    /// back to it issues and keeps tickets, as a peer built some other way may.
    private static final int KEEPS_TICKETS_SECONDS = (int) Duration.ofDays(1).toSeconds();

    private static ExecutorService accepting;

    @TempDir
    Path dir;

    @BeforeAll
    static void startAccepting() {
        accepting = Threads.strand("tls-test-accept");
    }

    @AfterAll
    static void stopAccepting() {
        if (accepting != null) {
            ExecutorStop.now("tls-test", accepting);
        }
    }

    /// Two machines that pin each other finish a mutual handshake over TLS 1.3, and each sees the other's key.
    @Test
    void twoMachinesThatPinEachOtherShakeHands() throws Exception {
        final Machine a = machine("a");
        final Machine b = machine("b");
        a.pin(b);
        b.pin(a);
        final Sides sides = handshake(a.context(), b.context());
        assertThat(sides.server().failure()).isNull();
        assertThat(sides.client().failure()).isNull();
        assertThat(sides.server().protocol()).isEqualTo("TLSv1.3");
        assertThat(sides.client().protocol()).isEqualTo("TLSv1.3");
        assertThat(sides.server().peer()).isEqualTo("b");
        assertThat(sides.client().peer()).isEqualTo("a");
    }

    /// A server that does not pin the client refuses it at the handshake.
    @Test
    void aServerRefusesAClientItDoesNotPin() throws Exception {
        final Machine a = machine("a");
        final Machine b = machine("b");
        b.pin(a);
        final Sides sides = handshake(a.context(), b.context());
        assertThat(sides.server().failure()).isInstanceOf(SSLHandshakeException.class);
        assertThat(sides.client().failure()).isNotNull();
    }

    /// A client that does not pin the server refuses it at the handshake.
    @Test
    void aClientRefusesAServerItDoesNotPin() throws Exception {
        final Machine a = machine("a");
        final Machine b = machine("b");
        a.pin(b);
        final Sides sides = handshake(a.context(), b.context());
        assertThat(sides.client().failure()).isInstanceOf(SSLHandshakeException.class);
        assertThat(sides.server().failure()).isNotNull();
    }

    /// Pins are read at each handshake: once `set` drops the client's fingerprint, the same server context refuses
    /// the same client context, and a pin put back lets it in again.
    @Test
    void aPinRemovedRefusesTheNextHandshake() throws Exception {
        final Machine a = machine("a");
        final Machine b = machine("b");
        a.pin(b);
        b.pin(a);
        final SSLContext server = a.context();
        final SSLContext client = b.context();
        assertThat(handshake(server, client).server().failure()).isNull();
        a.pins().set(Map.of());
        assertThat(handshake(server, client).server().failure()).isInstanceOf(SSLHandshakeException.class);
        a.pin(b);
        assertThat(handshake(server, client).server().failure()).isNull();
    }

    /// A server context resumes no session: a client that comes back to the same server socket, and would keep
    /// and offer a ticket, still makes a full handshake, so once its pin is removed it is refused. A resumed session
    /// shows no certificate, so resuming would skip the pin check.
    @Test
    void aPinRemovedRefusesAClientThatComesBackToTheSameServer() throws Exception {
        final Machine a = machine("a");
        final Machine b = machine("b");
        a.pin(b);
        b.pin(a);
        try (SSLServerSocket listening = listen(a.context())) {
            final SSLContext client = b.context();
            client.getClientSessionContext().setSessionTimeout(KEEPS_TICKETS_SECONDS);
            assertThat(handshake(listening, client).server().failure()).isNull();
            assertThat(handshake(listening, client).server().failure()).isNull();
            a.pins().set(Map.of());
            final Sides again = handshake(listening, client);
            assertThat(again.server().failure()).isInstanceOf(SSLHandshakeException.class);
            assertThat(again.client().failure()).isNotNull();
        }
    }

    /// A client context resumes no session either: back at a server that would issue tickets, it still makes a full
    /// handshake, so once it no longer pins the server it refuses it.
    @Test
    void aPinRemovedRefusesAServerTheClientComesBackTo() throws Exception {
        final Machine a = machine("a");
        final Machine b = machine("b");
        a.pin(b);
        b.pin(a);
        final SSLContext server = a.context();
        server.getServerSessionContext().setSessionTimeout(KEEPS_TICKETS_SECONDS);
        try (SSLServerSocket listening = listen(server)) {
            final SSLContext client = b.context();
            assertThat(handshake(listening, client).client().failure()).isNull();
            assertThat(handshake(listening, client).client().failure()).isNull();
            b.pins().set(Map.of());
            final Sides again = handshake(listening, client);
            assertThat(again.client().failure()).isInstanceOf(SSLHandshakeException.class);
            assertThat(again.server().failure()).isNotNull();
        }
    }

    /// A client that offers only TLS 1.2, to a server whose socket would take it, is refused: the trust manager
    /// accepts a pinned key in a TLS 1.3 session alone.
    @Test
    void aTls12SessionIsRefused() throws Exception {
        final Machine a = machine("a");
        final Machine b = machine("b");
        a.pin(b);
        b.pin(a);
        try (SSLServerSocket listening = listen(a.context())) {
            listening.setEnabledProtocols(new String[] {"TLSv1.2", "TLSv1.3"});
            final Sides sides = handshake(listening, b.context(), "TLSv1.2");
            assertThat(sides.client().failure()).isInstanceOf(SSLHandshakeException.class);
            assertThat(sides.server().failure()).isNotNull();
        }
    }

    /// The trust manager names no issuers, so a server asks a client for any certificate.
    @Test
    void theTrustManagerNamesNoIssuers() {
        final X509ExtendedTrustManager trust = new PinningTrustManager(new Pins());
        assertThat(trust.getAcceptedIssuers()).isEmpty();
    }

    /// `set` refuses a key that is not a fingerprint and keeps the pins it had; it copies the map it is given, so a
    /// later change to that map pins nothing.
    @Test
    void pinsAreAValidatedCopy() throws Exception {
        final Machine a = machine("a");
        final Machine b = machine("b");
        final Pins pins = new Pins();
        final Map<String, String> given = new HashMap<>(Map.of(a.key().fingerprint(), "a"));
        pins.set(given);
        given.put(b.key().fingerprint(), "b");
        assertThat(pins.machineOf(a.key().certificate())).isEqualTo("a");
        assertThat(pins.machineOf(b.key().certificate())).isNull();
        assertThatThrownBy(() -> pins.set(Map.of(a.key().fingerprint().toUpperCase(), "a")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> pins.set(Map.of("abc", "a"))).isInstanceOf(IllegalArgumentException.class);
        assertThat(pins.machineOf(a.key().certificate())).isEqualTo("a");
    }

    private Machine machine(final String id) throws IOException {
        return new Machine(MachineKey.loadOrCreate(dir.resolve(id), id), new Pins());
    }

    private static SSLServerSocket listen(final SSLContext context) throws IOException {
        final SSLServerSocket listening = (SSLServerSocket) context.getServerSocketFactory().createServerSocket(0, 1,
                InetAddress.getLoopbackAddress());
        listening.setNeedClientAuth(true);
        return listening;
    }

    private static Sides handshake(final SSLContext server, final SSLContext client) throws Exception {
        try (SSLServerSocket listening = listen(server)) {
            return handshake(listening, client);
        }
    }

    /// One connection from `client` to `listening`, each side's handshake run to its end; `protocols`, when given,
    /// are the only ones the client offers.
    private static Sides handshake(final SSLServerSocket listening, final SSLContext client,
            final String... protocols) throws Exception {
        final CompletableFuture<Side> served = CompletableFuture.supplyAsync(() -> serve(listening), accepting);
        final Side asClient = connect(client, listening.getLocalPort(), protocols);
        return new Sides(served.get(WAIT.toMillis(), TimeUnit.MILLISECONDS), asClient);
    }

    private static Side serve(final SSLServerSocket listening) {
        try (SSLSocket s = (SSLSocket) listening.accept()) {
            s.setSoTimeout((int) WAIT.toMillis());
            s.startHandshake();
            s.getOutputStream().write(OK);
            s.getOutputStream().flush();
            return Side.of(s);
        } catch (final IOException e) {
            return new Side(null, null, e);
        }
    }

    private static Side connect(final SSLContext client, final int port, final String... protocols) {
        try (SSLSocket s = (SSLSocket) client.getSocketFactory().createSocket(InetAddress.getLoopbackAddress(),
                port)) {
            s.setSoTimeout((int) WAIT.toMillis());
            if (protocols.length > 0) {
                s.setEnabledProtocols(protocols);
            }
            s.startHandshake();
            // in TLS 1.3 the server checks the client's certificate after the client's handshake is done: the
            // server's byte, or its alert, says which
            if (s.getInputStream().read() != OK) {
                throw new EOFException("server closed without its byte");
            }
            return Side.of(s);
        } catch (final IOException e) {
            return new Side(null, null, e);
        }
    }

    /// A machine's key and the pins its context trusts.
    private record Machine(MachineKey key, Pins pins) {

        void pin(final Machine other) {
            final Map<String, String> next = new HashMap<>();
            next.put(other.key().fingerprint(), other.key().machine());
            pins.set(next);
        }

        SSLContext context() {
            return Tls.context(key, pins);
        }
    }

    /// One side of a handshake: the protocol and the peer's machine id when it finished, else what failed.
    private record Side(String protocol, String peer, IOException failure) {

        static Side of(final SSLSocket s) throws IOException {
            final String cn = ((X509Certificate) s.getSession().getPeerCertificates()[0])
                    .getSubjectX500Principal().getName();
            return new Side(s.getSession().getProtocol(), cn.substring("CN=".length()), null);
        }
    }

    private record Sides(Side server, Side client) {
    }
}
